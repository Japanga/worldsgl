package objviewer;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reusable editor for object Behavior sub-profiles.
 *
 * A BehaviorProfile is deliberately limited to the individual object behavior
 * switches that were previously configured directly on ThirdPersonGameNew:
 * Is Enemy?, Chase?, Is NPC?, Game Over?, Patrol?, Trigger Zone?,
 * Collision?, Physics?, Reflective Object, and Auto-Rotate?.
 */
public final class BehaviorEditor {

    private BehaviorEditor() {}

    public static final String NONE = "Select a behavior...";

    public static final class BehaviorProfile {
        public String name;
        public boolean isEnemy;
        public boolean chase;
        public boolean isNPC;
        public boolean gameOver;
        public boolean patrol;
        public boolean triggerZone;
        public boolean collision;
        public boolean physics;
        public boolean reflective;
        public boolean autoRotate;

        public BehaviorProfile(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public BehaviorProfile copy() {
            BehaviorProfile p = new BehaviorProfile(name);
            p.isEnemy = isEnemy;
            p.chase = chase;
            p.isNPC = isNPC;
            p.gameOver = gameOver;
            p.patrol = patrol;
            p.triggerZone = triggerZone;
            p.collision = collision;
            p.physics = physics;
            p.reflective = reflective;
            p.autoRotate = autoRotate;
            return p;
        }
    }

    public static void writeProfilesToProperties(
            Properties properties,
            Map<String, BehaviorProfile> profiles) {

        properties.setProperty("behaviors.count",
                Integer.toString(profiles == null ? 0 : profiles.size()));

        if (profiles == null) return;

        int index = 0;
        for (BehaviorProfile profile : profiles.values()) {
            if (profile == null) continue;

            String k = "behavior." + index + ".";
            properties.setProperty(k + "name", profile.name == null ? "" : profile.name);
            properties.setProperty(k + "isEnemy", Boolean.toString(profile.isEnemy));
            properties.setProperty(k + "chase", Boolean.toString(profile.chase));
            properties.setProperty(k + "isNPC", Boolean.toString(profile.isNPC));
            properties.setProperty(k + "gameOver", Boolean.toString(profile.gameOver));
            properties.setProperty(k + "patrol", Boolean.toString(profile.patrol));
            properties.setProperty(k + "triggerZone", Boolean.toString(profile.triggerZone));
            properties.setProperty(k + "collision", Boolean.toString(profile.collision));
            properties.setProperty(k + "physics", Boolean.toString(profile.physics));
            properties.setProperty(k + "reflective", Boolean.toString(profile.reflective));
            properties.setProperty(k + "autoRotate", Boolean.toString(profile.autoRotate));
            index++;
        }
    }

    public static void readProfilesFromProperties(
            Properties properties,
            Map<String, BehaviorProfile> profiles) {

        if (profiles == null) return;
        profiles.clear();

        int count = parseInt(properties.getProperty("behaviors.count", "0"), 0);
        for (int i = 0; i < count; i++) {
            String k = "behavior." + i + ".";
            String name = properties.getProperty(k + "name", "").trim();
            if (name.isEmpty()) continue;

            BehaviorProfile profile = new BehaviorProfile(name);
            profile.isEnemy = Boolean.parseBoolean(properties.getProperty(k + "isEnemy", "false"));
            profile.chase = Boolean.parseBoolean(properties.getProperty(k + "chase", "false"));
            profile.isNPC = Boolean.parseBoolean(properties.getProperty(k + "isNPC", "false"));
            profile.gameOver = Boolean.parseBoolean(properties.getProperty(k + "gameOver", "false"));
            profile.patrol = Boolean.parseBoolean(properties.getProperty(k + "patrol", "false"));
            profile.triggerZone = Boolean.parseBoolean(properties.getProperty(k + "triggerZone", "false"));
            profile.collision = Boolean.parseBoolean(properties.getProperty(k + "collision", "false"));
            profile.physics = Boolean.parseBoolean(properties.getProperty(k + "physics", "false"));
            profile.reflective = Boolean.parseBoolean(properties.getProperty(k + "reflective", "false"));
            profile.autoRotate = Boolean.parseBoolean(properties.getProperty(k + "autoRotate", "false"));

            profiles.put(profile.name, profile);
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    public static void openEditor(
            Component parent,
            Map<String, BehaviorProfile> profiles,
            Runnable onChanged) {

        final JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(parent),
                "Behavior Editor",
                Dialog.ModalityType.APPLICATION_MODAL);

        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(8, 8));

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String name : profiles.keySet()) listModel.addElement(name);

        JList<String> profileList = new JList<>(listModel);
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setPreferredSize(new Dimension(180, 250));

        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(new JLabel("Behavior Profiles"), BorderLayout.NORTH);
        left.add(new JScrollPane(profileList), BorderLayout.CENTER);

        JButton addButton = new JButton("New");
        JButton renameButton = new JButton("Rename");
        JButton deleteButton = new JButton("Delete");

        JPanel listButtons = new JPanel(new GridLayout(1, 3, 4, 4));
        listButtons.add(addButton);
        listButtons.add(renameButton);
        listButtons.add(deleteButton);
        left.add(listButtons, BorderLayout.SOUTH);

        JCheckBox isEnemy = new JCheckBox("Is Enemy?");
        JCheckBox chase = new JCheckBox("Chase?");
        JCheckBox isNPC = new JCheckBox("Is NPC?");
        JCheckBox gameOver = new JCheckBox("Game Over?");
        JCheckBox patrol = new JCheckBox("Patrol?");
        JCheckBox triggerZone = new JCheckBox("Trigger Zone?");
        JCheckBox collision = new JCheckBox("Collision?");
        JCheckBox physics = new JCheckBox("Physics?");
        JCheckBox reflective = new JCheckBox("Metallic/Glossy?");
        JCheckBox autoRotate = new JCheckBox("Auto-Rotate?");

        JPanel checks = new JPanel();
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        checks.setBorder(BorderFactory.createTitledBorder("Behavior Settings"));
        checks.add(isEnemy);
        checks.add(chase);
        checks.add(isNPC);
        checks.add(gameOver);
        checks.add(patrol);
        checks.add(triggerZone);
        checks.add(collision);
        checks.add(physics);
        checks.add(reflective);
        checks.add(autoRotate);

        JPanel center = new JPanel(new BorderLayout());
        center.add(checks, BorderLayout.NORTH);

        JLabel selectedLabel = new JLabel("No profile selected.");
        center.add(selectedLabel, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        content.add(left, BorderLayout.WEST);
        content.add(center, BorderLayout.CENTER);

        JButton saveButton = new JButton("Apply Changes");
        JButton closeButton = new JButton("Close");
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(saveButton);
        bottom.add(closeButton);

        dialog.add(content, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);

        final BehaviorProfile[] editing = new BehaviorProfile[1];
        final String[] editingName = new String[1];

        Runnable loadSelection = () -> {
            String name = profileList.getSelectedValue();
            BehaviorProfile profile = name == null ? null : profiles.get(name);
            editing[0] = profile == null ? null : profile.copy();
            editingName[0] = name;

            boolean enabled = profile != null;
            isEnemy.setEnabled(enabled);
            chase.setEnabled(enabled);
            isNPC.setEnabled(enabled);
            gameOver.setEnabled(enabled);
            patrol.setEnabled(enabled);
            triggerZone.setEnabled(enabled);
            collision.setEnabled(enabled);
            physics.setEnabled(enabled);
            reflective.setEnabled(enabled);
            autoRotate.setEnabled(enabled);

            if (!enabled) {
                isEnemy.setSelected(false);
                chase.setSelected(false);
                isNPC.setSelected(false);
                gameOver.setSelected(false);
                patrol.setSelected(false);
                triggerZone.setSelected(false);
                collision.setSelected(false);
                physics.setSelected(false);
                reflective.setSelected(false);
                autoRotate.setSelected(false);
                selectedLabel.setText("No profile selected.");
                return;
            }

            BehaviorProfile p = editing[0];
            isEnemy.setSelected(p.isEnemy);
            chase.setSelected(p.chase);
            isNPC.setSelected(p.isNPC);
            gameOver.setSelected(p.gameOver);
            patrol.setSelected(p.patrol);
            triggerZone.setSelected(p.triggerZone);
            collision.setSelected(p.collision);
            physics.setSelected(p.physics);
            reflective.setSelected(p.reflective);
            autoRotate.setSelected(p.autoRotate);
            selectedLabel.setText("Editing: " + p.name);
        };

        profileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelection.run();
        });

        addButton.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(
                    dialog,
                    "Behavior profile name:",
                    "New Behavior Profile",
                    JOptionPane.PLAIN_MESSAGE);

            if (name == null) return;
            name = name.trim();

            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(dialog,
                        "Please enter a profile name.",
                        "Behavior Editor",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (NONE.equals(name) || profiles.containsKey(name)) {
                JOptionPane.showMessageDialog(dialog,
                        "That profile name is already in use.",
                        "Behavior Editor",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            BehaviorProfile profile = new BehaviorProfile(name);
            profiles.put(name, profile);
            listModel.addElement(name);
            profileList.setSelectedValue(name, true);

            if (onChanged != null) onChanged.run();
        });

        renameButton.addActionListener(e -> {
            String oldName = profileList.getSelectedValue();
            if (oldName == null) return;

            String newName = JOptionPane.showInputDialog(
                    dialog,
                    "New profile name:",
                    "Rename Behavior Profile",
                    JOptionPane.PLAIN_MESSAGE);

            if (newName == null) return;
            newName = newName.trim();

            if (newName.isEmpty() || NONE.equals(newName) || profiles.containsKey(newName)) {
                JOptionPane.showMessageDialog(dialog,
                        "Please choose a unique profile name.",
                        "Behavior Editor",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            BehaviorProfile profile = profiles.remove(oldName);
            profile.name = newName;

            // Rebuild in insertion order so the renamed profile stays usable.
            LinkedHashMap<String, BehaviorProfile> rebuilt = new LinkedHashMap<>();
            for (Map.Entry<String, BehaviorProfile> entry : profiles.entrySet()) {
                rebuilt.put(entry.getKey(), entry.getValue());
            }
            rebuilt.put(newName, profile);
            profiles.clear();
            profiles.putAll(rebuilt);

            int selectedIndex = profileList.getSelectedIndex();
            listModel.setElementAt(newName, selectedIndex);
            profileList.setSelectedIndex(selectedIndex);

            if (onChanged != null) onChanged.run();
        });

        deleteButton.addActionListener(e -> {
            String name = profileList.getSelectedValue();
            if (name == null) return;

            int answer = JOptionPane.showConfirmDialog(
                    dialog,
                    "Delete behavior profile \"" + name + "\"?\n\n"
                            + "Objects pinned to it will become unassigned.",
                    "Delete Behavior Profile",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (answer != JOptionPane.YES_OPTION) return;

            profiles.remove(name);
            listModel.removeElement(name);
            profileList.clearSelection();

            if (onChanged != null) onChanged.run();
        });

        saveButton.addActionListener(e -> {
            BehaviorProfile profile = editing[0];
            String originalName = editingName[0];
            if (profile == null || originalName == null) return;

            BehaviorProfile target = profiles.get(originalName);
            if (target == null) return;

            target.isEnemy = isEnemy.isSelected();
            target.chase = chase.isSelected();
            target.isNPC = isNPC.isSelected();
            target.gameOver = gameOver.isSelected();
            target.patrol = patrol.isSelected();
            target.triggerZone = triggerZone.isSelected();
            target.collision = collision.isSelected();
            target.physics = physics.isSelected();
            target.reflective = reflective.isSelected();
            target.autoRotate = autoRotate.isSelected();

            editing[0] = target.copy();

            if (onChanged != null) onChanged.run();
        });

        closeButton.addActionListener(e -> dialog.dispose());

        if (!listModel.isEmpty()) profileList.setSelectedIndex(0);
        loadSelection.run();

        dialog.pack();
        dialog.setMinimumSize(new Dimension(520, 360));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
