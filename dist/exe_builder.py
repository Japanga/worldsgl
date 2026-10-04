import os
import sys
import shutil
import subprocess
import tkinter as tk
from tkinter import filedialog, messagebox

class JavaPackagerApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Java EXE Packager")
        self.root.geometry("600x350")
        
        # State variables
        self.wix_dir = tk.StringVar()
        self.jar_path = tk.StringVar()
        self.natives_dir = tk.StringVar()
        self.output_dir = tk.StringVar()
        self.exe_name = tk.StringVar(value="MyApp")

        self.create_widgets()

    def create_widgets(self):
        # Grid configuration
        self.root.columnconfigure(1, weight=1)

        # WiX Tools Directory
        tk.Label(self.root, text="WiX Tools Directory:").grid(row=0, column=0, sticky="w", padx=10, pady=5)
        tk.Entry(self.root, textvariable=self.wix_dir).grid(row=0, column=1, sticky="ew", padx=5)
        tk.Button(self.root, text="Browse...", command=lambda: self.browse_dir(self.wix_dir)).grid(row=0, column=2, padx=10)

        # JAR File Path
        tk.Label(self.root, text="Source .jar File:").grid(row=1, column=0, sticky="w", padx=10, pady=5)
        tk.Entry(self.root, textvariable=self.jar_path).grid(row=1, column=1, sticky="ew", padx=5)
        tk.Button(self.root, text="Browse...", command=self.browse_jar).grid(row=1, column=2, padx=10)

        # Natives Directory
        tk.Label(self.root, text="Natives Folder:").grid(row=2, column=0, sticky="w", padx=10, pady=5)
        tk.Entry(self.root, textvariable=self.natives_dir).grid(row=2, column=1, sticky="ew", padx=5)
        tk.Button(self.root, text="Browse...", command=lambda: self.browse_dir(self.natives_dir)).grid(row=2, column=2, padx=10)

        # Output Directory
        tk.Label(self.root, text="Output Directory:").grid(row=3, column=0, sticky="w", padx=10, pady=5)
        tk.Entry(self.root, textvariable=self.output_dir).grid(row=3, column=1, sticky="ew", padx=5)
        tk.Button(self.root, text="Browse...", command=lambda: self.browse_dir(self.output_dir)).grid(row=3, column=2, padx=10)

        # EXE Name
        tk.Label(self.root, text="Executable Name:").grid(row=4, column=0, sticky="w", padx=10, pady=5)
        tk.Entry(self.root, textvariable=self.exe_name).grid(row=4, column=1, sticky="ew", padx=5)

        # Build Button
        tk.Button(self.root, text="Generate Package", bg="#4CAF50", fg="white", font=("Arial", 12, "bold"), command=self.process_package).grid(row=5, column=0, columnspan=3, pady=20, ipadx=20)

    def browse_dir(self, var):
        directory = filedialog.askdirectory()
        if directory:
            var.set(os.path.normpath(directory))

    def browse_jar(self):
        file_path = filedialog.askopenfilename(filetypes=[("Java Archive", "*.jar")])
        if file_path:
            self.jar_path.set(os.path.normpath(file_path))

    def process_package(self):
        # 1. Validation
        if not all([self.wix_dir.get(), self.jar_path.get(), self.natives_dir.get(), self.output_dir.get(), self.exe_name.get()]):
            messagebox.showerror("Error", "All fields are required!")
            return

        target_dir = os.path.join(self.output_dir.get(), "Bundle_Output")
        jar_filename = os.path.basename(self.jar_path.get())
        
        try:
            # 2. Setup output directory structure
            if os.path.exists(target_dir):
                shutil.rmtree(target_dir)
            os.makedirs(target_dir, exist_ok=True)

            # Copy JAR
            shutil.copy(self.jar_path.get(), os.path.join(target_dir, jar_filename))
            # Copy Natives Folder
            shutil.copytree(self.natives_dir.get(), os.path.join(target_dir, "natives"))

            # 3. Temporarily modify %PATH% env variable for this process execution context
            env = os.environ.copy()
            env["PATH"] = self.wix_dir.get() + os.pathsep + env["PATH"]

            # 4. Generate the launcher script source that the .exe will run
            launcher_script_path = os.path.join(target_dir, "launcher.py")
            with open(launcher_script_path, "w") as f:
                f.write(f"""import subprocess
import os
import sys

if __name__ == '__main__':
    # Determine the directory where this compiled .exe is running
    base_dir = os.path.dirname(sys.executable) if getattr(sys, 'frozen', False) else os.path.dirname(__file__)
    jar_path = os.path.join(base_dir, "{jar_filename}")
    
    # Run the java command pointing to the relative jar file
    subprocess.Popen(["java", "-jar", jar_path], creationflags=subprocess.CREATE_NO_WINDOW)
""")

            # 5. Compile the launcher script into a standalone .exe inside the bundle folder
            exe_final_name = self.exe_name.get()
            print(f"Compiling launcher via PyInstaller using modified PATH context...")
            
            subprocess.run([
                "pyinstaller",
                "--noconfirm",
                "--onefile",
                "--windowed",
                f"--name={exe_final_name}",
                f"--distpath={target_dir}",
                f"--workpath={os.path.join(target_dir, 'build')}",
                launcher_script_path
            ], env=env, shell=True, check=True)

            # Clean up build artifacts, leaving only the generated files
            os.remove(launcher_script_path)
            shutil.rmtree(os.path.join(target_dir, 'build'), ignore_errors=True)
            launcher_spec = os.path.join(os.getcwd(), f"{exe_final_name}.spec")
            if os.path.exists(launcher_spec):
                os.remove(launcher_spec)

            messagebox.showinfo("Success", f"Package successfully created at:\n{target_dir}")

        except Exception as e:
            messagebox.showerror("Execution Error", f"An error occurred: {str(e)}")

if __name__ == "__main__":
    root = tk.Tk()
    app = JavaPackagerApp(root)
    root.mainloop()
