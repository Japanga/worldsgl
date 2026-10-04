package objviewer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads optional Java Sound codec providers from the engine's local lib/audio
 * directories before AudioSystem is first used. The actual MP3/OGG decoders
 * are the standard MP3SPI/JLayer and VorbisSPI/JOrbis artifacts listed in
 * audio-codecs-dependencies.txt.
 */
public final class AudioCodecSupport {
    private static volatile boolean initialized;
    private static volatile ClassLoader codecLoader;

    private AudioCodecSupport() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            ClassLoader parent = Thread.currentThread().getContextClassLoader();
            if (parent == null) parent = AudioCodecSupport.class.getClassLoader();

            List<URL> urls = new ArrayList<>();
            File base = new File(System.getProperty("user.dir", "."));
            collectJars(new File(base, "lib"), urls);
            collectJars(new File(base, "lib/audio"), urls);
            collectJars(new File(base, "audio-libs"), urls);

            if (!urls.isEmpty()) {
                codecLoader = new URLClassLoader(urls.toArray(new URL[0]), parent);
                Thread.currentThread().setContextClassLoader(codecLoader);
            } else {
                codecLoader = parent;
            }
        } catch (Throwable ex) {
            codecLoader = AudioCodecSupport.class.getClassLoader();
            System.err.println("Audio codec bootstrap warning: " + ex.getMessage());
        }
    }

    private static void collectJars(File dir, List<URL> urls) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".jar") && (n.contains("mp3spi") || n.contains("jlayer")
                    || n.contains("tritonus") || n.contains("vorbis") || n.contains("jorbis")
                    || n.contains("jogg"));
        });
        if (files == null) return;
        for (File file : files) {
            try { urls.add(file.toURI().toURL()); }
            catch (Exception ignored) {}
        }
    }

    public static String describe(File file) {
        initialize();
        try {
            AudioFileFormat format = AudioSystem.getAudioFileFormat(file);
            return format.getType().toString();
        } catch (Exception ex) {
            return "Unsupported: " + ex.getMessage();
        }
    }

    public static boolean canParse(File file) {
        initialize();
        try {
            AudioSystem.getAudioFileFormat(file);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static ClassLoader getCodecClassLoader() {
        initialize();
        return codecLoader;
    }
}
