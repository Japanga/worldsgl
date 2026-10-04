/*
 * WorldsGL Timeline Editor
 *
 * Edits the scene/timeline metadata embedded in an existing .tpgproject.
 * It deliberately leaves the existing project assets untouched.
 */
package objviewer;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class TimelineEditor {

    private final File projectFile;
    private final DefaultListModel<String> sceneListModel = new DefaultListModel<>();
    private final JList<String> sceneList = new JList<>(sceneListModel);
    private final JLabel projectLabel = new JLabel();
    private final JTextArea details = new JTextArea();
    private JFrame frame;

    private final Runnable onClose;

    private TimelineEditor(File projectFile, Runnable onClose) {
        this.projectFile = projectFile.getAbsoluteFile();
        this.onClose = onClose;
        buildUI();
        reloadScenes();
    }

    public static void open(File projectFile) {
        open(projectFile, null);
    }

    /**
     * Opens the editor and invokes onClose after the editor window is disposed.
     * This lets the game reload the archive after TimelineEditor has changed it.
     */
    public static void open(File projectFile, Runnable onClose) {
        SwingUtilities.invokeLater(() ->
                new TimelineEditor(projectFile, onClose).show());
    }

    private void buildUI() {
        frame = new JFrame("WorldsGL Timeline Editor");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (onClose != null) {
                    try {
                        onClose.run();
                    } catch (Throwable ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });
        frame.setSize(760, 540);
        frame.setLocationByPlatform(true);

        projectLabel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        sceneList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sceneList.addListSelectionListener(e -> updateDetails());

        JButton addScene = new JButton("New Scene");
        addScene.addActionListener(e -> addScene());

        JButton renameScene = new JButton("Rename Scene");
        renameScene.addActionListener(e -> renameScene());

        JButton deleteScene = new JButton("Delete Scene");
        deleteScene.addActionListener(e -> deleteScene());

        JButton addTrigger = new JButton("Add Scene Trigger");
        addTrigger.addActionListener(e -> addTrigger());

        JButton save = new JButton("Save Timeline");
        save.addActionListener(e -> {
            try {
                saveProject();
                JOptionPane.showMessageDialog(frame, "Timeline saved successfully.",
                        "Timeline Editor", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                showError("Could not save timeline", ex);
            }
        });

        JButton reload = new JButton("Reload");
        reload.addActionListener(e -> reloadScenes());

        JPanel buttons = new JPanel(new GridLayout(1, 0, 6, 6));
        buttons.add(addScene);
        buttons.add(renameScene);
        buttons.add(deleteScene);
        buttons.add(addTrigger);
        buttons.add(save);
        buttons.add(reload);

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane listScroll = new JScrollPane(sceneList);
        listScroll.setPreferredSize(new Dimension(250, 350));

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        center.add(listScroll, BorderLayout.WEST);
        center.add(new JScrollPane(details), BorderLayout.CENTER);

        frame.setLayout(new BorderLayout(8, 8));
        frame.add(projectLabel, BorderLayout.NORTH);
        frame.add(center, BorderLayout.CENTER);
        frame.add(buttons, BorderLayout.SOUTH);
    }

    private void show() {
        frame.setVisible(true);
    }

    private static class Archive {
        final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        final Properties manifest = new Properties();
    }

    private Archive readArchive() throws IOException {
        Archive a = new Archive();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(projectFile)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zin.read(buf)) != -1) out.write(buf, 0, n);
                a.entries.put(e.getName(), out.toByteArray());
                if ("project.properties".equals(e.getName())) {
                    try (InputStream in = new ByteArrayInputStream(out.toByteArray())) {
                        a.manifest.load(in);
                    }
                }
                zin.closeEntry();
            }
        }
        if (!"WorldsGL Third Person Game Project".equals(
                a.manifest.getProperty("project.format"))) {
            throw new IOException("The selected file is not a WorldsGL .tpgproject.");
        }
        return a;
    }

    private void reloadScenes() {
        try {
            Archive a = readArchive();
            projectLabel.setText("Project: " + projectFile.getAbsolutePath());

            sceneListModel.clear();
            int count = sceneCount(a.manifest);

            if (count == 0) {
                // Convert a legacy single-scene project into a timeline in memory.
                sceneListModel.addElement("Main Scene");
            } else {
                for (int i = 0; i < count; i++) {
                    sceneListModel.addElement(sceneName(a.manifest, i));
                }
            }

            if (!sceneListModel.isEmpty()) sceneList.setSelectedIndex(0);
            updateDetails();
        } catch (Exception ex) {
            showError("Could not read project", ex);
        }
    }

    private int sceneCount(Properties p) {
        try {
            return Math.max(0, Integer.parseInt(p.getProperty("scene.count", "0")));
        } catch (Exception ex) {
            return 0;
        }
    }

    private String sceneName(Properties p, int index) {
        return p.getProperty("scene." + index + ".scene.name", "Scene " + index);
    }

    private void updateDetails() {
        int index = sceneList.getSelectedIndex();
        if (index < 0) {
            details.setText("");
            return;
        }

        try {
            Archive a = readArchive();
            int count = sceneCount(a.manifest);
            Properties scene = getScene(a.manifest, index, count);

            int objectCount = intProp(scene, "objects.count", 0);
            int triggerCount = intProp(scene, "triggers.count", 0);

            details.setText(
                    "Scene: " + scene.getProperty("scene.name", "Scene " + index) + "\n\n"
                    + "Objects: " + objectCount + "\n"
                    + "Scene Triggers: " + triggerCount + "\n\n"
                    + "A scene contains its own player position, world objects,\n"
                    + "lights, skybox, lighting settings, game-over settings,\n"
                    + "and scene triggers.\n\n"
                    + "Use \"Add Scene Trigger\" to create a visible doorway marker\n"
                    + "that transfers the player to another scene when entered."
            );
        } catch (Exception ex) {
            details.setText("Could not inspect scene:\n" + ex.getMessage());
        }
    }

    private static int intProp(Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, Integer.toString(fallback))); }
        catch (Exception ex) { return fallback; }
    }

    private static Properties getScene(Properties manifest, int index, int count) {
        Properties scene = new Properties();
        if (count == 0 && index == 0) {
            copyLegacyBaseScene(manifest, scene);
            scene.setProperty("scene.name", "Main Scene");
            return scene;
        }

        String prefix = "scene." + index + ".";
        for (String key : manifest.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                scene.setProperty(key.substring(prefix.length()), manifest.getProperty(key));
            }
        }
        if (!scene.containsKey("scene.name")) scene.setProperty("scene.name", "Scene " + index);
        return scene;
    }

    private static void copyLegacyBaseScene(Properties manifest, Properties scene) {
        String[] prefixes = {"player.", "ground.", "sky.", "lighting.", "gameOver.", "object.", "objects."};
        for (String key : manifest.stringPropertyNames()) {
            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    scene.setProperty(key, manifest.getProperty(key));
                    break;
                }
            }
        }
        // Triggers did not exist in the original format.
        scene.setProperty("triggers.count", "0");
    }

    private void addScene() {
        try {
            Archive a = readArchive();
            int count = sceneCount(a.manifest);

            List<Properties> scenes = readScenes(a.manifest);
            if (scenes.isEmpty()) {
                Properties first = getScene(a.manifest, 0, 0);
                scenes.add(first);
            }

            int source = sceneList.getSelectedIndex();
            if (source < 0) source = scenes.size() - 1;

            Properties newScene = cloneProperties(scenes.get(source));
            String name = JOptionPane.showInputDialog(frame,
                    "Name for the new scene:", "New Scene",
                    JOptionPane.PLAIN_MESSAGE);
            if (name == null) return;
            name = name.trim();
            if (name.isEmpty()) name = "Scene " + scenes.size();

            // New scenes do not inherit scene triggers. A trigger is
            // created only by an explicit "Add Scene Trigger" action.
            removeSceneTriggers(newScene);
            newScene.setProperty("scene.name", name);
            scenes.add(newScene);

            writeScenes(a.manifest, scenes);
            writeArchive(a);
            reloadScenes();
            sceneList.setSelectedIndex(scenes.size() - 1);
        } catch (Exception ex) {
            showError("Could not create scene", ex);
        }
    }

    private void renameScene() {
        int index = sceneList.getSelectedIndex();
        if (index < 0) return;

        try {
            Archive a = readArchive();
            List<Properties> scenes = readScenes(a.manifest);
            if (scenes.isEmpty()) scenes.add(getScene(a.manifest, 0, 0));

            String old = scenes.get(index).getProperty("scene.name", "Scene " + index);
            String name = JOptionPane.showInputDialog(frame, "Scene name:", old);
            if (name == null) return;
            name = name.trim();
            if (name.isEmpty()) return;

            scenes.get(index).setProperty("scene.name", name);
            writeScenes(a.manifest, scenes);
            writeArchive(a);
            reloadScenes();
            sceneList.setSelectedIndex(index);
        } catch (Exception ex) {
            showError("Could not rename scene", ex);
        }
    }

    private void deleteScene() {
        int index = sceneList.getSelectedIndex();
        if (index < 0) return;

        if (sceneListModel.size() <= 1) {
            JOptionPane.showMessageDialog(frame,
                    "A project must contain at least one scene.",
                    "Delete Scene", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int answer = JOptionPane.showConfirmDialog(frame,
                "Delete \"" + sceneListModel.get(index) + "\"?",
                "Delete Scene", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;

        try {
            Archive a = readArchive();
            List<Properties> scenes = readScenes(a.manifest);
            scenes.remove(index);
            // Repair trigger destination indexes after the deletion.
            for (Properties scene : scenes) {
                int n = intProp(scene, "triggers.count", 0);
                for (int i = 0; i < n; i++) {
                    String k = "trigger." + i + ".destination";
                    int dest = intProp(scene, k, 0);
                    if (dest == index) dest = Math.max(0, Math.min(index, scenes.size() - 1));
                    else if (dest > index) dest--;
                    scene.setProperty(k, Integer.toString(dest));
                }
            }
            writeScenes(a.manifest, scenes);
            writeArchive(a);
            reloadScenes();
        } catch (Exception ex) {
            showError("Could not delete scene", ex);
        }
    }

    private static void removeSceneTriggers(Properties scene) {
        int count = intProp(scene, "triggers.count", 0);
        for (int i = 0; i < count; i++) {
            String prefix = "trigger." + i + ".";
            for (String key : new ArrayList<>(scene.stringPropertyNames())) {
                if (key.startsWith(prefix)) scene.remove(key);
            }
        }

        // Remove any stale trigger keys left by older project versions too.
        for (String key : new ArrayList<>(scene.stringPropertyNames())) {
            if (key.startsWith("trigger.")) scene.remove(key);
        }

        scene.setProperty("triggers.count", "0");
    }

    private void addTrigger() {
        int source = sceneList.getSelectedIndex();
        if (source < 0) return;

        try {
            Archive a = readArchive();
            List<Properties> scenes = readScenes(a.manifest);
            if (scenes.isEmpty()) scenes.add(getScene(a.manifest, 0, 0));

            String[] names = new String[scenes.size()];
            for (int i = 0; i < names.length; i++)
                names[i] = i + ": " + scenes.get(i).getProperty("scene.name", "Scene " + i);

            JComboBox<String> destination = new JComboBox<>(names);
            destination.setSelectedIndex(Math.min(source + 1, names.length - 1));

            JSpinner x = spinner(0);
            JSpinner y = spinner(0);
            JSpinner z = spinner(0);
            JSpinner width = spinner(2);
            JSpinner height = spinner(2.5);
            JSpinner depth = spinner(0.5);
            JSpinner rotation = spinner(0);

            JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));
            panel.add(new JLabel("Destination scene:")); panel.add(destination);
            panel.add(new JLabel("X:")); panel.add(x);
            panel.add(new JLabel("Y:")); panel.add(y);
            panel.add(new JLabel("Z:")); panel.add(z);
            panel.add(new JLabel("Width:")); panel.add(width);
            panel.add(new JLabel("Height:")); panel.add(height);
            panel.add(new JLabel("Depth:")); panel.add(depth);
            panel.add(new JLabel("Rotation Y:")); panel.add(rotation);

            int result = JOptionPane.showConfirmDialog(frame, panel,
                    "Add Scene Trigger", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            Properties scene = scenes.get(source);
            int count = intProp(scene, "triggers.count", 0);
            String k = "trigger." + count + ".";
            scene.setProperty(k + "x", Float.toString(((Number)x.getValue()).floatValue()));
            scene.setProperty(k + "y", Float.toString(((Number)y.getValue()).floatValue()));
            scene.setProperty(k + "z", Float.toString(((Number)z.getValue()).floatValue()));
            scene.setProperty(k + "width", Float.toString(((Number)width.getValue()).floatValue()));
            scene.setProperty(k + "height", Float.toString(((Number)height.getValue()).floatValue()));
            scene.setProperty(k + "depth", Float.toString(((Number)depth.getValue()).floatValue()));
            scene.setProperty(k + "rotationY", Float.toString(((Number)rotation.getValue()).floatValue()));
            scene.setProperty(k + "destination", Integer.toString(destination.getSelectedIndex()));
            scene.setProperty(k + "enabled", "true");
            scene.setProperty("triggers.count", Integer.toString(count + 1));

            writeScenes(a.manifest, scenes);
            writeArchive(a);
            reloadScenes();
            sceneList.setSelectedIndex(source);
        } catch (Exception ex) {
            showError("Could not add scene trigger", ex);
        }
    }

    private static JSpinner spinner(double value) {
        SpinnerNumberModel model = new SpinnerNumberModel(value, -10000.0, 10000.0, 0.25);
        return new JSpinner(model);
    }

    private List<Properties> readScenes(Properties manifest) {
        int count = sceneCount(manifest);
        List<Properties> scenes = new ArrayList<>();
        if (count == 0) {
            scenes.add(getScene(manifest, 0, 0));
            return scenes;
        }
        for (int i = 0; i < count; i++) scenes.add(getScene(manifest, i, count));
        return scenes;
    }

    private static Properties cloneProperties(Properties source) {
        Properties copy = new Properties();
        for (String key : source.stringPropertyNames())
            copy.setProperty(key, source.getProperty(key));
        return copy;
    }

    private void writeScenes(Properties manifest, List<Properties> scenes) {
        // Remove old scene.* keys.
        for (String key : new ArrayList<>(manifest.stringPropertyNames())) {
            if (key.startsWith("scene.")) manifest.remove(key);
        }

        manifest.setProperty("scene.count", Integer.toString(scenes.size()));
        int oldCurrent = intProp(manifest, "scene.current", 0);
        manifest.setProperty("scene.current",
                Integer.toString(Math.max(0, Math.min(oldCurrent, scenes.size() - 1))));

        for (int i = 0; i < scenes.size(); i++) {
            String prefix = "scene." + i + ".";
            Properties scene = scenes.get(i);
            for (String key : scene.stringPropertyNames()) {
                manifest.setProperty(prefix + key, scene.getProperty(key));
            }
        }
    }

    private void saveProject() throws IOException {
        Archive a = readArchive();
        // If the project was edited by an older TimelineEditor, preserve its scenes.
        List<Properties> scenes = readScenes(a.manifest);
        writeScenes(a.manifest, scenes);
        writeArchive(a);
    }

    private void writeArchive(Archive a) throws IOException {
        ByteArrayOutputStream propsBytes = new ByteArrayOutputStream();
        a.manifest.store(propsBytes, "WorldsGL Third Person Game Project");

        a.entries.put("project.properties", propsBytes.toByteArray());

        File temp = new File(projectFile.getParentFile(),
                projectFile.getName() + ".timeline.tmp");

        try (ZipOutputStream zout = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(temp)))) {
            for (Map.Entry<String, byte[]> entry : a.entries.entrySet()) {
                if ("project.properties".equals(entry.getKey())) {
                    // written from the updated manifest below
                    continue;
                }
                ZipEntry ze = new ZipEntry(entry.getKey());
                zout.putNextEntry(ze);
                zout.write(entry.getValue());
                zout.closeEntry();
            }

            ZipEntry manifestEntry = new ZipEntry("project.properties");
            zout.putNextEntry(manifestEntry);
            zout.write(propsBytes.toByteArray());
            zout.closeEntry();
        }

        if (!temp.renameTo(projectFile)) {
            // Windows can reject rename-over-existing files.
            if (projectFile.delete() && temp.renameTo(projectFile)) return;
            throw new IOException("Could not replace the project file.");
        }
    }

    private void showError(String title, Exception ex) {
        JOptionPane.showMessageDialog(frame,
                title + ":\n\n" + ex.getMessage(),
                "Timeline Editor Error", JOptionPane.ERROR_MESSAGE);
        ex.printStackTrace();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Open WorldsGL Project");
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "WorldsGL Third Person Game Project (*.tpgproject)", "tpgproject"));
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                new TimelineEditor(chooser.getSelectedFile(), null).show();
            }
        });
    }
}
