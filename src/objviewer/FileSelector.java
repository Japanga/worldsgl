package objviewer;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

public class FileSelector implements GLEventListener, ActionListener, Runnable {

    // Swing UI Components
    private JFrame frame;
    private JPanel topPanel;
    private JButton openButton;
    private GLCanvas glCanvas;

    // File Data Pools (Flat Arrays to completely bypass object overhead/inner classes)
    private ArrayList<Float> rawVertices = new ArrayList<>();
    private ArrayList<Integer> faceIndices = new ArrayList<>();
    
    // File reference for background processing thread
    private File selectedFile;

    public static void main(String[] args) {
        // Safe Swing invocation thread start
        SwingUtilities.invokeLater(new FileSelector());
    }

    @Override
    public void run() {
        // 1. Initialize JOGL Canvas Setup
        GLProfile profile = GLProfile.get(GLProfile.GL2);
        GLCapabilities capabilities = new GLCapabilities(profile);
        glCanvas = new GLCanvas(capabilities);
        glCanvas.addGLEventListener(this); // Registers this instance directly

        // 2. Build Swing User Interface
        frame = new JFrame("Simple JOGL OBJ Viewer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        topPanel = new JPanel();
        openButton = new JButton("Select .obj File");
        openButton.addActionListener(this); // Registers this instance directly
        topPanel.add(openButton);

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(glCanvas, BorderLayout.CENTER);

        frame.setSize(new Dimension(800, 600));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // Handles the Swing Button click events
    @Override
    public void actionPerformed(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("Wavefront 3D Object (.obj)", "obj"));
        int option = fileChooser.showOpenDialog(frame);
        
        if (option == JFileChooser.APPROVE_OPTION) {
            selectedFile = fileChooser.getSelectedFile();
            
            // Spawn a standard linear execution thread to prevent freezing the UI canvas while parsing text
            Thread parseThread = new Thread(this::parseObjFile);
            parseThread.start();
        }
    }

    // Non-complex, linear text scanner for .obj structural contents
    private void parseObjFile() {
        if (selectedFile == null) return;

        ArrayList<Float> tempVertices = new ArrayList<>();
        ArrayList<Integer> tempIndices = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(selectedFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("v ")) {
                    // Extract vertex dimensions "v X Y Z"
                    String[] tokens = line.split("\\s+");
                    tempVertices.add(Float.parseFloat(tokens[1]));
                    tempVertices.add(Float.parseFloat(tokens[2]));
                    tempVertices.add(Float.parseFloat(tokens[3]));
                } else if (line.startsWith("f ")) {
                    // Extract Face index mapping tokens "f v1/vt1/vn1 v2/vt2/vn2..." 
                    String[] tokens = line.split("\\s+");
                    // Handles standard triangles & converts basic quads to simple triangles on the fly
                    for (int i = 1; i < tokens.length; i++) {
                        String[] vertexParts = tokens[i].split("/");
                        // Wavefront indices are 1-based index mapping. Subtract 1 for Java array mapping
                        int vertexIndex = Integer.parseInt(vertexParts[0]) - 1;
                        
                        if (i > 3) {
                            // Quad triangulation falloff logic: Connects last 2 vertices with the initial point
                            tempIndices.add(Integer.parseInt(tokens[1].split("/")[0]) - 1);
                            tempIndices.add(Integer.parseInt(tokens[i - 1].split("/")[0]) - 1);
                        }
                        tempIndices.add(vertexIndex);
                    }
                }
            }

            // Thread-safe injection of raw data back into the graphics context scope 
            synchronized (this) {
                this.rawVertices = tempVertices;
                this.faceIndices = tempIndices;
            }

            // Force the JOGL Canvas to update and redraw instantly
            glCanvas.repaint();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // ==========================================
    // JOGL GLEventListener Hooks 
    // ==========================================

    @Override
    public void init(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
        gl.glClearColor(0.15f, 0.15f, 0.15f, 1.0f); // Dark Slate background
        gl.glEnable(GL2.GL_DEPTH_TEST);             // Setup depth hardware buffer parsing
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
        gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
        gl.glLoadIdentity();

        // Position camera back to encapsulate normalized model scales
        gl.glTranslatef(0.0f, 0.0f, -5.0f);

        // Simple runtime rotation so user sees depth layout clearly
        gl.glRotatef(20.0f, 1.0f, 0.0f, 0.0f);
        gl.glRotatef(45.0f, 0.0f, 1.0f, 0.0f);

        // Thread safe reading of active geometric configurations
        synchronized (this) {
            if (faceIndices.isEmpty() || rawVertices.isEmpty()) {
                return; 
            }

            // Render object layout using a flat, simple Immediate pipeline loop
            gl.glBegin(GL2.GL_TRIANGLES);
            gl.glColor3f(0.7f, 0.7f, 0.9f); // Solid soft bluish tint model hue
            
            for (int i = 0; i < faceIndices.size(); i++) {
                int baseIndex = faceIndices.get(i) * 3;
                
                if (baseIndex + 2 < rawVertices.size()) {
                    float x = rawVertices.get(baseIndex);
                    float y = rawVertices.get(baseIndex + 1);
                    float z = rawVertices.get(baseIndex + 2);
                    gl.glVertex3f(x, y, z);
                }
            }
            gl.glEnd();
        }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        GL2 gl = drawable.getGL().getGL2();
        if (height <= 0) height = 1;

        gl.glViewport(0, 0, width, height);
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadIdentity();

        // Handle basic field of view aspect ratio setup
        float aspect = (float) width / (float) height;
        gl.glFrustum(-aspect, aspect, -1.0, 1.0, 1.0, 100.0);
        
        gl.glMatrixMode(GL2.GL_MODELVIEW);
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        // Not used
    }
}