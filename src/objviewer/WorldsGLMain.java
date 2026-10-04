package objviewer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.io.File;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * WorldsGL main launcher window.
 *
 * This class is intentionally written by hand and does not depend on the
 * NetBeans/Swing GUI Builder.
 *
 * IMPORTANT:
 *   jogamp-all.jar and gluegen-rt.jar must be on the Java classpath.
 *   The JOGL/GlueGen native libraries are initialized exactly once by this
 *   class before any OpenGL-dependent tool is launched.
 *
 * Expected Windows native libraries:
 *     natives/windows-amd64/gluegen-rt.dll
 *     natives/windows-amd64/nativewindow_win32.dll
 *     natives/windows-amd64/nativewindow_awt.dll
 *     natives/windows-amd64/jogl_desktop.dll
 *
 * The Windows NativeWindow DLL is essential for GDIUtil. Loading only
 * nativewindow_awt.dll is insufficient and can cause GDIUtil.initIDs()
 * / initlDso() UnsatisfiedLinkError failures.
 *
 * Child tools are launched from a separate launcher thread. Each child then
 * schedules its Swing UI on the AWT Event Dispatch Thread. This prevents a
 * child tool from blocking the launcher UI while still keeping all Swing
 * component creation on the EDT.
 */
public class WorldsGLMain extends JFrame {

    private static final Logger LOGGER =
            Logger.getLogger(WorldsGLMain.class.getName());

    private static final String WINDOW_TITLE =
            "WorldsGL OpenGL Game Development Software 1.0.0";

    private static final boolean AUTO_LOAD_NATIVE_LIBRARIES = true;

    /*
     * Native initialization state is guarded by NATIVE_LOCK.  This is
     * deliberately process-wide and never reset while the JVM is running.
     */
    private static final Object NATIVE_LOCK = new Object();
    private static volatile boolean nativeLibrariesInitialized = false;

    public WorldsGLMain() {
        super(WINDOW_TITLE);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setFocusable(false);
        buildInterface();

        pack();
        setLocationRelativeTo(null);
    }

    /** Builds the complete Swing interface without the NetBeans GUI editor. */
    private void buildInterface() {
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        JButton toolsButton = new JButton("Tools");
        JButton helpButton = new JButton("Help");

        toolsButton.addActionListener(e -> showToolsMenu(toolsButton));
        helpButton.addActionListener(e -> showHelpMenu(helpButton));

        topBar.add(toolsButton);
        topBar.add(helpButton);

        rootPanel.add(topBar, BorderLayout.NORTH);

        ImagePanel imagePanel = new ImagePanel("/textures/worldsgl.png");
        imagePanel.setPreferredSize(new Dimension(165, 179));

        JPanel center = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 83));

        // Give the main/base panel more horizontal room without stretching
        // the WorldsGL image itself.
        center.setPreferredSize(new Dimension(450, 345));

        center.add(imagePanel);

        rootPanel.add(center, BorderLayout.CENTER);
        setContentPane(rootPanel);
    }

    private void showToolsMenu(JButton source) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem objViewer = new JMenuItem("Open .obj model viewer");
        objViewer.addActionListener(e -> launchTool(
                "OBJ model viewer",
                () -> ObjViewer.main(new String[0])
        ));

        JMenuItem gameCreator =
                new JMenuItem("Open third person game creator");
        gameCreator.addActionListener(e -> launchTool(
                "third person game creator",
                () -> ThirdPersonGameNew.main(new String[0])
        ));

        JMenuItem dialogueEditor = new JMenuItem("Open dialogue editor");
        dialogueEditor.addActionListener(e -> launchTool(
                "dialogue editor",
                () -> DialogueEditor.main(new String[0])
        ));
        
         JMenuItem timelineEditor = new JMenuItem("Open timeline editor");
        timelineEditor.addActionListener(e -> launchTool(
                "timeline editor",
                () -> TimelineEditor.main(new String[0])
        ));
        

        JMenuItem lightsRenderer = new JMenuItem("Open lights tester");
        lightsRenderer.addActionListener(e -> launchTool(
                "lights rendering",
                () -> LightRender.main(new String[0])
        ));
        
                JMenuItem physicsTester = new JMenuItem("Open physics tester");
        physicsTester.addActionListener(e -> launchTool(
                "lights rendering",
                () -> Newton.main(new String[0])
        ));
        
         JMenuItem exeBuilding = new JMenuItem("Export .JAR to .EXE");
        exeBuilding.addActionListener(e -> launchTool(
                "exe builder",
                () -> ExeBuilder.main(new String[0])
        ));

        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));

        popup.add(objViewer);
        popup.add(gameCreator);
        popup.add(dialogueEditor);
        popup.add(timelineEditor);
        popup.add(lightsRenderer);
        popup.add(physicsTester);
        popup.addSeparator();
         popup.add(exeBuilding);
        popup.add(exit);

        popup.show(source, 0, source.getHeight());
    }

    /**
     * Launches an OpenGL tool without blocking the AWT event thread.
     *
     * Native initialization belongs to this launcher and is performed once,
     * before the main window exists. The child tool is responsible for
     * constructing its Swing UI on the EDT.
     */
    private void launchTool(String name, Runnable launcher) {
        System.out.println("Opening " + name + "...");

        Thread launcherThread = new Thread(() -> {
            try {
                launcher.run();
            } catch (Throwable error) {
                /*
                 * UnsatisfiedLinkError is an Error, not an Exception, so this
                 * must intentionally catch Throwable. The error is reported
                 * without killing the AWT event thread.
                 */
                LOGGER.log(
                        Level.SEVERE,
                        "Could not launch " + name,
                        error
                );

                SwingUtilities.invokeLater(() -> showLaunchError(name, error));
            }
        }, "WorldsGL-Launcher-" + name.replaceAll("[^A-Za-z0-9]+", "-"));

        launcherThread.setDaemon(false);
        launcherThread.start();
    }

    private void showLaunchError(String name, Throwable error) {
        String message = "Could not open " + name + ".\n\n"
                + error.getClass().getName() + ": "
                + (error.getMessage() == null ? "No additional message." : error.getMessage())
                + "\n\n"
                + "Check that the JOGL/GlueGen JAR files and matching native "
                + "libraries are from the same version and architecture.";

        JOptionPane.showMessageDialog(
                this,
                message,
                "WorldsGL - Tool Launch Error",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private void showHelpMenu(JButton source) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem help = new JMenuItem("Help on using the engine/software");
        help.addActionListener(e -> showInfo(
                "WorldsGL Help",
                "Use the Tools menu to open the available editors and "
                        + "rendering tools."));

        JMenuItem assets = new JMenuItem("View default assets list");
        assets.addActionListener(e -> showInfo(
                "Default Assets",
                "Default assets are loaded by the individual WorldsGL tools."));

        JMenuItem updates = new JMenuItem("Check for updates...");
        updates.addActionListener(e -> showInfo(
                "Updates",
                "Update checking has not been configured yet."));

        JMenuItem website = new JMenuItem("Visit website");
        website.addActionListener(e -> showInfo(
                "Website",
                "A website URL has not been configured yet."));

        popup.add(help);
        popup.add(assets);
        popup.add(updates);
        popup.add(website);

        popup.show(source, 0, source.getHeight());
    }

    private void showInfo(String title, String message) {
        JOptionPane.showMessageDialog(
                this,
                message,
                title,
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    /**
     * Initializes JOGL/GlueGen native libraries exactly once.
     *
     * This method is synchronized independently of the EDT. If two parts of
     * the program request initialization at the same time, only one thread
     * can perform the native loads.
     */
    public static void initializeJOGL() {
        /*
         * These properties must be set before AWT creates any heavyweight
         * Windows peers. They improve compatibility with the Windows AWT/WGL
         * path used by GLCanvas, especially on newer JDKs.
         */
        System.setProperty("sun.java2d.noddraw", "true");
        System.setProperty("sun.java2d.d3d", "false");
        System.setProperty("sun.java2d.opengl", "false");

        /*
         * ThirdPersonGameNew uses the fixed-function GL2 API. Keep JOGL from
         * preferring a core profile, but do not force AWT/GLCanvas to choose a
         * particular Windows GraphicsConfiguration. The game now uses GLJPanel,
         * which avoids the failing GLCanvas.addNotify()/WGL configuration path.
         */
        System.setProperty("jogl.disable.openglcore", "true");

        /*
         * These diagnostics are useful while the launcher is being tested. They
         * do not select a graphics adapter or suppress a real initialization
         * failure.
         */
        System.setProperty("jogl.debug.NativeLibrary", "true");
        System.setProperty("jogl.debug.GLProfile", "true");

        if (!AUTO_LOAD_NATIVE_LIBRARIES || nativeLibrariesInitialized) {
            return;
        }

        synchronized (NATIVE_LOCK) {
            if (nativeLibrariesInitialized) {
                return;
            }

            /*
             * Do not rely on a single project-local folder name. JogAmp's
             * standard extracted distribution normally places Windows DLLs
             * under:
             *
             *     natives/windows-amd64/
             *
             * while many IDE projects use:
             *
             *     natives/
             *     lib/natives/
             *     lib/windows-amd64/
             *
             * The resolver below checks all of these forms.
             */
            File nativeDirectory = findNativeDirectory();

            if (nativeDirectory != null) {
                System.out.println(
                        "WorldsGL: JOGL native directory: "
                                + nativeDirectory.getAbsolutePath()
                );
            } else {
                System.out.println(
                        "WorldsGL: No project-local JOGL native directory found; "
                                + "using java.library.path / JOGL's native-JAR loader."
                );
            }

            /*
             * IMPORTANT:
             *
             * GDIUtil is implemented by NativeWindow's Windows native layer.
             * The previous bootstrap loaded nativewindow_awt.dll but skipped
             * nativewindow_win32.dll. That can leave the Java GDIUtil class
             * present while the JNI implementation it expects is missing,
             * producing:
             *
             *   UnsatisfiedLinkError:
             *   jogamp.nativewindow.windows.GDIUtil.initIDs()
             *
             * The Windows NativeWindow DLL must therefore be loaded before
             * nativewindow_awt.dll.
             *
             * These libraries MUST all come from the same JogAmp release and
             * the same architecture.
             */
            loadNativeLibrary(nativeDirectory, "gluegen-rt");
            loadNativeLibrary(nativeDirectory, "nativewindow_win32");
            loadNativeLibrary(nativeDirectory, "nativewindow_awt");
            loadNativeLibrary(nativeDirectory, "jogl_desktop");

            /*
             * Force JOGL's singleton/native profile initialization now, while
             * the native dependencies are known to be loaded. This makes a
             * broken JOGL installation fail here instead of later when an
             * OpenGL drawable is created.
             */
            try {
                com.jogamp.opengl.GLProfile.initSingleton();
            } catch (Throwable profileError) {
                throw new IllegalStateException(
                        "JOGL native libraries loaded, but JOGL could not "
                                + "initialize its graphics profiles. "
                                + "If you are using Java 17 or newer on Windows, "
                                + "add the required --add-opens / --add-exports options shown "
                                + "by WorldsGL's startup diagnostic.\n\n"
                                + profileError.getMessage(),
                        profileError
                );
            }

            nativeLibrariesInitialized = true;

            System.out.println(
                    "WorldsGL: JOGL/GlueGen/NativeWindow native initialization "
                            + "completed successfully."
            );
        }
    }

    /**
     * Loads one native library by absolute path when available, then falls
     * back to System.loadLibrary().
     *
     * The absolute-path attempt is intentional: it prevents an unrelated
     * DLL with the same name elsewhere on PATH/java.library.path from being
     * selected before the project's matching JogAmp binaries.
     */
    private static void loadNativeLibrary(
            File nativeDirectory,
            String libraryName) {

        String mappedName = System.mapLibraryName(libraryName);
        File libraryFile = nativeDirectory == null
                ? null
                : new File(nativeDirectory, mappedName);

        if (libraryFile != null && libraryFile.isFile()) {
            try {
                System.out.println(
                        "WorldsGL: Loading " + libraryName
                                + " from " + libraryFile.getAbsolutePath()
                );

                System.load(libraryFile.getAbsolutePath());

                System.out.println(
                        "WorldsGL: Successfully loaded " + mappedName
                );
                return;

            } catch (UnsatisfiedLinkError error) {
                throw new UnsatisfiedLinkError(
                        "WorldsGL found the JOGL native library but Windows "
                                + "could not load it:\n\n"
                                + libraryFile.getAbsolutePath()
                                + "\n\nOriginal error:\n"
                                + error.getMessage()
                                + "\n\n"
                                + "Verify that gluegen-rt.dll, "
                                + "nativewindow_win32.dll, nativewindow_awt.dll "
                                + "and jogl_desktop.dll all come from the SAME "
                                + "JogAmp version and are all "
                                + architectureDescription() + "."
                );
            }
        }

        /*
         * No explicit DLL was found in the project directory. In this case
         * allow JOGL/GlueGen's normal java.library.path/native-JAR mechanism
         * to resolve it.
         */
        try {
            System.out.println(
                    "WorldsGL: Trying System.loadLibrary(\""
                            + libraryName + "\")"
            );

            System.loadLibrary(libraryName);

            System.out.println(
                    "WorldsGL: Successfully loaded " + libraryName
            );

        } catch (UnsatisfiedLinkError error) {
            throw new UnsatisfiedLinkError(
                    "Unable to load JOGL native libraries.'" + "'.\n\n" 
                            + "WorldsGL requires the .dll files in the natives folder to run. Please do NOT remove, or make any changes to this folder.\n"
            );
        }
    }

    private static String architectureDescription() {
        String arch = System.getProperty("os.arch", "unknown");
        String model = System.getProperty("sun.arch.data.model", "unknown");
        return "os.arch=" + arch + ", Java=" + model + "-bit";
    }

    /** Finds the project's Windows JOGL native library directory. */
    private static File findNativeDirectory() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dataModel = System.getProperty("sun.arch.data.model", "");

        boolean is64Bit =
                "64".equals(dataModel)
                || arch.contains("amd64")
                || arch.contains("x86_64")
                || arch.contains("aarch64");

        boolean is32Bit =
                "32".equals(dataModel)
                || arch.equals("x86")
                || arch.contains("i386")
                || arch.contains("i486")
                || arch.contains("i586")
                || arch.contains("i686");

        String platformFolder = is64Bit
                ? "windows-amd64"
                : (is32Bit ? "windows-i586" : null);

        File workingDirectory =
                new File(System.getProperty("user.dir"));

        /*
         * Standard JogAmp extracted layout first, followed by common
         * hand-installed layouts.
         */
        File[] candidates = platformFolder == null
                ? new File[] {
                    new File(workingDirectory, "natives"),
                    new File(workingDirectory, "native"),
                    new File(workingDirectory, "lib/natives"),
                    new File(workingDirectory, "lib")
                }
                : new File[] {
                    new File(workingDirectory, "natives/" + platformFolder),
                    new File(workingDirectory, "native/" + platformFolder),
                    new File(workingDirectory, "lib/natives/" + platformFolder),
                    new File(workingDirectory, "lib/" + platformFolder),

                    new File(workingDirectory, "natives"),
                    new File(workingDirectory, "native"),
                    new File(workingDirectory, "lib/natives"),
                    new File(workingDirectory, "lib")
                };

        for (File candidate : candidates) {
            if (candidate.isDirectory()) {
                /*
                 * Only accept a directory as a native directory when it
                 * actually contains at least one expected JOGL DLL. This
                 * prevents an unrelated "natives" folder from winning.
                 */
                if (new File(candidate, "gluegen-rt.dll").isFile()
                        || new File(candidate, "nativewindow_win32.dll").isFile()
                        || new File(candidate, "jogl_desktop.dll").isFile()) {
                    return candidate;
                }
            }
        }

        return null;
    }

    /**
     * Main entry point.
     *
     * Native libraries are loaded before the Swing GUI is created.
     */
    public static void main(String[] args) {
        executeIconGen();

        try {
            initializeJOGL();
        } catch (Throwable error) {
            LOGGER.log(
                    Level.SEVERE,
                    "WorldsGL could not initialize its native OpenGL libraries.",
                    error
            );

            String diagnostic = (error.getMessage() == null
                    ? error.toString()
                    : error.getMessage())
                    + "\n\nIf this computer is using Java 17 or newer, add these VM options "
                    + "to the NetBeans project:\n\n"
                    + "--add-opens=java.base/java.lang=ALL-UNNAMED\n"
                    + "--add-opens=java.desktop/sun.awt=ALL-UNNAMED\n"
                    + "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED\n\n"
                    + "The matching --add-exports options are also safe to include:\n"
                    + "--add-exports=java.base/java.lang=ALL-UNNAMED\n"
                    + "--add-exports=java.desktop/sun.awt=ALL-UNNAMED\n"
                    + "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED";

            JOptionPane.showMessageDialog(
                    null,
                    diagnostic,
                    "WorldsGL - OpenGL Initialization Error",
                    JOptionPane.ERROR_MESSAGE
            );

            return;
        }

        try {
            for (UIManager.LookAndFeelInfo info
                    : UIManager.getInstalledLookAndFeels()) {

                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception error) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not initialize Nimbus look and feel.",
                    error
            );
        }

        SwingUtilities.invokeLater(() -> {
            WorldsGLMain frame = new WorldsGLMain();
            frame.setVisible(true);
        });
    }


    /**
     * Executes icongen.py from the application's working directory at startup.
     * The script is launched asynchronously so it does not block application startup.
     */
    private static void executeIconGen() {
        File script = new File(System.getProperty("user.dir"), "icongen.py");

        if (!script.isFile()) {
            System.out.println(
                    "WorldsGL: icongen.py was not found at "
                            + script.getAbsolutePath()
            );
            return;
        }

        Thread iconGenThread = new Thread(() -> {
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(
                        "python",
                        script.getAbsolutePath()
                );
                processBuilder.directory(script.getParentFile());
                processBuilder.inheritIO();

                Process process = processBuilder.start();
                int exitCode = process.waitFor();

                System.out.println(
                        "WorldsGL: icongen.py finished with exit code "
                                + exitCode
                );
            } catch (Exception error) {
                LOGGER.log(
                        Level.WARNING,
                        "WorldsGL could not execute icongen.py.",
                        error
                );
            }
        }, "WorldsGL-IconGen");

        iconGenThread.setDaemon(true);
        iconGenThread.start();
    }

    /** JPanel that displays the WorldsGL image. */
    private static class ImagePanel extends JPanel {

        private Image backgroundImage;

        ImagePanel(String resourcePath) {
            setOpaque(true);

            try {
                backgroundImage = ImageIO.read(
                        WorldsGLMain.class.getResource(resourcePath)
                );

                if (backgroundImage == null) {
                    throw new IllegalArgumentException(
                            "Resource was not found: " + resourcePath
                    );
                }

            } catch (Exception error) {
                LOGGER.log(
                        Level.WARNING,
                        "Could not load image " + resourcePath,
                        error
                );
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            super.paintComponent(graphics);

            if (backgroundImage != null) {
                graphics.drawImage(
                        backgroundImage,
                        0,
                        0,
                        getWidth(),
                        getHeight(),
                        this
                );
            }
        }
    }
}
