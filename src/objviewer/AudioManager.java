package objviewer;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * WorldsGL project Audio Manager.
 *
 * The editor owns the audio library list; this window is responsible only for
 * importing, listing, previewing and removing audio assets. The host supplies
 * the project-local storage directory and receives the imported runtime path.
 */
public final class AudioManager extends JDialog {

    public static final class AudioEntry {
        public String name;
        public String path;

        public AudioEntry(String name, String path) {
            this.name = name == null ? "" : name;
            this.path = path == null ? "" : path;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public interface Host {
        List<AudioEntry> getAudioLibrary();
        File getAudioStorageDirectory() throws IOException;
        void audioLibraryChanged();
    }

    private final Host host;
    private final DefaultListModel<AudioEntry> listModel = new DefaultListModel<>();
    private final JList<AudioEntry> audioList = new JList<>(listModel);
    private Clip previewClip;

    private AudioManager(Window owner, Host host) {
        super(owner, "Audio Manager", Dialog.ModalityType.MODELESS);
        this.host = host;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        buildUi();
        reloadLibrary();
        setSize(520, 430);
        setLocationRelativeTo(owner);
    }

    public static AudioManager open(Window owner, Host host) {
        AudioManager manager = new AudioManager(owner, host);
        manager.setVisible(true);
        return manager;
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel("♫ Audio Library");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        root.add(title, BorderLayout.NORTH);

        audioList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        audioList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                            int index, boolean selected,
                                                            boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focused);
                AudioEntry entry = (AudioEntry) value;
                label.setText("♫ " + (entry == null || entry.name == null ? "Audio" : entry.name));
                return label;
            }
        });
        root.add(new JScrollPane(audioList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton importButton = new JButton("Import Audio...");
        JButton playButton = new JButton("▶ Preview");
        JButton stopButton = new JButton("■ Stop");
        JButton removeButton = new JButton("Remove");

        importButton.addActionListener(e -> importAudio());
        playButton.addActionListener(e -> previewSelected());
        stopButton.addActionListener(e -> stopPreview());
        removeButton.addActionListener(e -> removeSelected());

        buttons.add(importButton);
        buttons.add(playButton);
        buttons.add(stopButton);
        buttons.add(removeButton);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private void reloadLibrary() {
        listModel.clear();
        if (host == null || host.getAudioLibrary() == null) return;
        for (AudioEntry entry : host.getAudioLibrary()) {
            if (entry != null) listModel.addElement(entry);
        }
    }

    private void importAudio() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Audio File");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Audio files (*.wav, *.aiff, *.aif, *.au, *.snd, *.mp3, *.ogg)",
                "wav", "aiff", "aif", "au", "snd", "mp3", "ogg"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File source = chooser.getSelectedFile();
        if (source == null || !source.isFile()) return;

        try {
            AudioCodecSupport.initialize();
            if (!AudioCodecSupport.canParse(source)) {
                throw new IOException("Unsupported audio format. Install the MP3SPI/JLayer and VorbisSPI/JOrbis codec JARs listed in audio-codecs-dependencies.txt.");
            }

            File storage = host.getAudioStorageDirectory();
            if (!storage.exists() && !storage.mkdirs() && !storage.isDirectory()) {
                throw new IOException("Could not create project audio library directory.");
            }

            String originalName = source.getName();
            String safeName = originalName.replaceAll("[^A-Za-z0-9._()\\[\\]-]", "_");
            if (safeName.trim().isEmpty()) safeName = "audio_" + UUID.randomUUID();

            File target = new File(storage, safeName);
            int suffix = 2;
            String base = safeName;
            String ext = "";
            int dot = safeName.lastIndexOf('.');
            if (dot > 0) {
                base = safeName.substring(0, dot);
                ext = safeName.substring(dot);
            }
            while (target.exists()) {
                target = new File(storage, base + " " + suffix++ + ext);
            }

            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            AudioEntry entry = new AudioEntry(target.getName(), target.getAbsolutePath());
            host.getAudioLibrary().add(entry);
            host.audioLibraryChanged();
            reloadLibrary();
            audioList.setSelectedValue(entry, true);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not import audio:\n\n" + ex.getMessage(),
                    "Audio Manager", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeSelected() {
        AudioEntry selected = audioList.getSelectedValue();
        if (selected == null) return;

        int answer = JOptionPane.showConfirmDialog(this,
                "Remove ♫ " + selected.name + " from the project audio library?",
                "Remove Audio", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;

        stopPreview();
        host.getAudioLibrary().remove(selected);
        host.audioLibraryChanged();
        reloadLibrary();
    }

    private void previewSelected() {
        AudioEntry selected = audioList.getSelectedValue();
        if (selected == null || selected.path == null || selected.path.trim().isEmpty()) return;
        stopPreview();

        try {
            AudioCodecSupport.initialize();
            try (AudioInputStream source = AudioSystem.getAudioInputStream(new File(selected.path))) {
                AudioFormat base = source.getFormat();
                AudioFormat decoded = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        base.getSampleRate(), 16,
                        Math.max(1, base.getChannels()),
                        Math.max(1, base.getChannels()) * 2,
                        base.getSampleRate(), false);
                try (AudioInputStream stream = AudioSystem.getAudioInputStream(decoded, source)) {
                    Clip clip = AudioSystem.getClip();
                    clip.open(stream);
                    previewClip = clip;
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP && previewClip == clip) {
                    clip.close();
                    previewClip = null;
                }
            });
                    clip.start();
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "This audio format could not be previewed by the Java Sound backend.\n\n"
                            + ex.getMessage(),
                    "Audio Preview", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void stopPreview() {
        if (previewClip != null) {
            try { previewClip.stop(); } catch (Exception ignored) { }
            try { previewClip.close(); } catch (Exception ignored) { }
            previewClip = null;
        }
    }

    @Override
    public void dispose() {
        stopPreview();
        super.dispose();
    }
}
