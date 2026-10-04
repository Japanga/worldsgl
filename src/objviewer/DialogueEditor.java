/*
 * DialogueEditor.java
 *
 * Scene-aware dialogue and quest editor for ThirdPersonGameNew.
 */
package objviewer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class DialogueEditor {

    private static final String DEFAULT_DATA_FILE = "dialogue_topics.dat";
    private static final String FORMAT_HEADER = "WORLDSGL_DIALOGUE_V2";

    private static File activeDataFile = new File(DEFAULT_DATA_FILE);
    private static final Map<String, TopicData> TOPICS = new LinkedHashMap<>();
    private static final List<String> QUESTS = new ArrayList<>();
    private static JFrame editorFrame;
    private static final List<Runnable> TOPICS_CHANGED_LISTENERS = new CopyOnWriteArrayList<>();

    private static class TopicData {
        String paragraph = "";
        Map<String, String> questParagraphs = new LinkedHashMap<>();
    }

    static {
        loadTopics();
    }

    public static synchronized void setDataFile(File file) {
        activeDataFile = (file == null)
                ? new File(DEFAULT_DATA_FILE)
                : file.getAbsoluteFile();
        loadTopics();
    }

    public static synchronized File getDataFile() {
        return activeDataFile;
    }

    public static synchronized List<String> getTopics() {
        loadTopics();
        return new ArrayList<>(TOPICS.keySet());
    }

    public static synchronized String getParagraph(String topic) {
        loadTopics();
        TopicData data = TOPICS.get(topic);
        return data == null ? "" : data.paragraph;
    }

    /**
     * Returns the completion paragraph for a particular quest.
     */
    public static synchronized String getParagraph(String topic, String quest) {
        loadTopics();
        TopicData data = TOPICS.get(topic);
        if (data == null || quest == null) return "";
        String text = data.questParagraphs.get(quest);
        return text == null ? "" : text;
    }

    public static synchronized List<String> getQuests() {
        loadTopics();
        return new ArrayList<>(QUESTS);
    }

    public static synchronized Map<String, String> getQuestParagraphs(String topic) {
        loadTopics();
        TopicData data = TOPICS.get(topic);
        return data == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(data.questParagraphs);
    }

    public static synchronized Map<String, String> getTopicMap() {
        loadTopics();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, TopicData> entry : TOPICS.entrySet()) {
            result.put(entry.getKey(), entry.getValue().paragraph);
        }
        return result;
    }

    /**
     * Snapshot of all quest-aware dialogue data.
     * The map is topic -> (quest -> completion paragraph).
     */
    public static synchronized Map<String, Map<String, String>> getQuestTopicMap() {
        loadTopics();
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, TopicData> entry : TOPICS.entrySet()) {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue().questParagraphs));
        }
        return result;
    }

    /**
     * Registers a listener notified whenever the active dialogue data is saved.
     * Quest creation, renaming, deletion, and quest-paragraph changes all use
     * the same notification path, allowing other GUIs to refresh immediately.
     */
    public static void addTopicsChangedListener(Runnable listener) {
        if (listener != null && !TOPICS_CHANGED_LISTENERS.contains(listener)) {
            TOPICS_CHANGED_LISTENERS.add(listener);
        }
    }

    public static void removeTopicsChangedListener(Runnable listener) {
        if (listener != null) TOPICS_CHANGED_LISTENERS.remove(listener);
    }

    private static void notifyTopicsChanged() {
        for (Runnable listener : TOPICS_CHANGED_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
        }
    }

    /**
     * Legacy-compatible writer. It writes normal topics and keeps the current
     * quest definitions/completion paragraphs when the supplied topic names match.
     */
    public static synchronized void writeTopics(File file, Map<String, String> topics)
            throws IOException {
        Map<String, TopicData> data = new LinkedHashMap<>();
        if (topics != null) {
            for (Map.Entry<String, String> entry : topics.entrySet()) {
                TopicData td = new TopicData();
                td.paragraph = entry.getValue() == null ? "" : entry.getValue();
                TopicData current = TOPICS.get(entry.getKey());
                if (current != null) {
                    td.questParagraphs.putAll(current.questParagraphs);
                }
                data.put(entry.getKey(), td);
            }
        }
        writeData(file, QUESTS, data);
    }

    /**
     * Writes a complete quest-aware dialogue database.
     */
    public static synchronized void writeData(
            File file,
            List<String> quests,
            Map<String, String> normalTopics,
            Map<String, Map<String, String>> questTopicMap) throws IOException {

        Map<String, TopicData> data = new LinkedHashMap<>();
        if (normalTopics != null) {
            for (Map.Entry<String, String> entry : normalTopics.entrySet()) {
                TopicData td = new TopicData();
                td.paragraph = entry.getValue() == null ? "" : entry.getValue();
                if (questTopicMap != null) {
                    Map<String, String> q = questTopicMap.get(entry.getKey());
                    if (q != null) td.questParagraphs.putAll(q);
                }
                data.put(entry.getKey(), td);
            }
        }
        writeData(file, quests, data);
    }

    private static void writeData(File file, List<String> quests,
                                  Map<String, TopicData> data) throws IOException {
        if (file == null) throw new IOException("Dialogue file is null.");
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {

            writer.write(FORMAT_HEADER);
            writer.write("\n");

            List<String> cleanQuests = new ArrayList<>();
            if (quests != null) {
                for (String quest : quests) {
                    if (quest != null && !quest.trim().isEmpty()
                            && !cleanQuests.contains(quest.trim())) {
                        cleanQuests.add(quest.trim());
                    }
                }
            }

            writer.write(Integer.toString(cleanQuests.size()));
            writer.write("\n");
            for (String quest : cleanQuests) {
                writeStringRecord(writer, quest);
            }

            writer.write(Integer.toString(data == null ? 0 : data.size()));
            writer.write("\n");

            if (data != null) {
                for (Map.Entry<String, TopicData> entry : data.entrySet()) {
                    String topic = entry.getKey() == null ? "" : entry.getKey();
                    TopicData td = entry.getValue() == null ? new TopicData() : entry.getValue();

                    writeStringRecord(writer, topic);
                    writeStringRecord(writer, td.paragraph);

                    List<Map.Entry<String, String>> qEntries = new ArrayList<>();
                    for (Map.Entry<String, String> q : td.questParagraphs.entrySet()) {
                        if (cleanQuests.contains(q.getKey())) qEntries.add(q);
                    }

                    writer.write(Integer.toString(qEntries.size()));
                    writer.write("\n");
                    for (Map.Entry<String, String> q : qEntries) {
                        writeStringRecord(writer, q.getKey());
                        writeStringRecord(writer, q.getValue());
                    }
                }
            }
        }
    }

    private static void writeStringRecord(Writer writer, String value) throws IOException {
        String safe = value == null ? "" : value;
        writer.write(Integer.toString(safe.length()));
        writer.write("\n");
        writer.write(safe);
        writer.write("\n");
    }

    public static synchronized void openEditor(File sceneDialogueFile) {
        setDataFile(sceneDialogueFile);
        if (editorFrame != null && editorFrame.isDisplayable()) {
            editorFrame.dispose();
            editorFrame = null;
        }
        openEditor();
    }

    public static synchronized void openEditor() {
        if (editorFrame != null && editorFrame.isDisplayable()) {
            refreshExistingEditor();
            editorFrame.toFront();
            editorFrame.requestFocus();
            return;
        }

        editorFrame = new JFrame("DialogueEditor");
        editorFrame.setSize(900, 700);
        editorFrame.setLocationByPlatform(true);
        editorFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        editorFrame.setLayout(new BorderLayout(8, 8));

        DefaultListModel<String> listModel = new DefaultListModel<>();
        JList<String> topicList = new JList<>(listModel);
        topicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScroll = new JScrollPane(topicList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Topics"));

        JTextField topicField = new JTextField();
        JTextArea paragraphArea = new JTextArea();
        paragraphArea.setLineWrap(true);
        paragraphArea.setWrapStyleWord(true);
        JScrollPane paragraphScroll = new JScrollPane(paragraphArea);
        paragraphScroll.setBorder(BorderFactory.createTitledBorder("NPC Speech Paragraph"));

        JPanel topicPanel = new JPanel(new BorderLayout(4, 4));
        topicPanel.add(new JLabel("Topic:"), BorderLayout.WEST);
        topicPanel.add(topicField, BorderLayout.CENTER);

        // ============================================================
        // QUEST COMPLETION PARAGRAPH EDITOR
        // ============================================================
        JComboBox<String> questCombo = new JComboBox<>();
        questCombo.setPrototypeDisplayValue("Select Quest");
        JTextField questNameField = new JTextField();
        questNameField.setPreferredSize(new Dimension(180, questNameField.getPreferredSize().height));

        JTextArea questParagraphArea = new JTextArea();
        questParagraphArea.setLineWrap(true);
        questParagraphArea.setWrapStyleWord(true);
        JScrollPane questParagraphScroll = new JScrollPane(questParagraphArea);
        questParagraphScroll.setBorder(
                BorderFactory.createTitledBorder("Quest Completion Paragraph"));

        JButton newQuest = new JButton("New Quest");
        JButton saveQuest = new JButton("Save Quest");
        JButton deleteQuest = new JButton("Delete Quest");

        JPanel questTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        questTop.add(new JLabel("Quest:"));
        questTop.add(questCombo);
        questTop.add(newQuest);
        questTop.add(deleteQuest);

        JPanel questNamePanel = new JPanel(new BorderLayout(4, 4));
        questNamePanel.add(new JLabel("Quest Name:"), BorderLayout.WEST);
        questNamePanel.add(questNameField, BorderLayout.CENTER);

        JPanel questEditor = new JPanel(new BorderLayout(4, 4));
        questEditor.setBorder(BorderFactory.createTitledBorder(
                "Quest Completion Dialogue"));
        questEditor.add(questTop, BorderLayout.NORTH);

        JPanel questCenter = new JPanel(new BorderLayout(4, 4));
        questCenter.add(questNamePanel, BorderLayout.NORTH);
        questCenter.add(questParagraphScroll, BorderLayout.CENTER);
        questEditor.add(questCenter, BorderLayout.CENTER);
        questEditor.add(saveQuest, BorderLayout.SOUTH);

        JPanel editor = new JPanel(new BorderLayout(6, 6));
        editor.add(topicPanel, BorderLayout.NORTH);

        JPanel textAreas = new JPanel(new GridLayout(2, 1, 4, 4));
        textAreas.add(paragraphScroll);
        textAreas.add(questEditor);
        editor.add(textAreas, BorderLayout.CENTER);

        JButton add = new JButton("New Topic");
        JButton save = new JButton("Save Topic");
        JButton delete = new JButton("Delete Topic");
        JButton reload = new JButton("Reload");
        JButton close = new JButton("Close");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(add);
        buttons.add(save);
        buttons.add(delete);
        buttons.add(reload);
        buttons.add(close);
        editor.add(buttons, BorderLayout.SOUTH);

        editorFrame.add(listScroll, BorderLayout.WEST);
        editorFrame.add(editor, BorderLayout.CENTER);

        final boolean[] refreshing = {false};

        Runnable refreshQuestList = () -> {
            loadTopics();
            String previous = (String) questCombo.getSelectedItem();
            refreshing[0] = true;
            try {
                questCombo.removeAllItems();
                for (String quest : QUESTS) questCombo.addItem(quest);
                if (previous != null && QUESTS.contains(previous)) {
                    questCombo.setSelectedItem(previous);
                } else if (questCombo.getItemCount() > 0) {
                    questCombo.setSelectedIndex(0);
                }
            } finally {
                refreshing[0] = false;
            }
        };

        Runnable loadSelectedQuestParagraph = () -> {
            if (refreshing[0]) return;
            String topic = topicList.getSelectedValue();
            String quest = (String) questCombo.getSelectedItem();
            if (topic == null || quest == null) {
                questNameField.setText("");
                questParagraphArea.setText("");
                return;
            }
            questNameField.setText(quest);
            questParagraphArea.setText(getParagraph(topic, quest));
        };

        Runnable refreshList = () -> {
            loadTopics();
            String selected = topicList.getSelectedValue();
            listModel.clear();
            for (String topic : TOPICS.keySet()) listModel.addElement(topic);
            if (selected != null && TOPICS.containsKey(selected)) {
                topicList.setSelectedValue(selected, true);
            } else if (!TOPICS.isEmpty()) {
                topicList.setSelectedIndex(0);
            }
            refreshQuestList.run();
            loadSelectedQuestParagraph.run();
        };

        topicList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String topic = topicList.getSelectedValue();
            if (topic != null) {
                topicField.setText(topic);
                paragraphArea.setText(getParagraph(topic));
                loadSelectedQuestParagraph.run();
            }
        });

        questCombo.addActionListener(e -> loadSelectedQuestParagraph.run());

        newQuest.addActionListener(e -> {
            String suggested = "Quest " + (QUESTS.size() + 1);
            String name = JOptionPane.showInputDialog(
                    editorFrame, "Quest name:", suggested);
            if (name == null) return;
            name = name.trim();
            if (name.isEmpty()) return;

            if (!QUESTS.contains(name)) QUESTS.add(name);
            TopicData current = TOPICS.get(topicList.getSelectedValue());
            if (current != null && !current.questParagraphs.containsKey(name)) {
                current.questParagraphs.put(name, "");
            }
            saveTopics();
            refreshQuestList.run();
            questCombo.setSelectedItem(name);
            questNameField.setText(name);
            questParagraphArea.setText("");
        });

        saveQuest.addActionListener(e -> {
            String topic = topicList.getSelectedValue();
            String oldQuest = (String) questCombo.getSelectedItem();
            String newQuestName = questNameField.getText().trim();

            if (topic == null || topic.isEmpty()) {
                JOptionPane.showMessageDialog(editorFrame,
                        "Select a topic before saving a quest paragraph.",
                        "Missing Topic", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (newQuestName.isEmpty()) {
                JOptionPane.showMessageDialog(editorFrame,
                        "Please enter a quest name.",
                        "Missing Quest", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (oldQuest != null && !oldQuest.equals(newQuestName)) {
                QUESTS.remove(oldQuest);
                for (TopicData td : TOPICS.values()) {
                    String old = td.questParagraphs.remove(oldQuest);
                    if (old != null) td.questParagraphs.put(newQuestName, old);
                }
            }

            if (!QUESTS.contains(newQuestName)) QUESTS.add(newQuestName);
            TopicData td = TOPICS.get(topic);
            if (td == null) {
                td = new TopicData();
                TOPICS.put(topic, td);
            }
            td.questParagraphs.put(newQuestName, questParagraphArea.getText());

            saveTopics();
            refreshList.run();
            topicList.setSelectedValue(topic, true);
            questCombo.setSelectedItem(newQuestName);
        });

        deleteQuest.addActionListener(e -> {
            String quest = (String) questCombo.getSelectedItem();
            if (quest == null || quest.isEmpty()) return;

            int answer = JOptionPane.showConfirmDialog(
                    editorFrame,
                    "Delete quest \"" + quest + "\" and all of its completion paragraphs?",
                    "Delete Quest", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) return;

            QUESTS.remove(quest);
            for (TopicData td : TOPICS.values()) td.questParagraphs.remove(quest);
            saveTopics();
            refreshList.run();
        });

        add.addActionListener(e -> {
            topicList.clearSelection();
            topicField.setText("");
            paragraphArea.setText("");
            questParagraphArea.setText("");
            topicField.requestFocusInWindow();
        });

        save.addActionListener(e -> {
            String topic = topicField.getText().trim();
            if (topic.isEmpty()) {
                JOptionPane.showMessageDialog(editorFrame,
                        "Please enter a topic name.",
                        "Missing Topic", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String previousTopic = topicList.getSelectedValue();
            if (previousTopic != null && !previousTopic.equals(topic)) {
                TopicData previous = TOPICS.remove(previousTopic);
                if (previous != null) TOPICS.put(topic, previous);
            } else {
                TopicData td = TOPICS.get(topic);
                if (td == null) {
                    td = new TopicData();
                    TOPICS.put(topic, td);
                }
                td.paragraph = paragraphArea.getText();
            }

            TopicData td = TOPICS.get(topic);
            if (td != null) td.paragraph = paragraphArea.getText();

            saveTopics();
            refreshList.run();
            topicList.setSelectedValue(topic, true);
        });

        delete.addActionListener(e -> {
            String topic = topicList.getSelectedValue();
            if (topic == null) topic = topicField.getText().trim();
            if (topic != null && !topic.isEmpty()) {
                TOPICS.remove(topic);
                saveTopics();
                topicField.setText("");
                paragraphArea.setText("");
                questParagraphArea.setText("");
                refreshList.run();
            }
        });

        reload.addActionListener(e -> refreshList.run());
        close.addActionListener(e -> editorFrame.dispose());

        editorFrame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { editorFrame = null; }
        });

        refreshList.run();
        editorFrame.setVisible(true);
    }

    private static synchronized void refreshExistingEditor() {
        if (editorFrame == null || !editorFrame.isDisplayable()) return;
        editorFrame.repaint();
    }

    private static synchronized void loadTopics() {
        TOPICS.clear();
        QUESTS.clear();

        File file = activeDataFile;
        if (file == null || !file.isFile()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {

            String first = reader.readLine();
            if (FORMAT_HEADER.equals(first)) {
                loadV2(reader);
            } else if (first != null) {
                // Legacy format: first line is the first topic.
                loadLegacy(reader, first);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void loadLegacy(BufferedReader reader, String firstTopic) throws IOException {
        String topic = firstTopic;
        while (topic != null) {
            String lengthLine = reader.readLine();
            if (lengthLine == null) break;

            int length = Integer.parseInt(lengthLine);
            String paragraph = readFixedString(reader, length);
            TopicData td = new TopicData();
            td.paragraph = paragraph;
            TOPICS.put(topic, td);

            reader.readLine();
            topic = reader.readLine();
        }
    }

    private static void loadV2(BufferedReader reader) throws IOException {
        int questCount = Integer.parseInt(reader.readLine());
        for (int i = 0; i < questCount; i++) {
            QUESTS.add(readStringRecord(reader));
        }

        int topicCount = Integer.parseInt(reader.readLine());
        for (int i = 0; i < topicCount; i++) {
            String topic = readStringRecord(reader);
            TopicData td = new TopicData();
            td.paragraph = readStringRecord(reader);

            int questParagraphCount = Integer.parseInt(reader.readLine());
            for (int q = 0; q < questParagraphCount; q++) {
                String quest = readStringRecord(reader);
                String paragraph = readStringRecord(reader);
                if (!quest.isEmpty()) td.questParagraphs.put(quest, paragraph);
            }
            TOPICS.put(topic, td);
        }
    }

    private static String readStringRecord(BufferedReader reader) throws IOException {
        String lengthLine = reader.readLine();
        if (lengthLine == null) return "";
        int length = Integer.parseInt(lengthLine);
        return readFixedString(reader, length);
    }

    private static String readFixedString(BufferedReader reader, int length) throws IOException {
        char[] chars = new char[Math.max(0, length)];
        int read = 0;
        while (read < chars.length) {
            int n = reader.read(chars, read, chars.length - read);
            if (n < 0) break;
            read += n;
        }
        reader.readLine();
        return new String(chars, 0, read);
    }

    private static synchronized void saveTopics() {
        File file = activeDataFile;
        if (file == null) return;

        try {
            writeData(file, QUESTS, TOPICS);
            notifyTopicsChanged();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(DialogueEditor::openEditor);
    }
}
