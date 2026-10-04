package objviewer;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reusable editor for object Action sub-profiles.
 *
 * Each ActionProfile groups the four interaction/action systems that were
 * previously configured directly on ThirdPersonGameNew:
 * Gives Points?, Gives HP?, Attacks Player?, and Quest Item?.
 */
public final class ActionBuilder {
    private ActionBuilder() {}

    public static final String NONE = "Select an action...";

    public static final class ActionProfile {
        public String name;
        public boolean givesPoints;
        public int points = 10;
        public boolean givesHP;
        public int hpAmount = 10;
        public boolean attacksPlayer;
        public int attackDamage = 10;
        public boolean questItem;
        public String questName = "";

        public ActionProfile(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public ActionProfile copy() {
            ActionProfile p = new ActionProfile(name);
            p.givesPoints = givesPoints;
            p.points = points;
            p.givesHP = givesHP;
            p.hpAmount = hpAmount;
            p.attacksPlayer = attacksPlayer;
            p.attackDamage = attackDamage;
            p.questItem = questItem;
            p.questName = questName == null ? "" : questName;
            return p;
        }
    }

    public static void openEditor(Component parent,
                                   Map<String, ActionProfile> profiles,
                                   List<String> questNames,
                                   Runnable onChanged) {
        final JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(parent),
                "Action Builder", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        for (String name : profiles.keySet()) model.addElement(name);

        JTextField nameField = new JTextField();
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameField.getPreferredSize().height));
        JCheckBox givesPoints = new JCheckBox("Gives Points?");
        JSpinner points = new JSpinner(new SpinnerNumberModel(10, 0, 1000000, 1));
        JCheckBox givesHP = new JCheckBox("Gives HP?");
        JSpinner hp = new JSpinner(new SpinnerNumberModel(10, 0, 1000000, 1));
        JCheckBox attacksPlayer = new JCheckBox("Attacks Player?");
        JSpinner damage = new JSpinner(new SpinnerNumberModel(10, 0, 1000000, 1));
        JCheckBox questItem = new JCheckBox("Quest Item?");
        JComboBox<String> questCombo = new JComboBox<>();
        questCombo.setPrototypeDisplayValue("Select a quest...");
        questCombo.addItem("Select a quest...");
        if (questNames != null) {
            for (String q : questNames) {
                if (q != null && !q.trim().isEmpty()) questCombo.addItem(q);
            }
        }

        JPanel fields = new JPanel();
        fields.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        JLabel nameLabel = new JLabel("Action profile name:");
      //  nameLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        fields.add(nameLabel);
        fields.add(nameField);
        fields.add(Box.createVerticalStrut(8));
        fields.add(row(givesPoints, "Points:", points));
        fields.add(row(givesHP, "HP:", hp));
        fields.add(row(attacksPlayer, "Damage:", damage));
        JPanel questRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        questRow.add(questItem);
        questRow.add(new JLabel("Quest:"));
        questRow.add(questCombo);
        fields.add(questRow);

        JButton newButton = new JButton("New");
        JButton renameButton = new JButton("Rename");
        JButton deleteButton = new JButton("Delete");
        JButton applyButton = new JButton("Apply Changes");
        JButton closeButton = new JButton("Close");

        Runnable loadSelected = () -> {
            String selected = list.getSelectedValue();
            ActionProfile p = selected == null ? null : profiles.get(selected);
            if (p == null) {
                nameField.setText("");
                givesPoints.setSelected(false);
                points.setValue(10);
                givesHP.setSelected(false);
                hp.setValue(10);
                attacksPlayer.setSelected(false);
                damage.setValue(10);
                questItem.setSelected(false);
                questCombo.setSelectedIndex(0);
                return;
            }
            nameField.setText(p.name);
            givesPoints.setSelected(p.givesPoints);
            points.setValue(Math.max(0, p.points));
            givesHP.setSelected(p.givesHP);
            hp.setValue(Math.max(0, p.hpAmount));
            attacksPlayer.setSelected(p.attacksPlayer);
            damage.setValue(Math.max(0, p.attackDamage));
            questItem.setSelected(p.questItem);
            questCombo.setSelectedItem(p.questName == null || p.questName.trim().isEmpty()
                    ? "Select a quest..." : p.questName);
            if (questCombo.getSelectedIndex() < 0) questCombo.setSelectedIndex(0);
        };

        newButton.addActionListener(e -> {
            String base = "Action " + (model.size() + 1);
            String name = base;
            int n = 2;
            while (profiles.containsKey(name)) name = base + " (" + n++ + ")";
            ActionProfile p = new ActionProfile(name);
            profiles.put(name, p);
            model.addElement(name);
            list.setSelectedValue(name, true);
            loadSelected.run();
            if (onChanged != null) onChanged.run();
        });

        renameButton.addActionListener(e -> {
            String oldName = list.getSelectedValue();
            if (oldName == null) return;
            String proposed = JOptionPane.showInputDialog(dialog, "New action profile name:", oldName);
            if (proposed == null) return;
            proposed = proposed.trim();
            if (proposed.isEmpty() || proposed.equals(oldName)) return;
            if (profiles.containsKey(proposed)) {
                JOptionPane.showMessageDialog(dialog, "An action profile with that name already exists.");
                return;
            }
            ActionProfile p = profiles.remove(oldName);
            p.name = proposed;
            profiles.put(proposed, p);
            int idx = list.getSelectedIndex();
            model.set(idx, proposed);
            list.setSelectedIndex(idx);
            if (onChanged != null) onChanged.run();
        });

        deleteButton.addActionListener(e -> {
            String selected = list.getSelectedValue();
            if (selected == null) return;
            profiles.remove(selected);
            int idx = list.getSelectedIndex();
            model.remove(idx);
            if (!model.isEmpty()) list.setSelectedIndex(Math.min(idx, model.size() - 1));
            loadSelected.run();
            if (onChanged != null) onChanged.run();
        });

        applyButton.addActionListener(e -> {
            String oldName = list.getSelectedValue();
            if (oldName == null) return;
            String newName = nameField.getText().trim();
            if (newName.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Please enter an action profile name.");
                return;
            }
            if (!oldName.equals(newName) && profiles.containsKey(newName)) {
                JOptionPane.showMessageDialog(dialog, "An action profile with that name already exists.");
                return;
            }
            ActionProfile p = profiles.remove(oldName);
            p.name = newName;
            p.givesPoints = givesPoints.isSelected();
            p.points = Math.max(0, ((Number) points.getValue()).intValue());
            p.givesHP = givesHP.isSelected();
            p.hpAmount = Math.max(0, ((Number) hp.getValue()).intValue());
            p.attacksPlayer = attacksPlayer.isSelected();
            p.attackDamage = Math.max(0, ((Number) damage.getValue()).intValue());
            p.questItem = questItem.isSelected();
            String q = (String) questCombo.getSelectedItem();
            p.questName = q == null || "Select a quest...".equals(q) ? "" : q.trim();
            profiles.put(newName, p);
            int idx = list.getSelectedIndex();
            model.set(idx, newName);
            list.setSelectedIndex(idx);
            if (onChanged != null) onChanged.run();
        });

        closeButton.addActionListener(e -> dialog.dispose());
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadSelected.run(); });

        JPanel leftButtons = new JPanel(new GridLayout(1, 3, 4, 4));
        leftButtons.add(newButton);
        leftButtons.add(renameButton);
        leftButtons.add(deleteButton);
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.add(leftButtons, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(190, 300));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(applyButton);
        bottom.add(closeButton);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(left, BorderLayout.WEST);
        content.add(fields, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(700, 390);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(parent);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        else loadSelected.run();
        dialog.setVisible(true);
    }

    private static JPanel row(JCheckBox box, String label, JSpinner spinner) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.add(box); p.add(new JLabel(label)); p.add(spinner);
        return p;
    }

    public static void writeProfilesToProperties(Properties p,
                                                 Map<String, ActionProfile> profiles) {
        p.setProperty("actions.count", Integer.toString(profiles.size()));
        int i = 0;
        for (ActionProfile a : profiles.values()) {
            String k = "action." + i + ".";
            p.setProperty(k + "name", a.name == null ? "" : a.name);
            p.setProperty(k + "givesPoints", Boolean.toString(a.givesPoints));
            p.setProperty(k + "points", Integer.toString(Math.max(0, a.points)));
            p.setProperty(k + "givesHP", Boolean.toString(a.givesHP));
            p.setProperty(k + "hpAmount", Integer.toString(Math.max(0, a.hpAmount)));
            p.setProperty(k + "attacksPlayer", Boolean.toString(a.attacksPlayer));
            p.setProperty(k + "attackDamage", Integer.toString(Math.max(0, a.attackDamage)));
            p.setProperty(k + "questItem", Boolean.toString(a.questItem));
            p.setProperty(k + "questName", a.questName == null ? "" : a.questName);
            i++;
        }
    }

    public static void readProfilesFromProperties(Properties p,
                                                  Map<String, ActionProfile> profiles) {
        profiles.clear();
        int count;
        try { count = Integer.parseInt(p.getProperty("actions.count", "0")); }
        catch (Exception ex) { count = 0; }
        for (int i = 0; i < count; i++) {
            String k = "action." + i + ".";
            String name = p.getProperty(k + "name", "").trim();
            if (name.isEmpty()) continue;
            ActionProfile a = new ActionProfile(name);
            a.givesPoints = Boolean.parseBoolean(p.getProperty(k + "givesPoints", "false"));
            a.points = parseInt(p, k + "points", 10);
            a.givesHP = Boolean.parseBoolean(p.getProperty(k + "givesHP", "false"));
            a.hpAmount = parseInt(p, k + "hpAmount", 10);
            a.attacksPlayer = Boolean.parseBoolean(p.getProperty(k + "attacksPlayer", "false"));
            a.attackDamage = parseInt(p, k + "attackDamage", 10);
            a.questItem = Boolean.parseBoolean(p.getProperty(k + "questItem", "false"));
            a.questName = p.getProperty(k + "questName", "");
            profiles.put(name, a);
        }
    }

    private static int parseInt(Properties p, String key, int fallback) {
        try { return Math.max(0, Integer.parseInt(p.getProperty(key, Integer.toString(fallback)))); }
        catch (Exception ex) { return fallback; }
    }
}
