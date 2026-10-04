package objviewer;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import java.util.ArrayList;

public class ObjViewer extends JFrame
        implements GLEventListener,
                   ActionListener,
                   MouseListener,
                   MouseMotionListener,
                   MouseWheelListener {

    // ============================================================
    // UI
    // ============================================================

    private GLJPanel canvas;
    private JButton btnLoadOBJ;
    private JButton btnLoadTexture;

    // ============================================================
    // Camera / View
    // ============================================================

    private float rotX = 0.0f;
    private float rotY = 0.0f;
    private float zoom = -5.0f;

    private int lastMouseX;
    private int lastMouseY;

    // ============================================================
    // OBJ data
    // ============================================================

    /*
     * Each vertex:
     *
     * [ x, y, z ]
     */
    private ArrayList<float[]> vertices = new ArrayList<>();

    /*
     * Each texture coordinate:
     *
     * [ u, v ]
     */
    private ArrayList<float[]> textures = new ArrayList<>();

    /*
     * Each face is stored as triangles.
     *
     * face[0] = { vertexIndex, textureIndex }
     * face[1] = { vertexIndex, textureIndex }
     * face[2] = { vertexIndex, textureIndex }
     *
     * A quad such as:
     *
     * f 1/1 2/2 3/3 4/4
     *
     * becomes:
     *
     * triangle 1:
     * 1/1 2/2 3/3
     *
     * triangle 2:
     * 1/1 3/3 4/4
     */
    private ArrayList<int[][]> faces = new ArrayList<>();

    // ============================================================
    // Texture
    // ============================================================

    private Texture modelTexture = null;

    private File pendingTextureFile = null;
    private boolean textureNeedsLoading = false;

    private GLU glu = new GLU();

    // ============================================================
    // Constructor
    // ============================================================

    public ObjViewer() {

        setTitle("Simple JOGL OBJ Viewer");

        setSize(400, 200);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        setLocationRelativeTo(null);

        // --------------------------------------------------------
        // Control panel
        // --------------------------------------------------------

        JPanel controlPanel = new JPanel(new FlowLayout());

        btnLoadOBJ = new JButton("Load .OBJ File");
        btnLoadTexture = new JButton("Load Texture Image");

        btnLoadOBJ.addActionListener(this);
        btnLoadTexture.addActionListener(this);

        controlPanel.add(btnLoadOBJ);
        controlPanel.add(btnLoadTexture);

        add(controlPanel, BorderLayout.NORTH);

        // --------------------------------------------------------
        // OpenGL Swing surface
        // --------------------------------------------------------
        //
        // Do not use GLCanvas here. On the affected Windows/JOGL setup,
        // GLCanvas.addNotify() enters JOGL's heavyweight AWT/WGL
        // GraphicsConfiguration selection and can fail with:
        // "Unable to determine GraphicsConfiguration".
        //
        // GLJPanel is the Swing-compatible JOGL drawable used by
        // ThirdPersonGameNew. It keeps the application on the same
        // initialization path while avoiding that failing GLCanvas path.

        final GLProfile profile;
        try {
            profile = GLProfile.get(GLProfile.GL2);
        } catch (Throwable profileError) {
            throw new IllegalStateException(
                    "Unable to initialize the JOGL GL2 profile. "
                    + "Make sure WorldsGLMain has loaded the matching "
                    + "JOGL/GlueGen native libraries before launching ObjViewer.",
                    profileError
            );
        }

        GLCapabilities capabilities = new GLCapabilities(profile);
        capabilities.setDoubleBuffered(true);
        capabilities.setHardwareAccelerated(true);
        capabilities.setDepthBits(24);
        capabilities.setStencilBits(8);

        canvas = new GLJPanel(capabilities);
        canvas.setOpaque(true);
        canvas.setFocusable(true);

        canvas.addGLEventListener(this);

        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
        canvas.addMouseWheelListener(this);

        add(canvas, BorderLayout.CENTER);

        // --------------------------------------------------------
        // Animation
        // --------------------------------------------------------

        FPSAnimator animator = new FPSAnimator(canvas, 60, true);
        animator.start();
    }

    // ============================================================
    // Button events
    // ============================================================

    @Override
    public void actionPerformed(ActionEvent e) {

        JFileChooser chooser = new JFileChooser();

        int returnValue = chooser.showOpenDialog(this);

        if (returnValue != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File selectedFile = chooser.getSelectedFile();

        if (e.getSource() == btnLoadOBJ) {

            parseOBJFile(selectedFile);

        } else if (e.getSource() == btnLoadTexture) {

            /*
             * Texture loading must occur while the OpenGL context
             * is current, so we defer it until display().
             */
            pendingTextureFile = selectedFile;
            textureNeedsLoading = true;
        }
    }

    // ============================================================
    // OBJ parser
    // ============================================================

    private void parseOBJFile(File file) {

        ArrayList<float[]> newVertices = new ArrayList<>();
        ArrayList<float[]> newTextures = new ArrayList<>();
        ArrayList<int[][]> newFaces = new ArrayList<>();

        int skippedFaces = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {

            String line;

            while ((line = br.readLine()) != null) {

                line = line.trim();

                // Ignore blank lines
                if (line.isEmpty()) {
                    continue;
                }

                // Ignore comments
                if (line.startsWith("#")) {
                    continue;
                }

                // =================================================
                // Vertex
                // =================================================

                if (line.startsWith("v ")) {

                    String[] tokens = line.split("\\s+");

                    if (tokens.length < 4) {
                        continue;
                    }

                    float x = Float.parseFloat(tokens[1]);
                    float y = Float.parseFloat(tokens[2]);
                    float z = Float.parseFloat(tokens[3]);

                    newVertices.add(new float[] {
                            x, y, z
                    });
                }

                // =================================================
                // Texture coordinate
                // =================================================

                else if (line.startsWith("vt ")) {

                    String[] tokens = line.split("\\s+");

                    if (tokens.length < 3) {
                        continue;
                    }

                    float u = Float.parseFloat(tokens[1]);

                    /*
                     * Keep the OBJ V coordinate unchanged here.
                     *
                     * We perform the OpenGL V flip exactly once
                     * when rendering.
                     */
                    float v = 1.0f - Float.parseFloat(tokens[2]);

                    newTextures.add(new float[] {
                            u, v
                    });
                }

                // =================================================
                // Face
                // =================================================

                else if (line.startsWith("f ")) {

                    String[] tokens = line.split("\\s+");

                    /*
                     * A valid polygon needs at least 3 vertices.
                     *
                     * Example:
                     *
                     * f 1 2 3
                     *
                     * tokens.length == 4
                     */
                    if (tokens.length < 4) {
                        skippedFaces++;
                        continue;
                    }

                    /*
                     * OBJ faces may contain:
                     *
                     * 3 vertices:
                     * f 1 2 3
                     *
                     * 4 vertices:
                     * f 1 2 3 4
                     *
                     * 5+ vertices:
                     * f 1 2 3 4 5 ...
                     *
                     * We convert all of them into triangles
                     * using a triangle fan.
                     *
                     * Quad:
                     *
                     *     1 -------- 2
                     *     |        / |
                     *     |      /   |
                     *     |    /     |
                     *     |  /       |
                     *     4 -------- 3
                     *
                     * becomes:
                     *
                     * 1,2,3
                     * 1,3,4
                     */

                    for (int i = 2; i < tokens.length - 1; i++) {

                        int[][] triangle = new int[3][2];

                        String[] p0 = tokens[1].split("/");
                        String[] p1 = tokens[i].split("/");
                        String[] p2 = tokens[i + 1].split("/");

                        String[][] points = {
                                p0, p1, p2
                        };

                        boolean validTriangle = true;

                        for (int j = 0; j < 3; j++) {

                            String[] parts = points[j];

                            // -------------------------------------------------
                            // Vertex index
                            // -------------------------------------------------

                            if (parts.length == 0 || parts[0].isEmpty()) {
                                validTriangle = false;
                                break;
                            }

                            int vertexIndex;

                            try {
                                vertexIndex =
                                        Integer.parseInt(parts[0]);
                            } catch (NumberFormatException ex) {
                                validTriangle = false;
                                break;
                            }

                            /*
                             * Positive OBJ indices are 1-based.
                             *
                             * OBJ:
                             * 1 = first vertex
                             *
                             * Java:
                             * 0 = first vertex
                             */
                            if (vertexIndex > 0) {

                                vertexIndex--;

                            } else {

                                /*
                                 * Negative OBJ indices refer backwards
                                 * from the current end of the list.
                                 *
                                 * -1 = most recently defined vertex
                                 * -2 = second most recent
                                 */
                                vertexIndex =
                                        newVertices.size() + vertexIndex;
                            }

                            // Validate vertex index
                            if (vertexIndex < 0 ||
                                vertexIndex >= newVertices.size()) {

                                validTriangle = false;
                                break;
                            }

                            triangle[j][0] = vertexIndex;

                            // -------------------------------------------------
                            // Texture coordinate index
                            // -------------------------------------------------

                            int textureIndex = -1;

                            /*
                             * Possible formats:
                             *
                             * v
                             * v/vt
                             * v//vn
                             * v/vt/vn
                             */
                            if (parts.length > 1 &&
                                !parts[1].isEmpty()) {

                                try {

                                    textureIndex =
                                            Integer.parseInt(parts[1]);

                                    if (textureIndex > 0) {

                                        textureIndex--;

                                    } else {

                                        textureIndex =
                                                newTextures.size()
                                                        + textureIndex;
                                    }

                                    /*
                                     * Don't invalidate the entire vertex
                                     * just because a texture index is bad.
                                     *
                                     * We simply render it without a
                                     * texture coordinate.
                                     */
                                    if (textureIndex < 0 ||
                                        textureIndex >= newTextures.size()) {

                                        textureIndex = -1;
                                    }

                                } catch (NumberFormatException ex) {

                                    textureIndex = -1;
                                }
                            }

                            triangle[j][1] = textureIndex;
                        }

                        /*
                         * Only add a triangle if all three vertex
                         * indices are valid.
                         */
                        if (validTriangle) {

                            newFaces.add(triangle);

                        } else {

                            skippedFaces++;
                        }
                    }
                }

                /*
                 * Everything else is intentionally ignored:
                 *
                 * vn
                 * mtllib
                 * usemtl
                 * o
                 * g
                 * s
                 * etc.
                 *
                 * We don't need those for this renderer.
                 */
            }

            // =====================================================
            // Swap in newly loaded model
            // =====================================================

            this.vertices = newVertices;
            this.textures = newTextures;
            this.faces = newFaces;

            System.out.println("--------------------------------");
            System.out.println("OBJ loaded: " + file.getName());
            System.out.println("Vertices:   " + vertices.size());
            System.out.println("TexCoords:  " + textures.size());
            System.out.println("Triangles:  " + faces.size());
            System.out.println("Skipped:    " + skippedFaces);
            System.out.println("--------------------------------");

            /*
             * Request another repaint so the new model appears
             * immediately.
             */
            canvas.repaint();

        } catch (IOException ex) {

            ex.printStackTrace();

        } catch (NumberFormatException ex) {

            System.err.println("Invalid number in OBJ file:");
            ex.printStackTrace();
        }
    }

    // ============================================================
    // OpenGL initialization
    // ============================================================

    @Override
    public void init(GLAutoDrawable drawable) {

        GL2 gl = drawable.getGL().getGL2();

        gl.glClearColor(
                0.2f,
                0.2f,
                0.2f,
                1.0f
        );

        gl.glEnable(GL2.GL_DEPTH_TEST);

        gl.glDepthFunc(GL2.GL_LEQUAL);

        gl.glEnable(GL2.GL_TEXTURE_2D);

        /*
         * Keep back-face culling disabled while debugging.
         *
         * This ensures that winding order isn't hiding geometry.
         */
        gl.glDisable(GL2.GL_CULL_FACE);
    }

    // ============================================================
    // Rendering
    // ============================================================

    @Override
    public void display(GLAutoDrawable drawable) {

        GL2 gl = drawable.getGL().getGL2();

        // --------------------------------------------------------
        // Clear
        // --------------------------------------------------------

        gl.glClear(
                GL2.GL_COLOR_BUFFER_BIT |
                GL2.GL_DEPTH_BUFFER_BIT
        );

        // --------------------------------------------------------
        // Load pending texture
        // --------------------------------------------------------

        if (textureNeedsLoading &&
            pendingTextureFile != null) {

            try {

                if (modelTexture != null) {
                    modelTexture.destroy(gl);
                }

                modelTexture =
                        TextureIO.newTexture(
                                pendingTextureFile,
                                true
                        );

                modelTexture.setTexParameteri(
                        gl,
                        GL2.GL_TEXTURE_MIN_FILTER,
                        GL2.GL_LINEAR
                );

                modelTexture.setTexParameteri(
                        gl,
                        GL2.GL_TEXTURE_MAG_FILTER,
                        GL2.GL_LINEAR
                );

            } catch (Exception ex) {

                ex.printStackTrace();

                modelTexture = null;
            }

            textureNeedsLoading = false;
            pendingTextureFile = null;
        }

        // --------------------------------------------------------
        // Model-view transformation
        // --------------------------------------------------------

        gl.glMatrixMode(GL2.GL_MODELVIEW);

        gl.glLoadIdentity();

        gl.glTranslatef(
                0.0f,
                0.0f,
                zoom
        );

        gl.glRotatef(
                rotX,
                1.0f,
                0.0f,
                0.0f
        );

        gl.glRotatef(
                rotY,
                0.0f,
                1.0f,
                0.0f
        );

        // --------------------------------------------------------
        // Texture
        // --------------------------------------------------------

        if (modelTexture != null) {

            modelTexture.enable(gl);
            modelTexture.bind(gl);
        }

        // --------------------------------------------------------
        // Render triangles
        // --------------------------------------------------------

        gl.glBegin(GL2.GL_TRIANGLES);

        for (int[][] face : faces) {

            /*
             * Every entry should already be a triangle because
             * parseOBJFile() triangulates polygons.
             */
            if (face == null ||
                face.length != 3) {

                continue;
            }

            /*
             * Validate ALL THREE vertices before sending anything
             * to OpenGL.
             *
             * This prevents an invalid triangle from becoming a
             * partially submitted GL_TRIANGLES primitive.
             */
            boolean valid = true;

            for (int i = 0; i < 3; i++) {

                int vIdx = face[i][0];

                if (vIdx < 0 ||
                    vIdx >= vertices.size()) {

                    valid = false;
                    break;
                }
            }

            if (!valid) {
                continue;
            }

            // ----------------------------------------------------
            // Submit the three vertices
            // ----------------------------------------------------

            for (int i = 0; i < 3; i++) {

                int vIdx = face[i][0];
                int tIdx = face[i][1];

                // -----------------------------------------------
                // Texture coordinate
                // -----------------------------------------------

                if (tIdx >= 0 &&
                    tIdx < textures.size()) {

                    float[] tex =
                            textures.get(tIdx);

                    /*
                     * Flip V exactly once.
                     */
                    gl.glTexCoord2f(
                            tex[0],
                            1.0f - tex[1]
                    );

                } else {

                    /*
                     * No texture coordinate.
                     *
                     * Give OpenGL a default coordinate rather
                     * than accidentally inheriting the previous
                     * vertex's coordinate.
                     */
                    gl.glTexCoord2f(
                            0.0f,
                            0.0f
                    );
                }

                // -----------------------------------------------
                // Vertex
                // -----------------------------------------------

                float[] vert =
                        vertices.get(vIdx);

                gl.glVertex3f(
                        vert[0],
                        vert[1],
                        vert[2]
                );
            }
        }

        gl.glEnd();

        // --------------------------------------------------------
        // Disable texture
        // --------------------------------------------------------

        if (modelTexture != null) {
            modelTexture.disable(gl);
        }
    }

    // ============================================================
    // Resize
    // ============================================================

    @Override
    public void reshape(
            GLAutoDrawable drawable,
            int x,
            int y,
            int width,
            int height) {

        GL2 gl = drawable.getGL().getGL2();

        if (height <= 0) {
            height = 1;
        }

        gl.glViewport(
                0,
                0,
                width,
                height
        );

        gl.glMatrixMode(GL2.GL_PROJECTION);

        gl.glLoadIdentity();

        glu.gluPerspective(
                45.0,
                (double) width / height,
                0.1,
                100.0
        );

        gl.glMatrixMode(GL2.GL_MODELVIEW);

        gl.glLoadIdentity();
    }

    // ============================================================
    // Dispose
    // ============================================================

    @Override
    public void dispose(GLAutoDrawable drawable) {

        GL2 gl = drawable.getGL().getGL2();

        if (modelTexture != null) {

            modelTexture.destroy(gl);

            modelTexture = null;
        }
    }

    // ============================================================
    // Mouse rotation
    // ============================================================

    @Override
    public void mousePressed(MouseEvent e) {

        lastMouseX = e.getX();
        lastMouseY = e.getY();
    }

    @Override
    public void mouseDragged(MouseEvent e) {

        int deltaX =
                e.getX() - lastMouseX;

        int deltaY =
                e.getY() - lastMouseY;

        rotY += deltaX * 0.5f;
        rotX += deltaY * 0.5f;

        lastMouseX = e.getX();
        lastMouseY = e.getY();
    }

    // ============================================================
    // Mouse zoom
    // ============================================================

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {

        zoom -=
                e.getWheelRotation() * 0.3f;
    }

    // ============================================================
    // Unused mouse events
    // ============================================================

    @Override
    public void mouseClicked(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}

    @Override
    public void mouseMoved(MouseEvent e) {}

    // ============================================================
    // Main
    // ============================================================

    public static void main(String[] args) {

        /*
         * When launched from WorldsGLMain, its native JOGL bootstrap has
         * already completed before this EDT task is reached. Do not call
         * System.loadLibrary here a second time; keep native initialization
         * centralized in WorldsGLMain.
         */
        SwingUtilities.invokeLater(() -> {

            try {
                ObjViewer app = new ObjViewer();
                app.setVisible(true);
            } catch (Throwable error) {
                error.printStackTrace();
                javax.swing.JOptionPane.showMessageDialog(
                        null,
                        "ObjViewer could not initialize its JOGL surface.\n\n"
                                + error.getClass().getName() + ": "
                                + error.getMessage(),
                        "ObjViewer - JOGL Initialization Error",
                        javax.swing.JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }
}