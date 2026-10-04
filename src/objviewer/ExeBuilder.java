package objviewer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * ExeBuilder
 *
 * A small help window for the executable-builder tool.
 * Displays pythonwixtools.png, numbered help points, and a button
 * that launches exe_builder.py from the main application directory.
 */
public class ExeBuilder extends JFrame {

    private final JLabel imageLabel;
    private final JButton buildButton;

    public ExeBuilder() {
        setTitle("EXE Builder");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setResizable(false);

        JPanel mainPanel = new JPanel(new BorderLayout(12, 12));
        mainPanel.setBorder(new EmptyBorder(14, 14, 14, 14));

        JLabel header = new JLabel("EXE Builder");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 20f));
        header.setHorizontalAlignment(SwingConstants.CENTER);

        imageLabel = new JLabel();
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setVerticalAlignment(SwingConstants.CENTER);
        imageLabel.setPreferredSize(new Dimension(420, 150));
        loadHelpImage();

        JPanel helpPanel = new JPanel();
        helpPanel.setLayout(new BoxLayout(helpPanel, BoxLayout.Y_AXIS));
        helpPanel.setBorder(BorderFactory.createTitledBorder("General information:"));

        String[] helpPoints = {
            "WELCOME to the WorldsGL .exe builder portal",
            "After you are finished working on your game project, you are directed here to convert your .jar files into .exe for the final application.",
            "Make sure Python is installed and available from the system PATH.",
            "Wix Toolset (specifically its deprecated v3.14x for light.exe and candle.exe) is REQUIRED for the .exe conversion",
            "BUT, it does not have to be added to your system %PATH%, our tool does it on its own.",
            "Keep the generated EXE together with any required runtime files and folders."
        };

        for (int i = 0; i < helpPoints.length; i++) {
            JLabel point = new JLabel((i + 1) + ". " + helpPoints[i]);
            point.setAlignmentX(Component.LEFT_ALIGNMENT);
            point.setBorder(new EmptyBorder(4, 6, 4, 6));
            helpPanel.add(point);
        }

        buildButton = new JButton("Build EXE");
        buildButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        buildButton.setPreferredSize(new Dimension(160, 34));
        buildButton.addActionListener(e -> launchExeBuilder());

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottomPanel.add(buildButton);

        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.add(imageLabel, BorderLayout.NORTH);
        centerPanel.add(helpPanel, BorderLayout.CENTER);

        mainPanel.add(header, BorderLayout.NORTH);
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(null);
    }

    private void loadHelpImage() {
        File imageFile = new File(getApplicationDirectory(), "pythonwixtools.png");

        if (!imageFile.isFile()) {
            imageLabel.setText("pythonwixtools.png not found");
            return;
        }

        try {
            BufferedImage original = ImageIO.read(imageFile);

            if (original == null) {
                imageLabel.setText("Unable to read pythonwixtools.png");
                return;
            }

            int maxWidth = 420;
            int maxHeight = 150;

            double scale = Math.min(
                (double) maxWidth / original.getWidth(),
                (double) maxHeight / original.getHeight()
            );

            // Never enlarge a small image.
            scale = Math.min(scale, 1.0);

            int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(original.getHeight() * scale));

            Image scaled = original.getScaledInstance(
                width,
                height,
                Image.SCALE_SMOOTH
            );

            imageLabel.setIcon(new ImageIcon(scaled));
            imageLabel.setText("");
        } catch (IOException ex) {
            imageLabel.setText("Unable to load pythonwixtools.png");
        }
    }

    private File getApplicationDirectory() {
        try {
            File location = new File(
                ExeBuilder.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
            );

            // When running from a JAR, use its containing directory.
            if (location.isFile()) {
                return location.getParentFile();
            }

            // When running from NetBeans/classes, walk up to the working
            // application directory where the main Python tool is expected.
            File current = location;
            for (int i = 0; i < 5 && current != null; i++) {
                File candidate = new File(current, "exe_builder.py");
                if (candidate.isFile()) {
                    return current;
                }
                current = current.getParentFile();
            }

            return new File(System.getProperty("user.dir"));
        } catch (Exception ex) {
            return new File(System.getProperty("user.dir"));
        }
    }

    private void launchExeBuilder() {
        File appDirectory = getApplicationDirectory();
        File script = new File(appDirectory, "exe_builder.py");

        if (!script.isFile()) {
            JOptionPane.showMessageDialog(
                this,
                "Could not find exe_builder.py in:\n\n"
                    + appDirectory.getAbsolutePath(),
                "EXE Builder",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        try {
            String pythonCommand = findPythonCommand();

            ProcessBuilder processBuilder = new ProcessBuilder(
                pythonCommand,
                script.getAbsolutePath()
            );

            processBuilder.directory(appDirectory);
            processBuilder.redirectErrorStream(true);
            processBuilder.inheritIO();
            processBuilder.start();

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                this,
                "Could not start exe_builder.py.\n\n"
                    + "Make sure Python is installed and available on PATH.\n\n"
                    + "Details:\n" + ex.getMessage(),
                "EXE Builder",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private String findPythonCommand() throws IOException {
        String[] commands = {
            "python",
            "python3",
            "py"
        };

        for (String command : commands) {
            try {
                Process test = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();

                int result = test.waitFor();

                if (result == 0) {
                    return command;
                }
            } catch (IOException ex) {
                // Try the next Python command.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Python detection was interrupted.", ex);
            }
        }

        throw new IOException(
            "Python could not be found. Tried: "
                + Arrays.toString(commands)
        );
    }

    /**
     * Convenience method for opening the window from another class.
     */
    public static void showWindow() {
        SwingUtilities.invokeLater(() -> {
            ExeBuilder window = new ExeBuilder();
            window.setVisible(true);
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ExeBuilder window = new ExeBuilder();
            window.setVisible(true);
        });
    }
}
