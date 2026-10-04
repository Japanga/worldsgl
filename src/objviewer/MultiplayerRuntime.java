package objviewer;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.awt.TextRenderer;
import com.jogamp.opengl.glu.GLU;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.io.*;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;

/**
 * Optional multiplayer module.  This class is packaged only into the
 * multiplayer-enabled JAR.  The normal WorldsGL exporter deliberately omits
 * it, so ordinary exported games have no multiplayer UI or networking code.
 */
public final class MultiplayerRuntime {
    private static final Map<String, RemotePlayer> REMOTE = new ConcurrentHashMap<>();
    private static final java.util.List<String> CHAT = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static Socket socket;
    private static BufferedReader reader;
    private static BufferedWriter writer;
    private static WebSocket webSocket;
    private static Thread networkThread;
    private static volatile boolean running;
    private static volatile boolean usingWebSocket = false;
    private static volatile String username = "Player";
    private static volatile String host = "127.0.0.1";
    private static volatile int port = 8765;
    private static volatile String serverUrl = "";
    private static volatile String lockedServerUrl = "";
    private static volatile CountDownLatch welcomeLatch;
    private static volatile String connectionFailure = "";
    private static volatile Color playerCubeColor = new Color(70, 200, 255);

    // Load the endpoint embedded into the exported client JAR by server.py.
    // This must happen before the login UI is created so the actual connection
    // and the displayed CONNECTED TO address use the same public endpoint.
    static {
        lockedServerUrl = loadLockedServerEndpoint();
    }

    // In-game chat state. Chat is rendered directly by the OpenGL game window.
    private static volatile boolean chatOpen = false;
    private static volatile boolean kicked = false;
    private static String kickMessage = "You have been kicked by the admin/host";
    private static final StringBuilder chatDraft = new StringBuilder();
    private static final Map<String, ChatBubble> CHAT_BUBBLES = new ConcurrentHashMap<>();
    private static boolean locallyMuted = false;
    private static TextRenderer textRenderer;
    private static GL2 currentGL;

    private static final class ChatBubble {
        final String message;
        final long createdNanos;
        ChatBubble(String message) {
            this.message = message;
            this.createdNanos = System.nanoTime();
        }
    }
    private static final GLU glu = new GLU();
    private static long lastStateNanos;

    private static final class RemotePlayer {
        volatile float x, y, z, rot;
        volatile float r, g, b;
        RemotePlayer(float x, float y, float z, float rot) {
            this(x, y, z, rot, 70f / 255f, 200f / 255f, 1f);
        }
        RemotePlayer(float x, float y, float z, float rot, float r, float g, float b) {
            this.x = x; this.y = y; this.z = z; this.rot = rot;
            this.r = clamp01(r); this.g = clamp01(g); this.b = clamp01(b);
        }
    }

    private MultiplayerRuntime() {}

    /**
     * Shows the intentionally minimal multiplayer login screen and does not
     * return until the user connects or cancels.  The actual game window is
     * kept hidden by ThirdPersonGameNew while this screen is open.
     */
    public static boolean initialize(Object game) {
        if (running) return true;

        // server.py launches the selected exported JAR in this mode. The host
        // JAR connects directly to the relay without showing the normal login screen.
        if (Boolean.parseBoolean(System.getProperty("worldsgl.serverHost", "false"))) {
            String requestedName = System.getProperty("worldsgl.username", "ServerHost").trim();
            String requestedServer = System.getProperty("worldsgl.server", "ws://127.0.0.1:8756/game").trim();
            if (requestedName.isEmpty()) requestedName = "ServerHost";
            username = requestedName.substring(0, Math.min(24, requestedName.length()));
            if (!parseServer(requestedServer)) return false;
            try {
                openConnectionAndWaitForWelcome();
                return true;
            } catch (Exception ex) {
                disconnectMultiplayer();
                return false;
            }
        }

        // If server.py patched this exported JAR, force the login to use the
        // embedded public WebSocket endpoint instead of the localhost fallback.
        if (!lockedServerUrl.isEmpty()) {
            parseServer(lockedServerUrl);
        }

        final MultiplayerLogin login = new MultiplayerLogin();
        login.setVisible(true); // modal dialog: this blocks until connect/cancel

        if (!login.connected) {
            disconnectMultiplayer();
            return false;
        }

        return true;
    }

    private static final class MultiplayerLogin extends JDialog {
        private final JTextField usernameField = new JTextField("Player", 22);
        private final JTextField serverField = new JTextField(lockedServerUrl.isEmpty() ? "ws://127.0.0.1:8756/game" : lockedServerUrl, 22);
        private final JLabel status = new JLabel(" ");
        private final JButton colorButton = new JButton("Choose Cube Color");
        private final JPanel colorPreview = new JPanel();
        private final JLabel colorValue = new JLabel();
        private final JButton connectButton = new JButton("Connect");
        private volatile boolean connected = false;
        private volatile boolean connecting = false;
        private volatile boolean connectionConfirmed = false;

        MultiplayerLogin() {
            super((java.awt.Frame) null, "WorldsGL Multiplayer", true);
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            setResizable(false);

            JPanel root = new JPanel(new GridBagLayout());
            root.setBackground(Color.BLACK);
            // Keep the login dialog compact, but never let the optional cube-color
            // controls compete with the two primary connection fields for width.
            root.setBorder(BorderFactory.createEmptyBorder(28, 32, 24, 32));

            JLabel title = new JLabel("WORLDSGL MULTIPLAYER");
            title.setForeground(Color.WHITE);
            title.setFont(new Font("SansSerif", Font.BOLD, 22));

            JLabel usernameLabel = label("Username");
            JLabel serverLabel = label("Server IP / Address");

            styleField(usernameField);
            styleField(serverField);

            colorPreview.setPreferredSize(new Dimension(42, 26));
            colorPreview.setBorder(BorderFactory.createLineBorder(new Color(130, 130, 130)));
            updateColorPreview();
            colorButton.setFocusable(false);
            colorButton.setForeground(Color.WHITE);
            colorButton.setBackground(new Color(35, 35, 35));
            colorButton.setBorder(BorderFactory.createLineBorder(new Color(90, 90, 90)));
            colorButton.setPreferredSize(new Dimension(175, 30));
            colorButton.addActionListener(e -> chooseCubeColor());
            colorValue.setForeground(new Color(185, 185, 185));
            colorValue.setFont(new Font("SansSerif", Font.PLAIN, 12));
            if (!lockedServerUrl.isEmpty()) {
                serverField.setText(lockedServerUrl);
                serverField.setEditable(false);
                serverField.setToolTipText("This client JAR is locked to the server endpoint supplied by the host.");
            }

            connectButton.setFocusable(false);
            connectButton.setForeground(Color.WHITE);
            connectButton.setBackground(new Color(35, 35, 35));
            connectButton.setBorder(BorderFactory.createLineBorder(new Color(90, 90, 90)));
            connectButton.setPreferredSize(new Dimension(150, 34));
            connectButton.addActionListener(e -> connect());

            status.setForeground(new Color(180, 180, 180));
            status.setFont(new Font("SansSerif", Font.BOLD, 14));
            status.setHorizontalAlignment(SwingConstants.CENTER);

            // The connection fields get their own full-width column.  Cube Color
            // is deliberately placed underneath them so adding the selector can
            // never shrink the Username or Server IP / Address fields.
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(6, 6, 6, 6);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
            c.anchor = GridBagConstraints.CENTER;
            root.add(title, c);

            c.gridy++;
            c.gridwidth = 1;
            c.weightx = 0.0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.EAST;
            root.add(usernameLabel, c);

            c.gridx = 1;
            c.weightx = 1.0;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            root.add(usernameField, c);

            c.gridx = 0; c.gridy++;
            c.weightx = 0.0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.EAST;
            root.add(serverLabel, c);

            c.gridx = 1;
            c.weightx = 1.0;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            root.add(serverField, c);

            JPanel colorPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
            colorPanel.setOpaque(false);
            colorPanel.add(label("Cube Color"));
            colorPanel.add(colorPreview);
            colorPanel.add(colorButton);
            colorPanel.add(colorValue);

            c.gridx = 0; c.gridy++; c.gridwidth = 2;
            c.weightx = 1.0;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.CENTER;
            root.add(colorPanel, c);

            c.gridy++;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.CENTER;
            root.add(connectButton, c);

            c.gridy++;
            c.fill = GridBagConstraints.HORIZONTAL;
            root.add(status, c);

            setContentPane(root);
            getRootPane().setDefaultButton(connectButton);
            setSize(720, 390);
            setMinimumSize(new Dimension(720, 390));
            setLocationRelativeTo(null);
        }

        private JLabel label(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(Color.WHITE);
            l.setFont(new Font("SansSerif", Font.PLAIN, 14));
            return l;
        }

        private void styleField(JTextField field) {
            field.setForeground(Color.WHITE);
            field.setBackground(new Color(12, 12, 12));
            field.setCaretColor(Color.WHITE);
            field.setBorder(BorderFactory.createLineBorder(new Color(75, 75, 75)));
            field.setPreferredSize(new Dimension(430, 32));
            field.setMinimumSize(new Dimension(430, 32));
            field.setFont(new Font("SansSerif", Font.PLAIN, 14));
        }

        private void updateColorPreview() {
            Color c = playerCubeColor;
            colorPreview.setBackground(c);
            colorValue.setText(String.format("RGB %d, %d, %d", c.getRed(), c.getGreen(), c.getBlue()));
        }

        private void chooseCubeColor() {
            Color chosen = JColorChooser.showDialog(this, "Choose Your Player Cube Color", playerCubeColor);
            if (chosen != null) {
                playerCubeColor = chosen;
                updateColorPreview();
            }
        }

        private void connect() {
            if (connecting) return;

            String requestedName = usernameField.getText().trim();
            String requestedServer = serverField.getText().trim();
            if (requestedName.isEmpty()) {
                status.setText("Enter a username.");
                return;
            }
            if (requestedServer.isEmpty()) {
                status.setText("Enter a server IP address.");
                return;
            }

            username = requestedName.substring(0, Math.min(24, requestedName.length()));
            if (!parseServer(requestedServer)) {
                status.setText("Enter a ws:// or wss:// server URL.");
                return;
            }

            connecting = true;
            connectButton.setEnabled(false);
            status.setText("Connecting to " + displayEndpoint() + "...");

            Thread connector = new Thread(() -> {
                try {
                    openConnectionAndWaitForWelcome();
                    connected = true;
                    connectionConfirmed = true;
                    SwingUtilities.invokeLater(() -> {
                        status.setForeground(new Color(100, 255, 140));
                        status.setText("CONNECTED TO " + displayEndpoint());
                        connectButton.setText("Connected");
                    });
                    // Keep the black connection screen visible briefly so the
                    // player can actually see that the server accepted the connection.
                    try { Thread.sleep(900L); } catch (InterruptedException ignored) { }
                    SwingUtilities.invokeLater(this::dispose);
                } catch (Exception ex) {
                    disconnectMultiplayer();
                    SwingUtilities.invokeLater(() -> {
                        connecting = false;
                        connectionConfirmed = false;
                        connectButton.setEnabled(true);
                        status.setForeground(new Color(255, 95, 95));
                        status.setText("CONNECTION FAILED: " + shortError(ex));
                    });
                }
            }, "WorldsGL-Multiplayer-Connect");
            connector.setDaemon(true);
            connector.start();
        }

        private String shortError(Exception ex) {
            String m = ex.getMessage();
            return (m == null || m.trim().isEmpty()) ? ex.getClass().getSimpleName() : m;
        }
    }

    private static String loadLockedServerEndpoint() {
        try (InputStream in = MultiplayerRuntime.class.getResourceAsStream("/worldsgl_client_endpoint.txt")) {
            if (in == null) return "";
            String value = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String displayEndpoint() {
        if (serverUrl != null && !serverUrl.trim().isEmpty()) return serverUrl;
        if (usingWebSocket) {
            return (port == 443 ? "wss://" : "ws://") + host + ":" + port + "/game";
        }
        return host + ":" + port;
    }

    private static boolean parseServer(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return false;

        if (text.startsWith("ws://") || text.startsWith("wss://")) {
            try {
                URI uri = URI.create(text);
                if (uri.getHost() == null || uri.getHost().trim().isEmpty()) return false;
                if (!"/game".equals(uri.getPath()) && (uri.getPath() == null || uri.getPath().isEmpty())) {
                    text = text.endsWith("/") ? text + "game" : text + "/game";
                    uri = URI.create(text);
                }
                host = uri.getHost();
                port = uri.getPort() > 0 ? uri.getPort() : ("wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
                serverUrl = uri.toString();
                usingWebSocket = true;
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        // Direct TCP compatibility for local/manual development.
        int parsedPort = 8765;
        String parsedHost = text;
        if (text.startsWith("[") && text.contains("]")) {
            int close = text.indexOf(']');
            parsedHost = text.substring(1, close);
            if (text.length() > close + 1 && text.charAt(close + 1) == ':') {
                try { parsedPort = Integer.parseInt(text.substring(close + 2)); }
                catch (NumberFormatException ex) { return false; }
            }
        } else {
            int colon = text.lastIndexOf(':');
            if (colon > 0 && text.indexOf(':') == colon) {
                String portText = text.substring(colon + 1).trim();
                if (!portText.isEmpty()) {
                    try { parsedPort = Integer.parseInt(portText); }
                    catch (NumberFormatException ex) { return false; }
                    parsedHost = text.substring(0, colon).trim();
                }
            }
        }
        if (parsedHost.isEmpty() || parsedPort < 1 || parsedPort > 65535) return false;
        host = parsedHost;
        port = parsedPort;
        serverUrl = "";
        usingWebSocket = false;
        return true;
    }

    private static void openConnectionAndWaitForWelcome() throws Exception {
        if (usingWebSocket) {
            connectionFailure = "";
            welcomeLatch = new CountDownLatch(1);
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();

            WebSocket.Listener listener = new WebSocket.Listener() {
                private final StringBuilder partial = new StringBuilder();

                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                        String message = partial.toString();
                        partial.setLength(0);
                        for (String line : message.replace("\r", "").split("\n")) {
                            if (!line.isEmpty()) process(line);
                        }
                    }
                    ws.request(1);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    if (running) {
                        running = false;
                        CHAT.add("[Server] Connection closed: " + reason);
            
                    }
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    connectionFailure = error == null ? "WebSocket error" : String.valueOf(error.getMessage());
                    CountDownLatch latch = welcomeLatch;
                    if (latch != null) latch.countDown();
                    if (running) {
                        running = false;
                        CHAT.add("[Server] Connection lost.");
            
                    }
                }
            };

            webSocket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .buildAsync(URI.create(serverUrl), listener)
                    .join();

            webSocket.request(1);
            webSocket.sendText("JOIN|" + safe(username), true).join();

            CountDownLatch latch = welcomeLatch;
            if (latch != null && !latch.await(8, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for WELCOME from server.");
            }
            if (!connectionFailure.isEmpty()) throw new IOException(connectionFailure);
            if (!running) throw new IOException("Server rejected or closed the WebSocket connection.");
            sendPlayerColor();
            return;
        }

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
        send("JOIN|" + safe(username));

        String first = reader.readLine();
        if (first == null) throw new IOException("Server closed the connection.");
        process(first);
        if (!first.startsWith("WELCOME|")) {
            throw new IOException("Server rejected the connection.");
        }

        running = true;
        sendPlayerColor();
        networkThread = new Thread(MultiplayerRuntime::readLoop, "WorldsGL-Multiplayer");
        networkThread.setDaemon(true);
        networkThread.start();
    }

    private static void readLoop() {
        try {
            String line;
            while (running && reader != null && (line = reader.readLine()) != null) {
                process(line);
            }
        } catch (IOException ignored) {
            if (running) CHAT.add("[Server] Connection lost.");
        } finally {
            if (running) {
                running = false;
    
            }
        }
    }

    private static String safe(String s) {
        return (s == null ? "" : s)
                .replace("|", "_")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float parseColorByte(String value) {
        try {
            return clamp01(Integer.parseInt(value.trim()) / 255f);
        } catch (Exception ignored) {
            return 1f;
        }
    }

    /** Returns the local player's selected multiplayer cube color. */
    public static Color getPlayerCubeColor() {
        return playerCubeColor;
    }

    private static void sendPlayerColor() {
        Color c = playerCubeColor;
        send("COLOR|" + safe(username) + "|" + c.getRed() + "|" + c.getGreen() + "|" + c.getBlue());
    }

    private static synchronized void send(String line) {
        try {
            if (usingWebSocket && webSocket != null) {
                webSocket.sendText(line, true);
                return;
            }
            if (writer != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (Exception ignored) { }
    }

    private static void process(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length == 0) return;
        try {
            if ("WELCOME".equals(p[0]) && p.length >= 2) {
                username = p[1];
                running = true;
                CountDownLatch latch = welcomeLatch;
                if (latch != null) latch.countDown();
                CHAT.add("[Server] Connected as " + username);
            } else if ("PLAYER".equals(p[0]) && p.length >= 6) {
                float rr = p.length >= 9 ? parseColorByte(p[6]) : 70f / 255f;
                float gg = p.length >= 9 ? parseColorByte(p[7]) : 200f / 255f;
                float bb = p.length >= 9 ? parseColorByte(p[8]) : 1f;
                REMOTE.put(p[1], new RemotePlayer(
                        Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                        Float.parseFloat(p[4]), Float.parseFloat(p[5]), rr, gg, bb));
            } else if ("JOIN".equals(p[0]) && p.length >= 2) {
                CHAT.add("[Server] " + p[1] + " joined the game.");
            } else if ("LEFT".equals(p[0]) && p.length >= 2) {
                REMOTE.remove(p[1]);
                CHAT.add("[Server] " + p[1] + " left the game.");
            } else if ("STATE".equals(p[0]) && p.length >= 6) {
                REMOTE.compute(p[1], (k, old) -> {
                    RemotePlayer r = old == null ? new RemotePlayer(0, 0, 0, 0) : old;
                    r.x = Float.parseFloat(p[2]);
                    r.y = Float.parseFloat(p[3]);
                    r.z = Float.parseFloat(p[4]);
                    r.rot = Float.parseFloat(p[5]);
                    return r;
                });
            } else if ("COLOR".equals(p[0]) && p.length >= 5) {
                REMOTE.compute(p[1], (k, old) -> {
                    RemotePlayer r = old == null ? new RemotePlayer(0, 0, 0, 0) : old;
                    r.r = parseColorByte(p[2]);
                    r.g = parseColorByte(p[3]);
                    r.b = parseColorByte(p[4]);
                    return r;
                });
            } else if ("CHAT".equals(p[0]) && p.length >= 3) {
                boolean admin = p.length >= 4 && "1".equals(p[3]);
                appendChatMessage(p[1], p[2], admin);
            } else if ("MUTED".equals(p[0])) {
                locallyMuted = true;
                String message = p.length >= 2 && !p[1].trim().isEmpty()
                        ? p[1] : "You have been muted by the admin/host";
                appendSystemMessage("[MUTED] " + message, true);

            } else if ("MUTE_STATE".equals(p[0])) {
                locallyMuted = p.length >= 2 && "1".equals(p[1]);

                if (!locallyMuted) {
                    appendSystemMessage("[Server] You have been unmuted.", false);
                }
            } else if ("WARN".equals(p[0])) {
                String message = p.length >= 2 && !p[1].trim().isEmpty()
                        ? p[1] : "You have been warned by the admin/host";
                appendSystemMessage("[WARNING] " + message, true);
                // Administrative warnings must always be visible immediately.
                chatOpen = true;
            } else if ("KICK".equals(p[0])) {
                String message = p.length >= 2 && !p[1].trim().isEmpty()
                        ? p[1] : "You have been kicked by the admin/host";
                kickMessage = message;
                appendSystemMessage("[KICKED] " + message, true);
                kicked = true;
                chatOpen = false;
                chatDraft.setLength(0);
                running = false;
                try { if (webSocket != null) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "kicked"); } catch (Exception ignored) { }
                try { if (socket != null) socket.close(); } catch (IOException ignored) { }

            }
        } catch (Exception ignored) { }

    }

    public static void update(Object game) {
        if (!running) return;
        long now = System.nanoTime();
        if (now - lastStateNanos < 100_000_000L) return; // 10 updates/sec
        lastStateNanos = now;
        try {
            Field x = game.getClass().getDeclaredField("playerX");
            Field y = game.getClass().getDeclaredField("playerY");
            Field z = game.getClass().getDeclaredField("playerZ");
            Field r = game.getClass().getDeclaredField("playerRot");
            x.setAccessible(true); y.setAccessible(true); z.setAccessible(true); r.setAccessible(true);
            send("STATE|" + safe(username) + "|"
                    + x.getFloat(game) + "|"
                    + y.getFloat(game) + "|"
                    + z.getFloat(game) + "|"
                    + r.getFloat(game));
        } catch (Exception ignored) { }
    }

    /**
     * Draws lightweight remote-player markers and usernames.  The usernames
     * are deliberately projected into screen space rather than relying on
     * TextRenderer's 3D matrix path.  This makes the labels reliably visible
     * with WorldsGL's camera/projection state and keeps them positioned over
     * the remote player's head.
     */
    public static void render(Object game, GL2 gl) {
        if (!running && !kicked) return;
        currentGL = gl;
        try {
            if (textRenderer == null) {
                textRenderer = new TextRenderer(new Font("SansSerif", Font.BOLD, 20), true, true);
            }

            int[] currentViewport = new int[4];
            gl.glGetIntegerv(GL2.GL_VIEWPORT, currentViewport, 0);
            if (kicked) {
                drawKickScreen(gl, textRenderer, currentViewport);
                return;
            }

            gl.glPushAttrib(GL2.GL_ENABLE_BIT | GL2.GL_CURRENT_BIT | GL2.GL_TEXTURE_BIT);
            gl.glDisable(GL2.GL_TEXTURE_2D);

            for (Map.Entry<String, RemotePlayer> e : REMOTE.entrySet()) {
                RemotePlayer p = e.getValue();

                gl.glPushMatrix();
                gl.glTranslatef(p.x, p.y + 0.8f, p.z);
                gl.glRotatef(-p.rot, 0f, 1f, 0f);
                gl.glColor3f(p.r, p.g, p.b);
                gl.glBegin(GL2.GL_QUADS);
                cube(gl, 0.35f);
                gl.glEnd();
                gl.glPopMatrix();
            }

            // Capture the actual camera matrices BEFORE TextRenderer changes
            // the OpenGL projection to its 2D rendering mode.
            float[] model = new float[16];
            float[] projection = new float[16];
            int[] viewport = new int[4];
            gl.glGetFloatv(GL2.GL_MODELVIEW_MATRIX, model, 0);
            gl.glGetFloatv(GL2.GL_PROJECTION_MATRIX, projection, 0);
            gl.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);

            gl.glPopAttrib();

            // Convert player head positions to screen positions.  Labels are
            // rendered in a normal 2D overlay so they cannot disappear because
            // of depth testing, scene textures, or later engine matrix changes.
            textRenderer.beginRendering(viewport[2], viewport[3]);
            textRenderer.setColor(1f, 1f, 1f, 1f);

            // The local player is not in REMOTE because the server deliberately
            // excludes a client's own STATE packets.  Project the local player
            // directly from ThirdPersonGameNew so the local username is visible
            // above their own head as well.
            try {
                Field fx = game.getClass().getDeclaredField("playerX");
                Field fy = game.getClass().getDeclaredField("playerY");
                Field fz = game.getClass().getDeclaredField("playerZ");
                fx.setAccessible(true); fy.setAccessible(true); fz.setAccessible(true);
                float localX = fx.getFloat(game);
                float localY = fy.getFloat(game);
                float localZ = fz.getFloat(game);
                drawProjectedName(textRenderer, username, localX, localY + 1.75f, localZ, model, projection, viewport);
            } catch (Throwable ignored) { }

            for (Map.Entry<String, RemotePlayer> e : REMOTE.entrySet()) {
                RemotePlayer p = e.getValue();
                float[] win = new float[3];
                boolean projected = glu.gluProject(
                        p.x, p.y + 1.75f, p.z,
                        model, 0, projection, 0, viewport, 0,
                        win, 0);

                if (!projected) continue;

                // OpenGL's projected Y coordinate is bottom-up, while
                // TextRenderer's 2D coordinates are also based from the bottom.
                // Keep the label centered horizontally over the player.
                drawProjectedName(textRenderer, e.getKey(), p.x, p.y + 1.75f, p.z, model, projection, viewport);
                drawChatBubble(textRenderer, e.getKey(), p.x, p.y + 2.15f, p.z,
                        model, projection, viewport);
            }

            // The local player's speech bubble follows their own head.
            try {
                Field fx = game.getClass().getDeclaredField("playerX");
                Field fy = game.getClass().getDeclaredField("playerY");
                Field fz = game.getClass().getDeclaredField("playerZ");
                fx.setAccessible(true); fy.setAccessible(true); fz.setAccessible(true);
                drawChatBubble(textRenderer, username,
                        fx.getFloat(game), fy.getFloat(game) + 2.15f, fz.getFloat(game),
                        model, projection, viewport);
            } catch (Throwable ignored) { }

            if (chatOpen) drawChatPanel(gl, textRenderer, viewport);

            // Persistent connection confirmation in the actual game window.
            textRenderer.setColor(0.55f, 1f, 0.65f, 1f);
            textRenderer.draw("CONNECTED: " + displayEndpoint(), 14, viewport[3] - 28);
            textRenderer.endRendering();
        } catch (Throwable ignored) { }
    }

    private static void drawKickScreen(GL2 gl, TextRenderer renderer, int[] viewport) {
        // The game world is intentionally completely hidden after a kick.
        drawOverlayRect(gl, viewport, 0, 0, viewport[2], viewport[3], 0f, 0f, 0f, 1f);
        renderer.beginRendering(viewport[2], viewport[3]);
        renderer.setColor(1f, 0.12f, 0.12f, 1f);
        String title = "You have been kicked by the admin/host";
        java.awt.geom.Rectangle2D titleBounds = renderer.getBounds(title);
        int titleX = (int)((viewport[2] - titleBounds.getWidth()) / 2);
        int titleY = viewport[3] / 2 + 12;
        renderer.draw(title, titleX, titleY);
        renderer.setColor(0.72f, 0.72f, 0.72f, 1f);
        String detail = kickMessage == null || kickMessage.trim().isEmpty()
                ? "You have been kicked by the admin/host" : kickMessage;
        java.awt.geom.Rectangle2D detailBounds = renderer.getBounds(detail);
        renderer.draw(detail, (int)((viewport[2] - detailBounds.getWidth()) / 2), viewport[3] / 2 - 24);
        renderer.endRendering();
    }

    private static void drawProjectedName(TextRenderer renderer, String name, float x, float y, float z,
                                          float[] model, float[] projection, int[] viewport) {
        float[] win = new float[3];
        if (!glu.gluProject(x, y, z, model, 0, projection, 0, viewport, 0, win, 0)) return;
        if (win[2] < 0f || win[2] > 1f) return;
        int width = renderer.getBounds(name).getBounds().width;
        float drawX = win[0] - width * 0.5f;
        float drawY = win[1] + 4f;
        renderer.setColor(0f, 0f, 0f, 0.88f);
        renderer.draw(name, (int)(drawX + 2f), (int)(drawY - 1f));
        renderer.draw(name, (int)(drawX - 2f), (int)(drawY - 1f));
        renderer.draw(name, (int)(drawX + 2f), (int)(drawY + 1f));
        renderer.draw(name, (int)(drawX - 2f), (int)(drawY + 1f));
        renderer.setColor(1f, 1f, 1f, 1f);
        renderer.draw(name, (int)drawX, (int)drawY);
    }

    private static void cube(GL2 gl, float s) {
        float a = -s, b = s;
        gl.glVertex3f(a,a,a); gl.glVertex3f(b,a,a); gl.glVertex3f(b,b,a); gl.glVertex3f(a,b,a);
        gl.glVertex3f(a,a,b); gl.glVertex3f(a,b,b); gl.glVertex3f(b,b,b); gl.glVertex3f(b,a,b);
        gl.glVertex3f(a,a,a); gl.glVertex3f(a,a,b); gl.glVertex3f(b,a,b); gl.glVertex3f(b,a,a);
        gl.glVertex3f(a,b,a); gl.glVertex3f(b,b,a); gl.glVertex3f(b,b,b); gl.glVertex3f(a,b,b);
        gl.glVertex3f(a,a,a); gl.glVertex3f(a,b,a); gl.glVertex3f(a,b,b); gl.glVertex3f(a,a,b);
        gl.glVertex3f(b,a,a); gl.glVertex3f(b,a,b); gl.glVertex3f(b,b,b); gl.glVertex3f(b,b,a);
    }

    public static boolean handleKey(Object game, KeyEvent e) {
        if (!running || e == null) return false;

        if (e.getKeyCode() == KeyEvent.VK_C && e.isShiftDown()
                && !e.isControlDown() && !e.isAltDown()
                && e.getID() == KeyEvent.KEY_PRESSED && !chatOpen) {
            toggleChat();
            return true;
        }

        if (!chatOpen) return false;

        if (e.getID() == KeyEvent.KEY_PRESSED) {
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                chatOpen = false;
                return true;
            }
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                submitChat();
                return true;
            }
            if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                if (chatDraft.length() > 0) chatDraft.deleteCharAt(chatDraft.length() - 1);
                return true;
            }
            if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                chatDraft.setLength(0);
                return true;
            }

            char ch = printableKeyChar(e);
            if (ch != 0 && chatDraft.length() < 180) {
                chatDraft.append(ch);
            }
            return true;
        }

        // Some AWT/JOGL configurations deliver KEY_TYPED as well. Only use it
        // when a real printable character is supplied and the key wasn't already
        // consumed by the KEY_PRESSED path.
        if (e.getID() == KeyEvent.KEY_TYPED) {
            char ch = e.getKeyChar();
            if (ch >= 32 && ch != 127 && chatDraft.length() < 180) {
                chatDraft.append(ch);
            }
            return true;
        }
        return true;
    }

    private static char printableKeyChar(KeyEvent e) {
        int code = e.getKeyCode();
        boolean shift = e.isShiftDown();
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            char c = (char)('a' + (code - KeyEvent.VK_A));
            return shift ? Character.toUpperCase(c) : c;
        }
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
            final String normal = "0123456789";
            final String shifted = ")!@#$%^&*(";
            return (shift ? shifted : normal).charAt(code - KeyEvent.VK_0);
        }
        switch (code) {
            case KeyEvent.VK_SPACE: return ' ';
            case KeyEvent.VK_PERIOD: return shift ? '>' : '.';
            case KeyEvent.VK_COMMA: return shift ? '<' : ',';
            case KeyEvent.VK_SLASH: return shift ? '?' : '/';
            case KeyEvent.VK_SEMICOLON: return shift ? ':' : ';';
            case KeyEvent.VK_QUOTE: return shift ? '"' : '\'';
            case KeyEvent.VK_OPEN_BRACKET: return shift ? '{' : '[';
            case KeyEvent.VK_CLOSE_BRACKET: return shift ? '}' : ']';
            case KeyEvent.VK_BACK_SLASH: return shift ? '|' : '\\';
            case KeyEvent.VK_MINUS: return shift ? '_' : '-';
            case KeyEvent.VK_EQUALS: return shift ? '+' : '=';
            case KeyEvent.VK_BACK_QUOTE: return shift ? '~' : '`';
            default: return 0;
        }
    }

    private static void toggleChat() {
        chatOpen = !chatOpen;
        if (chatOpen) {
            chatDraft.setLength(0);
        } else {
        }
    }

    private static void submitChat() {
        if (!running || locallyMuted) return;
        String msg = chatDraft.toString().trim();
        if (msg.isEmpty()) {
            chatOpen = false;
            return;
        }
        send("CHAT|" + safe(username) + "|" + safe(msg));
        CHAT_BUBBLES.put(username, new ChatBubble(msg));
        chatDraft.setLength(0);
        chatOpen = false;
    }

    private static void drawChatBubble(TextRenderer renderer, String name, float x, float y, float z,
                                       float[] model, float[] projection, int[] viewport) {
        ChatBubble bubble = CHAT_BUBBLES.get(name);
        if (bubble == null) return;
        if (System.nanoTime() - bubble.createdNanos > 8_000_000_000L) {
            CHAT_BUBBLES.remove(name, bubble);
            return;
        }

        float[] win = new float[3];
        if (!glu.gluProject(x, y, z, model, 0, projection, 0, viewport, 0, win, 0)) return;
        if (win[2] < 0f || win[2] > 1f) return;

        String message = bubble.message;
        if (message.length() > 90) message = message.substring(0, 90) + "...";
        java.awt.geom.Rectangle2D bounds = renderer.getBounds(message);
        int textW = Math.max(24, (int)Math.ceil(bounds.getWidth()));
        int textH = Math.max(18, (int)Math.ceil(bounds.getHeight()));
        int bubbleW = textW + 20;
        int bubbleH = textH + 12;
        int left = (int)win[0] - bubbleW / 2;
        int bottom = (int)win[1] + 2;

        drawOverlayRect(currentGL, viewport, left, bottom, bubbleW, bubbleH, 0f, 0f, 0f, 0.78f);
        drawOverlayRect(currentGL, viewport, (int)win[0] - 5, bottom - 5, 10, 7, 0f, 0f, 0f, 0.78f);
        renderer.setColor(1f, 1f, 1f, 1f);
        renderer.draw(message, left + 10, bottom + 6);
    }

    private static void drawChatPanel(GL2 gl, TextRenderer renderer, int[] viewport) {
        int width = Math.min(520, Math.max(360, viewport[2] - 40));
        int height = Math.min(250, Math.max(190, viewport[3] / 3));
        int left = 20, bottom = 20;

        drawOverlayRect(gl, viewport, left, bottom, width, height, 0f, 0f, 0f, 0.78f);
        drawOverlayRect(gl, viewport, left, bottom + height - 34, width, 34, 0.02f, 0.02f, 0.02f, 0.92f);

        renderer.setColor(1f, 1f, 1f, 1f);
        renderer.draw("MULTIPLAYER CHAT", left + 14, bottom + height - 26);

        int y = bottom + height - 58;
        int shown = 0;
        for (int i = CHAT.size() - 1; i >= 0 && shown < 7; i--, shown++) {
            String line = CHAT.get(i);
            if (line.length() > 68) line = line.substring(0, 68) + "...";
            boolean redSystem = line.startsWith("[WARNING]") || line.startsWith("[KICKED]") || line.startsWith("[MUTED]");
            boolean adminLine = line.startsWith("[ADMIN/HOST] ");
            if (redSystem) {
                renderer.setColor(1f, 0.15f, 0.15f, 1f);
                renderer.draw(line, left + 14, y);
            } else if (adminLine) {
                String tag = "[ADMIN/HOST] ";
                String rest = line.substring(tag.length());
                renderer.setColor(1f, 0.15f, 0.15f, 1f);
                renderer.draw(tag, left + 14, y);
                float tagWidth = (float) renderer.getBounds(tag).getWidth();
                renderer.setColor(1f, 1f, 1f, 1f);
                renderer.draw(rest, left + 14 + (int) tagWidth, y);
            } else {
                renderer.setColor(1f, 1f, 1f, 1f);
                renderer.draw(line, left + 14, y);
            }
            y -= 25;
        }

        int inputTop = bottom + 10;
        drawOverlayRect(gl, viewport, left + 10, inputTop, width - 20, 30,
                0.06f, 0.06f, 0.06f, 0.96f);
        renderer.setColor(0.72f, 0.72f, 0.72f, 1f);
        String draft = chatDraft.toString();
        if (draft.length() > 65) draft = draft.substring(draft.length() - 65);
        renderer.draw("> " + draft + (System.nanoTime() / 500_000_000L % 2 == 0 ? "_" : ""),
                left + 18, inputTop + 8);

    }

    private static void drawOverlayRect(GL2 gl, int[] viewport, int x, int y, int w, int h,
                                        float r, float g, float b, float a) {
        gl.glPushAttrib(GL2.GL_ENABLE_BIT | GL2.GL_COLOR_BUFFER_BIT | GL2.GL_CURRENT_BIT);
        gl.glDisable(GL2.GL_DEPTH_TEST);
        gl.glDisable(GL2.GL_TEXTURE_2D);
        gl.glEnable(GL2.GL_BLEND);
        gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        gl.glOrtho(0, viewport[2], 0, viewport[3], -1, 1);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        gl.glColor4f(r, g, b, a);
        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex2f(x, y);
        gl.glVertex2f(x + w, y);
        gl.glVertex2f(x + w, y + h);
        gl.glVertex2f(x, y + h);
        gl.glEnd();
        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPopAttrib();
    }

    private static void appendChatMessage(String name, String message, boolean admin) {
        CHAT.add((admin ? "[ADMIN/HOST] " : "") + "[" + name + "] " + message);
        CHAT_BUBBLES.put(name, new ChatBubble(message));
        while (CHAT.size() > 200) CHAT.remove(0);
    }

    private static void appendSystemMessage(String message, boolean redItalicBold) {
        CHAT.add(message);
        while (CHAT.size() > 200) CHAT.remove(0);
    }

    public static void disconnectMultiplayer() {
        running = false;
        kicked = false;
        kickMessage = "You have been kicked by the admin/host";
        locallyMuted = false;
        try { if (webSocket != null) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect").join(); } catch (Exception ignored) { }
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        webSocket = null;
        socket = null;
        reader = null;
        writer = null;
        welcomeLatch = null;
        connectionFailure = "";
        REMOTE.clear();
        CHAT_BUBBLES.clear();
        chatOpen = false;
        chatDraft.setLength(0);
    }
}
