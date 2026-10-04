package objviewer;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Heightmap editor and stage terrain authoring tool.
 *
 * Black = lowest point, gray = intermediate elevation, white = highest point.
 * The editor keeps the generated terrain in a form that can be sent directly
 * to ThirdPersonGameNew instead of round-tripping through an OBJ loader.
 */
public class Heightmaps {

    /** A material that can be painted onto selected terrain triangles. */
    public static class MaterialBrushMaterial {
        public String name = "Terrain Material";
        public String baseColorMap = "";
        public String normalMap = "";
        public String metallicMap = "";
        public String roughnessMap = "";
        public String emissionMap = "";
        public float metallic = 0f;
        public float roughness = 0.5f;
        public float specular = 0.95f;
        public float shininess = 96f;
        // Overall material opacity used by independent render layers such as water.
        public float alpha = 1.0f;

        // The water material is kept as the same material type as normal
        // terrain materials so World/Environment Settings can assign any
        // normal .mat created by Material Editor.
        public MaterialBrushMaterial() { }

        public MaterialBrushMaterial(String name, String baseColorMap) {
            this.name = name == null ? "Terrain Material" : name;
            this.baseColorMap = baseColorMap == null ? "" : baseColorMap;
        }
    }

    public interface MaterialSelectionListener {
        void materialSelected(MaterialBrushMaterial material);
    }

    /**
     * Serializable-in-memory stage terrain description.  The main editor
     * serializes the image path/settings and the compressed material paint map
     * into the .tpgproject scene data.
     */
    public static class HeightmapStageData {
        public final String sourceImagePath;
        public final float width;
        public final float depth;
        public final float height;
        public final float importScale;
        public final int sample;
        public final int nx;
        public final int nz;
        public final float[] heights;
        public final int[] materialIds;
        public final List<MaterialBrushMaterial> materials;
        public boolean waterEnabled;
        /** Water surface height in the Heightmaps editor terrain coordinate space (before importScale). */
        public float waterHeight;
        /** One flag per terrain cell; true means that cell is an exact-black water cell. */
        public final boolean[] waterCells;
        /** Material assigned to the generated water surface. */
        public MaterialBrushMaterial waterMaterial;
        public final float playerX;
        public final float playerY;
        public final float playerZ;

        public HeightmapStageData(String sourceImagePath, float width, float depth,
                                  float height, float importScale, int sample,
                                  int nx, int nz, float[] heights, int[] materialIds,
                                  List<MaterialBrushMaterial> materials,
                                  float playerX, float playerY, float playerZ) {
            this(sourceImagePath, width, depth, height, importScale, sample, nx, nz,
                    heights, materialIds, materials, false, 0f, null, playerX, playerY, playerZ);
        }

        public HeightmapStageData(String sourceImagePath, float width, float depth,
                                  float height, float importScale, int sample,
                                  int nx, int nz, float[] heights, int[] materialIds,
                                  List<MaterialBrushMaterial> materials,
                                  boolean waterEnabled, float waterHeight, boolean[] waterCells,
                                  float playerX, float playerY, float playerZ) {
            this.sourceImagePath = sourceImagePath == null ? "" : sourceImagePath;
            this.width = Math.max(0.001f, width);
            this.depth = Math.max(0.001f, depth);
            this.height = Math.max(0.001f, height);
            this.importScale = Math.max(0.001f, importScale);
            this.sample = Math.max(1, sample);
            this.nx = nx;
            this.nz = nz;
            this.heights = heights == null ? new float[0] : heights;
            int triangleCount = Math.max(0, (nx - 1) * (nz - 1) * 2);
            this.materialIds = materialIds == null ? new int[triangleCount] : materialIds;
            this.materials = materials == null
                    ? new ArrayList<MaterialBrushMaterial>()
                    : new ArrayList<MaterialBrushMaterial>(materials);
            this.waterEnabled = waterEnabled && waterCells != null && waterCells.length > 0;
            this.waterHeight = waterHeight;
            this.waterCells = waterCells == null ? new boolean[0] : waterCells.clone();
            this.waterMaterial = null;
            this.playerX = playerX;
            this.playerY = playerY;
            this.playerZ = playerZ;
        }

        public float getHeightAt(float worldX, float worldZ) {
            if (nx < 2 || nz < 2 || heights.length < nx * nz) return 0f;
            float x = ((worldX / importScale) / width + 0.5f) * (nx - 1);
            float z = ((worldZ / importScale) / depth + 0.5f) * (nz - 1);
            x = Math.max(0f, Math.min(nx - 1, x));
            z = Math.max(0f, Math.min(nz - 1, z));
            int x0 = Math.min(nx - 1, Math.max(0, (int)Math.floor(x)));
            int z0 = Math.min(nz - 1, Math.max(0, (int)Math.floor(z)));
            int x1 = Math.min(nx - 1, x0 + 1);
            int z1 = Math.min(nz - 1, z0 + 1);
            float fx = x - x0;
            float fz = z - z0;
            float h00 = heights[z0 * nx + x0];
            float h10 = heights[z0 * nx + x1];
            float h01 = heights[z1 * nx + x0];
            float h11 = heights[z1 * nx + x1];
            float hx0 = h00 + (h10 - h00) * fx;
            float hx1 = h01 + (h11 - h01) * fx;
            return (hx0 + (hx1 - hx0) * fz) * importScale;
        }
    }

    private JFrame frame;
    private JTextField imageField;
    private JTextField outputField;
    private JSpinner terrainWidth;
    private JSpinner terrainDepth;
    private JSpinner terrainHeight;
    private JSpinner sampleSpinner;
    private JSpinner importScaleSpinner;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JButton generateButton;
    private JButton exportButton;
    private JButton applyButton;
    private JSpinner waterHeightSpinner;

    private TerrainRenderer renderer;
    private BufferedImage heightmapImage;
    private volatile TerrainMesh mesh;
    private volatile WaterMesh waterMesh;
    private WaterMaterial waterMaterial = WaterMaterial.loadDefault();
    private volatile Thread generationWorker;

    // Automatic terrain optimization: keep imported heightfields within a
    // reasonable runtime grid while smoothing high-frequency pixel noise.
    private static final int AUTO_MAX_GRID_DIMENSION = 512;
    private static final int AUTO_SMOOTH_PASSES = 2;
    private float waterLayerHeight = 0.30f;
    private final List<MaterialBrushMaterial> materials = new ArrayList<>();

    /** Opens the Heightmaps editor from another WorldsGL editor window. */
    public static void openEditor() {
        Runnable open = () -> {
            try {
                Heightmaps app = new Heightmaps();
                app.createAndShow();
            } catch (Throwable t) {
                t.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "Could not open Heightmaps Editor.\n\n" + t,
                        "Heightmaps", JOptionPane.ERROR_MESSAGE);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) open.run();
        else SwingUtilities.invokeLater(open);
    }

    public static void main(String[] args) { openEditor(); }

    private void createAndShow() {
        frame = new JFrame("Heightmap Terrain Editor");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1080, 700));
        frame.setSize(1220, 800);
        frame.setLayout(new BorderLayout(8, 8));

        frame.add(buildControls(), BorderLayout.WEST);
        renderer = new TerrainRenderer(resolveProfile());
        frame.add(renderer.canvas, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        File test = findTestImage();
        if (test != null) loadImage(test);
    }

    private JPanel buildControls() {
        JPanel root = new JPanel();
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 6));
        root.setPreferredSize(new Dimension(315, 0));
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Heightmap Terrain Editor");
        title.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(8));

        JButton loadButton = new JButton("Load Heightmap...");
        loadButton.setAlignmentX(JButton.LEFT_ALIGNMENT);
        loadButton.addActionListener(e -> chooseImage());
        root.add(loadButton);
        root.add(Box.createVerticalStrut(4));

        imageField = new JTextField();
        imageField.setEditable(false);
        imageField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        imageField.setAlignmentX(JTextField.LEFT_ALIGNMENT);
        root.add(imageField);
        root.add(Box.createVerticalStrut(8));

        JPanel dimensions = new JPanel(new GridLayout(0, 2, 5, 5));
        dimensions.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        dimensions.add(new JLabel("Terrain Width:"));
        terrainWidth = spinner(100.0, 0.1, 100000.0, 10.0);
        dimensions.add(terrainWidth);
        dimensions.add(new JLabel("Terrain Depth:"));
        terrainDepth = spinner(100.0, 0.1, 100000.0, 10.0);
        dimensions.add(terrainDepth);
        dimensions.add(new JLabel("Terrain Height:"));
        terrainHeight = spinner(30.0, 0.01, 100000.0, 1.0);
        dimensions.add(terrainHeight);
        dimensions.add(new JLabel("Sample Every N:"));
        sampleSpinner = spinner(2, 1, 128, 1);
        dimensions.add(sampleSpinner);
        dimensions.add(new JLabel("Import Scale:"));
        importScaleSpinner = spinner(1.0, 0.01, 100.0, 0.05);
        dimensions.add(importScaleSpinner);
        root.add(dimensions);
        root.add(Box.createVerticalStrut(8));

        JPanel waterPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        waterPanel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        waterPanel.add(new JLabel("🌊 Water Layer Height:"));
        waterHeightSpinner = spinner(Math.max(0.05, Math.min(0.35, 30.0 * 0.01)), 0.0, 100000.0, 0.05);
        waterHeightSpinner.addChangeListener(e -> {
            waterLayerHeight = Math.max(0f, ((Number) waterHeightSpinner.getValue()).floatValue());
            rebuildWaterPreview();
        });
        waterPanel.add(waterHeightSpinner);
        root.add(waterPanel);
        root.add(Box.createVerticalStrut(4));

        outputField = new JTextField("heightmap.obj");
        outputField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        root.add(new JLabel("OBJ Output Filename:"));
        root.add(outputField);
        root.add(Box.createVerticalStrut(6));

        generateButton = new JButton("Generate 3D Heightmap");
        generateButton.setAlignmentX(JButton.LEFT_ALIGNMENT);
        generateButton.addActionListener(e -> generateMesh());
        root.add(generateButton);
        root.add(Box.createVerticalStrut(4));

        exportButton = new JButton("Export OBJ");
        exportButton.setEnabled(false);
        exportButton.setAlignmentX(JButton.LEFT_ALIGNMENT);
        exportButton.addActionListener(e -> exportObj());
        root.add(exportButton);
        root.add(Box.createVerticalStrut(4));

        applyButton = new JButton("Apply Heightmap to Current Stage");
        applyButton.setEnabled(false);
        applyButton.setAlignmentX(JButton.LEFT_ALIGNMENT);
        applyButton.addActionListener(e -> applyToCurrentStage());
        root.add(applyButton);
        root.add(Box.createVerticalStrut(10));

        statusLabel = new JLabel("Load a heightmap to begin.");
        statusLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        root.add(statusLabel);
        root.add(Box.createVerticalStrut(5));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setAlignmentX(JProgressBar.LEFT_ALIGNMENT);
        root.add(progressBar);
        root.add(Box.createVerticalStrut(8));

        JLabel help = new JLabel("<html><b>Height rule</b><br>Black = valleys / 0%<br>Gray = slopes / middle<br>White = peaks / 100%<br><br>Normal mode: drag to orbit, wheel to zoom.<br>Water Layer Height controls the actual height of the generated water surface over black heightmap cells.<br>Terrain materials are painted from World/Environment Settings.</html>");
        help.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        root.add(help);
        root.add(Box.createVerticalGlue());
        return root;
    }

    private void rebuildWaterPreview() {
        BufferedImage image = heightmapImage;
        if (image == null || mesh == null) return;
        final int sample = Math.max(1, ((Number) sampleSpinner.getValue()).intValue());
        final float width = ((Number) terrainWidth.getValue()).floatValue();
        final float depth = ((Number) terrainDepth.getValue()).floatValue();
        final float height = ((Number) terrainHeight.getValue()).floatValue();
        final float waterHeight = Math.max(0f, waterLayerHeight);
        try {
            waterMesh = buildWaterMesh(image, sample, width, depth, height, percent -> { });
            if (renderer != null) renderer.setMesh(mesh, waterMesh);
            if (statusLabel != null) statusLabel.setText(waterMesh.indices.length > 0
                    ? String.format(Locale.US, "Water layer: %.2f", waterHeight)
                    : "Water layer: no black heightmap areas found.");
        } catch (Exception ex) {
            if (statusLabel != null) statusLabel.setText("Water layer update failed: " + ex.getMessage());
        }
    }

    private JSpinner spinner(double value, double min, double max, double step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        s.setPreferredSize(new Dimension(92, 24));
        s.setMaximumSize(new Dimension(100, 24));
        return s;
    }

    private void chooseImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose Heightmap Image");
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) loadImage(chooser.getSelectedFile());
    }

    private File findTestImage() {
        String[] names = {"wp_myst1.png", "heightmap2.jpg", "heightmap.png"};
        for (String name : names) {
            File f = new File(name);
            if (f.isFile()) return f;
        }
        return null;
    }

    private void loadImage(File file) {
        if (file == null || !file.isFile()) return;
        progressBar.setIndeterminate(true);
        statusLabel.setText("Loading heightmap...");
        generateButton.setEnabled(false);
        exportButton.setEnabled(false);
        applyButton.setEnabled(false);
        Thread loader = new Thread(() -> {
            try {
                BufferedImage source = ImageIO.read(file);
                if (source == null) throw new IOException("Unsupported or unreadable image format.");
                BufferedImage gray = toGrayscale(source);
                SwingUtilities.invokeLater(() -> {
                    heightmapImage = gray;
                    imageField.setText(file.getAbsolutePath());
                    statusLabel.setText("Loaded " + gray.getWidth() + " x " + gray.getHeight() + " heightmap.");
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(0);
                    generateButton.setEnabled(true);
                    generateMesh();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(false);
                    statusLabel.setText("Could not load heightmap.");
                    generateButton.setEnabled(true);
                    JOptionPane.showMessageDialog(frame, ex.getMessage(), "Heightmap Load Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "Heightmap-ImageLoader");
        loader.setDaemon(true);
        loader.start();
    }

    private BufferedImage toGrayscale(BufferedImage source) {
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return gray;
    }

    private void generateMesh() {
        if (heightmapImage == null) {
            JOptionPane.showMessageDialog(frame, "Load a heightmap image first.", "Heightmap", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (generationWorker != null && generationWorker.isAlive()) return;
        final BufferedImage image = heightmapImage;
        final int sample = Math.max(1, ((Number) sampleSpinner.getValue()).intValue());
        final float worldWidth = ((Number) terrainWidth.getValue()).floatValue();
        final float worldDepth = ((Number) terrainDepth.getValue()).floatValue();
        final float worldHeight = ((Number) terrainHeight.getValue()).floatValue();
        final float importScale = Math.max(0.001f, ((Number) importScaleSpinner.getValue()).floatValue());
        final float configuredWaterHeight = Math.max(0f, waterLayerHeight);
        waterMaterial = WaterMaterial.loadDefault();
        generateButton.setEnabled(false);
        exportButton.setEnabled(false);
        applyButton.setEnabled(false);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        statusLabel.setText("Generating 3D heightmap...");

        Thread worker = new Thread(() -> {
            try {
                TerrainMesh result = buildMesh(image, sample, worldWidth, worldDepth, worldHeight, percent ->
                        SwingUtilities.invokeLater(() -> progressBar.setValue(Math.max(0, Math.min(100, percent)))));
                WaterMesh water = buildWaterMesh(image, sample, worldWidth, worldDepth, worldHeight, percent ->
                        SwingUtilities.invokeLater(() -> progressBar.setValue(Math.max(0, Math.min(100, percent)))));
                mesh = result;
                waterMesh = water;
                SwingUtilities.invokeLater(() -> {
                    renderer.setMesh(result, water);
                    progressBar.setValue(100);
                    statusLabel.setText(String.format(Locale.US,
                            "Generated %d vertices / %d triangles.", result.vertexCount, result.triangleCount));
                    generateButton.setEnabled(true);
                    exportButton.setEnabled(true);
                    applyButton.setEnabled(ThirdPersonGameNew.getActiveInstance() != null);
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(0);
                    statusLabel.setText("Generation failed: " + getCauseOrSelfMessage(ex));
                    generateButton.setEnabled(true);
                    JOptionPane.showMessageDialog(frame, "Heightmap generation failed.\n\n" + getCauseOrSelfMessage(ex), "Generation Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "Heightmap-MeshGenerator");
        generationWorker = worker;
        worker.setDaemon(true);
        worker.start();
    }

    private interface ProgressListener { void onProgress(int percent); }

    /**
     * Chooses the minimum sampling needed to keep a very large source image
     * from becoming a multi-million-vertex terrain. The user's requested
     * sample is still respected when it is already coarser.
     */
    private static int effectiveSample(BufferedImage image, int requestedSample) {
        int requested = Math.max(1, requestedSample);
        if (image == null) return requested;
        int maxDim = Math.max(image.getWidth(), image.getHeight());
        int auto = Math.max(1, (int)Math.ceil((maxDim - 1) / (double)Math.max(2, AUTO_MAX_GRID_DIMENSION - 1)));
        return Math.max(requested, auto);
    }

    /** Smooths only the sampled height grid, avoiding an expensive full-resolution image pass. */
    private static void smoothHeightGrid(float[] heights, int nx, int nz, int passes) {
        if (heights == null || nx < 2 || nz < 2 || passes <= 0) return;
        float[] temp = new float[heights.length];
        for (int pass = 0; pass < passes; pass++) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    float weighted = 0f;
                    float weightTotal = 0f;
                    for (int oz = -1; oz <= 1; oz++) {
                        int sz = Math.max(0, Math.min(nz - 1, z + oz));
                        int wy = oz == 0 ? 2 : 1;
                        for (int ox = -1; ox <= 1; ox++) {
                            int sx = Math.max(0, Math.min(nx - 1, x + ox));
                            int wx = ox == 0 ? 2 : 1;
                            float w = wx * wy;
                            weighted += heights[sz * nx + sx] * w;
                            weightTotal += w;
                        }
                    }
                    float blurred = weighted / Math.max(1f, weightTotal);
                    temp[z * nx + x] = heights[z * nx + x] * 0.55f + blurred * 0.45f;
                }
            }
            System.arraycopy(temp, 0, heights, 0, heights.length);
        }
    }

    private TerrainMesh buildMesh(BufferedImage image, int sample, float width, float depth,
                                  float maxHeight, ProgressListener progress) throws Exception {
        sample = effectiveSample(image, sample);
        int iw = image.getWidth(), ih = image.getHeight();
        int nx = ((iw - 1) / sample) + 1;
        int nz = ((ih - 1) / sample) + 1;
        if (nx < 2 || nz < 2) throw new IOException("Heightmap is too small for the selected sampling.");
        long verticesLong = (long) nx * nz;
        long trianglesLong = (long) (nx - 1) * (nz - 1) * 2L;
        if (verticesLong > 4_000_000L) throw new IOException("The selected sampling would create " + verticesLong + " vertices. Increase Sample Every N Pixels.");
        if (trianglesLong > 8_000_000L) throw new IOException("The selected sampling would create too many triangles. Increase Sample Every N Pixels.");

        int vertexCount = (int)verticesLong;
        int indexCount = (int)(trianglesLong * 3L);
        float[] interleaved = new float[vertexCount * 6];
        int[] indices = new int[indexCount];
        float[] heights = new float[vertexCount];
        for (int z = 0; z < nz; z++) {
            int srcY = Math.min(ih - 1, z * sample);
            float vz = nz == 1 ? 0f : (float)z / (nz - 1);
            float worldZ = (vz - 0.5f) * depth;
            for (int x = 0; x < nx; x++) {
                int srcX = Math.min(iw - 1, x * sample);
                int gray = image.getRaster().getSample(srcX, srcY, 0);
                float h = (gray / 255f) * maxHeight;
                int vi = z * nx + x;
                float vx = (nx == 1 ? 0f : ((float)x / (nx - 1) - 0.5f)) * width;
                heights[vi] = h;
                int p = vi * 6;
                interleaved[p] = vx; interleaved[p + 1] = h; interleaved[p + 2] = worldZ;
            }
            if ((z & 7) == 0) progress.onProgress(Math.min(65, 5 + (int)((z / (double)(nz - 1)) * 60.0)));
        }
        // Smooth the sampled terrain rather than the full source image. This
        // removes high-frequency noise without making large imports expensive.
        smoothHeightGrid(heights, nx, nz, AUTO_SMOOTH_PASSES);
        for (int z = 0; z < nz; z++) {
            int srcY = Math.min(ih - 1, z * sample);
            for (int x = 0; x < nx; x++) {
                int srcX = Math.min(iw - 1, x * sample);
                int vi = z * nx + x;
                int gray = image.getRaster().getSample(srcX, srcY, 0);
                // Keep exact-black water/valley samples pinned to the floor.
                if (gray <= 2) heights[vi] = 0f;
                interleaved[vi * 6 + 1] = heights[vi];
            }
        }
        progress.onProgress(70);
        for (int z = 0; z < nz; z++) {
            int zm = Math.max(0, z - 1), zp = Math.min(nz - 1, z + 1);
            float dzWorld = depth / Math.max(1, nz - 1);
            for (int x = 0; x < nx; x++) {
                int xm = Math.max(0, x - 1), xp = Math.min(nx - 1, x + 1);
                float dxWorld = width / Math.max(1, nx - 1);
                float dhdx = (heights[z * nx + xp] - heights[z * nx + xm]) / Math.max(0.000001f, (xp - xm) * dxWorld);
                float dhdz = (heights[zp * nx + x] - heights[zm * nx + x]) / Math.max(0.000001f, (zp - zm) * dzWorld);
                float nxv = -dhdx, nyv = 1f, nzv = -dhdz;
                float len = (float)Math.sqrt(nxv * nxv + nyv * nyv + nzv * nzv);
                if (len < 0.000001f) len = 1f;
                int p = (z * nx + x) * 6 + 3;
                interleaved[p] = nxv / len; interleaved[p + 1] = nyv / len; interleaved[p + 2] = nzv / len;
            }
        }
        int ii = 0;
        for (int z = 0; z < nz - 1; z++) {
            for (int x = 0; x < nx - 1; x++) {
                int a = z * nx + x, b = a + 1, d = (z + 1) * nx + x, c = d + 1;
                indices[ii++] = a; indices[ii++] = d; indices[ii++] = b;
                indices[ii++] = b; indices[ii++] = d; indices[ii++] = c;
            }
        }
        progress.onProgress(88);
        return new TerrainMesh(interleaved, indices, heights, nx, nz, width, depth, maxHeight, sample);
    }

    /**
     * Builds a separate water surface from exact-black heightmap cells.
     * The surface is intentionally raised slightly above the black terrain floor
     * so the water reads as a real layer instead of a flat texture painted on
     * the bottom of the terrain.
     */
    private WaterMesh buildWaterMesh(BufferedImage image, int sample, float width, float depth,
                                     float maxHeight, ProgressListener progress) throws Exception {
        int iw = image.getWidth(), ih = image.getHeight();
        sample = effectiveSample(image, sample);
        int nx = ((iw - 1) / sample) + 1;
        int nz = ((ih - 1) / sample) + 1;
        if (nx < 2 || nz < 2) return new WaterMesh(new float[0], new int[0], 0);

        boolean[] black = new boolean[nx * nz];
        int blackCount = 0;
        for (int z = 0; z < nz; z++) {
            int srcY = Math.min(ih - 1, z * sample);
            for (int x = 0; x < nx; x++) {
                int srcX = Math.min(iw - 1, x * sample);
                boolean isBlack = image.getRaster().getSample(srcX, srcY, 0) <= 2;
                black[z * nx + x] = isBlack;
                if (isBlack) blackCount++;
            }
        }
        if (blackCount < 4) {
            progress.onProgress(100);
            return new WaterMesh(new float[0], new int[0], 0);
        }

        // The configured value is an actual terrain-space Y height.  It is
        // intentionally not tied to the terrain's maximum height so the user
        // can place the water surface at a precise level.
        float waterOffset = Math.max(0f, Math.min(maxHeight, waterLayerHeight));

        List<Float> verts = new ArrayList<>();
        List<Integer> inds = new ArrayList<>();
        int cellsX = nx - 1, cellsZ = nz - 1;

        for (int z = 0; z < cellsZ; z++) {
            for (int x = 0; x < cellsX; x++) {
                int a = z * nx + x;
                int b = a + 1;
                int d = (z + 1) * nx + x;
                int c = d + 1;

                // Require the entire sampled cell to be black. This prevents
                // water from climbing onto the gray shoreline/slope.
                if (!black[a] || !black[b] || !black[c] || !black[d]) continue;

                float x0 = ((float)x / cellsX - 0.5f) * width;
                float x1 = ((float)(x + 1) / cellsX - 0.5f) * width;
                float z0 = ((float)z / cellsZ - 0.5f) * depth;
                float z1 = ((float)(z + 1) / cellsZ - 0.5f) * depth;

                int base = verts.size() / 8;
                addWaterVertex(verts, x0, waterOffset, z0, 0f, 1f, 0f, 0f, 0f);
                addWaterVertex(verts, x1, waterOffset, z0, 0f, 1f, 0f, 1f, 0f);
                addWaterVertex(verts, x1, waterOffset, z1, 0f, 1f, 0f, 1f, 1f);
                addWaterVertex(verts, x0, waterOffset, z1, 0f, 1f, 0f, 0f, 1f);

                inds.add(base);     inds.add(base + 2); inds.add(base + 1);
                inds.add(base);     inds.add(base + 3); inds.add(base + 2);
            }
            if ((z & 15) == 0) {
                progress.onProgress(88 + (int)((z / (double)Math.max(1, cellsZ - 1)) * 12.0));
            }
        }

        float[] data = new float[verts.size()];
        for (int i = 0; i < data.length; i++) data[i] = verts.get(i);
        int[] indices = new int[inds.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = inds.get(i);
        progress.onProgress(100);
        return new WaterMesh(data, indices, waterOffset);
    }

    private static void addWaterVertex(List<Float> out, float x, float y, float z,
                                       float nx, float ny, float nz, float u, float v) {
        out.add(x); out.add(y); out.add(z);
        out.add(nx); out.add(ny); out.add(nz);
        out.add(u); out.add(v);
    }

    private void applyToCurrentStage() {
        ThirdPersonGameNew game = ThirdPersonGameNew.getActiveInstance();
        TerrainMesh m = mesh;
        if (game == null || m == null) return;
        float scale = Math.max(0.001f, ((Number)importScaleSpinner.getValue()).floatValue());
        WaterMesh w = waterMesh;
        boolean[] waterCells = buildWaterCellMask(heightmapImage, m.sample, m.nx, m.nz);
        float waterHeight = w == null ? 0f : w.height;
        HeightmapStageData data = new HeightmapStageData(
                imageField.getText().trim(), m.width, m.depth, m.maxHeight, scale,
                m.sample,
                m.nx, m.nz, m.heights.clone(), m.materialIds.clone(), materials,
                w != null && w.indices.length > 0, waterHeight, waterCells,
                0f, 0f, 0f);
        game.applyHeightmapStageData(data);
        statusLabel.setText(String.format(Locale.US, "Applied terrain and water layer (height %.2f) to current stage.", waterHeight));
    }

    private boolean[] buildWaterCellMask(BufferedImage image, int sample, int nx, int nz) {
        int cellCount = Math.max(0, (nx - 1) * (nz - 1));
        boolean[] mask = new boolean[cellCount];
        if (image == null || nx < 2 || nz < 2) return mask;
        int iw = image.getWidth(), ih = image.getHeight();
        for (int z = 0; z < nz - 1; z++) {
            int srcY0 = Math.min(ih - 1, z * sample);
            int srcY1 = Math.min(ih - 1, (z + 1) * sample);
            for (int x = 0; x < nx - 1; x++) {
                int srcX0 = Math.min(iw - 1, x * sample);
                int srcX1 = Math.min(iw - 1, (x + 1) * sample);
                boolean black = image.getRaster().getSample(srcX0, srcY0, 0) <= 2
                        && image.getRaster().getSample(srcX1, srcY0, 0) <= 2
                        && image.getRaster().getSample(srcX1, srcY1, 0) <= 2
                        && image.getRaster().getSample(srcX0, srcY1, 0) <= 2;
                mask[z * (nx - 1) + x] = black;
            }
        }
        return mask;
    }

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    private void exportObj() {
        TerrainMesh m = mesh;
        if (m == null) return;
        String name = outputField.getText().trim();
        if (name.isEmpty()) name = "heightmap.obj";
        if (!name.toLowerCase(Locale.ROOT).endsWith(".obj")) name += ".obj";
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save OBJ Mesh");
        chooser.setSelectedFile(new File(name));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File target = chooser.getSelectedFile();
        progressBar.setValue(0);
        statusLabel.setText("Writing OBJ...");
        exportButton.setEnabled(false);
        Thread exporter = new Thread(() -> {
            try {
                writeObj(target, m, p -> SwingUtilities.invokeLater(() -> progressBar.setValue(Math.max(0, Math.min(100, p)))));
                WaterMesh water = waterMesh;
                if (water != null && water.indices.length > 0) {
                    File waterTarget = new File(target.getParentFile(),
                            stripObjExtension(target.getName()) + "_water.obj");
                    writeWaterObj(waterTarget, water);
                }
                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(100);
                    statusLabel.setText(waterMesh != null && waterMesh.indices.length > 0
                            ? "OBJ exported with water layer: " + target.getName()
                            : "OBJ exported: " + target.getName());
                    exportButton.setEnabled(mesh != null);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("OBJ export failed.");
                    exportButton.setEnabled(mesh != null);
                    JOptionPane.showMessageDialog(frame, ex.getMessage(), "OBJ Export Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "Heightmap-OBJExporter");
        exporter.setDaemon(true);
        exporter.start();
    }

    private void writeObj(File file, TerrainMesh m, ProgressListener progress) throws IOException {
        try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
            out.write("# Heightmap OBJ generated by Heightmaps.java\n# Black=lowest, White=highest\n\n");
            for (int i = 0; i < m.vertexCount; i++) {
                int p = i * 6;
                out.write(String.format(Locale.US, "v %.6f %.6f %.6f\n", m.data[p], m.data[p + 1], m.data[p + 2]));
            }
            out.write("\n");
            for (int i = 0; i < m.vertexCount; i++) {
                int x = i % m.nx, z = i / m.nx;
                float u = m.nx <= 1 ? 0f : x / (float)(m.nx - 1);
                float v = m.nz <= 1 ? 0f : 1f - z / (float)(m.nz - 1);
                out.write(String.format(Locale.US, "vt %.6f %.6f\n", u, v));
            }
            out.write("\n");
            for (int i = 0; i < m.vertexCount; i++) {
                int p = i * 6 + 3;
                out.write(String.format(Locale.US, "vn %.6f %.6f %.6f\n", m.data[p], m.data[p + 1], m.data[p + 2]));
            }
            out.write("\n");
            for (int i = 0; i < m.indices.length; i += 3) {
                int a = m.indices[i] + 1, b = m.indices[i + 1] + 1, c = m.indices[i + 2] + 1;
                out.write("f " + a + "/" + a + "/" + a + " " + b + "/" + b + "/" + b + " " + c + "/" + c + "/" + c + "\n");
                if ((i & 16383) == 0) progress.onProgress(65 + (int)((i / (double)m.indices.length) * 35.0));
            }
        }
        progress.onProgress(100);
    }

    private static String stripObjExtension(String name) {
        if (name == null) return "heightmap";
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".obj") ? name.substring(0, name.length() - 4) : name;
    }

    private static void writeWaterObj(File file, WaterMesh w) throws IOException {
        try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
            out.write("# Water layer generated from exact-black heightmap areas by Heightmaps.java\\n");
            out.write("# The water surface is elevated slightly above the black terrain floor.\\n\\n");
            int vertexCount = w.data.length / 8;
            for (int i = 0; i < vertexCount; i++) {
                int p = i * 8;
                out.write(String.format(Locale.US, "v %.6f %.6f %.6f\\n",
                        w.data[p], w.data[p + 1], w.data[p + 2]));
            }
            out.write("\\n");
            for (int i = 0; i < vertexCount; i++) {
                int p = i * 8;
                out.write(String.format(Locale.US, "vt %.6f %.6f\\n", w.data[p + 6], 1f - w.data[p + 7]));
            }
            out.write("\\n");
            for (int i = 0; i < vertexCount; i++) {
                int p = i * 8;
                out.write(String.format(Locale.US, "vn %.6f %.6f %.6f\\n",
                        w.data[p + 3], w.data[p + 4], w.data[p + 5]));
            }
            out.write("\\n");
            for (int i = 0; i < w.indices.length; i += 3) {
                int a = w.indices[i] + 1, b = w.indices[i + 1] + 1, c = w.indices[i + 2] + 1;
                out.write("f " + a + "/" + a + "/" + a + " "
                        + b + "/" + b + "/" + b + " "
                        + c + "/" + c + "/" + c + "\\n");
            }
        }
    }

    public static String encodeMaterialIds(int[] ids) throws IOException {
        if (ids == null || ids.length == 0) return "";
        ByteArrayOutputStream raw = new ByteArrayOutputStream(ids.length * 2);
        try (DeflaterOutputStream def = new DeflaterOutputStream(raw)) {
            for (int id : ids) {
                int v = Math.max(0, Math.min(65535, id));
                def.write(v & 255);
                def.write((v >>> 8) & 255);
            }
        }
        return Base64.getEncoder().encodeToString(raw.toByteArray());
    }

    public static String encodeWaterCells(boolean[] cells) throws IOException {
        if (cells == null || cells.length == 0) return "";
        ByteArrayOutputStream raw = new ByteArrayOutputStream(cells.length);
        try (DeflaterOutputStream def = new DeflaterOutputStream(raw)) {
            int packed = 0, bit = 0;
            for (boolean cell : cells) {
                if (cell) packed |= (1 << bit);
                bit++;
                if (bit == 8) { def.write(packed); packed = 0; bit = 0; }
            }
            if (bit != 0) def.write(packed);
        }
        return Base64.getEncoder().encodeToString(raw.toByteArray());
    }

    public static boolean[] decodeWaterCells(String encoded, int expected) throws IOException {
        boolean[] result = new boolean[Math.max(0, expected)];
        if (encoded == null || encoded.trim().isEmpty()) return result;
        byte[] compressed = Base64.getDecoder().decode(encoded);
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            int index = 0;
            int value;
            while (index < result.length && (value = in.read()) >= 0) {
                for (int bit = 0; bit < 8 && index < result.length; bit++, index++) {
                    result[index] = (value & (1 << bit)) != 0;
                }
            }
        }
        return result;
    }

    /** Rebuilds stage terrain from a saved .tpgproject scene entry. */
    public static HeightmapStageData loadStageDataFromFile(String imagePath,
                                                            float width, float depth, float height,
                                                            float importScale, int sample,
                                                            String encodedMaterialIds,
                                                            List<MaterialBrushMaterial> materials,
                                                            float playerX, float playerY, float playerZ) throws IOException {
        if (imagePath == null || imagePath.trim().isEmpty()) throw new IOException("Heightmap image path is empty.");
        File file = new File(imagePath);
        if (!file.isFile()) throw new IOException("Heightmap image was not found: " + imagePath);
        BufferedImage source = ImageIO.read(file);
        if (source == null) throw new IOException("Heightmap image could not be decoded: " + imagePath);
        Heightmaps tool = new Heightmaps();
        TerrainMesh m;
        try {
            m = tool.buildMesh(tool.toGrayscale(source), Math.max(1, sample), width, depth, height, percent -> { });
        } catch (Exception ex) {
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException("Could not build heightmap terrain: " + ex.getMessage(), ex);
        }
        int expected = m.triangleCount;
        int[] ids = decodeMaterialIds(encodedMaterialIds, expected);
        // Preserve the saved player transform exactly.  Y is intentionally not
        // snapped back to the terrain height so an author can place the spawn
        // point above, below, or independently of the heightfield.
        boolean[] waterCells = new boolean[Math.max(0, (m.nx - 1) * (m.nz - 1))];
        int iw = source.getWidth(), ih = source.getHeight();
        for (int z = 0; z < m.nz - 1; z++) {
            int sy0 = Math.min(ih - 1, z * Math.max(1, sample));
            int sy1 = Math.min(ih - 1, (z + 1) * Math.max(1, sample));
            for (int x = 0; x < m.nx - 1; x++) {
                int sx0 = Math.min(iw - 1, x * Math.max(1, sample));
                int sx1 = Math.min(iw - 1, (x + 1) * Math.max(1, sample));
                waterCells[z * (m.nx - 1) + x] = source.getRaster().getSample(sx0, sy0, 0) <= 2
                        && source.getRaster().getSample(sx1, sy0, 0) <= 2
                        && source.getRaster().getSample(sx1, sy1, 0) <= 2
                        && source.getRaster().getSample(sx0, sy1, 0) <= 2;
            }
        }
        float defaultWaterHeight = Math.max(0.05f, Math.min(height, height * 0.01f));
        return new HeightmapStageData(imagePath, width, depth, height, importScale, m.sample,
                m.nx, m.nz, m.heights, ids, materials,
                true, defaultWaterHeight, waterCells, playerX, playerY, playerZ);
    }

    /** Rebuilds stage terrain and uses explicit saved water-layer settings when present. */
    public static HeightmapStageData loadStageDataFromFile(String imagePath,
                                                            float width, float depth, float height,
                                                            float importScale, int sample,
                                                            String encodedMaterialIds,
                                                            List<MaterialBrushMaterial> materials,
                                                            float playerX, float playerY, float playerZ,
                                                            boolean waterEnabled, float waterHeight,
                                                            String encodedWaterCells) throws IOException {
        if (imagePath == null || imagePath.trim().isEmpty()) throw new IOException("Heightmap image path is empty.");
        File file = new File(imagePath);
        if (!file.isFile()) throw new IOException("Heightmap image was not found: " + imagePath);
        BufferedImage source = ImageIO.read(file);
        if (source == null) throw new IOException("Heightmap image could not be decoded: " + imagePath);
        Heightmaps tool = new Heightmaps();
        TerrainMesh m;
        try {
            m = tool.buildMesh(tool.toGrayscale(source), Math.max(1, sample), width, depth, height, percent -> { });
        } catch (Exception ex) {
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException("Could not build heightmap terrain: " + ex.getMessage(), ex);
        }
        int expected = m.triangleCount;
        int[] ids = decodeMaterialIds(encodedMaterialIds, expected);
        int waterExpected = Math.max(0, (m.nx - 1) * (m.nz - 1));
        boolean[] cells = decodeWaterCells(encodedWaterCells, waterExpected);
        if (encodedWaterCells == null || encodedWaterCells.trim().isEmpty()) {
            cells = new boolean[waterExpected];
            int iw = source.getWidth(), ih = source.getHeight();
            int s = Math.max(1, sample);
            for (int z = 0; z < m.nz - 1; z++) {
                int sy0 = Math.min(ih - 1, z * s), sy1 = Math.min(ih - 1, (z + 1) * s);
                for (int x = 0; x < m.nx - 1; x++) {
                    int sx0 = Math.min(iw - 1, x * s), sx1 = Math.min(iw - 1, (x + 1) * s);
                    cells[z * (m.nx - 1) + x] = source.getRaster().getSample(sx0, sy0, 0) <= 2
                            && source.getRaster().getSample(sx1, sy0, 0) <= 2
                            && source.getRaster().getSample(sx1, sy1, 0) <= 2
                            && source.getRaster().getSample(sx0, sy1, 0) <= 2;
                }
            }
        }
        return new HeightmapStageData(imagePath, width, depth, height, importScale, m.sample,
                m.nx, m.nz, m.heights, ids, materials, waterEnabled,
                Math.max(0f, Math.min(height, waterHeight)), cells, playerX, playerY, playerZ);
    }

    public static int[] decodeMaterialIds(String encoded, int expected) throws IOException {
        int[] result = new int[Math.max(0, expected)];
        if (encoded == null || encoded.trim().isEmpty()) return result;
        byte[] compressed = Base64.getDecoder().decode(encoded);
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] pair = new byte[2];
            for (int i = 0; i < result.length; i++) {
                int a = in.read();
                int b = in.read();
                if (a < 0 || b < 0) break;
                result[i] = a | (b << 8);
            }
        }
        return result;
    }

    private static class WaterMesh {
        final float[] data;
        final int[] indices;
        final float height;

        WaterMesh(float[] data, int[] indices, float height) {
            this.data = data;
            this.indices = indices;
            this.height = height;
        }
    }

    private static class WaterMaterial {
        String baseColorMap = "";
        float alpha = 0.72f;

        static WaterMaterial loadDefault() {
            WaterMaterial m = new WaterMaterial();
            File matFile = new File("water.mat");
            if (!matFile.isFile()) return m;
            java.util.Properties props = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(matFile)) {
                props.load(in);
                m.baseColorMap = props.getProperty("baseColorMap", "").trim();
                try {
                    m.alpha = Float.parseFloat(props.getProperty("alpha", "0.72"));
                } catch (NumberFormatException ignored) { }
                m.alpha = clamp(m.alpha, 0.05f, 1f);
            } catch (Exception ignored) { }
            return m;
        }

        File resolveTextureFile() {
            if (baseColorMap.isEmpty()) return null;
            File f = new File(baseColorMap);
            if (f.isFile()) return f;
            File f2 = new File(new File("water.mat").getAbsoluteFile().getParentFile(), baseColorMap);
            return f2.isFile() ? f2 : null;
        }
    }

    private static class TerrainMesh {
        final float[] data;
        final int[] indices;
        final float[] heights;
        final int nx, nz, vertexCount, triangleCount, sample;
        final float width, depth, maxHeight;
        int[] materialIds;
        long materialRevision = 0;

        TerrainMesh(float[] data, int[] indices, float[] heights, int nx, int nz, float width, float depth, float maxHeight, int sample) {
            this.data = data; this.indices = indices; this.heights = heights;
            this.nx = nx; this.nz = nz; this.width = width; this.depth = depth; this.maxHeight = maxHeight; this.sample = sample;
            this.vertexCount = nx * nz; this.triangleCount = indices.length / 3;
            this.materialIds = new int[triangleCount];
        }

        float getHeightAt(float worldX, float worldZ) {
            if (nx < 2 || nz < 2) return 0f;
            float gx = ((worldX / width) + 0.5f) * (nx - 1);
            float gz = ((worldZ / depth) + 0.5f) * (nz - 1);
            gx = clamp(gx, 0, nx - 1); gz = clamp(gz, 0, nz - 1);
            int x0 = Math.min(nx - 1, (int)Math.floor(gx));
            int z0 = Math.min(nz - 1, (int)Math.floor(gz));
            int x1 = Math.min(nx - 1, x0 + 1), z1 = Math.min(nz - 1, z0 + 1);
            float fx = gx - x0, fz = gz - z0;
            float a = heights[z0 * nx + x0] + (heights[z0 * nx + x1] - heights[z0 * nx + x0]) * fx;
            float b = heights[z1 * nx + x0] + (heights[z1 * nx + x1] - heights[z1 * nx + x0]) * fx;
            return a + (b - a) * fz;
        }

        void paint(float worldX, float worldZ, float radius, int material) {
            if (materialIds.length == 0) return;
            int cellsX = nx - 1, cellsZ = nz - 1;
            int minX = Math.max(0, (int)Math.floor(((worldX - radius) / width + 0.5f) * cellsX) - 1);
            int maxX = Math.min(cellsX - 1, (int)Math.ceil(((worldX + radius) / width + 0.5f) * cellsX) + 1);
            int minZ = Math.max(0, (int)Math.floor(((worldZ - radius) / depth + 0.5f) * cellsZ) - 1);
            int maxZ = Math.min(cellsZ - 1, (int)Math.ceil(((worldZ + radius) / depth + 0.5f) * cellsZ) + 1);
            float radiusSq = radius * radius;
            for (int z = minZ; z <= maxZ; z++) {
                float cz = ((z + 0.5f) / cellsZ - 0.5f) * depth;
                for (int x = minX; x <= maxX; x++) {
                    float cx = ((x + 0.5f) / cellsX - 0.5f) * width;
                    float dx = cx - worldX, dz = cz - worldZ;
                    if (dx * dx + dz * dz <= radiusSq) {
                        int cell = z * cellsX + x;
                        materialIds[cell * 2] = material;
                        materialIds[cell * 2 + 1] = material;
                    }
                }
            }
            materialRevision++;
        }
    }

    private class TerrainRenderer implements GLEventListener {
        final GLJPanel canvas;
        final int[] vbo = new int[2];
        final int[] waterVbo = new int[2];
        TerrainMesh gpuMesh;
        WaterMesh gpuWaterMesh;
        volatile WaterMesh pendingWaterMesh;
        Texture waterTexture;
        boolean waterTextureAttempted = false;
        volatile TerrainMesh pendingMesh;
        long uploadedMaterialRevision = -1;
        float yaw = 35f, pitch = 35f, distance = 150f;
        Point dragStart;
        float startYaw, startPitch;
        final GLU glu = new GLU();

        TerrainRenderer(GLProfile profile) {
            if (profile == null) throw new IllegalStateException("No usable JOGL GL2 profile is available.");
            GLCapabilities caps = new GLCapabilities(profile);
            caps.setDoubleBuffered(true); caps.setHardwareAccelerated(true); caps.setDepthBits(24); caps.setStencilBits(8);
            canvas = new GLJPanel(caps);
            canvas.setOpaque(true);
            canvas.addGLEventListener(this);
            canvas.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    dragStart = e.getPoint(); startYaw = yaw; startPitch = pitch;
                }
            });
            canvas.addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStart == null) return;
                    yaw = startYaw + (e.getX() - dragStart.x) * 0.45f;
                    pitch = Math.max(5f, Math.min(85f, startPitch - (e.getY() - dragStart.y) * 0.35f));
                    canvas.display();
                }
            });
            canvas.addMouseWheelListener((MouseWheelEvent e) -> {
                distance *= (float)Math.pow(1.10, e.getWheelRotation());
                distance = Math.max(5f, Math.min(5000f, distance));
                canvas.display();
            });
        }

        void setMesh(TerrainMesh m, WaterMesh water) {
            pendingMesh = m;
            pendingWaterMesh = water;
            distance = Math.max(10f, Math.max(m.width, m.depth) * 1.25f);
            canvas.display();
        }

        void invalidateMaterialPreview() { canvas.display(); }



        private float[] raySlab(float origin, float direction, float min, float max,
                                float currentMin, float currentMax) {
            if (Math.abs(direction) < 0.000001f) {
                if (origin < min || origin > max) return null;
                return new float[]{currentMin, currentMax};
            }
            float a = (min - origin) / direction;
            float b = (max - origin) / direction;
            if (a > b) { float tmp = a; a = b; b = tmp; }
            float lo = Math.max(currentMin, a);
            float hi = Math.min(currentMax, b);
            if (hi < lo) return null;
            return new float[]{lo, hi};
        }

        @Override public void init(GLAutoDrawable drawable) {
            GL2 gl = drawable.getGL().getGL2();
            gl.glClearColor(0.035f,0.035f,0.045f,1f);
            gl.glEnable(GL2.GL_DEPTH_TEST); gl.glEnable(GL2.GL_CULL_FACE); gl.glCullFace(GL2.GL_BACK);
            gl.glEnable(GL2.GL_LIGHTING); gl.glEnable(GL2.GL_LIGHT0); gl.glEnable(GL2.GL_COLOR_MATERIAL);
            gl.glColorMaterial(GL2.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE);
            gl.glGenBuffers(2, vbo, 0); gl.glGenBuffers(2, waterVbo, 0);
        }

        @Override public void dispose(GLAutoDrawable drawable) { GL2 gl = drawable.getGL().getGL2(); gl.glDeleteBuffers(2, vbo, 0); gl.glDeleteBuffers(2, waterVbo, 0); if (waterTexture != null) waterTexture.destroy(gl); }

        @Override public void display(GLAutoDrawable drawable) {
            GL2 gl = drawable.getGL().getGL2();
            uploadPending(gl);
            gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
            int w = Math.max(1, canvas.getWidth()), h = Math.max(1, canvas.getHeight());
            gl.glMatrixMode(GL2.GL_PROJECTION); gl.glLoadIdentity(); perspective(gl,45f,w/(float)h,0.1f,10000f);
            gl.glMatrixMode(GL2.GL_MODELVIEW); gl.glLoadIdentity();
            TerrainMesh m = gpuMesh;
            if (m == null) { drawWaiting(gl); return; }
            float cy=(float)Math.cos(Math.toRadians(pitch)), sy=(float)Math.sin(Math.toRadians(pitch));
            float cx=(float)Math.cos(Math.toRadians(yaw)), sx=(float)Math.sin(Math.toRadians(yaw));
            float eyeX=distance*cy*sx, eyeY=distance*sy, eyeZ=distance*cy*cx;
            lookAt(gl,eyeX,eyeY,eyeZ,0,0,0,0,1,0);
            float[] lightPos={80f,180f,70f,1f}; gl.glLightfv(GL2.GL_LIGHT0,GL2.GL_POSITION,FloatBuffer.wrap(lightPos));
            gl.glColor3f(0.72f,0.72f,0.72f);
            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER,vbo[0]);
            gl.glEnableClientState(GL2.GL_VERTEX_ARRAY); gl.glEnableClientState(GL2.GL_NORMAL_ARRAY);
            gl.glVertexPointer(3,GL2.GL_FLOAT,24,0L); gl.glNormalPointer(GL2.GL_FLOAT,24,12L);
            gl.glBindBuffer(GL2.GL_ELEMENT_ARRAY_BUFFER,vbo[1]);
            gl.glDrawElements(GL2.GL_TRIANGLES,m.indices.length,GL2.GL_UNSIGNED_INT,0L);
            gl.glDisableClientState(GL2.GL_NORMAL_ARRAY); gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER,0); gl.glBindBuffer(GL2.GL_ELEMENT_ARRAY_BUFFER,0);

            drawWater(gl, gpuWaterMesh);
        }

        private void uploadPending(GL2 gl) {
            TerrainMesh m=pendingMesh;
            if (m != null && m != gpuMesh) {
                gl.glBindBuffer(GL2.GL_ARRAY_BUFFER,vbo[0]);
                gl.glBufferData(GL2.GL_ARRAY_BUFFER,(long)m.data.length*4L,FloatBuffer.wrap(m.data),GL2.GL_STATIC_DRAW);
                gl.glBindBuffer(GL2.GL_ELEMENT_ARRAY_BUFFER,vbo[1]);
                gl.glBufferData(GL2.GL_ELEMENT_ARRAY_BUFFER,(long)m.indices.length*4L,IntBuffer.wrap(m.indices),GL2.GL_STATIC_DRAW);
                gpuMesh=m;
                uploadedMaterialRevision=-1;
            }

            WaterMesh w=pendingWaterMesh;
            if (w != null && w != gpuWaterMesh) {
                gl.glBindBuffer(GL2.GL_ARRAY_BUFFER,waterVbo[0]);
                gl.glBufferData(GL2.GL_ARRAY_BUFFER,(long)w.data.length*4L,FloatBuffer.wrap(w.data),GL2.GL_STATIC_DRAW);
                gl.glBindBuffer(GL2.GL_ELEMENT_ARRAY_BUFFER,waterVbo[1]);
                gl.glBufferData(GL2.GL_ELEMENT_ARRAY_BUFFER,(long)w.indices.length*4L,IntBuffer.wrap(w.indices),GL2.GL_STATIC_DRAW);
                gpuWaterMesh=w;
            }

            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER,0); gl.glBindBuffer(GL2.GL_ELEMENT_ARRAY_BUFFER,0);
        }

        private void drawWater(GL2 gl, WaterMesh w) {
            if (w == null || w.indices.length == 0) return;

            if (!waterTextureAttempted) {
                waterTextureAttempted = true;
                try {
                    File texFile = waterMaterial.resolveTextureFile();
                    if (texFile != null) waterTexture = TextureIO.newTexture(texFile, true);
                } catch (Exception ignored) { }
            }

            gl.glPushAttrib(GL2.GL_ENABLE_BIT | GL2.GL_COLOR_BUFFER_BIT | GL2.GL_CURRENT_BIT |
                    GL2.GL_DEPTH_BUFFER_BIT | GL2.GL_TEXTURE_BIT);
            gl.glEnable(GL2.GL_BLEND);
            gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
            gl.glDepthMask(false);
            gl.glDisable(GL2.GL_CULL_FACE);
            gl.glEnable(GL2.GL_TEXTURE_2D);

            if (waterTexture != null) {
                waterTexture.enable(gl);
                waterTexture.bind(gl);
                gl.glColor4f(1f, 1f, 1f, waterMaterial.alpha);
            } else {
                gl.glColor4f(0.08f, 0.35f, 0.75f, waterMaterial.alpha);
            }

            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER,waterVbo[0]);
            gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glEnableClientState(GL2.GL_NORMAL_ARRAY);
            gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            gl.glVertexPointer(3,GL2.GL_FLOAT,32,0L);
            gl.glNormalPointer(GL2.GL_FLOAT,32,12L);
            gl.glTexCoordPointer(2,GL2.GL_FLOAT,32,24L);
            gl.glBindBuffer(GL2.GL_ELEMENT_ARRAY_BUFFER,waterVbo[1]);
            gl.glDrawElements(GL2.GL_TRIANGLES,w.indices.length,GL2.GL_UNSIGNED_INT,0L);
            gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            gl.glDisableClientState(GL2.GL_NORMAL_ARRAY);
            gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER,0);
            gl.glBindBuffer(GL2.GL_ELEMENT_ARRAY_BUFFER,0);

            if (waterTexture != null) waterTexture.disable(gl);
            gl.glPopAttrib();
        }

        private void drawWaiting(GL2 gl){gl.glDisable(GL2.GL_LIGHTING);gl.glColor3f(.35f,.38f,.42f);gl.glBegin(GL2.GL_LINES);gl.glVertex3f(-20,0,0);gl.glVertex3f(20,0,0);gl.glVertex3f(0,0,-20);gl.glVertex3f(0,0,20);gl.glEnd();gl.glEnable(GL2.GL_LIGHTING);}
        private void perspective(GL2 gl,float fovy,float aspect,float zn,float zf){float f=1f/(float)Math.tan(Math.toRadians(fovy)/2);float[] m=new float[16];m[0]=f/aspect;m[5]=f;m[10]=(zf+zn)/(zn-zf);m[11]=-1;m[14]=(2*zf*zn)/(zn-zf);gl.glMultMatrixf(m,0);}
        private void lookAt(GL2 gl,float ex,float ey,float ez,float cx,float cy,float cz,float ux,float uy,float uz){float[] f=normalize(cx-ex,cy-ey,cz-ez);float[] s=normalize(cross(f[0],f[1],f[2],ux,uy,uz));float[] u=cross(s[0],s[1],s[2],f[0],f[1],f[2]);float[] m={s[0],u[0],-f[0],0,s[1],u[1],-f[1],0,s[2],u[2],-f[2],0,0,0,0,1};gl.glMultMatrixf(m,0);gl.glTranslatef(-ex,-ey,-ez);}
        private float[] cross(float ax,float ay,float az,float bx,float by,float bz){return new float[]{ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx};}
        private float[] normalize(float x,float y,float z){float l=(float)Math.sqrt(x*x+y*y+z*z);if(l<.000001f)return new float[]{0,0,0};return new float[]{x/l,y/l,z/l};}
        private float[] normalize(float[] v){
            if(v==null || v.length<3) return new float[]{0,0,0};
            return normalize(v[0],v[1],v[2]);
        }
        @Override public void reshape(GLAutoDrawable drawable,int x,int y,int width,int height) { }
    }

    private static GLProfile resolveProfile() {
        try { GLProfile shared=ThirdPersonGameNew.getSharedGLProfile(); if(shared!=null)return shared; } catch(Throwable ignored) { }
        try { WorldsGLMain.initializeJOGL(); } catch(Throwable ignored) { }
        return GLProfile.get(GLProfile.GL2);
    }

    private static String getCauseOrSelfMessage(Throwable t) {
        Throwable c=t; while(c.getCause()!=null)c=c.getCause(); return c.getMessage()==null?c.toString():c.getMessage();
    }
}
