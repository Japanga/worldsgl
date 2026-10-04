package objviewer;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone lighting test scene for ThirdPersonGameNew.
 *
 * Put a file named "skull.obj" beside the class/jar (or use Load Skull OBJ).
 * The scene contains exactly two movable point lights beside a continuously
 * rotating skull. Both lights use the same real OpenGL point-light approach
 * as ThirdPersonGameNew: RGB diffuse/specular light, intensity, and radius-
 * based attenuation. The small colored spheres are only visual indicators.
 */
public class LightRender implements GLEventListener {

    private final GLU glu = new GLU();
    private GLJPanel canvas;
    private JFrame frame;
    private FPSAnimator animator;

    private OBJModel skull;
    private volatile String pendingObjPath = "skull.OBJ";
    private float skullRotation = 0.0f;

    private final TestLight[] lights = {
            new TestLight(-1.65f, 0.95f, 0.15f, 1.0f, 0.20f, 0.10f),
            new TestLight( 1.65f, 0.95f, 0.15f, 0.10f, 0.35f, 1.0f)
    };

    private static final float SKULL_SCALE = 0.05f;
    private static final float CAMERA_DISTANCE = 6.2f;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LightRender().start());
    }

    private void start() {
        /*
         * ThirdPersonGameNew now uses GLJPanel instead of GLCanvas because
         * the heavyweight GLCanvas/AWT WGL path can fail on Windows while
         * selecting its GraphicsConfiguration. Keep LightRender on the same
         * working JOGL surface implementation.
         *
         * WorldsGLMain is responsible for loading the matching JOGL/GlueGen
         * native libraries before this class is launched.
         */
        final GLProfile profile;
        try {
            profile = GLProfile.get(GLProfile.GL2);
        } catch (Throwable profileError) {
            profileError.printStackTrace();
            JOptionPane.showMessageDialog(
                    null,
                    "Unable to initialize the JOGL GL2 profile.\n\n"
                            + profileError.getClass().getName() + ": "
                            + profileError.getMessage() + "\n\n"
                            + "Make sure WorldsGLMain has loaded the matching "
                            + "JOGL/GlueGen native libraries before launching "
                            + "LightRender.",
                    "LightRender - JOGL Initialization Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDoubleBuffered(true);
        caps.setHardwareAccelerated(true);
        caps.setDepthBits(24);
        caps.setStencilBits(8);

        canvas = new GLJPanel(caps);
        canvas.setOpaque(true);
        canvas.setFocusable(true);
        canvas.addGLEventListener(this);

        frame = new JFrame("LightRender - Two Light Skull Tester");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(canvas, BorderLayout.CENTER);
        frame.add(buildControls(), BorderLayout.EAST);
        frame.setSize(1120, 700);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (animator != null) animator.stop();
            }
        });
        frame.setVisible(true);

        animator = new FPSAnimator(canvas, 60, true);
        animator.start();
    }

    private JPanel buildControls() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.setPreferredSize(new java.awt.Dimension(310, 0));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton load = new JButton("Load Skull OBJ");
        load.addActionListener(e -> chooseObj());
        root.add(top, BorderLayout.NORTH);

        JPanel lightsPanel = new JPanel(new GridLayout(0, 1, 4, 8));
        lightsPanel.setBorder(BorderFactory.createTitledBorder("Point Lights"));

        for (int i = 0; i < lights.length; i++) {
            lightsPanel.add(buildLightPanel(i, lights[i]));
        }
        root.add(lightsPanel, BorderLayout.CENTER);

        JLabel help = new JLabel("<html><b>Scene</b><br>Black background<br>Rotating skull.obj<br>Two real point lights<br>Colored spheres show light positions</html>");
        help.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));
        root.add(help, BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildLightPanel(int index, TestLight light) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Light " + (index + 1)));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton colorButton = new JButton("Set RGB Color");
        colorButton.setBackground(light.toColor());
        colorButton.addActionListener(e -> {
            Color c = JColorChooser.showDialog(frame, "Light " + (index + 1) + " RGB", light.toColor());
            if (c != null) {
                light.r = c.getRed() / 255f;
                light.g = c.getGreen() / 255f;
                light.b = c.getBlue() / 255f;
                colorButton.setBackground(c);
                colorButton.setForeground(contrast(c));
                canvas.display();
            }
        });
        row.add(colorButton);
        panel.add(row);

        JSpinner intensity = spinner(light.intensity, 0.0, 5.0, 0.05);
        panel.add(labeled("Intensity", intensity));
        intensity.addChangeListener(e -> light.intensity = ((Number) intensity.getValue()).floatValue());

        JSpinner radius = spinner(light.radius, 0.5, 40.0, 0.25);
        panel.add(labeled("Radius", radius));
        radius.addChangeListener(e -> light.radius = ((Number) radius.getValue()).floatValue());

        JSpinner x = spinner(light.x, -5.0, 5.0, 0.1);
        JSpinner y = spinner(light.y, -2.0, 5.0, 0.1);
        JSpinner z = spinner(light.z, -5.0, 5.0, 0.1);
        panel.add(labeled("X", x));
        panel.add(labeled("Y", y));
        panel.add(labeled("Z", z));
        x.addChangeListener(e -> light.x = ((Number) x.getValue()).floatValue());
        y.addChangeListener(e -> light.y = ((Number) y.getValue()).floatValue());
        z.addChangeListener(e -> light.z = ((Number) z.getValue()).floatValue());

        return panel;
    }

    private JPanel labeled(String name, JComponent component) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.add(new JLabel(name + ":"));
        p.add(component);
        return p;
    }

    private JSpinner spinner(float value, double min, double max, double step) {
        return new JSpinner(new SpinnerNumberModel((double) value, min, max, step));
    }

    private Color contrast(Color c) {
        int y = (299 * c.getRed() + 587 * c.getGreen() + 114 * c.getBlue()) / 1000;
        return y > 150 ? Color.BLACK : Color.WHITE;
    }

    private void chooseObj() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose skull OBJ");
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            pendingObjPath = chooser.getSelectedFile().getAbsolutePath();
            loadSkullOnGLThread();
        }
    }

    private void loadSkullOnGLThread() {
        final String path = pendingObjPath;
        try {
            OBJModel newModel = OBJModel.load(new File(path));
            if (canvas != null) {
                final OBJModel old = skull;
                skull = newModel;
                canvas.invoke(true, drawable -> {
                    if (old != null) old.dispose(drawable.getGL().getGL2());
                    return true;
                });
            } else {
                skull = newModel;
            }
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
                    "Could not load OBJ:\n" + ex.getMessage(), "OBJ Load Error", JOptionPane.ERROR_MESSAGE));
        }
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
        gl.setSwapInterval(1);
        gl.glEnable(GL2.GL_DEPTH_TEST);
        gl.glDepthFunc(GL2.GL_LEQUAL);
        gl.glShadeModel(GL2.GL_SMOOTH);
        gl.glEnable(GL2.GL_NORMALIZE);
        gl.glEnable(GL2.GL_COLOR_MATERIAL);
        gl.glColorMaterial(GL2.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE);
        gl.glClearColor(0f, 0f, 0f, 1f);
        gl.glEnable(GL2.GL_CULL_FACE);
        gl.glCullFace(GL2.GL_BACK);
        loadSkullOnGLThread();
    }

    @Override public void dispose(GLAutoDrawable drawable) {
        if (skull != null) skull.dispose(drawable.getGL().getGL2());
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
        gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glLoadIdentity();
        glu.gluLookAt(0.0, 1.05, CAMERA_DISTANCE, 0.0, 0.45, 0.0, 0.0, 1.0, 0.0);

        setupLights(gl);
        drawGroundlessBlackSpace(gl);

        gl.glPushMatrix();
        gl.glTranslatef(0f, 0.45f, 0f);
        gl.glRotatef(skullRotation, 0f, 1f, 0f);
        gl.glScalef(SKULL_SCALE, SKULL_SCALE, SKULL_SCALE);
        gl.glColor3f(0.72f, 0.72f, 0.72f);
        if (skull != null) skull.render(gl);
        gl.glPopMatrix();

        // Visual source markers are emissive/unlit so they don't interfere
        // with the actual point-light calculation.
        gl.glDisable(GL2.GL_LIGHTING);
        for (TestLight light : lights) drawLightMarker(gl, light);
        gl.glEnable(GL2.GL_LIGHTING);

        skullRotation += 0.55f;
        if (skullRotation >= 360f) skullRotation -= 360f;
    }

    private void drawGroundlessBlackSpace(GL2 gl) {
        // Intentionally empty: the tester uses a pure black empty background.
    }

    private void setupLights(GL2 gl) {
        gl.glEnable(GL2.GL_LIGHTING);
        float[] global = {0.018f, 0.018f, 0.018f, 1f};
        gl.glLightModelfv(GL2.GL_LIGHT_MODEL_AMBIENT, global, 0);

        for (int i = 0; i < lights.length; i++) {
            int id = GL2.GL_LIGHT0 + i;
            TestLight l = lights[i];
            gl.glEnable(id);

            float intensity = Math.max(0f, Math.min(5f, l.intensity));
            float[] pos = {l.x, l.y, l.z, 1f};
            float[] diffuse = {l.r * intensity, l.g * intensity, l.b * intensity, 1f};
            float[] ambient = {l.r * intensity * 0.02f, l.g * intensity * 0.02f, l.b * intensity * 0.02f, 1f};
            float[] specular = {l.r * intensity * 0.32f, l.g * intensity * 0.32f, l.b * intensity * 0.32f, 1f};

            gl.glLightfv(id, GL2.GL_POSITION, pos, 0);
            gl.glLightfv(id, GL2.GL_DIFFUSE, diffuse, 0);
            gl.glLightfv(id, GL2.GL_AMBIENT, ambient, 0);
            gl.glLightfv(id, GL2.GL_SPECULAR, specular, 0);

            float radius = Math.max(0.5f, Math.min(40f, l.radius));
            gl.glLightf(id, GL2.GL_CONSTANT_ATTENUATION, 0.35f);
            gl.glLightf(id, GL2.GL_LINEAR_ATTENUATION, 2.8f / radius);
            gl.glLightf(id, GL2.GL_QUADRATIC_ATTENUATION, 4.5f / (radius * radius));
        }

        // Material/specular settings for the skull.
        float[] matSpec = {0.72f, 0.72f, 0.72f, 1f};
        gl.glMaterialfv(GL2.GL_FRONT_AND_BACK, GL2.GL_SPECULAR, matSpec, 0);
        gl.glMaterialf(GL2.GL_FRONT_AND_BACK, GL2.GL_SHININESS, 48f);
    }

    private void drawLightMarker(GL2 gl, TestLight l) {
        gl.glPushMatrix();
        gl.glTranslatef(l.x, l.y, l.z);
        gl.glColor3f(l.r, l.g, l.b);
        int slices = 18;
        int stacks = 10;
        float radius = 0.105f + 0.045f * Math.min(4f, l.intensity);
        for (int i = 0; i < stacks; i++) {
            float lat0 = (float) (-Math.PI / 2 + Math.PI * i / stacks);
            float lat1 = (float) (-Math.PI / 2 + Math.PI * (i + 1) / stacks);
            float z0 = (float) Math.sin(lat0), zr0 = (float) Math.cos(lat0);
            float z1 = (float) Math.sin(lat1), zr1 = (float) Math.cos(lat1);
            gl.glBegin(GL2.GL_QUAD_STRIP);
            for (int j = 0; j <= slices; j++) {
                double lng = 2 * Math.PI * (j - 1) / slices;
                float x = (float) Math.cos(lng), y = (float) Math.sin(lng);
                gl.glVertex3f(radius * x * zr0, radius * y * zr0, radius * z0);
                gl.glVertex3f(radius * x * zr1, radius * y * zr1, radius * z1);
            }
            gl.glEnd();
        }
        gl.glPopMatrix();
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        GL2 gl = drawable.getGL().getGL2();
        height = Math.max(1, height);
        float aspect = (float) width / height;
        gl.glViewport(0, 0, width, height);
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadIdentity();
        glu.gluPerspective(45.0, aspect, 0.1, 100.0);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
    }

    private static class TestLight {
        float x, y, z;
        float intensity = 1f;
        float radius = 8f;
        float r, g, b;

        TestLight(float x, float y, float z, float r, float g, float b) {
            this.x = x; this.y = y; this.z = z;
            this.r = r; this.g = g; this.b = b;
        }

        Color toColor() {
            return new Color(clamp01(r), clamp01(g), clamp01(b));
        }
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** Small OBJ loader supporting v, vn, vt and triangular/quadrilateral faces. */
    private static class OBJModel {
        final List<float[]> vertices = new ArrayList<>();
        final List<float[]> normals = new ArrayList<>();
        final List<float[]> texCoords = new ArrayList<>();
        final List<Face> faces = new ArrayList<>();
        int displayList = 0;

        static OBJModel load(File file) throws IOException {
            if (!file.isFile()) throw new FileNotFoundException(file.getAbsolutePath());
            OBJModel m = new OBJModel();
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split("\\s+");
                    if (p[0].equals("v") && p.length >= 4) m.vertices.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                    else if (p[0].equals("vn") && p.length >= 4) m.normals.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                    else if (p[0].equals("vt") && p.length >= 3) m.texCoords.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2])});
                    else if (p[0].equals("f") && p.length >= 4) {
                        for (int i = 2; i < p.length - 1; i++) m.faces.add(Face.triangle(p[1], p[i], p[i + 1], m.vertices.size(), m.texCoords.size(), m.normals.size()));
                    }
                }
            }
            return m;
        }

        void render(GL2 gl) {
            if (displayList == 0) createDisplayList(gl);
            gl.glCallList(displayList);
        }

        void createDisplayList(GL2 gl) {
            displayList = gl.glGenLists(1);
            gl.glNewList(displayList, GL2.GL_COMPILE);
            gl.glBegin(GL2.GL_TRIANGLES);
            for (Face f : faces) {
                for (VertexRef ref : f.refs) {
                    if (ref.n > 0 && ref.n <= normals.size()) gl.glNormal3fv(normals.get(ref.n - 1), 0);
                    gl.glVertex3fv(vertices.get(ref.v - 1), 0);
                }
            }
            gl.glEnd();
            gl.glEndList();
        }

        void dispose(GL2 gl) {
            if (displayList != 0) {
                gl.glDeleteLists(displayList, 1);
                displayList = 0;
            }
        }

        static class Face {
            VertexRef[] refs = new VertexRef[3];
            static Face triangle(String a, String b, String c, int vc, int tc, int nc) {
                Face f = new Face();
                f.refs[0] = VertexRef.parse(a, vc, tc, nc);
                f.refs[1] = VertexRef.parse(b, vc, tc, nc);
                f.refs[2] = VertexRef.parse(c, vc, tc, nc);
                return f;
            }
        }

        static class VertexRef {
            int v, t, n;
            static VertexRef parse(String s, int vc, int tc, int nc) {
                String[] p = s.split("/", -1);
                VertexRef r = new VertexRef();
                r.v = resolve(p[0], vc);
                r.t = p.length > 1 && !p[1].isEmpty() ? resolve(p[1], tc) : 0;
                r.n = p.length > 2 && !p[2].isEmpty() ? resolve(p[2], nc) : 0;
                return r;
            }
            static int resolve(String s, int size) {
                int n = Integer.parseInt(s);
                return n >= 0 ? n : size + n + 1;
            }
        }
    }
}
