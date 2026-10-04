#!/usr/bin/env python3
"""
FBX -> WGANIM GUI converter.

This is a normal Python/Tkinter application. It launches Blender as a
subprocess to import/evaluate the FBX and bake each animation frame into the
custom .wganim geometry-frame format.

Requirements:
  - Python 3 with tkinter
  - Blender installed (the GUI can locate blender.exe)

The generated WGANIM contains evaluated mesh geometry for every baked frame
and preserves the Blender UV layout, including UV seams.
AnimationsEditor.java does not need an FBX/skeletal-animation runtime.
"""

import os
import sys
import math
import json
import shutil
import queue
import threading
import subprocess
import tempfile
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

# ---------------------------------------------------------------------------
# Blender worker code. It is passed to Blender through a temporary .py file.
# ---------------------------------------------------------------------------
BLENDER_WORKER = r'''import bpy
import os, sys, math, json, traceback


def args_after_separator():
    a = sys.argv
    if "--" in a:
        return a[a.index("--") + 1:]
    return []


def clean_text(s):
    return str(s).replace("\\n", " ").replace("\\r", " ")


def import_fbx(path):
    if not os.path.isfile(path):
        raise FileNotFoundError(path)
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)
    bpy.ops.import_scene.fbx(filepath=os.path.abspath(path), use_anim=True)


def get_action(name):
    if not name:
        return None
    action = bpy.data.actions.get(name)
    if action is None:
        raise ValueError("Action not found: " + name)
    return action


def attach_action(action):
    if action is None:
        return
    for obj in bpy.context.scene.objects:
        if obj.type == 'ARMATURE':
            if obj.animation_data is None:
                obj.animation_data_create()
            obj.animation_data.action = action


def detect_animation_range(scene, action):
    """Find the actual animation frame range instead of falling back to
    Blender's default scene range (often 1-250).

    Priority:
      1. Explicitly selected action.
      2. An action currently assigned to an imported armature.
      3. A single imported action.
      4. Scene range as a final fallback for static/no-action FBX files.
    """
    if action is not None:
        return action.frame_range[0], action.frame_range[1], action.name

    # FBX imports normally assign the active animation to the armature.
    active_actions = []
    for obj in bpy.context.scene.objects:
        if obj.type != 'ARMATURE' or obj.animation_data is None:
            continue
        assigned = obj.animation_data.action
        if assigned is not None and assigned not in active_actions:
            active_actions.append(assigned)

    if len(active_actions) == 1:
        detected = active_actions[0]
        return detected.frame_range[0], detected.frame_range[1], detected.name

    # If the FBX contains exactly one action, it is unambiguous.
    if len(bpy.data.actions) == 1:
        detected = next(iter(bpy.data.actions))
        return detected.frame_range[0], detected.frame_range[1], detected.name

    # No usable animation was detected. Keep the scene range as a safe fallback.
    return scene.frame_start, scene.frame_end, None


def frame_range(scene, action, start, end):
    detected_start, detected_end, detected_name = detect_animation_range(scene, action)

    if start is None:
        start = detected_start
    if end is None:
        end = detected_end

    first = int(math.ceil(float(start)))
    last = int(math.floor(float(end)))
    if last < first:
        raise ValueError("End frame must be >= start frame")
    return first, last, detected_name


def mesh_objects(selected_only):
    if selected_only:
        result = [o for o in bpy.context.selected_objects if o.type == 'MESH']
    else:
        result = [o for o in bpy.context.scene.objects if o.type == 'MESH']
    return sorted(result, key=lambda x: x.name.lower())


def build_uv_table(objects, depsgraph, frame):
    """Build a stable global UV table from the evaluated meshes.

    Blender UVs are stored per loop/corner rather than per position vertex,
    so each loop gets its own UV index. This preserves UV seams exactly while
    allowing baked positions to remain shared.
    """
    bpy.context.scene.frame_set(frame)
    depsgraph.update()
    uv_table = []
    uv_offsets = {}
    loop_counts = {}
    for obj in objects:
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh(preserve_all_data_layers=True, depsgraph=depsgraph)
        try:
            offset = len(uv_table)
            uv_offsets[obj.name] = offset
            loop_counts[obj.name] = len(mesh.loops)
            uv_layer = mesh.uv_layers.active
            if uv_layer is not None and len(uv_layer.data) == len(mesh.loops):
                for loop in mesh.loops:
                    uv = uv_layer.data[loop.index].uv
                    uv_table.append((float(uv.x), float(uv.y)))
            else:
                uv_table.extend((0.0, 0.0) for _ in mesh.loops)
        finally:
            evaluated.to_mesh_clear()
    return uv_table, uv_offsets, loop_counts


def bake_frame(frame, objects, depsgraph, scale, uv_offsets, expected_loop_counts):
    bpy.context.scene.frame_set(frame)
    depsgraph.update()
    vertices = []
    triangles = []
    for obj in objects:
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh(preserve_all_data_layers=True, depsgraph=depsgraph)
        try:
            mesh.calc_loop_triangles()
            expected = expected_loop_counts.get(obj.name)
            if expected is not None and len(mesh.loops) != expected:
                raise ValueError(
                    'Mesh topology/UV layout changed during baking for object %s at frame %d.'
                    % (obj.name, frame)
                )
            base = len(vertices)
            uv_base = uv_offsets.get(obj.name, 0)
            matrix = evaluated.matrix_world
            for v in mesh.vertices:
                p = matrix @ v.co
                vertices.append((p.x * scale, p.y * scale, p.z * scale))
            for tri in mesh.loop_triangles:
                a, b, c = tri.vertices
                la, lb, lc = tri.loops
                triangles.append((
                    base + a, uv_base + la,
                    base + b, uv_base + lb,
                    base + c, uv_base + lc
                ))
        finally:
            evaluated.to_mesh_clear()
    return vertices, triangles


def write_wganim(path, name, fps, loop, source_start, source_end, uv_table, frames):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as out:
        out.write('# WGANIM ANIMATION PACKAGE\n')
        out.write('VERSION 2\n')
        out.write('NAME %s\n' % clean_text(name))
        out.write('FPS %.6f\n' % fps)
        out.write('LOOP %d\n' % (1 if loop else 0))
        out.write('SOURCE_START %d\n' % source_start)
        out.write('SOURCE_END %d\n' % source_end)
        out.write('TOTAL_FRAMES %d\n' % len(frames))
        out.write('UV_COUNT %d\n\n' % len(uv_table))
        for u, v in uv_table:
            out.write('vt %.9f %.9f\n' % (u, v))
        out.write('\n')
        for i, (vertices, triangles) in enumerate(frames):
            out.write('--- FRAME_%d ---\n' % i)
            for x,y,z in vertices:
                out.write('v %.9f %.9f %.9f\n' % (x,y,z))
            for a,ua,b,ub,c,uc in triangles:
                out.write('f %d/%d %d/%d %d/%d\n' % (a+1,ua+1,b+1,ub+1,c+1,uc+1))
            out.write('--- END_FRAME_%d ---\n\n' % i)


def main():
    a = args_after_separator()
    if len(a) != 1:
        raise ValueError('Expected one JSON job file after --')
    with open(a[0], 'r', encoding='utf-8') as f:
        job = json.load(f)

    inp = os.path.abspath(job['input'])
    outp = os.path.abspath(job['output'])
    action_name = job.get('action') or None
    start_arg = job.get('start')
    end_arg = job.get('end')
    fps_arg = job.get('fps')
    loop = bool(job.get('loop', True))
    scale = float(job.get('scale', 1.0))
    selected_only = bool(job.get('selected_only', False))

    print('WGANIM_PROGRESS|0|Importing FBX... ', flush=True)
    import_fbx(inp)
    scene = bpy.context.scene
    action = get_action(action_name)
    attach_action(action)
    fps = float(fps_arg) if fps_arg else float(scene.render.fps) / max(1.0, float(scene.render.fps_base))
    first, last, detected_action_name = frame_range(scene, action, start_arg, end_arg)
    if action is None and detected_action_name:
        print('WGANIM_PROGRESS|1|Auto-detected animation "%s": frames %d-%d (%d frames).' %
              (detected_action_name, first, last, last - first + 1), flush=True)
    elif action is None:
        print('WGANIM_PROGRESS|1|No unique animation action detected; using scene range %d-%d (%d frames).' %
              (first, last, last - first + 1), flush=True)
    objects = mesh_objects(selected_only)
    if not objects:
        raise ValueError('No mesh objects were found in the imported FBX.')

    depsgraph = bpy.context.evaluated_depsgraph_get()
    total = last - first + 1

    # Preserve the source Blender UV layout. UVs are loop/corner based, so
    # faces store vertex/UV pairs (OBJ-style v/vt) and seams remain intact.
    print('WGANIM_PROGRESS|0|Reading source UV maps...', flush=True)
    uv_table, uv_offsets, expected_loop_counts = build_uv_table(objects, depsgraph, first)
    print('WGANIM_PROGRESS|2|Preserved %d UV corners from source mesh.' % len(uv_table), flush=True)
    # Stream frames directly to disk instead of keeping the entire animation in RAM.
    # Large FBX files can contain tens of thousands of vertices per frame; keeping
    # every frame in a Python list made the previous version appear to freeze and
    # could exhaust memory before the output file was written.
    os.makedirs(os.path.dirname(outp) or '.', exist_ok=True)
    temp_out = outp + '.partial'
    try:
        with open(temp_out, 'w', encoding='utf-8', newline='\n') as out:
            out.write('# WGANIM ANIMATION PACKAGE\n')
            out.write('VERSION 2\n')
            out.write('NAME %s\n' % clean_text(os.path.splitext(os.path.basename(inp))[0]))
            out.write('FPS %.6f\n' % fps)
            out.write('LOOP %d\n' % (1 if loop else 0))
            out.write('SOURCE_START %d\n' % first)
            out.write('SOURCE_END %d\n' % last)
            out.write('TOTAL_FRAMES %d\n' % total)
            out.write('UV_COUNT %d\n\n' % len(uv_table))
            for u, v in uv_table:
                out.write('vt %.9f %.9f\n' % (u, v))
            out.write('\n')
            out.flush()

            for i, frame in enumerate(range(first, last+1)):
                print('WGANIM_PROGRESS|%d|Baking frame %d/%d...' %
                      (int((i / max(1,total)) * 90), i+1, total), flush=True)
                verts, tris = bake_frame(frame, objects, depsgraph, scale, uv_offsets, expected_loop_counts)
                if not verts:
                    raise ValueError('Frame %d produced no vertices.' % frame)
                if not tris:
                    raise ValueError('Frame %d produced no triangles.' % frame)

                out.write('--- FRAME_%d ---\n' % i)
                for x, y, z in verts:
                    out.write('v %.9f %.9f %.9f\n' % (x, y, z))
                for a, ua, b, ub, c, uc in tris:
                    out.write('f %d/%d %d/%d %d/%d\n' %
                              (a+1, ua+1, b+1, ub+1, c+1, uc+1))
                out.write('--- END_FRAME_%d ---\n\n' % i)
                out.flush()

                percent = 90 + int(((i+1) / total) * 10)
                print('WGANIM_PROGRESS|%d|Wrote frame %d/%d: %d vertices, %d triangles' %
                      (percent, i+1, total, len(verts), len(tris)), flush=True)

        os.replace(temp_out, outp)
    except Exception:
        try:
            if os.path.exists(temp_out):
                os.remove(temp_out)
        except OSError:
            pass
        raise

    print('WGANIM_DONE|%s|%d|%.6f' % (outp, total, fps), flush=True)


try:
    main()
except Exception as exc:
    print('WGANIM_ERROR|' + str(exc), flush=True)
    traceback.print_exc()
    raise
'''


def find_blender_candidates():
    candidates = []
    path = shutil.which("blender")
    if path:
        candidates.append(path)
    if sys.platform.startswith("win"):
        roots = [os.environ.get("PROGRAMFILES", "C:\\Program Files"),
                 os.environ.get("PROGRAMFILES(X86)", "C:\\Program Files (x86)"),
                 os.environ.get("LOCALAPPDATA", "")]
        for root in roots:
            if not root:
                continue
            for base in (os.path.join(root, "Blender Foundation"),
                         os.path.join(root, "Programs", "Blender Foundation")):
                if os.path.isdir(base):
                    try:
                        for version in sorted(os.listdir(base), reverse=True):
                            exe = os.path.join(base, version, "blender.exe")
                            if os.path.isfile(exe):
                                candidates.append(exe)
                    except OSError:
                        pass
    # Preserve order and remove duplicates.
    seen = set()
    return [x for x in candidates if not (x in seen or seen.add(x))]


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("FBX → WGANIM Converter")
        self.geometry("820x650")
        self.minsize(760, 580)
        self.proc = None
        self.worker_file = None
        self.job_file = None
        self.msg_queue = queue.Queue()
        self._build_ui()
        self.after(100, self._poll_queue)
        candidates = find_blender_candidates()
        if candidates:
            self.blender_var.set(candidates[0])

    def _build_ui(self):
        pad = {"padx": 10, "pady": 6}
        main = ttk.Frame(self)
        main.pack(fill="both", expand=True, **pad)

        title = ttk.Label(main, text="FBX → WGANIM Animation Converter", font=("Segoe UI", 16, "bold"))
        title.pack(anchor="w", pady=(0, 10))
        ttk.Label(main, text="Bake an FBX animation into frame-by-frame geometry that AnimationsEditor.java can render.").pack(anchor="w", pady=(0, 12))

        paths = ttk.LabelFrame(main, text="Files")
        paths.pack(fill="x", pady=5)
        self._path_row(paths, "FBX file:", "fbx_var", self.browse_fbx)
        self._path_row(paths, "Output WGANIM:", "out_var", self.browse_output)
        self._path_row(paths, "Blender executable:", "blender_var", self.browse_blender)

        opts = ttk.LabelFrame(main, text="Animation Settings")
        opts.pack(fill="x", pady=5)
        grid = ttk.Frame(opts); grid.pack(fill="x", padx=8, pady=8)
        self.action_var = tk.StringVar(value="")
        self.start_var = tk.StringVar(value="")
        self.end_var = tk.StringVar(value="")
        self.fps_var = tk.StringVar(value="")
        self.scale_var = tk.StringVar(value="1.0")
        self.loop_var = tk.BooleanVar(value=True)
        self.all_objects_var = tk.BooleanVar(value=True)

        labels = [("Action name (optional):", self.action_var), ("Start frame (optional):", self.start_var),
                  ("End frame (optional):", self.end_var), ("Output FPS (optional):", self.fps_var),
                  ("Model scale:", self.scale_var)]
        for i,(lab,var) in enumerate(labels):
            r, c = divmod(i, 2)
            ttk.Label(grid, text=lab).grid(row=r, column=c*2, sticky="w", padx=6, pady=5)
            ttk.Entry(grid, textvariable=var, width=28).grid(row=r, column=c*2+1, sticky="ew", padx=6, pady=5)
        grid.columnconfigure(1, weight=1); grid.columnconfigure(3, weight=1)
        ttk.Checkbutton(grid, text="Loop animation", variable=self.loop_var).grid(row=3, column=0, columnspan=2, sticky="w", padx=6, pady=5)
        ttk.Checkbutton(grid, text="Bake all mesh objects", variable=self.all_objects_var).grid(row=3, column=2, columnspan=2, sticky="w", padx=6, pady=5)
        ttk.Label(
            opts,
            text="Leave Start/End blank to automatically use the animation's detected frame range.",
        ).pack(anchor="w", padx=14, pady=(0, 8))

        controls = ttk.Frame(main); controls.pack(fill="x", pady=8)
        self.convert_btn = ttk.Button(controls, text="Convert FBX → WGANIM", command=self.start_conversion)
        self.convert_btn.pack(side="left")
        self.cancel_btn = ttk.Button(controls, text="Cancel", command=self.cancel_conversion, state="disabled")
        self.cancel_btn.pack(side="left", padx=8)
        ttk.Button(controls, text="Clear Log", command=self.clear_log).pack(side="left", padx=8)

        self.progress = ttk.Progressbar(main, mode="determinate", maximum=100)
        self.progress.pack(fill="x", pady=(2, 6))
        self.status_var = tk.StringVar(value="Ready")
        ttk.Label(main, textvariable=self.status_var).pack(anchor="w")

        log_frame = ttk.LabelFrame(main, text="Conversion Log")
        log_frame.pack(fill="both", expand=True, pady=6)
        self.log = tk.Text(log_frame, wrap="word", height=16, state="disabled")
        scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.log.yview)
        self.log.configure(yscrollcommand=scroll.set)
        self.log.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

    def _path_row(self, parent, label, attr, command):
        var = tk.StringVar()
        setattr(self, attr, var)
        row = ttk.Frame(parent); row.pack(fill="x", padx=8, pady=6)
        ttk.Label(row, text=label, width=18).pack(side="left")
        ttk.Entry(row, textvariable=var).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(row, text="Browse...", command=command).pack(side="right")

    def browse_fbx(self):
        p = filedialog.askopenfilename(title="Select FBX animation", filetypes=[("FBX files", "*.fbx"), ("All files", "*.*")])
        if p:
            self.fbx_var.set(p)
            if not self.out_var.get():
                self.out_var.set(os.path.splitext(p)[0] + ".wganim")

    def browse_output(self):
        p = filedialog.asksaveasfilename(title="Save WGANIM", defaultextension=".wganim", filetypes=[("WGANIM files", "*.wganim"), ("All files", "*.*")])
        if p:
            self.out_var.set(p)

    def browse_blender(self):
        p = filedialog.askopenfilename(title="Select Blender executable", filetypes=[("Blender", "blender.exe"), ("All files", "*.*")])
        if p:
            self.blender_var.set(p)

    def append_log(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def clear_log(self):
        self.log.configure(state="normal"); self.log.delete("1.0", "end"); self.log.configure(state="disabled")

    def _validate(self):
        fbx = self.fbx_var.get().strip(); out = self.out_var.get().strip(); blender = self.blender_var.get().strip()
        if not fbx or not os.path.isfile(fbx):
            messagebox.showerror("Missing FBX", "Please select a valid .fbx file."); return None
        if not fbx.lower().endswith(".fbx"):
            messagebox.showerror("Invalid FBX", "The input file must have a .fbx extension."); return None
        if not out:
            out = os.path.splitext(fbx)[0] + ".wganim"; self.out_var.set(out)
        if not blender or not os.path.isfile(blender):
            messagebox.showerror("Blender not found", "Please select the Blender executable (blender.exe).\n\nBlender is required to read and evaluate FBX animation data."); return None
        try:
            scale = float(self.scale_var.get())
            if not math.isfinite(scale) or scale == 0: raise ValueError
        except Exception:
            messagebox.showerror("Invalid scale", "Model scale must be a non-zero number."); return None
        for label, value in (("Start frame", self.start_var.get()), ("End frame", self.end_var.get()), ("FPS", self.fps_var.get())):
            if value.strip():
                try:
                    n = float(value)
                    if label == "FPS" and n <= 0: raise ValueError
                except Exception:
                    messagebox.showerror("Invalid setting", f"{label} must be numeric."); return None
        return fbx, out, blender, scale

    def start_conversion(self):
        if self.proc is not None:
            return
        data = self._validate()
        if not data: return
        fbx, out, blender, scale = data
        os.makedirs(os.path.dirname(os.path.abspath(out)) or ".", exist_ok=True)
        job = {
            "input": fbx, "output": out, "action": self.action_var.get().strip() or None,
            "start": float(self.start_var.get()) if self.start_var.get().strip() else None,
            "end": float(self.end_var.get()) if self.end_var.get().strip() else None,
            "fps": float(self.fps_var.get()) if self.fps_var.get().strip() else None,
            "loop": self.loop_var.get(), "scale": scale,
            "selected_only": not self.all_objects_var.get()
        }
        fd1, self.worker_file = tempfile.mkstemp(suffix="_wganim_worker.py", text=True); os.close(fd1)
        with open(self.worker_file, "w", encoding="utf-8") as f: f.write(BLENDER_WORKER)
        fd2, self.job_file = tempfile.mkstemp(suffix="_wganim_job.json", text=True); os.close(fd2)
        with open(self.job_file, "w", encoding="utf-8") as f: json.dump(job, f, indent=2)
        self.progress["value"] = 0; self.status_var.set("Starting Blender..."); self.append_log("Starting conversion...")
        self.append_log("FBX: " + fbx); self.append_log("Output: " + out); self.append_log("Blender: " + blender)
        self.convert_btn.configure(state="disabled")
        self.cancel_btn.configure(state="normal")
        threading.Thread(target=self._run_process, args=(blender,), daemon=True).start()

    def _run_process(self, blender):
        cmd = [blender, '--background', '--factory-startup', '--python', self.worker_file, '--', self.job_file]
        try:
            self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, universal_newlines=True)
            for line in self.proc.stdout:
                self.msg_queue.put(("log", line.rstrip()))
            code = self.proc.wait()
            self.msg_queue.put(("done", code))
        except Exception as exc:
            self.msg_queue.put(("error", str(exc)))

    def _poll_queue(self):
        try:
            while True:
                kind, value = self.msg_queue.get_nowait()
                if kind == "log":
                    line = value
                    self.append_log(line)
                    if line.startswith("WGANIM_PROGRESS|"):
                        parts = line.split("|", 2)
                        try: self.progress["value"] = int(parts[1])
                        except Exception: pass
                        self.status_var.set(parts[2] if len(parts) > 2 else "Converting...")
                    elif line.startswith("WGANIM_DONE|"):
                        self.progress["value"] = 100
                        self.status_var.set("Conversion complete")
                elif kind == "done":
                    self.proc = None; self.convert_btn.configure(state="normal"); self.cancel_btn.configure(state="disabled")
                    if value == 0 and self.out_var.get() and os.path.isfile(self.out_var.get()):
                        messagebox.showinfo("Conversion complete", "WGANIM conversion completed successfully.\n\nOutput:\n" + self.out_var.get())
                    elif value != 0:
                        self.status_var.set("Conversion failed")
                        messagebox.showerror("Conversion failed", "Blender returned an error. See the Conversion Log for details.")
                    self._cleanup_temp()
                elif kind == "error":
                    self.proc = None; self.convert_btn.configure(state="normal"); self.cancel_btn.configure(state="disabled")
                    self.status_var.set("Conversion failed")
                    self.append_log("ERROR: " + value)
                    messagebox.showerror("Conversion failed", value)
                    self._cleanup_temp()
        except queue.Empty:
            pass
        self.after(100, self._poll_queue)

    def cancel_conversion(self):
        if self.proc is None:
            return
        self.status_var.set("Cancelling...")
        self.append_log("Cancellation requested...")
        try:
            self.proc.terminate()
        except Exception as exc:
            self.append_log("Cancel error: " + str(exc))

    def _cleanup_temp(self):
        for p in (self.worker_file, self.job_file):
            if p:
                try: os.remove(p)
                except OSError: pass
        self.worker_file = None; self.job_file = None

    def on_close(self):
        if self.proc is not None:
            if not messagebox.askyesno("Conversion running", "A conversion is still running. Stop it and exit?"):
                return
            try: self.proc.terminate()
            except Exception: pass
        self._cleanup_temp(); self.destroy()


if __name__ == "__main__":
    app = App()
    app.protocol("WM_DELETE_WINDOW", app.on_close)
    app.mainloop()
