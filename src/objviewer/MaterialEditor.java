package objviewer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.util.Properties;
import java.util.prefs.Preferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Reusable material authoring window for ThirdPersonGameNew.
 * The editor stores all five texture maps and scalar material properties as
 * reusable .mat assets. The software 3D preview evaluates every selected map
 * directly so the preview remains useful even before the scene renderer runs.
 */
public class MaterialEditor extends JDialog {
    public static class Material {
        public String name = "New Material";
        public String baseColorMap = "";
        public String normalMap = "";
        public String metallicMap = "";
        public String roughnessMap = "";
        public String emissionMap = "";
        public float metallic = 0.0f;
        public float roughness = 0.5f;
        public float specular = 0.95f;
        public float shininess = 96.0f;
        public float alpha = 1.0f;

        public Properties toProperties() {
            Properties p = new Properties();
            p.setProperty("material.name", safe(name));
            p.setProperty("baseColorMap", safe(baseColorMap));
            p.setProperty("normalMap", safe(normalMap));
            p.setProperty("metallicMap", safe(metallicMap));
            p.setProperty("roughnessMap", safe(roughnessMap));
            p.setProperty("emissionMap", safe(emissionMap));
            p.setProperty("metallic", Float.toString(metallic));
            p.setProperty("roughness", Float.toString(roughness));
            p.setProperty("specular", Float.toString(specular));
            p.setProperty("shininess", Float.toString(shininess));
            p.setProperty("alpha", Float.toString(alpha));
            return p;
        }

        public static Material fromProperties(Properties p) {
            Material m = new Material();
            m.name = p.getProperty("material.name", "New Material");
            m.baseColorMap = p.getProperty("baseColorMap", "");
            m.normalMap = p.getProperty("normalMap", "");
            m.metallicMap = p.getProperty("metallicMap", "");
            m.roughnessMap = p.getProperty("roughnessMap", "");
            m.emissionMap = p.getProperty("emissionMap", "");
            m.metallic = number(p, "metallic", 0f);
            m.roughness = number(p, "roughness", 0.5f);
            m.specular = number(p, "specular", 0.95f);
            m.shininess = number(p, "shininess", 96f);
            m.alpha = number(p, "alpha", 1f);
            return m;
        }

        private static float number(Properties p, String key, float fallback) {
            try { return Float.parseFloat(p.getProperty(key, Float.toString(fallback))); }
            catch (Exception ignored) { return fallback; }
        }
        private static String safe(String s) { return s == null ? "" : s; }
    }

    public interface Listener {
        void materialApplied(Material material);
    }

    /** Optional live selection information supplied by ThirdPersonGameNew. */
    public interface SelectionProvider {
        String getSelectedObjectName();
    }

    private final Listener listener;
    private final SelectionProvider selectionProvider;
    private final Material material;
    private JLabel selectedObjectLabel;
    private final PreviewPanel preview = new PreviewPanel();
    private final JTextField nameField = new JTextField();
    private final JLabel[] mapPathLabels = new JLabel[5];
    private final JSlider metallic = slider(0, 100);
    private final JSlider roughness = slider(0, 100);
    private final JSlider specular = slider(0, 100);
    private final JSlider shininess = slider(1, 128);
    private final JSlider alpha = slider(0, 100);

    // The currently opened .tpgproject.  Material assets are discovered from
    // the archive itself so the list never depends on external source paths.
    private File projectFile;
    private final DefaultListModel<String> projectMaterialListModel = new DefaultListModel<>();
    private final JList<String> projectMaterialList = new JList<>(projectMaterialListModel);
    private final List<String> projectMaterialEntries = new ArrayList<>();
    private final List<String> recentMaterialFiles = new ArrayList<>();
    private String selectedProjectMaterialEntry = null;
    private String selectedRecentMaterialFile = null;
    private static final Preferences MATERIAL_PREFS =
            Preferences.userNodeForPackage(MaterialEditor.class).node("recentMaterials");
    private static final int MAX_RECENT_MATERIALS = 12;

    private static JSlider slider(int min, int max) {
        return new JSlider(min, max, min);
    }

    public MaterialEditor(Window owner, Material initial, Listener listener) {
        this(owner, initial, listener, null);
    }

    public MaterialEditor(Window owner, Material initial, Listener listener, SelectionProvider selectionProvider) {
        this(owner, initial, listener, selectionProvider, null);
    }

    /**
     * Project-aware constructor.  The existing constructor remains compatible,
     * while this overload lets ThirdPersonGameNew supply the currently opened
     * .tpgproject so the editor can enumerate and edit its embedded .mat assets.
     */
    public MaterialEditor(Window owner, Material initial, Listener listener,
                          SelectionProvider selectionProvider, File projectFile) {
        super(owner, "Material Editor", Dialog.ModalityType.MODELESS);
        this.listener = listener;
        this.selectionProvider = selectionProvider;
        this.projectFile = projectFile != null ? projectFile : discoverCurrentProjectFile();
        this.material = normalize(copy(initial == null ? new Material() : initial));
        build();
        preview.reloadMaps();
        preview.repaint();
        refreshProjectMaterials();
        setSize(1060, 620);
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Sets/replaces the current .tpgproject and refreshes the material list. */
    public void setProjectFile(File projectFile) {
        this.projectFile = projectFile;
        selectedProjectMaterialEntry = null;
        selectedRecentMaterialFile = null;
        refreshProjectMaterials();
    }

    /** Returns the .tpgproject currently associated with this editor. */
    public File getProjectFile() {
        return projectFile;
    }

    public Material getMaterial() { return copy(material); }

    /**
     * Returns the valid .mat assets from the same recently-used library shown
     * by the Material Editor.  This is intentionally static so other editor
     * windows can use the exact same material library without opening a second
     * Material Editor or forcing the user to create a new material first.
     */
    public static List<File> getRecentMaterialFiles() {
        List<File> result = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT_MATERIALS; i++) {
            String path = MATERIAL_PREFS.get("material" + i, "");
            if (path == null || path.trim().isEmpty()) continue;
            File file = new File(path.trim());
            if (!file.isFile() || !file.getName().toLowerCase().endsWith(".mat")) continue;
            String canonical = canonicalStaticPath(file);
            boolean duplicate = false;
            for (File existing : result) {
                if (canonical.equalsIgnoreCase(canonicalStaticPath(existing))) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) result.add(file);
        }
        return result;
    }

    /** Loads a standalone .mat asset from the Material Editor's recent library. */
    public static Material loadRecentMaterialFile(File file) {
        if (file == null || !file.isFile()) return null;
        try (Reader reader = new FileReader(file)) {
            Properties p = new Properties();
            p.load(reader);
            return normalize(Material.fromProperties(p));
        } catch (IOException ex) {
            return null;
        }
    }

    /** Registers an existing .mat in the persistent recent-material library. */
    public static void rememberRecentMaterialFile(File file) {
        if (file == null || !file.isFile()
                || !file.getName().toLowerCase().endsWith(".mat")) return;
        String path = canonicalStaticPath(file);
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT_MATERIALS; i++) {
            String existing = MATERIAL_PREFS.get("material" + i, "");
            if (existing == null || existing.trim().isEmpty()) continue;
            File existingFile = new File(existing.trim());
            if (!existingFile.isFile() || !existingFile.getName().toLowerCase().endsWith(".mat")) continue;
            String canonical = canonicalStaticPath(existingFile);
            boolean duplicate = false;
            for (String prior : paths) {
                if (canonical.equalsIgnoreCase(prior)) { duplicate = true; break; }
            }
            if (!duplicate && !canonical.equalsIgnoreCase(path)) paths.add(canonical);
        }
        paths.add(0, path);
        while (paths.size() > MAX_RECENT_MATERIALS) paths.remove(paths.size() - 1);
        for (int i = 0; i < MAX_RECENT_MATERIALS; i++) {
            if (i < paths.size()) MATERIAL_PREFS.put("material" + i, paths.get(i));
            else MATERIAL_PREFS.remove("material" + i);
        }
    }

    private static String canonicalStaticPath(File file) {
        try { return file.getCanonicalPath(); }
        catch (IOException ex) { return file.getAbsolutePath(); }
    }

    /**
     * Exposes the same live 3D preview component used by the Material Editor.
     * Object Properties embeds this component so its material preview is
     * rendered by exactly the same preview implementation.
     */
    public JComponent getPreviewComponent() {
        return preview;
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        setContentPane(root);

        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.setPreferredSize(new Dimension(255, 0));
        left.add(preview, BorderLayout.CENTER);
        root.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout(5, 5));
        root.add(right, BorderLayout.CENTER);

        // Material browser. It shows embedded project .mat assets first, followed
        // by recently used standalone .mat files. Selecting one loads only that
        // material; it never overwrites another material merely by selecting it.
        JPanel libraryPanel = new JPanel(new BorderLayout(4, 4));
        libraryPanel.setBorder(BorderFactory.createTitledBorder("Materials / Recently Used .mat Files"));

        projectMaterialList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        projectMaterialList.setVisibleRowCount(5);
        projectMaterialList.setFont(projectMaterialList.getFont().deriveFont(Font.PLAIN, 12f));
        projectMaterialList.setToolTipText("Select a saved .mat material to edit it without replacing other materials.");
        projectMaterialList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelectedProjectMaterial();
        });

        JScrollPane materialScroll = new JScrollPane(projectMaterialList);
        materialScroll.setPreferredSize(new Dimension(0, 104));
        libraryPanel.add(materialScroll, BorderLayout.CENTER);

        JPanel libraryButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        JButton refreshMaterials = new JButton("Refresh List");
        JButton newMaterial = new JButton("New Material");
        refreshMaterials.setMargin(new Insets(2, 7, 2, 7));
        newMaterial.setMargin(new Insets(2, 7, 2, 7));
        refreshMaterials.addActionListener(e -> refreshProjectMaterials());
        newMaterial.addActionListener(e -> clearCurrentMaterial());
        libraryButtons.add(refreshMaterials);
        libraryButtons.add(newMaterial);
        libraryPanel.add(libraryButtons, BorderLayout.SOUTH);
        right.add(libraryPanel, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        right.add(formScroll, BorderLayout.CENTER);

        nameField.setText(material.name);
        addRow(form, "Material Name", nameField, null);

        selectedObjectLabel = new JLabel();
        selectedObjectLabel.setBorder(new EmptyBorder(4, 4, 5, 4));
        selectedObjectLabel.setFont(selectedObjectLabel.getFont().deriveFont(Font.BOLD));
        updateSelectedObjectLabel();
        form.add(selectedObjectLabel);

        String[] labels = {"Base Color", "Normal Map", "Metallic Map", "Roughness Map", "Emission Map"};
        String[] values = {material.baseColorMap, material.normalMap, material.metallicMap, material.roughnessMap, material.emissionMap};
        for (int i = 0; i < labels.length; i++) {
            mapPathLabels[i] = new JLabel();
            mapPathLabels[i].setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("TextField.border")),
                    new EmptyBorder(3, 5, 3, 5)
            ));
            mapPathLabels[i].setOpaque(true);
            mapPathLabels[i].setBackground(UIManager.getColor("TextField.background"));
            mapPathLabels[i].setForeground(UIManager.getColor("TextField.foreground"));
            mapPathLabels[i].setToolTipText(values[i].isEmpty() ? "No file selected" : values[i]);
            setMapPathLabel(i, values[i]);

            JPanel mapRow = new JPanel(new BorderLayout(4, 3));
            mapRow.setBorder(new EmptyBorder(2, 2, 2, 2));
            mapRow.add(new JLabel(labels[i]), BorderLayout.WEST);
            mapRow.add(mapPathLabels[i], BorderLayout.CENTER);

            JPanel mapButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            JButton browse = new JButton("Browse");
            browse.setMargin(new Insets(2, 5, 2, 5));
            final int index = i;
            browse.addActionListener(e -> chooseImage(index));
            mapButtons.add(browse);

            JButton clear = new JButton("Clear");
            clear.setMargin(new Insets(2, 5, 2, 5));
            clear.setToolTipText("Remove this material map");
            clear.addActionListener(e -> {
                setMapPathLabel(index, "");
                preview.reloadMaps();
                preview.invalidate();
                preview.repaint();
            });
            mapButtons.add(clear);
            mapRow.add(mapButtons, BorderLayout.EAST);
            form.add(mapRow);
        }

        metallic.setValue(Math.round(material.metallic * 100f));
        roughness.setValue(Math.round(material.roughness * 100f));
        specular.setValue(Math.round(material.specular * 100f));
        shininess.setValue(Math.round(Math.max(1f, Math.min(128f, material.shininess))));
        alpha.setValue(Math.round(material.alpha * 100f));
        addSlider(form, "Metallic", metallic);
        addSlider(form, "Roughness", roughness);
        addSlider(form, "Specular", specular);
        addSlider(form, "Shininess", shininess);
        addSlider(form, "Alpha / Opacity", alpha);

        JLabel note = new JLabel("<html><b>Layer order:</b> Base Color → Normal → Metallic → Roughness → Emission.<br>Map paths and scalar properties, including alpha/opacity, are stored with each material asset.</html>");
        note.setBorder(new EmptyBorder(7, 4, 7, 4));
        form.add(note);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton load = new JButton("Load Material Asset");
        JButton save = new JButton("Save Material Asset");
        JButton apply = new JButton("Apply to Currently Selected Object");
        apply.setToolTipText("Applies this material to the object currently selected in ThirdPersonGameNew.");
        load.addActionListener(e -> loadMaterial());
        JButton close = new JButton("Close");
        save.addActionListener(e -> saveMaterial());
        apply.addActionListener(e -> {
            sync();
            if (listener != null) listener.materialApplied(copy(material));
        });
        close.addActionListener(e -> dispose());
        buttons.add(load);
        buttons.add(save);
        buttons.add(apply);
        buttons.add(close);
        right.add(buttons, BorderLayout.SOUTH);

        // Keep the target indicator live while ThirdPersonGameNew selection
        // changes, because this window is intentionally modeless.
        if (selectionProvider != null) {
            Timer selectionTimer = new Timer(200, e -> updateSelectedObjectLabel());
            selectionTimer.setRepeats(true);
            selectionTimer.start();
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) { selectionTimer.stop(); }
            });
        }

        preview.setBackground(Color.DARK_GRAY);
    }

    private void updateSelectedObjectLabel() {
        if (selectedObjectLabel == null) return;
        String name = selectionProvider == null ? "" : selectionProvider.getSelectedObjectName();
        if (name == null || name.trim().isEmpty()) {
            selectedObjectLabel.setText("Target: No object selected");
        } else {
            selectedObjectLabel.setText("Target: " + name + " (live selection)");
        }
    }

    /**
     * Scans the opened .tpgproject archive for embedded .mat files.
     * Every archive entry is kept as its own identity, so editing one material
     * cannot accidentally replace another material with the same display name.
     */
    private void refreshProjectMaterials() {
        projectMaterialListModel.clear();
        projectMaterialEntries.clear();
        recentMaterialFiles.clear();
        selectedProjectMaterialEntry = null;
        selectedRecentMaterialFile = null;

        boolean foundAny = false;

        // First enumerate .mat files embedded directly in the active project.
        if (projectFile != null && projectFile.isFile()
                && projectFile.getName().toLowerCase().endsWith(".tpgproject")) {
            try (ZipFile zip = new ZipFile(projectFile)) {
                List<String> entries = new ArrayList<>();
                zip.stream().filter(e -> !e.isDirectory())
                        .map(ZipEntry::getName)
                        .filter(n -> n.toLowerCase().endsWith(".mat"))
                        .forEach(entries::add);
                entries.sort(String.CASE_INSENSITIVE_ORDER);
                for (String entry : entries) {
                    projectMaterialEntries.add(entry);
                    projectMaterialListModel.addElement("PROJECT  •  " + materialDisplayName(entry));
                    foundAny = true;
                }
            } catch (IOException ex) {
                // The project may be a non-zip legacy file. Continue with the
                // recently-used list rather than making the editor unusable.
            }
        }

        // Then show standalone .mat files that were actually opened/saved by
        // this editor. The paths are persisted between editor sessions.
        loadRecentMaterialFiles();
        for (String path : recentMaterialFiles) {
            File f = new File(path);
            if (f.isFile() && f.getName().toLowerCase().endsWith(".mat")) {
                projectMaterialListModel.addElement("RECENT   •  " + f.getName());
                foundAny = true;
            }
        }

        // Also catch .mat files next to the current project. This covers projects
        // that keep material assets as sibling files instead of ZIP entries.
        if (projectFile != null && projectFile.isFile()) {
            File parent = projectFile.getAbsoluteFile().getParentFile();
            if (parent != null) {
                File[] mats = parent.listFiles((dir, name) ->
                        name.toLowerCase().endsWith(".mat"));
                if (mats != null) {
                    java.util.Arrays.sort(mats, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                    for (File f : mats) {
                        String path = canonicalPath(f);
                        if (!recentMaterialFiles.contains(path)) {
                            recentMaterialFiles.add(path);
                            projectMaterialListModel.addElement("PROJECT FOLDER  •  " + f.getName());
                            foundAny = true;
                        }
                    }
                }
            }
        }

        if (!foundAny) {
            projectMaterialListModel.addElement("No .mat materials found — load or save a .mat to add it here");
        }
    }

    private void loadRecentMaterialFiles() {
        for (int i = 0; i < MAX_RECENT_MATERIALS; i++) {
            String path = MATERIAL_PREFS.get("material" + i, "");
            if (!path.trim().isEmpty()) {
                File f = new File(path);
                if (f.isFile()) recentMaterialFiles.add(canonicalPath(f));
            }
        }
    }

    private void rememberRecentMaterial(File file) {
        if (file == null || !file.isFile()) return;
        String path = canonicalPath(file);
        recentMaterialFiles.remove(path);
        recentMaterialFiles.add(0, path);
        while (recentMaterialFiles.size() > MAX_RECENT_MATERIALS) {
            recentMaterialFiles.remove(recentMaterialFiles.size() - 1);
        }
        for (int i = 0; i < MAX_RECENT_MATERIALS; i++) {
            if (i < recentMaterialFiles.size()) MATERIAL_PREFS.put("material" + i, recentMaterialFiles.get(i));
            else MATERIAL_PREFS.remove("material" + i);
        }
    }

    private String canonicalPath(File file) {
        try { return file.getCanonicalPath(); }
        catch (IOException ex) { return file.getAbsolutePath(); }
    }

    private File discoverCurrentProjectFile() {
        File dir = new File(System.getProperty("user.dir", "."));
        File[] projects = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".tpgproject"));
        if (projects == null || projects.length == 0) return null;
        java.util.Arrays.sort(projects, Comparator.comparingLong(File::lastModified).reversed());
        return projects[0];
    }

    private String materialDisplayName(String entry) {
        int slash = Math.max(entry.lastIndexOf('/'), entry.lastIndexOf('\\'));
        String name = slash >= 0 ? entry.substring(slash + 1) : entry;
        return name.isEmpty() ? entry : name;
    }

    /**
     * Loads a material directly from the embedded .mat entry in a .tpgproject.
     * This is shared by Object Properties so it uses the exact same project
     * material representation as the Material Editor rather than guessing from
     * an external material filename.
     */
    public static Material loadMaterialFromProject(File projectFile, String entryName) {
        if (projectFile == null || !projectFile.isFile() || entryName == null
                || entryName.trim().isEmpty()) return null;
        try (ZipFile zip = new ZipFile(projectFile)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                props.load(in);
            }
            return normalize(Material.fromProperties(props));
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Finds the embedded project .mat entry represented by an extracted/runtime
     * path. Matching first uses the normalized archive suffix and then the
     * filename, which keeps Object Properties independent of temporary cache
     * directory names.
     */
    public static String findProjectMaterialEntry(File projectFile, String materialPath) {
        if (projectFile == null || !projectFile.isFile() || materialPath == null
                || materialPath.trim().isEmpty()) return null;
        String normalizedPath = materialPath.trim().replace('\\', '/');
        int assetsIndex = normalizedPath.indexOf("/assets/");
        String suffix = assetsIndex >= 0 ? normalizedPath.substring(assetsIndex + 1) : null;
        String fileName = new File(materialPath.trim()).getName();
        try (ZipFile zip = new ZipFile(projectFile)) {
            String best = null;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".mat")) continue;
                String name = entry.getName().replace('\\', '/');
                if (suffix != null && name.equalsIgnoreCase(suffix)) return entry.getName();
                if (best == null && name.substring(name.lastIndexOf('/') + 1).equalsIgnoreCase(fileName)) {
                    best = entry.getName();
                }
            }
            return best;
        } catch (IOException ex) {
            return null;
        }
    }

    /** Returns the project material entry currently selected in the browser. */
    public String getSelectedProjectMaterialEntry() {
        return selectedProjectMaterialEntry;
    }

    /** Loads an embedded project material and selects it in the browser. */
    public boolean loadProjectMaterialEntry(String entryName) {
        if (entryName == null || entryName.trim().isEmpty()) return false;
        Material loaded = readMaterialFromProject(entryName);
        if (loaded == null) return false;
        selectedProjectMaterialEntry = entryName;
        selectedRecentMaterialFile = null;
        populateMaterial(loaded);
        selectProjectMaterialEntry(entryName);
        return true;
    }

    /** Loads the embedded material represented by an extracted/runtime path. */
    public boolean loadProjectMaterialForPath(String materialPath) {
        String entry = findProjectMaterialEntry(projectFile, materialPath);
        return entry != null && loadProjectMaterialEntry(entry);
    }

    private void loadSelectedProjectMaterial() {
        int index = projectMaterialList.getSelectedIndex();
        if (index < 0) return;

        int projectCount = projectMaterialEntries.size();
        if (index < projectCount) {
            String entryName = projectMaterialEntries.get(index);
            Material loaded = readMaterialFromProject(entryName);
            if (loaded == null) return;
            selectedProjectMaterialEntry = entryName;
            selectedRecentMaterialFile = null;
            populateMaterial(loaded);
            return;
        }

        int recentIndex = index - projectCount;
        List<String> validRecent = new ArrayList<>();
        for (String path : recentMaterialFiles) {
            File f = new File(path);
            if (f.isFile()) validRecent.add(path);
        }
        if (recentIndex < 0 || recentIndex >= validRecent.size()) return;

        File file = new File(validRecent.get(recentIndex));
        try (Reader reader = new FileReader(file)) {
            Properties p = new Properties();
            p.load(reader);
            selectedProjectMaterialEntry = null;
            selectedRecentMaterialFile = canonicalPath(file);
            rememberRecentMaterial(file);
            populateMaterial(Material.fromProperties(p));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Unable to load material:\n" + ex.getMessage(),
                    "Material Editor", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Material readMaterialFromProject(String entryName) {
        if (projectFile == null || !projectFile.isFile()) return null;

        try (ZipFile zip = new ZipFile(projectFile)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;

            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                props.load(in);
            }

            return normalize(Material.fromProperties(props));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Unable to load material from the project:\n" + ex.getMessage(),
                    "Material Editor", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private void populateMaterial(Material loaded) {
        if (loaded == null) return;
        loaded = normalize(loaded);

        nameField.setText(loaded.name);
        String[] values = {
                loaded.baseColorMap, loaded.normalMap, loaded.metallicMap,
                loaded.roughnessMap, loaded.emissionMap
        };
        for (int i = 0; i < values.length; i++) setMapPathLabel(i, values[i]);

        metallic.setValue(Math.round(loaded.metallic * 100f));
        roughness.setValue(Math.round(loaded.roughness * 100f));
        specular.setValue(Math.round(loaded.specular * 100f));
        shininess.setValue(Math.round(Math.max(1f, Math.min(128f, loaded.shininess))));
        alpha.setValue(Math.round(loaded.alpha * 100f));

        material.name = loaded.name;
        material.baseColorMap = loaded.baseColorMap;
        material.normalMap = loaded.normalMap;
        material.metallicMap = loaded.metallicMap;
        material.roughnessMap = loaded.roughnessMap;
        material.emissionMap = loaded.emissionMap;
        material.metallic = loaded.metallic;
        material.roughness = loaded.roughness;
        material.specular = loaded.specular;
        material.shininess = loaded.shininess;
        material.alpha = loaded.alpha;

        // Always reload all five maps immediately when a material changes.
        preview.reloadMaps();
        preview.invalidate();
        preview.repaint();
    }

    /** Completely clears the active material without touching saved project assets. */
    private void clearCurrentMaterial() {
        selectedProjectMaterialEntry = null;
        projectMaterialList.clearSelection();

        Material blank = new Material();
        blank.name = "New Material";
        populateMaterial(blank);

        nameField.setText("New Material");
        for (int i = 0; i < mapPathLabels.length; i++) setMapPathLabel(i, "");
        metallic.setValue(0);
        roughness.setValue(50);
        specular.setValue(95);
        shininess.setValue(96);
        alpha.setValue(100);

        preview.reloadMaps();
        preview.invalidate();
        preview.repaint();
    }

    private void addRow(JPanel parent, String label, JComponent field, JButton button) {
        JPanel row = new JPanel(new BorderLayout(6, 4));
        row.setBorder(new EmptyBorder(4, 2, 4, 2));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        if (button != null) row.add(button, BorderLayout.EAST);
        parent.add(row);
    }

    private void addSlider(JPanel parent, String label, JSlider slider) {
        JPanel row = new JPanel(new BorderLayout(6, 2));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        parent.add(row);
        slider.addChangeListener(e -> preview.repaint());
    }

    private void chooseImage(int index) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "bmp", "gif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setMapPathLabel(index, chooser.getSelectedFile().getAbsolutePath());
            preview.reloadMaps();
            preview.invalidate();
            preview.repaint();
        }
    }

    private String getMapPath(int index) {
        if (index < 0 || index >= mapPathLabels.length || mapPathLabels[index] == null) return "";
        Object path = mapPathLabels[index].getClientProperty("mapPath");
        return path == null ? "" : path.toString().trim();
    }

    private void setMapPathLabel(int index, String path) {
        if (index < 0 || index >= mapPathLabels.length) return;
        if (mapPathLabels[index] == null) {
            mapPathLabels[index] = new JLabel();
            mapPathLabels[index].setOpaque(true);
            mapPathLabels[index].setBackground(UIManager.getColor("TextField.background"));
        }
        String value = path == null ? "" : path.trim();
        mapPathLabels[index].putClientProperty("mapPath", value);
        if (value.isEmpty()) {
            mapPathLabels[index].setText("No file selected");
            mapPathLabels[index].setForeground(UIManager.getColor("Label.disabledForeground"));
        } else {
            mapPathLabels[index].setText(new File(value).getName());
            mapPathLabels[index].setForeground(UIManager.getColor("TextField.foreground"));
        }
        mapPathLabels[index].setToolTipText(value.isEmpty() ? "No file selected" : value);
    }

    private void sync() {
        material.name = nameField.getText().trim().isEmpty() ? "New Material" : nameField.getText().trim();
        material.baseColorMap = getMapPath(0);
        material.normalMap = getMapPath(1);
        material.metallicMap = getMapPath(2);
        material.roughnessMap = getMapPath(3);
        material.emissionMap = getMapPath(4);
        material.metallic = metallic.getValue() / 100f;
        material.roughness = roughness.getValue() / 100f;
        material.specular = specular.getValue() / 100f;
        material.shininess = shininess.getValue();
        material.alpha = alpha.getValue() / 100f;
    }

    private void loadMaterial() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Material Asset");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Material Assets (*.mat)", "mat"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (Reader reader = new FileReader(chooser.getSelectedFile())) {
            Properties p = new Properties();
            p.load(reader);
            selectedProjectMaterialEntry = null;
            selectedRecentMaterialFile = canonicalPath(chooser.getSelectedFile());
            projectMaterialList.clearSelection();
            rememberRecentMaterial(chooser.getSelectedFile());
            populateMaterial(Material.fromProperties(p));
            refreshProjectMaterials();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Unable to load material:\n" + ex.getMessage(), "Material Editor", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveMaterial() {
        sync();

        // If a material from the current .tpgproject is selected, update only
        // that archive entry.  Other material entries are copied byte-for-byte.
        if (selectedProjectMaterialEntry != null && projectFile != null && projectFile.isFile()) {
            try {
                String entryBeingSaved = selectedProjectMaterialEntry;
                writeMaterialToProject(entryBeingSaved, material);
                refreshProjectMaterials();
                selectProjectMaterialEntry(entryBeingSaved);
                return;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "Unable to save material into the project:\n" + ex.getMessage(),
                        "Material Editor", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Material Asset");
        chooser.setSelectedFile(new File(material.name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".mat"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Material Assets (*.mat)", "mat"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".mat")) {
            file = new File(file.getParentFile(), file.getName() + ".mat");
        }

        try (Writer writer = new FileWriter(file)) {
            material.toProperties().store(writer, "ThirdPersonGame Material Asset");
            rememberRecentMaterial(file);
            refreshProjectMaterials();
            JOptionPane.showMessageDialog(this, "Material saved:\n" + file.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Unable to save material:\n" + ex.getMessage(), "Material Editor", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectProjectMaterialEntry(String entryName) {
        if (entryName == null) return;
        for (int i = 0; i < projectMaterialEntries.size(); i++) {
            if (entryName.equals(projectMaterialEntries.get(i))) {
                projectMaterialList.setSelectedIndex(i);
                projectMaterialList.ensureIndexIsVisible(i);
                selectedProjectMaterialEntry = entryName;
                return;
            }
        }
    }

    /**
     * Rewrites the project archive while replacing exactly one .mat entry.
     * All unrelated files/materials remain in the archive unchanged.
     */
    private void writeMaterialToProject(String entryName, Material value) throws IOException {
        if (projectFile == null || !projectFile.isFile()) {
            throw new IOException("No current .tpgproject is available.");
        }

        File temp = new File(projectFile.getParentFile(), projectFile.getName() + ".material.tmp");
        byte[] replacement;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            Properties props = value.toProperties();
            props.store(bytes, "ThirdPersonGame Material Asset");
            replacement = bytes.toByteArray();
        }

        boolean replaced = false;
        try (ZipFile source = new ZipFile(projectFile);
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {

            java.util.Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                ZipEntry copyEntry = new ZipEntry(original.getName());
                boolean replacingThisEntry = original.getName().equals(entryName);
                // A replaced entry must not retain the old STORED size/CRC.
                // Keep STORED metadata only for untouched entries.
                if (!replacingThisEntry && original.getMethod() == ZipEntry.STORED) {
                    copyEntry.setMethod(ZipEntry.STORED);
                    copyEntry.setSize(original.getSize());
                    copyEntry.setCompressedSize(original.getCompressedSize());
                    copyEntry.setCrc(original.getCrc());
                }
                if (original.getTime() >= 0) copyEntry.setTime(original.getTime());

                out.putNextEntry(copyEntry);
                if (replacingThisEntry) {
                    out.write(replacement);
                    replaced = true;
                } else {
                    try (InputStream in = source.getInputStream(original)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                }
                out.closeEntry();
            }

            if (!replaced) {
                ZipEntry added = new ZipEntry(entryName);
                out.putNextEntry(added);
                out.write(replacement);
                out.closeEntry();
            }
        }

        try {
            Files.move(temp.toPath(), projectFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailure) {
            Files.move(temp.toPath(), projectFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        JOptionPane.showMessageDialog(this,
                "Material saved in the current .tpgproject:\n" + materialDisplayName(entryName));
    }

    /**
     * Defensive normalization used at every editor entry point. This ensures
     * map-path values are never null, even when an older .mat asset or older
     * game object supplies null values.
     */
    private static Material normalize(Material src) {
        if (src == null) src = new Material();
        src.name = src.name == null || src.name.trim().isEmpty() ? "New Material" : src.name.trim();
        src.baseColorMap = src.baseColorMap == null ? "" : src.baseColorMap.trim();
        src.normalMap = src.normalMap == null ? "" : src.normalMap.trim();
        src.metallicMap = src.metallicMap == null ? "" : src.metallicMap.trim();
        src.roughnessMap = src.roughnessMap == null ? "" : src.roughnessMap.trim();
        src.emissionMap = src.emissionMap == null ? "" : src.emissionMap.trim();
        src.metallic = Math.max(0f, Math.min(1f, src.metallic));
        src.roughness = Math.max(0f, Math.min(1f, src.roughness));
        src.specular = Math.max(0f, Math.min(1f, src.specular));
        src.shininess = Math.max(1f, Math.min(128f, src.shininess));
        src.alpha = Math.max(0f, Math.min(1f, src.alpha));
        return src;
    }

    private static Material copy(Material src) {
        Material m = new Material();
        m.name = src.name; m.baseColorMap = src.baseColorMap; m.normalMap = src.normalMap;
        m.metallicMap = src.metallicMap; m.roughnessMap = src.roughnessMap; m.emissionMap = src.emissionMap;
        m.metallic = src.metallic; m.roughness = src.roughness; m.specular = src.specular; m.shininess = src.shininess; m.alpha = src.alpha;
        return m;
    }

    private class PreviewPanel extends JPanel {
        private BufferedImage base;
        private BufferedImage normal;
        private BufferedImage metallicMapImage;
        private BufferedImage roughnessMapImage;
        private BufferedImage emission;
        private BufferedImage renderedPreview;
        private int lastW = -1, lastH = -1;
        private String lastKey = "";

        PreviewPanel() {
            setPreferredSize(new Dimension(300, 300));
            setBackground(Color.DARK_GRAY);
            // Map labels are initialized by build(); do not access them from
            // the field initializer/PreviewPanel constructor. The outer
            // constructor performs the first reload after build() completes.
        }

        void reloadMaps() {
            base = loadImage(getMapPath(0));
            normal = loadImage(getMapPath(1));
            metallicMapImage = loadImage(getMapPath(2));
            roughnessMapImage = loadImage(getMapPath(3));
            emission = loadImage(getMapPath(4));
            renderedPreview = null;
            lastKey = "";
        }

        private BufferedImage loadImage(String path) {
            if (path == null || path.trim().isEmpty()) return null;
            try {
                File f = new File(path);
                return f.isFile() ? ImageIO.read(f) : null;
            } catch (IOException ex) {
                return null;
            }
        }

        private String mapKey() {
            return getMapPath(0) + "|" + getMapPath(1) + "|" + getMapPath(2) + "|"
                    + getMapPath(3) + "|" + getMapPath(4) + "|"
                    + metallic.getValue() + "|" + roughness.getValue() + "|"
                    + specular.getValue() + "|" + shininess.getValue() + "|" + alpha.getValue();
        }

        private BufferedImage buildSphere(int width, int height) {
            int diameter = Math.max(80, Math.min(width, height) - 36);
            BufferedImage out = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
            float radius = diameter * 0.48f;
            float cx = diameter * 0.5f;
            float cy = diameter * 0.5f;

            float scalarMetallic = metallic.getValue() / 100f;
            float scalarRoughness = roughness.getValue() / 100f;
            float scalarSpecular = specular.getValue() / 100f;
            float scalarShininess = Math.max(1f, shininess.getValue());

            // A fixed studio-light setup makes the material maps easy to see.
            float lx = -0.45f, ly = -0.55f, lz = 0.72f;
            float len = (float)Math.sqrt(lx*lx + ly*ly + lz*lz);
            lx /= len; ly /= len; lz /= len;
            float vx = 0f, vy = 0f, vz = 1f;

            for (int py = 0; py < diameter; py++) {
                for (int px = 0; px < diameter; px++) {
                    float nx = (px + 0.5f - cx) / radius;
                    float ny = (py + 0.5f - cy) / radius;
                    float rr = nx * nx + ny * ny;
                    int idx = py * diameter + px;
                    if (rr > 1f) {
                        pixels[idx] = 0;
                        continue;
                    }

                    float nz = (float)Math.sqrt(Math.max(0f, 1f - rr));
                    float u = 0.5f + (float)(Math.atan2(nx, nz) / (2.0 * Math.PI));
                    float v = 0.5f - (float)(Math.asin(ny) / Math.PI);

                    Color bc = sampleColor(base, u, v, new Color(190, 192, 198));
                    float metal = sampleGray(metallicMapImage, u, v, scalarMetallic);
                    float rough = clamp(sampleGray(roughnessMapImage, u, v, scalarRoughness), 0f, 1f);

                    // Tangent-space normal-map preview. The sphere's analytic normal
                    // is blended with the sampled tangent-space normal so the selected
                    // normal map visibly changes the surface lighting.
                    float sx = nx, sy = ny, sz = nz;
                    if (normal != null) {
                        Color nc = sampleColor(normal, u, v, new Color(128,128,255));
                        float tx = nc.getRed() / 127.5f - 1f;
                        float ty = nc.getGreen() / 127.5f - 1f;
                        float tz = nc.getBlue() / 127.5f - 1f;
                        float inv = 1f / Math.max(0.001f, (float)Math.sqrt(tx*tx + ty*ty + tz*tz));
                        tx *= inv; ty *= inv; tz *= inv;

                        // Build an approximate tangent frame from the sphere normal.
                        float ax = 0f, ay = 1f, az = 0f;
                        if (Math.abs(sy) > 0.92f) { ax = 1f; ay = 0f; az = 0f; }
                        float tx0 = ay * sz - az * sy;
                        float ty0 = az * sx - ax * sz;
                        float tz0 = ax * sy - ay * sx;
                        float tl = (float)Math.sqrt(tx0*tx0 + ty0*ty0 + tz0*tz0);
                        if (tl > 0.001f) { tx0/=tl; ty0/=tl; tz0/=tl; }
                        float bx0 = sy*tz0 - sz*ty0;
                        float by0 = sz*tx0 - sx*tz0;
                        float bz0 = sx*ty0 - sy*tx0;
                        float pxn = tx0*tx + bx0*ty + sx*tz;
                        float pyn = ty0*tx + by0*ty + sy*tz;
                        float pzn = tz0*tx + bz0*ty + sz*tz;
                        float nl = (float)Math.sqrt(pxn*pxn + pyn*pyn + pzn*pzn);
                        if (nl > 0.001f) { sx=pxn/nl; sy=pyn/nl; sz=pzn/nl; }
                    }

                    float ndotl = Math.max(0f, sx*lx + sy*ly + sz*lz);
                    float hx = lx + vx, hy = ly + vy, hz = lz + vz;
                    float hl = (float)Math.sqrt(hx*hx + hy*hy + hz*hz);
                    hx/=hl; hy/=hl; hz/=hl;
                    float ndoth = Math.max(0f, sx*hx + sy*hy + sz*hz);
                    float exponent = Math.max(2f, scalarShininess * (1.05f - rough));
                    float spec = (float)Math.pow(ndoth, exponent) * scalarSpecular * (0.15f + 0.85f*(1f-rough));
                    float diffuse = 0.16f + ndotl * (1.0f - 0.18f * metal);
                    float ambient = 0.12f;

                    float cr = bc.getRed()/255f;
                    float cg = bc.getGreen()/255f;
                    float cb = bc.getBlue()/255f;

                    // Metallic surfaces reflect the white studio light more strongly,
                    // while their diffuse response is reduced.
                    float reflected = spec * (0.35f + 0.65f * metal);
                    cr = cr * (ambient + diffuse) * (1f - 0.45f*metal) + reflected;
                    cg = cg * (ambient + diffuse) * (1f - 0.45f*metal) + reflected;
                    cb = cb * (ambient + diffuse) * (1f - 0.45f*metal) + reflected;

                    if (emission != null) {
                        Color ec = sampleColor(emission, u, v, Color.BLACK);
                        float er=ec.getRed()/255f, eg=ec.getGreen()/255f, eb=ec.getBlue()/255f;
                        cr += er * 0.8f; cg += eg * 0.8f; cb += eb * 0.8f;
                    }

                    // Soft rim light helps preserve the spherical 3D shape even with
                    // very dark base-color maps.
                    float rim = (float)Math.pow(1f - Math.max(0f, sz), 2.5f) * 0.12f;
                    cr += rim; cg += rim; cb += rim;
                    int a = Math.max(0, Math.min(255, Math.round(alpha.getValue() * 2.55f)));
                    pixels[idx] = (a << 24)
                            | ((clamp255(cr) & 255) << 16)
                            | ((clamp255(cg) & 255) << 8)
                            | (clamp255(cb) & 255);
                }
            }
            return out;
        }

        private int clamp255(float value) {
            return Math.max(0, Math.min(255, Math.round(value * 255f)));
        }

        private float clamp(float v, float min, float max) {
            return Math.max(min, Math.min(max, v));
        }

        private Color sampleColor(BufferedImage image, float u, float v, Color fallback) {
            if (image == null) return fallback;
            int x = ((int)Math.floor(u * image.getWidth())) % image.getWidth();
            int y = ((int)Math.floor(v * image.getHeight())) % image.getHeight();
            if (x < 0) x += image.getWidth();
            if (y < 0) y += image.getHeight();
            return new Color(image.getRGB(x, y), true);
        }

        private float sampleGray(BufferedImage image, float u, float v, float fallback) {
            if (image == null) return fallback;
            Color c = sampleColor(image, u, v, Color.WHITE);
            return (c.getRed() * 0.299f + c.getGreen() * 0.587f + c.getBlue() * 0.114f) / 255f;
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            String key = mapKey();
            if (renderedPreview == null || lastW != getWidth() || lastH != getHeight() || !key.equals(lastKey)) {
                renderedPreview = buildSphere(getWidth(), getHeight());
                lastW = getWidth();
                lastH = getHeight();
                lastKey = key;
            }

            int x = (getWidth() - renderedPreview.getWidth()) / 2;
            int y = (getHeight() - renderedPreview.getHeight()) / 2;
            g2.drawImage(renderedPreview, x, y, null);
            g2.setColor(Color.WHITE);
            g2.drawString("3D Material Preview", 12, 20);

            // Show which material layers are currently active without obscuring the sphere.
            int yy = getHeight() - 12;
            String status = "Base" + (base != null ? " ✓" : " —")
                    + "  Normal" + (normal != null ? " ✓" : " —")
                    + "  Metal" + (metallicMapImage != null ? " ✓" : " —")
                    + "  Rough" + (roughnessMapImage != null ? " ✓" : " —")
                    + "  Emission" + (emission != null ? " ✓" : " —");
            g2.drawString(status, 8, yy);
            g2.dispose();
        }
    }
}
