package objviewer;

import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Newton.java
 *
 * Small standalone physics test scene for WorldsGL/JOGL.
 *
 * Put these files next to the running JAR:
 *   tree.obj
 *   apple.obj
 *
 * Optional textures:
 *   tree.png / tree.jpg / tree.jpeg
 *   apple.png / apple.jpg / apple.jpeg
 *   ground.png / ground.jpg / ground.jpeg
 *
 * The program also checks the OBJ's MTL file for a diffuse texture.
 *
 * The apple starts above the ground and falls under gravity.
 * The ground is a solid AABB collision surface.  When the apple
 * reaches it, the apple stops and receives a small bounce impulse.
 */
public class Newton implements GLEventListener, KeyListener, WindowListener {

    private final GLU glu = new GLU();

    private GLJPanel panel;
    private FPSAnimator animator;
    private JFrame frame;

    private OBJModel treeModel;
    private OBJModel appleModel;

    private Texture treeTexture;
    private Texture appleTexture;
    private Texture groundTexture;

    private final File baseDirectory;

    // Tree transform.
    private float treeX = 0.0f;
    private float treeY = 0.0f;
    private float treeZ = 0.0f;
    private float treeScale = 2.5f;

    // Apple rigid body.
    private float appleX = 0.0f;
    private float appleY = 6.0f;
    private float appleZ = 0.0f;
    private float appleScale = 0.45f;

    private float appleVelocityY = 0.0f;
    private float appleRotation = 0.0f;
    private boolean appleOnGround = false;

    // Lightweight rigid-body constants.
    private static final float GRAVITY = -9.81f;
    private static final float RESTITUTION = 0.18f;
    private static final float FIXED_DT = 1.0f / 120.0f;

    // Ground is a solid horizontal plane at y = 0.
    private static final float GROUND_Y = 0.0f;
    private static final float GROUND_HALF_SIZE = 14.0f;

    private long previousNanos;
    private double physicsAccumulator;

    // Camera.
    private float cameraYaw = 25.0f;
    private float cameraPitch = 16.0f;
    private float cameraDistance = 15.0f;

    private boolean running = true;

    public Newton() {
        baseDirectory = findApplicationDirectory();
    }

    public static void main(String[] args) {
        /*
         * Reuse the same JOGL bootstrap used by ThirdPersonGameNew.
         * If Newton is packaged beside the existing WorldsGL classes,
         * this initializes the native JOGL components before GLJPanel
         * is created.
         */
        try {
            WorldsGLMain.initializeJOGL();
            GLProfile.initSingleton();
        } catch (Throwable error) {
            error.printStackTrace();

            JOptionPane.showMessageDialog(
                    null,
                    "Newton could not initialize JOGL.\n\n"
                    + error.getClass().getName() + ": "
                    + String.valueOf(error.getMessage()) + "\n\n"
                    + "Newton is intended to run with the same JOGL setup "
                    + "used by WorldsGL/ThirdPersonGameNew.",
                    "Newton - OpenGL Initialization Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        SwingUtilities.invokeLater(() -> {
            Newton newton = new Newton();
            newton.createWindow();
        });
    }

    private void createWindow() {
        frame = new JFrame("Newton - Apple Physics Test");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(this);

        GLProfile profile = GLProfile.get(GLProfile.GL2);

        GLCapabilities capabilities = new GLCapabilities(profile);
        capabilities.setHardwareAccelerated(true);
        capabilities.setDoubleBuffered(true);
        capabilities.setDepthBits(24);

        panel = new GLJPanel(capabilities);
        panel.addGLEventListener(this);
        panel.setFocusable(true);
        panel.addKeyListener(this);

        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.CENTER);

        JLabel info = new JLabel(
                "Newton physics test  |  Apple falls onto solid ground  |  R = reset  |  ESC = exit",
                SwingConstants.CENTER
        );
        info.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        frame.add(info, BorderLayout.SOUTH);

        frame.setSize(1100, 760);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        panel.requestFocusInWindow();

        animator = new FPSAnimator(panel, 120, true);
        animator.start();
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();

        gl.glEnable(GL2.GL_DEPTH_TEST);
        gl.glDepthFunc(GL2.GL_LEQUAL);

        gl.glEnable(GL2.GL_LIGHTING);
        gl.glEnable(GL2.GL_LIGHT0);
        gl.glEnable(GL2.GL_NORMALIZE);

        gl.glShadeModel(GL2.GL_SMOOTH);

        gl.glClearColor(0.52f, 0.70f, 0.88f, 1.0f);

        setupLight(gl);

        try {
            File treeFile = findAsset("tree.obj");
            File appleFile = findAsset("apple.obj");

            if (treeFile == null) {
                throw new FileNotFoundException(
                        "tree.obj was not found next to the JAR:\n"
                        + baseDirectory.getAbsolutePath()
                );
            }

            if (appleFile == null) {
                throw new FileNotFoundException(
                        "apple.obj was not found next to the JAR:\n"
                        + baseDirectory.getAbsolutePath()
                );
            }

            treeModel = OBJModel.load(treeFile);
            treeModel.createDisplayList(gl);

            appleModel = OBJModel.load(appleFile);
            appleModel.createDisplayList(gl);

            treeTexture = findAndLoadTexture(gl, treeFile, "tree");
            appleTexture = findAndLoadTexture(gl, appleFile, "apple");

            File groundTextureFile = findTexture(
                    baseDirectory,
                    "ground"
            );

            if (groundTextureFile != null) {
                groundTexture = loadTexture(gl, groundTextureFile);
            }

            previousNanos = System.nanoTime();
            physicsAccumulator = 0.0;

            /*
             * Start the apple in a clearly visible position above the
             * ground and slightly in front of the tree trunk.
             */
            resetApple();

        } catch (Exception error) {
            error.printStackTrace();

            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(
                            frame,
                            "Newton could not load the test assets.\n\n"
                            + error.getMessage()
                            + "\n\nAssets are searched for in:\n"
                            + baseDirectory.getAbsolutePath(),
                            "Newton - Asset Loading Error",
                            JOptionPane.ERROR_MESSAGE
                    )
            );
        }
    }

    private void setupLight(GL2 gl) {
        float[] position = { 4.0f, 9.0f, 7.0f, 1.0f };
        float[] diffuse = { 1.0f, 0.96f, 0.90f, 1.0f };
        float[] ambient = { 0.24f, 0.24f, 0.24f, 1.0f };

        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_POSITION, position, 0);
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_DIFFUSE, diffuse, 0);
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_AMBIENT, ambient, 0);
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();

        updatePhysics();

        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        setupProjection(gl, drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
        setupCamera(gl);

        drawGround(gl);
        drawTree(gl);
        drawApple(gl);

        /*
         * Draw a small marker at the apple's current contact position.
         * This makes the solid ground collision point easy to see.
         */
        if (appleOnGround) {
            drawContactMarker(gl);
        }
    }

    private void setupProjection(GL2 gl, int width, int height) {
        int safeHeight = Math.max(1, height);

        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadIdentity();

        glu.gluPerspective(
                55.0,
                (double) width / (double) safeHeight,
                0.05,
                200.0
        );

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glLoadIdentity();
    }

    private void setupCamera(GL2 gl) {
        double yaw = Math.toRadians(cameraYaw);
        double pitch = Math.toRadians(cameraPitch);

        float targetX = 0.0f;
        float targetY = 2.7f;
        float targetZ = 0.0f;

        float horizontalDistance =
                (float) (cameraDistance * Math.cos(pitch));

        float cameraX =
                targetX + (float) (Math.sin(yaw) * horizontalDistance);

        float cameraY =
                targetY + (float) (Math.sin(pitch) * cameraDistance);

        float cameraZ =
                targetZ + (float) (Math.cos(yaw) * horizontalDistance);

        glu.gluLookAt(
                cameraX, cameraY, cameraZ,
                targetX, targetY, targetZ,
                0.0, 1.0, 0.0
        );
    }

    private void drawGround(GL2 gl) {
        gl.glPushMatrix();

        gl.glDisable(GL2.GL_LIGHTING);

        if (groundTexture != null) {
            gl.glEnable(GL2.GL_TEXTURE_2D);
            groundTexture.bind(gl);
            gl.glColor3f(1.0f, 1.0f, 1.0f);
        } else {
            gl.glDisable(GL2.GL_TEXTURE_2D);
            gl.glColor3f(0.25f, 0.48f, 0.20f);
        }

        float s = GROUND_HALF_SIZE;

        gl.glBegin(GL2.GL_QUADS);

        gl.glTexCoord2f(0.0f, 0.0f);
        gl.glVertex3f(-s, GROUND_Y, -s);

        gl.glTexCoord2f(8.0f, 0.0f);
        gl.glVertex3f(s, GROUND_Y, -s);

        gl.glTexCoord2f(8.0f, 8.0f);
        gl.glVertex3f(s, GROUND_Y, s);

        gl.glTexCoord2f(0.0f, 8.0f);
        gl.glVertex3f(-s, GROUND_Y, s);

        gl.glEnd();

        gl.glDisable(GL2.GL_TEXTURE_2D);
        gl.glEnable(GL2.GL_LIGHTING);

        gl.glPopMatrix();
    }

    private void drawTree(GL2 gl) {
        if (treeModel == null) {
            return;
        }

        gl.glPushMatrix();

        gl.glTranslatef(treeX, treeY, treeZ);
        gl.glScalef(treeScale, treeScale, treeScale);
        gl.glColor3f(1.0f, 1.0f, 1.0f);

        if (treeTexture != null) {
            gl.glEnable(GL2.GL_TEXTURE_2D);
            treeTexture.bind(gl);
        } else {
            gl.glDisable(GL2.GL_TEXTURE_2D);
        }

        treeModel.render(gl);

        gl.glDisable(GL2.GL_TEXTURE_2D);
        gl.glPopMatrix();
    }

    private void drawApple(GL2 gl) {
        if (appleModel == null) {
            return;
        }

        gl.glPushMatrix();

        gl.glTranslatef(appleX, appleY, appleZ);

        /*
         * Rotate the apple as it falls. The rotation is visual only;
         * the collision body remains a simple spherical/AABB-style
         * approximation for this test.
         */
        gl.glRotatef(appleRotation, 0.0f, 0.0f, 1.0f);
        gl.glRotatef(appleRotation * 0.55f, 1.0f, 0.0f, 0.0f);

        gl.glScalef(appleScale, appleScale, appleScale);
        gl.glColor3f(1.0f, 1.0f, 1.0f);

        if (appleTexture != null) {
            gl.glEnable(GL2.GL_TEXTURE_2D);
            appleTexture.bind(gl);
        } else {
            gl.glDisable(GL2.GL_TEXTURE_2D);
        }

        appleModel.render(gl);

        gl.glDisable(GL2.GL_TEXTURE_2D);
        gl.glPopMatrix();
    }

    private void drawContactMarker(GL2 gl) {
        gl.glPushMatrix();

        gl.glDisable(GL2.GL_LIGHTING);
        gl.glDisable(GL2.GL_TEXTURE_2D);

        gl.glColor3f(1.0f, 0.15f, 0.05f);

        gl.glBegin(GL2.GL_LINE_LOOP);

        int segments = 40;
        float radius = 0.48f;

        for (int i = 0; i < segments; i++) {
            double a = Math.PI * 2.0 * i / segments;
            gl.glVertex3f(
                    appleX + (float) Math.cos(a) * radius,
                    GROUND_Y + 0.012f,
                    appleZ + (float) Math.sin(a) * radius
            );
        }

        gl.glEnd();

        gl.glEnable(GL2.GL_LIGHTING);

        gl.glPopMatrix();
    }

    private void updatePhysics() {
        if (!running) {
            return;
        }

        long now = System.nanoTime();

        if (previousNanos == 0L) {
            previousNanos = now;
            return;
        }

        double frameSeconds =
                (now - previousNanos) / 1_000_000_000.0;

        previousNanos = now;

        /*
         * Clamp large frame gaps so closing/minimizing the window does
         * not cause the apple to tunnel through the ground.
         */
        frameSeconds = Math.min(frameSeconds, 0.10);

        physicsAccumulator += frameSeconds;

        while (physicsAccumulator >= FIXED_DT) {
            stepApplePhysics(FIXED_DT);
            physicsAccumulator -= FIXED_DT;
        }
    }

    private void stepApplePhysics(float dt) {
        if (appleOnGround) {
            return;
        }

        /*
         * Newtonian rigid-body integration:
         *
         *   v = v + a * dt
         *   p = p + v * dt
         *
         * with constant downward acceleration.
         */
        appleVelocityY += GRAVITY * dt;
        appleY += appleVelocityY * dt;

        appleRotation += Math.abs(appleVelocityY) * 22.0f * dt;

        /*
         * Collision against the solid ground.
         *
         * The imported model's minimum Y is used to find the bottom
         * of the scaled apple. This means the apple rests on the
         * actual model bounds instead of its origin.
         */
        float appleBottom =
                appleY + appleModel.minY * appleScale;

        if (appleBottom <= GROUND_Y) {
            appleY -= appleBottom - GROUND_Y;

            if (Math.abs(appleVelocityY) > 0.55f) {
                appleVelocityY =
                        -appleVelocityY * RESTITUTION;
            } else {
                appleVelocityY = 0.0f;
                appleOnGround = true;
            }
        }
    }

    private void resetApple() {
        if (appleModel == null) {
            appleX = 0.0f;
            appleY = 6.0f;
            appleZ = 0.0f;
        } else {
            /*
             * Put the apple near the lower part of the tree canopy.
             * Its exact visual position depends on the tree.obj model.
             */
            float treeTopY =
                    treeY + treeModel.maxY * treeScale;

            float treeMidY =
                    treeY + treeModel.minY * treeScale
                    + (treeTopY - treeY) * 0.70f;

            /*
             * Start the apple farther in front of the tree so its initial
             * position does not overlap the trunk/canopy. In this scene the
             * camera/front-facing direction is primarily the negative Z side,
             * so move the apple toward negative Z.
             */
            appleX = treeX + 0.35f;
            appleY = Math.max(4.0f, treeMidY);
            appleZ = treeZ + 3.75f;
        }

        appleVelocityY = 0.0f;
        appleRotation = 0.0f;
        appleOnGround = false;

        previousNanos = System.nanoTime();
        physicsAccumulator = 0.0;
    }

    private File findApplicationDirectory() {
        try {
            URI location =
                    Newton.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI();

            File codeLocation = new File(location);

            /*
             * When launched from a JAR, codeLocation is the JAR itself.
             * When launched from an IDE/classes directory, use that
             * directory directly.
             */
            if (codeLocation.isFile()) {
                File parent = codeLocation.getParentFile();
                if (parent != null) {
                    return parent.getCanonicalFile();
                }
            }

            if (codeLocation.isDirectory()) {
                return codeLocation.getCanonicalFile();
            }
        } catch (Exception ignored) {
        }

        return new File(".").getAbsoluteFile();
    }

    private File findAsset(String name) {
        File direct = new File(baseDirectory, name);

        if (direct.isFile()) {
            return direct;
        }

        /*
         * Also allow case-insensitive matching on Windows/filesystems
         * where the asset's capitalization differs.
         */
        File[] files = baseDirectory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isFile()
                        && file.getName().equalsIgnoreCase(name)) {
                    return file;
                }
            }
        }

        return null;
    }

    private File findTexture(File objFile, String baseName) {
        /*
         * First look beside the OBJ using the OBJ's own base name.
         */
        String objBase =
                stripExtension(objFile.getName());

        File found =
                findTextureByBaseName(objFile.getParentFile(), objBase);

        if (found != null) {
            return found;
        }

        /*
         * Then try the requested logical name.
         */
        return findTextureByBaseName(
                baseDirectory,
                baseName
        );
    }

    private File findBestTexture(File objFile, String baseName) {
        File texture = findTexture(objFile, baseName);

        if (texture != null) {
            return texture;
        }

        /*
         * If the OBJ references a material library, inspect the MTL
         * for map_Kd/diffuse texture names.
         */
        File mtl = findReferencedMtl(objFile);

        if (mtl != null) {
            File fromMtl = findDiffuseTextureFromMtl(mtl);

            if (fromMtl != null) {
                return fromMtl;
            }
        }

        return null;
    }

    private File findTextureByBaseName(File directory, String baseName) {
        if (directory == null || baseName == null) {
            return null;
        }

        String[] extensions = {
                ".png",
                ".jpg",
                ".jpeg",
                ".bmp",
                ".gif"
        };

        for (String extension : extensions) {
            File candidate =
                    new File(directory, baseName + extension);

            if (candidate.isFile()) {
                return candidate;
            }
        }

        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }

                String lower =
                        file.getName().toLowerCase(Locale.ROOT);

                String wanted =
                        baseName.toLowerCase(Locale.ROOT);

                for (String extension : extensions) {
                    if (lower.equals(wanted + extension)) {
                        return file;
                    }
                }
            }
        }

        return null;
    }

    private File findReferencedMtl(File objFile) {
        try (BufferedReader reader =
                     new BufferedReader(new FileReader(objFile))) {

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("mtllib ")) {
                    String name =
                            line.substring(7).trim();

                    File mtl =
                            new File(
                                    objFile.getParentFile(),
                                    name
                            );

                    if (mtl.isFile()) {
                        return mtl;
                    }
                }
            }
        } catch (IOException ignored) {
        }

        return null;
    }

    private File findDiffuseTextureFromMtl(File mtlFile) {
        try (BufferedReader reader =
                     new BufferedReader(new FileReader(mtlFile))) {

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("map_Kd ")) {
                    String path =
                            line.substring(7).trim();

                    /*
                     * MTL texture paths can contain spaces. For the
                     * common case, the entire remainder is the path.
                     */
                    File texture =
                            new File(
                                    mtlFile.getParentFile(),
                                    path
                            );

                    if (texture.isFile()) {
                        return texture;
                    }
                }
            }
        } catch (IOException ignored) {
        }

        return null;
    }

    private Texture findAndLoadTexture(
            GL2 gl,
            File objFile,
            String baseName) throws IOException {

        File textureFile = findBestTexture(objFile, baseName);

        if (textureFile == null) {
            return null;
        }

        return loadTexture(gl, textureFile);
    }

    private Texture loadTexture(GL2 gl, File file)
            throws IOException {

        Texture texture =
                TextureIO.newTexture(file, true);

        texture.setTexParameteri(
                gl,
                GL2.GL_TEXTURE_MIN_FILTER,
                GL2.GL_LINEAR_MIPMAP_LINEAR
        );

        texture.setTexParameteri(
                gl,
                GL2.GL_TEXTURE_MAG_FILTER,
                GL2.GL_LINEAR
        );

        texture.setTexParameteri(
                gl,
                GL2.GL_TEXTURE_WRAP_S,
                GL2.GL_REPEAT
        );

        texture.setTexParameteri(
                gl,
                GL2.GL_TEXTURE_WRAP_T,
                GL2.GL_REPEAT
        );

        return texture;
    }

    @Override
    public void reshape(
            GLAutoDrawable drawable,
            int x,
            int y,
            int width,
            int height) {
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();

        if (treeTexture != null) {
            treeTexture.destroy(gl);
            treeTexture = null;
        }

        if (appleTexture != null) {
            appleTexture.destroy(gl);
            appleTexture = null;
        }

        if (groundTexture != null) {
            groundTexture.destroy(gl);
            groundTexture = null;
        }

        if (treeModel != null) {
            treeModel.dispose(gl);
            treeModel = null;
        }

        if (appleModel != null) {
            appleModel.dispose(gl);
            appleModel = null;
        }
    }

    @Override
    public void keyPressed(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            close();
        } else if (event.getKeyCode() == KeyEvent.VK_R) {
            resetApple();
        }
    }

    @Override
    public void keyReleased(KeyEvent event) {
    }

    @Override
    public void keyTyped(KeyEvent event) {
    }

    private void close() {
        running = false;

        if (animator != null) {
            animator.stop();
        }

        if (frame != null) {
            frame.dispose();
        }
    }

    @Override public void windowOpened(WindowEvent e) {}
    @Override public void windowClosing(WindowEvent e) { close(); }
    @Override public void windowClosed(WindowEvent e) {}
    @Override public void windowIconified(WindowEvent e) {}
    @Override public void windowDeiconified(WindowEvent e) {}
    @Override public void windowActivated(WindowEvent e) {}
    @Override public void windowDeactivated(WindowEvent e) {}

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');

        if (dot > 0) {
            return name.substring(0, dot);
        }

        return name;
    }

    // ============================================================
    // SIMPLE OBJ MODEL
    // ============================================================

    private static class OBJModel {

        private final List<float[]> vertices =
                new ArrayList<>();

        private final List<float[]> texCoords =
                new ArrayList<>();

        private final List<float[]> normals =
                new ArrayList<>();

        private final List<Face> faces =
                new ArrayList<>();

        private int displayList = 0;

        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;

        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;

        public static OBJModel load(File file)
                throws IOException {

            OBJModel model = new OBJModel();

            try (BufferedReader reader =
                         new BufferedReader(
                                 new FileReader(file))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();

                    if (line.isEmpty()
                            || line.startsWith("#")) {
                        continue;
                    }

                    if (line.startsWith("v ")) {
                        String[] p =
                                line.substring(2)
                                        .trim()
                                        .split("\\s+");

                        if (p.length >= 3) {
                            float x = Float.parseFloat(p[0]);
                            float y = Float.parseFloat(p[1]);
                            float z = Float.parseFloat(p[2]);

                            model.vertices.add(
                                    new float[]{x, y, z}
                            );

                            model.minX =
                                    Math.min(model.minX, x);
                            model.minY =
                                    Math.min(model.minY, y);
                            model.minZ =
                                    Math.min(model.minZ, z);

                            model.maxX =
                                    Math.max(model.maxX, x);
                            model.maxY =
                                    Math.max(model.maxY, y);
                            model.maxZ =
                                    Math.max(model.maxZ, z);
                        }

                    } else if (line.startsWith("vt ")) {
                        String[] p =
                                line.substring(3)
                                        .trim()
                                        .split("\\s+");

                        if (p.length >= 2) {
                            model.texCoords.add(
                                    new float[]{
                                            Float.parseFloat(p[0]),
                                            Float.parseFloat(p[1])
                                    }
                            );
                        }

                    } else if (line.startsWith("vn ")) {
                        String[] p =
                                line.substring(3)
                                        .trim()
                                        .split("\\s+");

                        if (p.length >= 3) {
                            model.normals.add(
                                    new float[]{
                                            Float.parseFloat(p[0]),
                                            Float.parseFloat(p[1]),
                                            Float.parseFloat(p[2])
                                    }
                            );
                        }

                    } else if (line.startsWith("f ")) {
                        String[] p =
                                line.substring(2)
                                        .trim()
                                        .split("\\s+");

                        if (p.length >= 3) {
                            Face face = new Face();

                            /*
                             * Supports:
                             *   v
                             *   v/vt
                             *   v//vn
                             *   v/vt/vn
                             *
                             * Polygon faces are rendered as a triangle
                             * fan when the display list is built.
                             */
                            for (String token : p) {
                                face.vertices.add(
                                        parseFaceVertex(
                                                token,
                                                model.vertices.size(),
                                                model.texCoords.size(),
                                                model.normals.size()
                                        )
                                );
                            }

                            model.faces.add(face);
                        }
                    }
                }
            }

            if (model.vertices.isEmpty()) {
                throw new IOException(
                        "OBJ contains no vertices: "
                        + file.getAbsolutePath()
                );
            }

            return model;
        }

        private static FaceVertex parseFaceVertex(
                String token,
                int vertexCount,
                int texCount,
                int normalCount) {

            String[] parts = token.split("/", -1);

            int vi =
                    resolveIndex(
                            parts.length > 0 ? parts[0] : "",
                            vertexCount
                    );

            int ti = -1;
            int ni = -1;

            if (parts.length > 1
                    && !parts[1].isEmpty()) {
                ti =
                        resolveIndex(
                                parts[1],
                                texCount
                        );
            }

            if (parts.length > 2
                    && !parts[2].isEmpty()) {
                ni =
                        resolveIndex(
                                parts[2],
                                normalCount
                        );
            }

            return new FaceVertex(vi, ti, ni);
        }

        private static int resolveIndex(
                String text,
                int size) {

            try {
                int raw = Integer.parseInt(text);

                if (raw > 0) {
                    return raw - 1;
                }

                if (raw < 0) {
                    return size + raw;
                }
            } catch (NumberFormatException ignored) {
            }

            return -1;
        }

        public void createDisplayList(GL2 gl) {
            deleteDisplayList(gl);

            displayList =
                    gl.glGenLists(1);

            gl.glNewList(
                    displayList,
                    GL2.GL_COMPILE
            );

            for (Face face : faces) {
                if (face.vertices.size() < 3) {
                    continue;
                }

                gl.glBegin(GL2.GL_TRIANGLES);

                /*
                 * Triangle fan:
                 *   v0,v1,v2
                 *   v0,v2,v3
                 *   ...
                 */
                FaceVertex first =
                        face.vertices.get(0);

                for (int i = 1;
                     i < face.vertices.size() - 1;
                     i++) {

                    emitVertex(gl, first);

                    emitVertex(
                            gl,
                            face.vertices.get(i)
                    );

                    emitVertex(
                            gl,
                            face.vertices.get(i + 1)
                    );
                }

                gl.glEnd();
            }

            gl.glEndList();
        }

        private void emitVertex(
                GL2 gl,
                FaceVertex fv) {

            if (fv.texIndex >= 0
                    && fv.texIndex < texCoords.size()) {

                float[] uv =
                        texCoords.get(fv.texIndex);

                gl.glTexCoord2f(
                        uv[0],
                        uv[1]
                );
            }

            if (fv.normalIndex >= 0
                    && fv.normalIndex < normals.size()) {

                float[] normal =
                        normals.get(fv.normalIndex);

                gl.glNormal3f(
                        normal[0],
                        normal[1],
                        normal[2]
                );

            } else if (fv.vertexIndex >= 0
                    && fv.vertexIndex < vertices.size()) {

                /*
                 * Many OBJ files contain normals, but if they do not,
                 * provide a harmless fallback normal so lighting still
                 * behaves predictably.
                 */
                float[] vertex =
                        vertices.get(fv.vertexIndex);

                gl.glNormal3f(
                        vertex[0],
                        vertex[1],
                        vertex[2]
                );
            }

            if (fv.vertexIndex >= 0
                    && fv.vertexIndex < vertices.size()) {

                float[] vertex =
                        vertices.get(fv.vertexIndex);

                gl.glVertex3f(
                        vertex[0],
                        vertex[1],
                        vertex[2]
                );
            }
        }

        public void render(GL2 gl) {
            if (displayList != 0) {
                gl.glCallList(displayList);
            }
        }

        public void dispose(GL2 gl) {
            deleteDisplayList(gl);

            vertices.clear();
            texCoords.clear();
            normals.clear();
            faces.clear();
        }

        private void deleteDisplayList(GL2 gl) {
            if (displayList != 0) {
                gl.glDeleteLists(displayList, 1);
                displayList = 0;
            }
        }
    }

    private static class Face {
        private final List<FaceVertex> vertices =
                new ArrayList<>();
    }

    private static class FaceVertex {
        private final int vertexIndex;
        private final int texIndex;
        private final int normalIndex;

        private FaceVertex(
                int vertexIndex,
                int texIndex,
                int normalIndex) {

            this.vertexIndex = vertexIndex;
            this.texIndex = texIndex;
            this.normalIndex = normalIndex;
        }
    }
}
