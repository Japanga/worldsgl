package objviewer;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;
import static javax.swing.TransferHandler.COPY;

/**
 * Visual behavior-tree editor for WorldsGL.
 *
 * This class is intentionally independent from ThirdPersonGameNew's private
 * SpawnedObject implementation. It works with the public profile data exposed
 * by ActionBuilder and BehaviorEditor, while keeping its own graph data.
 *
 * Features:
 *  - Drag nodes around a large canvas.
 *  - Drag from an output socket to an input socket to create connections.
 *  - Select, delete, duplicate, rename, and inspect nodes.
 *  - Palette entries for Sequence, Selector, Behavior Profile, Action Profile,
 *    and Comment nodes.
 *  - Zoom and pan.
 *  - Save/load the behavior graph through Properties, making it suitable for
 *    embedding in the existing .tpgproject Properties stream.
 *
 * Suggested integration:
 *
 *   BehaviorTree.openEditor(parent, behaviorProfiles, actionProfiles, onChanged);
 *
 * And inside ThirdPersonGameNew's project serialization:
 *
 *   BehaviorTree.writeToProperties(properties, behaviorTreeNodes, behaviorTreeConnections);
 *
 *   BehaviorTree.readFromProperties(properties, behaviorTreeNodes, behaviorTreeConnections);
 *
 * The editor does not remove or replace the existing BehaviorEditor or
 * ActionBuilder. Those remain the source editors for their reusable profiles.
 */
public final class BehaviorTree {

    private BehaviorTree() {}

    public static final String NONE = "Select a behavior tree...";

    private static final String PROP_VERSION = "behaviorTree.version";
    private static final String PROP_NODE_COUNT = "behaviorTree.nodes.count";
    private static final String PROP_EDGE_COUNT = "behaviorTree.connections.count";
    private static final int VERSION = 5;

    // Live runtime countdowns keyed by node id. ThirdPersonGameNew updates these
    // while a Time node is waiting, and the editor paints them directly on the node.
    private static final Map<String, Float> runtimeTimeRemaining = new ConcurrentHashMap<>();

    public static void setRuntimeTimeRemaining(String nodeId, float secondsRemaining) {
        if (nodeId == null || nodeId.trim().isEmpty()) return;
        runtimeTimeRemaining.put(nodeId, Math.max(0.0f, secondsRemaining));
    }

    public static void clearRuntimeTimeRemaining(String nodeId) {
        if (nodeId != null) runtimeTimeRemaining.remove(nodeId);
    }

    private static float getRuntimeTimeRemaining(String nodeId) {
        Float value = runtimeTimeRemaining.get(nodeId);
        return value == null ? -1.0f : value;
    }

    public enum NodeType {
        ROOT("Root"),
        SEQUENCE("Sequence"),
        SELECTOR("Selector"),
        RANDOM("Random"),
        BEHAVIOR("Behavior"),
        ACTION("Action"),
        PLAYER("Player"),
        COLLISION("Collision"),
        RADIUS("Radius"),
        TIME("Time"),
        VISIBILITY("Visibility"),
        ACTIVATOR("Activator"),
        ON_DEATH("OnDeath"),
        DESTROY("Destroy"),
        RESPAWN("Respawn"),
        SOUND("Sound"),
        ON_CLICK("OnClick"),
        ADD_ITEM("AddItem"),
        OBJECT_DROPDOWN("Object Dropdown"),
        COMMENT("Comment");

        private final String displayName;

        NodeType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public static final class Node {
        public final String id;
        public NodeType type;
        public String name;
        public String profileName;
        public String comment;

        // Inline settings for Radius, Time, and Visibility nodes.
        public float radius = 5.0f;
        public float timeSeconds = 1.0f;
        // Health restored by a Respawn node. This is intentionally independent
        // of the object's original/default health so designers can restore
        // enemies with custom starting HP values.
        public int respawnHealth = 100;
        // Visibility command used by the runtime: true = Hide, false = Show.
        // The editor exposes this as two explicit, mutually-exclusive checkmarks.
        public boolean visibilityHidden = true;
        // Explicit Visibility targets. Multiple objects may be selected in one node.
        // profileName remains the legacy/primary target for old project files.
        public final List<String> visibilityTargets = new ArrayList<>();
        // Explicit Activator targets. Each target has its Disable when hidden flag cleared when this node executes.
        public final List<String> activatorTargets = new ArrayList<>();
        // Optional delay before an Activator clears Disable when hidden.
        public float activatorDelaySeconds = 0.0f;

        // Optional per-node inline settings. Legacy nodes leave these disabled
        // and continue using their referenced Behavior/Action Profile directly.
        public boolean inlineBehaviorConfigured;
        public boolean inlineIsEnemy;
        public boolean inlineChase;
        public boolean inlineIsNPC;
        public boolean inlineGameOver;
        public boolean inlinePatrol;
        public boolean inlineTriggerZone;
        public boolean inlineCollision;
        public boolean inlinePhysics;
        public boolean inlineReflective;
        public boolean inlineAutoRotate;

        public boolean inlineActionConfigured;
        // Action nodes are UI/game-state value setters only. They do not carry
        // object behavior, object targeting, destruction, collection, or damage flags.
        public boolean inlineUIActionConfigured;

        // Each UI action is a delta operation rather than an absolute setter.
        // ADD increases the current value; SUBTRACT decreases it.
        public enum UIActionOperation { ADD, SUBTRACT }
        public UIActionOperation inlinePlayerHPOperation = UIActionOperation.ADD;
        public UIActionOperation inlineEnemyHealthOperation = UIActionOperation.ADD;
        public UIActionOperation inlineScoreOperation = UIActionOperation.ADD;

        public boolean inlineSetPlayerHP;
        public int inlinePlayerHP = 10;
        // Player node: change the active Player model scale when this node executes.
        public boolean inlineSetPlayerScale;
        public float inlinePlayerScale = 1.0f;
        public boolean inlineSetEnemyHealth;
        public int inlineEnemyHealth = 10;
        public boolean inlineSetScore;
        public int inlineScore = 10;

        // Legacy Action fields are retained only so older project files can still load.
        public boolean inlineGivesPoints;
        public int inlinePoints = 10;
        public boolean inlineGivesHP;
        public int inlineHPAmount = 10;
        public boolean inlineAttacksPlayer;
        public int inlineAttackDamage = 10;
        public boolean inlineCompleteQuest;
        public String inlineQuestName = "";

        public int x;
        public int y;
        public int width = 190;
        public int height = 86;

        public Node(String id, NodeType type, String name) {
            this.id = id == null ? UUID.randomUUID().toString() : id;
            this.type = type == null ? NodeType.ACTION : type;
            this.name = name == null ? "" : name;
            this.profileName = "";
            this.comment = "";
        }

        public Node copy(String newId) {
            Node n = new Node(newId, type, name);
            n.profileName = profileName == null ? "" : profileName;
            n.comment = comment == null ? "" : comment;
            n.radius = radius;
            n.timeSeconds = timeSeconds;
            n.respawnHealth = respawnHealth;
            n.visibilityHidden = visibilityHidden;
            n.visibilityTargets.addAll(visibilityTargets);
            n.activatorTargets.addAll(activatorTargets);
            n.activatorDelaySeconds = activatorDelaySeconds;
            n.inlineBehaviorConfigured = inlineBehaviorConfigured;
            n.inlineIsEnemy = inlineIsEnemy;
            n.inlineChase = inlineChase;
            n.inlineIsNPC = inlineIsNPC;
            n.inlineGameOver = inlineGameOver;
            n.inlinePatrol = inlinePatrol;
            n.inlineTriggerZone = inlineTriggerZone;
            n.inlineCollision = inlineCollision;
            n.inlinePhysics = inlinePhysics;
            n.inlineReflective = inlineReflective;
            n.inlineAutoRotate = inlineAutoRotate;
            n.inlineActionConfigured = inlineActionConfigured;
            n.inlineUIActionConfigured = inlineUIActionConfigured;
            n.inlinePlayerHPOperation = inlinePlayerHPOperation;
            n.inlineEnemyHealthOperation = inlineEnemyHealthOperation;
            n.inlineScoreOperation = inlineScoreOperation;
            n.inlineSetPlayerHP = inlineSetPlayerHP;
            n.inlinePlayerHP = inlinePlayerHP;
            n.inlineSetPlayerScale = inlineSetPlayerScale;
            n.inlinePlayerScale = inlinePlayerScale;
            n.inlineSetEnemyHealth = inlineSetEnemyHealth;
            n.inlineEnemyHealth = inlineEnemyHealth;
            n.inlineSetScore = inlineSetScore;
            n.inlineScore = inlineScore;
            n.inlineGivesPoints = inlineGivesPoints;
            n.inlinePoints = inlinePoints;
            n.inlineGivesHP = inlineGivesHP;
            n.inlineHPAmount = inlineHPAmount;
            n.inlineAttacksPlayer = inlineAttacksPlayer;
            n.inlineAttackDamage = inlineAttackDamage;
            n.inlineCompleteQuest = inlineCompleteQuest;
            n.inlineQuestName = inlineQuestName == null ? "" : inlineQuestName;
            n.x = x;
            n.y = y;
            n.width = width;
            n.height = height;
            return n;
        }

        public boolean acceptsInput() {
            return type != NodeType.ROOT;
        }

        public boolean producesOutput() {
            return type != null;
        }

        @Override
        public String toString() {
            return name == null || name.trim().isEmpty() ? type.getDisplayName() : name;
        }
    }

    public static final class Connection {
        public final String id;
        public String fromNodeId;
        public String toNodeId;

        public Connection(String fromNodeId, String toNodeId) {
            this(UUID.randomUUID().toString(), fromNodeId, toNodeId);
        }

        public Connection(String id, String fromNodeId, String toNodeId) {
            this.id = id == null ? UUID.randomUUID().toString() : id;
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
        }
    }

    /** Reusable named behavior-tree definition, analogous to a BehaviorEditor profile. */
    public static final class TreeProfile {
        public String name;
        public final List<Node> nodes = new ArrayList<>();
        public final List<Connection> connections = new ArrayList<>();

        public TreeProfile(String name) {
            this.name = name == null ? "" : name.trim();
        }
    }

    private static final class EditorState {
        final List<Node> nodes = new ArrayList<>();
        final List<Connection> connections = new ArrayList<>();
        Node selected;
        Node connectionSource;
        Point dragOffset;
        Point panStart;
        int panStartX;
        int panStartY;
        boolean panning;
        Point mouse;
        double zoom = 1.0;
        boolean dirty;
    }

    public static void openEditor(
            Component parent,
            Map<String, BehaviorEditor.BehaviorProfile> behaviorProfiles,
            Map<String, ActionBuilder.ActionProfile> actionProfiles,
            Runnable onChanged) {
        openEditor(parent, behaviorProfiles, actionProfiles,
                new ArrayList<>(), new ArrayList<>(), onChanged);
    }

    /**
     * Opens the editor against the caller's persistent graph lists. Changes
     * made in the dialog therefore remain available to ThirdPersonGameNew's
     * existing project save/load routines.
     */
    public static void openEditor(
            Component parent,
            Map<String, BehaviorEditor.BehaviorProfile> behaviorProfiles,
            Map<String, ActionBuilder.ActionProfile> actionProfiles,
            List<Node> nodes,
            List<Connection> connections,
            Runnable onChanged) {
        openEditor(parent, behaviorProfiles, actionProfiles, nodes, connections,
                Collections.emptyList(), Collections.emptyList(), onChanged);
    }

    /** Opens the editor with the current object names available to Object Dropdown nodes. */
    public static void openEditor(
            Component parent,
            Map<String, BehaviorEditor.BehaviorProfile> behaviorProfiles,
            Map<String, ActionBuilder.ActionProfile> actionProfiles,
            List<Node> nodes,
            List<Connection> connections,
            List<String> objectNames,
            Runnable onChanged) {
        openEditor(parent, behaviorProfiles, actionProfiles, nodes, connections,
                objectNames, Collections.emptyList(), onChanged);
    }

    /** Opens the editor with object names and the project's Audio Manager library available. */
    public static void openEditor(
            Component parent,
            Map<String, BehaviorEditor.BehaviorProfile> behaviorProfiles,
            Map<String, ActionBuilder.ActionProfile> actionProfiles,
            List<Node> nodes,
            List<Connection> connections,
            List<String> objectNames,
            List<AudioManager.AudioEntry> audioLibrary,
            Runnable onChanged) {
        openEditor(parent, behaviorProfiles, actionProfiles, nodes, connections,
                objectNames, Collections.emptyList(), audioLibrary, onChanged);
    }

    /**
     * Opens the editor with both the complete object list and the subset of
     * objects explicitly enabled as Inventory Items. AddItem uses only the
     * item-enabled subset; all other target nodes continue to use objectNames.
     */
    public static void openEditor(
            Component parent,
            Map<String, BehaviorEditor.BehaviorProfile> behaviorProfiles,
            Map<String, ActionBuilder.ActionProfile> actionProfiles,
            List<Node> nodes,
            List<Connection> connections,
            List<String> objectNames,
            List<String> itemObjectNames,
            List<AudioManager.AudioEntry> audioLibrary,
            Runnable onChanged) {

        // Keep the caller-owned lists in final local references. The editor
        // uses them from Swing lambdas, so they must be effectively final.
        final List<Node> persistentNodes =
                nodes == null ? new ArrayList<>() : nodes;
        final List<Connection> persistentConnections =
                connections == null ? new ArrayList<>() : connections;

        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(parent),
                "Behavior Tree",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        EditorState state = new EditorState();

        final List<String> availableObjectNames = objectNames == null
                ? Collections.emptyList() : new ArrayList<>(objectNames);
        final List<String> availableItemObjectNames = itemObjectNames == null
                ? Collections.emptyList() : new ArrayList<>(itemObjectNames);
        final List<AudioManager.AudioEntry> availableAudioLibrary = audioLibrary == null
                ? Collections.emptyList() : new ArrayList<>(audioLibrary);

        GraphPanel graph = new GraphPanel(
                state, persistentNodes, persistentConnections,
                behaviorProfiles, actionProfiles, availableObjectNames,
                availableItemObjectNames, availableAudioLibrary);

        JPanel palette = buildPalette(graph, state, behaviorProfiles, actionProfiles);

        JButton newButton = new JButton("New Tree");
        JButton deleteButton = new JButton("Delete Node");
        JButton duplicateButton = new JButton("Duplicate");
        JButton fitButton = new JButton("Fit");
        JButton zoomIn = new JButton("+");
        JButton zoomOut = new JButton("-");
        JButton saveButton = new JButton("Apply Changes");
        JButton closeButton = new JButton("Close");

        newButton.addActionListener(e -> {
            if (!persistentNodes.isEmpty()) {
                int answer = JOptionPane.showConfirmDialog(
                        dialog,
                        "Clear the current behavior tree?",
                        "New Behavior Tree",
                        JOptionPane.YES_NO_OPTION);
                if (answer != JOptionPane.YES_OPTION) return;
            }
            persistentNodes.clear();
            persistentConnections.clear();
            state.selected = null;
            state.connectionSource = null;
            state.dirty = true;
            graph.repaint();
        });

        deleteButton.addActionListener(e -> {
            if (state.selected == null) return;
            removeNode(persistentNodes, persistentConnections, state.selected.id);
            state.selected = null;
            state.dirty = true;
            graph.repaint();
        });

        duplicateButton.addActionListener(e -> {
            if (state.selected == null) return;
            Node original = state.selected;
            Node copy = original.copy(UUID.randomUUID().toString());
            copy.x += 35;
            copy.y += 35;
            copy.name = original.name + " Copy";
            persistentNodes.add(copy);
            state.selected = copy;
            state.dirty = true;
            graph.repaint();
        });

        fitButton.addActionListener(e -> {
            graph.fitToNodes();
            graph.repaint();
        });

        zoomIn.addActionListener(e -> {
            state.zoom = Math.min(2.25, state.zoom * 1.15);
            graph.repaint();
        });

        zoomOut.addActionListener(e -> {
            state.zoom = Math.max(0.45, state.zoom / 1.15);
            graph.repaint();
        });

        saveButton.addActionListener(e -> {
            state.dirty = false;
            if (onChanged != null) onChanged.run();
            JOptionPane.showMessageDialog(
                    dialog,
                    "Behavior tree changes applied.",
                    "Behavior Tree",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        closeButton.addActionListener(e -> {
            if (state.dirty) {
                int answer = JOptionPane.showConfirmDialog(
                        dialog,
                        "Apply the behavior tree changes before closing?",
                        "Behavior Tree",
                        JOptionPane.YES_NO_CANCEL_OPTION);
                if (answer == JOptionPane.CANCEL_OPTION) return;
                if (answer == JOptionPane.YES_OPTION && onChanged != null) {
                    onChanged.run();
                }
            }
            dialog.dispose();
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
        top.add(new JLabel("Behavior Tree"));
        top.add(newSeparator());
        top.add(newButton);
        top.add(deleteButton);
        top.add(duplicateButton);
        top.add(newSeparator());
        top.add(fitButton);
        top.add(new JLabel("Zoom:"));
        top.add(zoomOut);
        top.add(zoomIn);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 4));
        bottom.add(new JLabel("Drag from an output dot to an input dot to connect nodes."));
        bottom.add(saveButton);
        bottom.add(closeButton);

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(palette, BorderLayout.WEST);
        content.add(new JScrollPane(graph), BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setSize(1120, 720);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static JSeparator newSeparator() {
        return new JSeparator(SwingConstants.VERTICAL);
    }

    private static JPanel buildPalette(
            GraphPanel graph,
            EditorState state,
            Map<String, BehaviorEditor.BehaviorProfile> behaviorProfiles,
            Map<String, ActionBuilder.ActionProfile> actionProfiles) {

        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Node Palette"),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        panel.setPreferredSize(new Dimension(220, 0));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel hint = new JLabel("<html><b>Drag</b> a node type<br>onto the canvas.</html>");
        panel.add(hint);
        panel.add(Box.createVerticalStrut(8));

        addPaletteButton(panel, graph, NodeType.ROOT, "Root");
        addPaletteButton(panel, graph, NodeType.SEQUENCE, "Sequence");
        addPaletteButton(panel, graph, NodeType.SELECTOR, "Selector");
        addPaletteButton(panel, graph, NodeType.RANDOM, "Random");
        addPaletteButton(panel, graph, NodeType.BEHAVIOR, "Behavior Profile");
        addPaletteButton(panel, graph, NodeType.ACTION, "Action Profile");
        addPaletteButton(panel, graph, NodeType.PLAYER, "Player");
        addPaletteButton(panel, graph, NodeType.COLLISION, "Collision");
        addPaletteButton(panel, graph, NodeType.RADIUS, "Radius");
        addPaletteButton(panel, graph, NodeType.TIME, "Time");
        addPaletteButton(panel, graph, NodeType.VISIBILITY, "Visibility");
        addPaletteButton(panel, graph, NodeType.ACTIVATOR, "Activator");
        addPaletteButton(panel, graph, NodeType.ON_DEATH, "OnDeath");
        addPaletteButton(panel, graph, NodeType.DESTROY, "Destroy");
        addPaletteButton(panel, graph, NodeType.RESPAWN, "Respawn");
        addPaletteButton(panel, graph, NodeType.SOUND, "Sound");
        addPaletteButton(panel, graph, NodeType.ON_CLICK, "OnClick");
        addPaletteButton(panel, graph, NodeType.ADD_ITEM, "AddItem");
        addPaletteButton(panel, graph, NodeType.OBJECT_DROPDOWN, "Object Dropdown");
        addPaletteButton(panel, graph, NodeType.COMMENT, "Comment");

        panel.add(Box.createVerticalStrut(12));

        JLabel profileInfo = new JLabel("<html><b>Existing profiles</b><br>"
                + "Behavior profiles: " + (behaviorProfiles == null ? 0 : behaviorProfiles.size())
                + "<br>Action profiles: " + (actionProfiles == null ? 0 : actionProfiles.size())
                + "</html>");
        panel.add(profileInfo);

        panel.add(Box.createVerticalGlue());

        JButton centerButton = new JButton("Center View");
        centerButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerButton.addActionListener(e -> {
            graph.centerView();
            graph.repaint();
        });
        panel.add(centerButton);

        return panel;
    }

    private static void addPaletteButton(
            JPanel palette,
            GraphPanel graph,
            NodeType type,
            String text) {

        JButton button = new JButton(text);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        button.setTransferHandler(new TransferHandler("nodeType") {
            @Override
            protected Transferable createTransferable(JComponent c) {
                return new StringSelection(type.name());
            }

            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }
        });
        button.putClientProperty("nodeType", type.name());

        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                JComponent c = (JComponent) e.getSource();
                TransferHandler th = c.getTransferHandler();
                if (th != null) th.exportAsDrag(c, e, TransferHandler.COPY);
            }
        };
        button.addMouseListener(adapter);
        palette.add(button);
        palette.add(Box.createVerticalStrut(4));
    }

    public static Node addNode(
            List<Node> nodes,
            NodeType type,
            String name,
            int x,
            int y) {

        Node n = new Node(UUID.randomUUID().toString(), type, name);
        // Behavior/Action nodes are miniature self-contained profile editors.
        // Their inline values are therefore enabled by default; an optional
        // referenced profile is only used as a convenient starting template.
        if (type == NodeType.BEHAVIOR) {
            n.inlineBehaviorConfigured = true;
        } else if (type == NodeType.ACTION) {
            n.inlineActionConfigured = true;
            n.inlineUIActionConfigured = true;
        }
        n.x = x;
        n.y = y;
        nodes.add(n);
        return n;
    }

    public static boolean connect(
            List<Node> nodes,
            List<Connection> connections,
            String fromNodeId,
            String toNodeId) {

        if (fromNodeId == null || toNodeId == null ||
                fromNodeId.equals(toNodeId)) {
            return false;
        }

        Node from = findNode(nodes, fromNodeId);
        Node to = findNode(nodes, toNodeId);

        if (from == null || to == null || !to.acceptsInput()) {
            return false;
        }

        // A behavior tree is directed. Prevent duplicate edges.
        for (Connection c : connections) {
            if (fromNodeId.equals(c.fromNodeId) &&
                    toNodeId.equals(c.toNodeId)) {
                return false;
            }
        }

        // Do not permit a cycle.
        if (wouldCreateCycle(nodes, connections, fromNodeId, toNodeId)) {
            return false;
        }

        connections.add(new Connection(fromNodeId, toNodeId));
        return true;
    }

    private static boolean wouldCreateCycle(
            List<Node> nodes,
            List<Connection> connections,
            String from,
            String to) {

        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(to);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) continue;
            if (from.equals(current)) return true;

            for (Connection c : connections) {
                if (current.equals(c.fromNodeId)) {
                    stack.push(c.toNodeId);
                }
            }
        }
        return false;
    }

    public static void removeNode(
            List<Node> nodes,
            List<Connection> connections,
            String nodeId) {

        nodes.removeIf(n -> nodeId.equals(n.id));
        connections.removeIf(c ->
                nodeId.equals(c.fromNodeId) || nodeId.equals(c.toNodeId));
    }

    public static Node findNode(List<Node> nodes, String id) {
        if (id == null) return null;
        for (Node n : nodes) {
            if (id.equals(n.id)) return n;
        }
        return null;
    }

    private static void writeInlineSettings(Properties p, String k, Node n) {
        p.setProperty(k + "inlineBehaviorConfigured", Boolean.toString(n.inlineBehaviorConfigured));
        p.setProperty(k + "inlineIsEnemy", Boolean.toString(n.inlineIsEnemy));
        p.setProperty(k + "inlineChase", Boolean.toString(n.inlineChase));
        p.setProperty(k + "inlineIsNPC", Boolean.toString(n.inlineIsNPC));
        p.setProperty(k + "inlineGameOver", Boolean.toString(n.inlineGameOver));
        p.setProperty(k + "inlinePatrol", Boolean.toString(n.inlinePatrol));
        p.setProperty(k + "inlineTriggerZone", Boolean.toString(n.inlineTriggerZone));
        p.setProperty(k + "inlineCollision", Boolean.toString(n.inlineCollision));
        p.setProperty(k + "inlinePhysics", Boolean.toString(n.inlinePhysics));
        p.setProperty(k + "inlineReflective", Boolean.toString(n.inlineReflective));
        p.setProperty(k + "inlineAutoRotate", Boolean.toString(n.inlineAutoRotate));
        p.setProperty(k + "inlineActionConfigured", Boolean.toString(n.inlineActionConfigured));
        p.setProperty(k + "inlineUIActionConfigured", Boolean.toString(n.inlineUIActionConfigured));
        p.setProperty(k + "inlinePlayerHPOperation", n.inlinePlayerHPOperation == null ? "ADD" : n.inlinePlayerHPOperation.name());
        p.setProperty(k + "inlineEnemyHealthOperation", n.inlineEnemyHealthOperation == null ? "ADD" : n.inlineEnemyHealthOperation.name());
        p.setProperty(k + "inlineScoreOperation", n.inlineScoreOperation == null ? "ADD" : n.inlineScoreOperation.name());
        p.setProperty(k + "inlineSetPlayerHP", Boolean.toString(n.inlineSetPlayerHP));
        p.setProperty(k + "inlinePlayerHP", Integer.toString(n.inlinePlayerHP));
        p.setProperty(k + "inlineSetPlayerScale", Boolean.toString(n.inlineSetPlayerScale));
        p.setProperty(k + "inlinePlayerScale", Float.toString(n.inlinePlayerScale));
        p.setProperty(k + "inlineSetEnemyHealth", Boolean.toString(n.inlineSetEnemyHealth));
        p.setProperty(k + "inlineEnemyHealth", Integer.toString(n.inlineEnemyHealth));
        p.setProperty(k + "inlineSetScore", Boolean.toString(n.inlineSetScore));
        p.setProperty(k + "inlineScore", Integer.toString(n.inlineScore));
        p.setProperty(k + "inlineGivesPoints", Boolean.toString(n.inlineGivesPoints));
        p.setProperty(k + "inlinePoints", Integer.toString(n.inlinePoints));
        p.setProperty(k + "inlineGivesHP", Boolean.toString(n.inlineGivesHP));
        p.setProperty(k + "inlineHPAmount", Integer.toString(n.inlineHPAmount));
        p.setProperty(k + "inlineAttacksPlayer", Boolean.toString(n.inlineAttacksPlayer));
        p.setProperty(k + "inlineAttackDamage", Integer.toString(n.inlineAttackDamage));
        p.setProperty(k + "inlineCompleteQuest", Boolean.toString(n.inlineCompleteQuest));
        p.setProperty(k + "inlineQuestName", safe(n.inlineQuestName));
        p.setProperty(k + "radius", Float.toString(n.radius));
        p.setProperty(k + "timeSeconds", Float.toString(n.timeSeconds));
        p.setProperty(k + "respawnHealth", Integer.toString(Math.max(1, n.respawnHealth)));
        p.setProperty(k + "visibilityHidden", Boolean.toString(n.visibilityHidden));
        p.setProperty(k + "visibilityTargetCount", Integer.toString(n.visibilityTargets.size()));
        for (int i = 0; i < n.visibilityTargets.size(); i++) {
            p.setProperty(k + "visibilityTarget." + i, safe(n.visibilityTargets.get(i)));
        }
        p.setProperty(k + "activatorDelaySeconds", Float.toString(Math.max(0.0f, n.activatorDelaySeconds)));
        p.setProperty(k + "activatorTargetCount", Integer.toString(n.activatorTargets.size()));
        for (int i = 0; i < n.activatorTargets.size(); i++) {
            p.setProperty(k + "activatorTarget." + i, safe(n.activatorTargets.get(i)));
        }
    }

    private static Node.UIActionOperation parseUIActionOperation(String value) {
        if (value == null) return Node.UIActionOperation.ADD;
        return "SUBTRACT".equalsIgnoreCase(value.trim())
                ? Node.UIActionOperation.SUBTRACT
                : Node.UIActionOperation.ADD;
    }

    private static void readInlineSettings(Properties p, String k, Node n) {
        n.inlineBehaviorConfigured = Boolean.parseBoolean(p.getProperty(k + "inlineBehaviorConfigured", "false"));
        n.inlineIsEnemy = Boolean.parseBoolean(p.getProperty(k + "inlineIsEnemy", "false"));
        n.inlineChase = Boolean.parseBoolean(p.getProperty(k + "inlineChase", "false"));
        n.inlineIsNPC = Boolean.parseBoolean(p.getProperty(k + "inlineIsNPC", "false"));
        n.inlineGameOver = Boolean.parseBoolean(p.getProperty(k + "inlineGameOver", "false"));
        n.inlinePatrol = Boolean.parseBoolean(p.getProperty(k + "inlinePatrol", "false"));
        n.inlineTriggerZone = Boolean.parseBoolean(p.getProperty(k + "inlineTriggerZone", "false"));
        n.inlineCollision = Boolean.parseBoolean(p.getProperty(k + "inlineCollision", "false"));
        n.inlinePhysics = Boolean.parseBoolean(p.getProperty(k + "inlinePhysics", "false"));
        n.inlineReflective = Boolean.parseBoolean(p.getProperty(k + "inlineReflective", "false"));
        n.inlineAutoRotate = Boolean.parseBoolean(p.getProperty(k + "inlineAutoRotate", "false"));
        n.inlineActionConfigured = Boolean.parseBoolean(p.getProperty(k + "inlineActionConfigured", "false"));
        boolean hasUIActionSettings = p.containsKey(k + "inlineUIActionConfigured");
        n.inlineUIActionConfigured = Boolean.parseBoolean(p.getProperty(k + "inlineUIActionConfigured", "false"));
        n.inlinePlayerHPOperation = parseUIActionOperation(p.getProperty(k + "inlinePlayerHPOperation", "ADD"));
        n.inlineEnemyHealthOperation = parseUIActionOperation(p.getProperty(k + "inlineEnemyHealthOperation", "ADD"));
        n.inlineScoreOperation = parseUIActionOperation(p.getProperty(k + "inlineScoreOperation", "ADD"));
        n.inlineSetPlayerHP = Boolean.parseBoolean(p.getProperty(k + "inlineSetPlayerHP", "false"));
        n.inlinePlayerHP = Math.max(0, parseInt(p.getProperty(k + "inlinePlayerHP", "100"), 100));
        n.inlineSetPlayerScale = Boolean.parseBoolean(p.getProperty(k + "inlineSetPlayerScale", "false"));
        n.inlinePlayerScale = Math.max(0.01f, Math.min(100.0f, parseFloat(p.getProperty(k + "inlinePlayerScale", "1.0"), 1.0f)));
        n.inlineSetEnemyHealth = Boolean.parseBoolean(p.getProperty(k + "inlineSetEnemyHealth", "false"));
        n.inlineEnemyHealth = Math.max(0, parseInt(p.getProperty(k + "inlineEnemyHealth", "100"), 100));
        n.inlineSetScore = Boolean.parseBoolean(p.getProperty(k + "inlineSetScore", "false"));
        n.inlineScore = Math.max(0, parseInt(p.getProperty(k + "inlineScore", "0"), 0));
        n.inlineGivesPoints = Boolean.parseBoolean(p.getProperty(k + "inlineGivesPoints", "false"));
        n.inlinePoints = Math.max(0, parseInt(p.getProperty(k + "inlinePoints", "10"), 10));
        n.inlineGivesHP = Boolean.parseBoolean(p.getProperty(k + "inlineGivesHP", "false"));
        n.inlineHPAmount = Math.max(0, parseInt(p.getProperty(k + "inlineHPAmount", "10"), 10));
        n.inlineAttacksPlayer = Boolean.parseBoolean(p.getProperty(k + "inlineAttacksPlayer", "false"));
        n.inlineAttackDamage = Math.max(0, parseInt(p.getProperty(k + "inlineAttackDamage", "10"), 10));
        n.inlineCompleteQuest = Boolean.parseBoolean(p.getProperty(k + "inlineCompleteQuest", "false"));
        n.inlineQuestName = p.getProperty(k + "inlineQuestName", "");
        if (!hasUIActionSettings && n.inlineActionConfigured) {
            // Older Action nodes become UI-only actions. Preserve their score/HP
            // values as absolute UI values, while intentionally discarding
            // object-oriented attack/quest behavior.
            n.inlineUIActionConfigured = true;
            if (n.inlineGivesHP) { n.inlineSetPlayerHP = true; n.inlinePlayerHP = n.inlineHPAmount; }
            if (n.inlineGivesPoints) { n.inlineSetScore = true; n.inlineScore = n.inlinePoints; }
        }
        n.radius = Math.max(0.01f, parseFloat(p.getProperty(k + "radius", "5.0"), 5.0f));
        n.timeSeconds = Math.max(0.0f, parseFloat(p.getProperty(k + "timeSeconds", "1.0"), 1.0f));
        n.respawnHealth = Math.max(1, parseInt(p.getProperty(k + "respawnHealth", "100"), 100));
        n.visibilityHidden = Boolean.parseBoolean(p.getProperty(k + "visibilityHidden", "true"));
        n.visibilityTargets.clear();
        int visibilityTargetCount = parseInt(p.getProperty(k + "visibilityTargetCount", "0"), 0);
        for (int i = 0; i < visibilityTargetCount; i++) {
            String target = p.getProperty(k + "visibilityTarget." + i, "").trim();
            if (!target.isEmpty()) n.visibilityTargets.add(target);
        }
        if (n.visibilityTargets.isEmpty() && n.type == NodeType.VISIBILITY
                && n.profileName != null && !n.profileName.trim().isEmpty()) {
            n.visibilityTargets.add(n.profileName.trim());
        }
        n.activatorDelaySeconds = Math.max(0.0f, parseFloat(p.getProperty(k + "activatorDelaySeconds", "0.0"), 0.0f));
        int activatorTargetCount = parseInt(p.getProperty(k + "activatorTargetCount", "0"), 0);
        for (int i = 0; i < activatorTargetCount; i++) {
            String target = p.getProperty(k + "activatorTarget." + i, "").trim();
            if (!target.isEmpty()) n.activatorTargets.add(target);
        }
    }

    /**
     * Serializes the supplied graph into the existing .tpgproject Properties
     * object. This does not create a separate file and therefore can be called
     * from ThirdPersonGameNew's existing project writer.
     */
    public static void writeToProperties(
            Properties properties,
            List<Node> nodes,
            List<Connection> connections) {

        if (properties == null) return;

        properties.setProperty(PROP_VERSION, Integer.toString(VERSION));
        properties.setProperty(PROP_NODE_COUNT,
                Integer.toString(nodes == null ? 0 : nodes.size()));
        properties.setProperty(PROP_EDGE_COUNT,
                Integer.toString(connections == null ? 0 : connections.size()));

        if (nodes != null) {
            int i = 0;
            for (Node n : nodes) {
                if (n == null) continue;
                String k = "behaviorTree.node." + i + ".";
                properties.setProperty(k + "id", safe(n.id));
                properties.setProperty(k + "type", n.type.name());
                properties.setProperty(k + "name", safe(n.name));
                properties.setProperty(k + "profile", safe(n.profileName));
                properties.setProperty(k + "comment", safe(n.comment));
                writeInlineSettings(properties, k, n);
                properties.setProperty(k + "x", Integer.toString(n.x));
                properties.setProperty(k + "y", Integer.toString(n.y));
                properties.setProperty(k + "width", Integer.toString(n.width));
                properties.setProperty(k + "height", Integer.toString(n.height));
                i++;
            }
        }

        if (connections != null) {
            int i = 0;
            for (Connection c : connections) {
                if (c == null) continue;
                String k = "behaviorTree.connection." + i + ".";
                properties.setProperty(k + "id", safe(c.id));
                properties.setProperty(k + "from", safe(c.fromNodeId));
                properties.setProperty(k + "to", safe(c.toNodeId));
                i++;
            }
        }
    }

    /**
     * Loads the behavior tree from .tpgproject Properties.
     */
    public static void readFromProperties(
            Properties properties,
            List<Node> nodes,
            List<Connection> connections) {

        if (properties == null || nodes == null || connections == null) return;

        nodes.clear();
        connections.clear();

        int nodeCount = parseInt(properties.getProperty(PROP_NODE_COUNT, "0"), 0);
        for (int i = 0; i < nodeCount; i++) {
            String k = "behaviorTree.node." + i + ".";
            String id = properties.getProperty(k + "id", UUID.randomUUID().toString());
            NodeType type = parseNodeType(properties.getProperty(k + "type", NodeType.ACTION.name()));
            String name = properties.getProperty(k + "name", type.getDisplayName());

            Node n = new Node(id, type, name);
            n.profileName = properties.getProperty(k + "profile", "");
            n.comment = properties.getProperty(k + "comment", "");
            readInlineSettings(properties, k, n);
            n.x = parseInt(properties.getProperty(k + "x", "100"), 100);
            n.y = parseInt(properties.getProperty(k + "y", "100"), 100);
            n.width = Math.max(130, parseInt(properties.getProperty(k + "width", "190"), 190));
            n.height = Math.max(65, parseInt(properties.getProperty(k + "height", "86"), 86));
            nodes.add(n);
        }

        Set<String> validIds = new HashSet<>();
        for (Node n : nodes) validIds.add(n.id);

        int edgeCount = parseInt(properties.getProperty(PROP_EDGE_COUNT, "0"), 0);
        for (int i = 0; i < edgeCount; i++) {
            String k = "behaviorTree.connection." + i + ".";
            String id = properties.getProperty(k + "id", UUID.randomUUID().toString());
            String from = properties.getProperty(k + "from", "");
            String to = properties.getProperty(k + "to", "");
            if (!validIds.contains(from) || !validIds.contains(to)) continue;
            connections.add(new Connection(id, from, to));
        }
    }

    /** Writes all reusable named behavior trees into the same project Properties stream. */
    public static void writeProfilesToProperties(
            Properties properties,
            Map<String, TreeProfile> profiles) {
        if (properties == null) return;
        properties.setProperty("behaviorTrees.version", "1");
        int count = 0;
        if (profiles != null) {
            for (Map.Entry<String, TreeProfile> entry : profiles.entrySet()) {
                TreeProfile profile = entry.getValue();
                if (profile == null) continue;
                String name = profile.name == null ? entry.getKey() : profile.name.trim();
                if (name == null || name.isEmpty()) continue;
                String prefix = "behaviorTrees.profile." + count + ".";
                properties.setProperty(prefix + "name", safe(name));
                properties.setProperty(prefix + "nodeCount", Integer.toString(profile.nodes.size()));
                for (int i = 0; i < profile.nodes.size(); i++) {
                    Node n = profile.nodes.get(i);
                    if (n == null) continue;
                    String k = prefix + "node." + i + ".";
                    properties.setProperty(k + "id", safe(n.id));
                    properties.setProperty(k + "type", n.type == null ? NodeType.ACTION.name() : n.type.name());
                    properties.setProperty(k + "name", safe(n.name));
                    properties.setProperty(k + "profile", safe(n.profileName));
                    properties.setProperty(k + "comment", safe(n.comment));
                    writeInlineSettings(properties, k, n);
                    properties.setProperty(k + "x", Integer.toString(n.x));
                    properties.setProperty(k + "y", Integer.toString(n.y));
                    properties.setProperty(k + "width", Integer.toString(n.width));
                    properties.setProperty(k + "height", Integer.toString(n.height));
                }
                properties.setProperty(prefix + "connectionCount", Integer.toString(profile.connections.size()));
                for (int i = 0; i < profile.connections.size(); i++) {
                    Connection c = profile.connections.get(i);
                    if (c == null) continue;
                    String k = prefix + "connection." + i + ".";
                    properties.setProperty(k + "id", safe(c.id));
                    properties.setProperty(k + "from", safe(c.fromNodeId));
                    properties.setProperty(k + "to", safe(c.toNodeId));
                }
                count++;
            }
        }
        properties.setProperty("behaviorTrees.count", Integer.toString(count));
    }

    /** Loads all reusable named behavior trees from a .tpgproject Properties stream. */
    public static void readProfilesFromProperties(
            Properties properties,
            Map<String, TreeProfile> profiles) {
        if (properties == null || profiles == null) return;
        profiles.clear();
        int count = parseInt(properties.getProperty("behaviorTrees.count", "0"), 0);
        for (int p = 0; p < count; p++) {
            String prefix = "behaviorTrees.profile." + p + ".";
            String name = properties.getProperty(prefix + "name", "").trim();
            if (name.isEmpty()) continue;
            TreeProfile profile = new TreeProfile(name);
            int nodeCount = parseInt(properties.getProperty(prefix + "nodeCount", "0"), 0);
            for (int i = 0; i < nodeCount; i++) {
                String k = prefix + "node." + i + ".";
                NodeType type = parseNodeType(properties.getProperty(k + "type", NodeType.ACTION.name()));
                Node n = new Node(properties.getProperty(k + "id", UUID.randomUUID().toString()),
                        type, properties.getProperty(k + "name", type.getDisplayName()));
                n.profileName = properties.getProperty(k + "profile", "");
                n.comment = properties.getProperty(k + "comment", "");
                readInlineSettings(properties, k, n);
                n.x = parseInt(properties.getProperty(k + "x", "100"), 100);
                n.y = parseInt(properties.getProperty(k + "y", "100"), 100);
                n.width = Math.max(130, parseInt(properties.getProperty(k + "width", "190"), 190));
                n.height = Math.max(65, parseInt(properties.getProperty(k + "height", "86"), 86));
                profile.nodes.add(n);
            }
            Set<String> validIds = new HashSet<>();
            for (Node n : profile.nodes) validIds.add(n.id);
            int edgeCount = parseInt(properties.getProperty(prefix + "connectionCount", "0"), 0);
            for (int i = 0; i < edgeCount; i++) {
                String k = prefix + "connection." + i + ".";
                String from = properties.getProperty(k + "from", "");
                String to = properties.getProperty(k + "to", "");
                if (validIds.contains(from) && validIds.contains(to)) {
                    profile.connections.add(new Connection(
                            properties.getProperty(k + "id", UUID.randomUUID().toString()), from, to));
                }
            }
            profiles.put(name, profile);
        }
    }

    private static NodeType parseNodeType(String value) {
        try {
            return NodeType.valueOf(value);
        } catch (Exception ex) {
            return NodeType.ACTION;
        }
    }

    private static float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value); }
        catch (Exception ex) { return fallback; }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class GraphPanel extends JPanel
            implements MouseListener, MouseMotionListener, MouseWheelListener {

        private final EditorState state;
        private final List<Node> nodes;
        private final List<Connection> connections;
        private final Map<String, BehaviorEditor.BehaviorProfile> behaviorProfiles;
        private final Map<String, ActionBuilder.ActionProfile> actionProfiles;
        private final List<String> objectNames;
        private final List<String> itemObjectNames;
        private final List<AudioManager.AudioEntry> audioLibrary;

        private Point lastMouse;
        private Node draggingNode;
        private boolean draggingCanvas;

        GraphPanel(
                EditorState state,
                List<Node> nodes,
                List<Connection> connections,
                Map<String, BehaviorEditor.BehaviorProfile> behaviorProfiles,
                Map<String, ActionBuilder.ActionProfile> actionProfiles,
                List<String> objectNames,
                List<String> itemObjectNames,
                List<AudioManager.AudioEntry> audioLibrary) {

            this.state = state;
            this.nodes = nodes;
            this.connections = connections;
            this.behaviorProfiles = behaviorProfiles == null
                    ? Collections.emptyMap() : behaviorProfiles;
            this.actionProfiles = actionProfiles == null
                    ? Collections.emptyMap() : actionProfiles;
            this.objectNames = objectNames == null
                    ? Collections.emptyList() : objectNames;
            this.itemObjectNames = itemObjectNames == null
                    ? Collections.emptyList() : itemObjectNames;
            this.audioLibrary = audioLibrary == null
                    ? Collections.emptyList() : audioLibrary;

            setPreferredSize(new Dimension(1800, 1200));
            setBackground(new Color(31, 34, 39));
            setOpaque(true);
            setFocusable(true);

            addMouseListener(this);
            addMouseMotionListener(this);
            addMouseWheelListener(this);

            // Refresh live Time-node countdowns while the editor is open.
            new javax.swing.Timer(50, e -> repaint()).start();

            setTransferHandler(new TransferHandler("text") {
                @Override
                public boolean canImport(TransferHandler.TransferSupport support) {
                    return support.isDataFlavorSupported(
                            java.awt.datatransfer.DataFlavor.stringFlavor);
                }

                @Override
                public boolean importData(TransferHandler.TransferSupport support) {
                    if (!canImport(support)) return false;
                    try {
                        String typeName = (String) support.getTransferable()
                                .getTransferData(
                                        java.awt.datatransfer.DataFlavor.stringFlavor);
                        NodeType type = NodeType.valueOf(typeName);
                        Point p = support.getDropLocation().getDropPoint();
                        Point world = screenToWorld(p);
                        Node n = addNode(nodes, type, defaultNodeName(type),
                                world.x, world.y);
                        state.selected = n;
                        state.dirty = true;
                        repaint();
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                }
            });
        }

        private String defaultNodeName(NodeType type) {
            switch (type) {
                case ROOT:
                    return "Root";
                case SEQUENCE:
                    return "Sequence";
                case SELECTOR:
                    return "Selector";
                case RANDOM:
                    return "Random";
                case BEHAVIOR:
                    return behaviorProfiles.isEmpty()
                            ? "Behavior"
                            : "Behavior: " + behaviorProfiles.keySet().iterator().next();
                case ACTION:
                    return actionProfiles.isEmpty()
                            ? "Action"
                            : "Action: " + actionProfiles.keySet().iterator().next();
                case PLAYER:
                    return "Player";
                case COLLISION:
                    return "Collision";
                case OBJECT_DROPDOWN:
                    return objectNames.isEmpty() ? "Object" : "Object: " + objectNames.get(0);
                case ON_DEATH:
                    return "OnDeath";
                case DESTROY:
                    return "Destroy";
                case RESPAWN:
                    return "Respawn";
                case SOUND:
                    // Keep the node title compact; the selected audio asset is
                    // shown in the node properties instead of being baked into
                    // the node title.
                    return "Sound";
                case ON_CLICK:
                    return "OnClick";
                case ADD_ITEM:
                    return itemObjectNames.isEmpty() ? "AddItem" : "AddItem: " + itemObjectNames.get(0);
                case COMMENT:
                    return "Comment";
                default:
                    return type.getDisplayName();
            }
        }

        private Point screenToWorld(Point p) {
            int x = (int) ((p.x - getWidth() / 2.0) / state.zoom + state.panStartX);
            int y = (int) ((p.y - getHeight() / 2.0) / state.zoom + state.panStartY);
            return new Point(x, y);
        }

        private Point worldToScreen(Point p) {
            int x = (int) ((p.x - state.panStartX) * state.zoom + getWidth() / 2.0);
            int y = (int) ((p.y - state.panStartY) * state.zoom + getHeight() / 2.0);
            return new Point(x, y);
        }

        private Rectangle nodeBounds(Node n) {
            Point p = worldToScreen(new Point(n.x, n.y));
            return new Rectangle(
                    p.x,
                    p.y,
                    (int) (n.width * state.zoom),
                    (int) (n.height * state.zoom));
        }

        private Point outputSocket(Node n) {
            Rectangle r = nodeBounds(n);
            return new Point(r.x + r.width, r.y + r.height / 2);
        }

        private Point inputSocket(Node n) {
            Rectangle r = nodeBounds(n);
            return new Point(r.x, r.y + r.height / 2);
        }

        private Node nodeAt(Point p) {
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Node n = nodes.get(i);
                if (nodeBounds(n).contains(p)) return n;
            }
            return null;
        }

        private boolean near(Point a, Point b, int distance) {
            return a.distance(b) <= distance;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                drawGrid(g2);
                drawConnections(g2);
                if (state.connectionSource != null && state.mouse != null) {
                    drawTemporaryConnection(g2);
                }
                for (Node n : nodes) drawNode(g2, n);
            } finally {
                g2.dispose();
            }
        }

        private void drawGrid(Graphics2D g2) {
            int spacing = Math.max(15, (int) (40 * state.zoom));
            g2.setColor(new Color(45, 48, 54));
            for (int x = 0; x < getWidth(); x += spacing) {
                g2.drawLine(x, 0, x, getHeight());
            }
            for (int y = 0; y < getHeight(); y += spacing) {
                g2.drawLine(0, y, getWidth(), y);
            }
        }

        private void drawConnections(Graphics2D g2) {
            g2.setStroke(new BasicStroke(
                    Math.max(1.5f, (float) (2.5 * state.zoom)),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));

            for (Connection c : connections) {
                Node from = findNode(nodes, c.fromNodeId);
                Node to = findNode(nodes, c.toNodeId);
                if (from == null || to == null) continue;

                Point a = outputSocket(from);
                Point b = inputSocket(to);
                drawBezier(g2, a, b, new Color(110, 185, 255));
            }
        }

        private void drawTemporaryConnection(Graphics2D g2) {
            Node from = state.connectionSource;
            Point a = outputSocket(from);
            Point b = state.mouse;
            g2.setStroke(new BasicStroke(
                    2f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    0f,
                    new float[]{7f, 7f},
                    0f));
            drawBezier(g2, a, b, new Color(235, 205, 100));
        }

        private void drawBezier(Graphics2D g2, Point a, Point b, Color color) {
            int dx = Math.max(45, Math.abs(b.x - a.x) / 2);
            java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
            path.moveTo(a.x, a.y);
            path.curveTo(
                    a.x + dx, a.y,
                    b.x - dx, b.y,
                    b.x, b.y);
            g2.setColor(color);
            g2.draw(path);
        }

        private void drawNode(Graphics2D g2, Node n) {
            Rectangle r = nodeBounds(n);

            Color fill;
            switch (n.type) {
                case ROOT:
                    fill = new Color(74, 105, 74);
                    break;
                case SEQUENCE:
                    fill = new Color(63, 92, 125);
                    break;
                case SELECTOR:
                    fill = new Color(112, 82, 130);
                    break;
                case RANDOM:
                    fill = new Color(51, 0, 51);
                    break;
                case BEHAVIOR:
                    fill = new Color(66, 118, 104);
                    break;
                case ACTION:
                    fill = new Color(128, 94, 61);
                    break;
                case PLAYER:
                    fill = new Color(64, 112, 155);
                    break;
                case COLLISION:
                    fill = new Color(145, 72, 72);
                    break;
                case RADIUS:
                    fill = new Color(210, 105, 30);
                    break;
                case TIME:
                    fill = new Color(150, 113, 48);
                    break;
                case VISIBILITY:
                    // Visibility is intentionally neutral grey so it reads as a
                    // state/visibility operation rather than a behavior type.
                    fill = new Color(96, 99, 105);
                    break;
                case ON_DEATH:
                    fill = new Color(20, 20, 20);
                    break;
                case DESTROY:
                    fill = new Color(20, 20, 20);
                    break;
                case RESPAWN:
                    fill = new Color(62, 139, 86);
                    break;
                case SOUND:
                    fill = new Color(199, 21, 133);
                    break;
                case ON_CLICK:
                case ADD_ITEM:
                    fill = new Color(61, 219, 124);
                    break;
                     case ACTIVATOR:
                    fill = new Color(139, 0, 0);
                    break;
                case OBJECT_DROPDOWN:
                    fill = new Color(72, 126, 145);
                    break;
                default:
                    fill = new Color(82, 85, 91);
                    break;
            }

            if (n == state.selected) {
                g2.setColor(new Color(245, 205, 85));
            } else {
                g2.setColor(new Color(12, 14, 17));
            }
            g2.fillRoundRect(r.x - 2, r.y - 2, r.width + 4, r.height + 4, 14, 14);

            g2.setColor(fill);
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);

            g2.setColor(new Color(255, 255, 255, 210));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD,
                    Math.max(10f, (float) (11 * state.zoom))));
            String title = n.name == null ? "" : n.name;
            if (n.type == NodeType.SOUND && title.startsWith("Sound: ")) {
                title = "Sound";
            }
            // Keep titles inside the node even when a user gives another node
            // type a long name. Sound nodes are handled above specifically.
            int maxTitleChars = Math.max(10, (int) (r.width / Math.max(6.0, 7.2 * state.zoom)));
            if (title.length() > maxTitleChars) {
                title = title.substring(0, Math.max(1, maxTitleChars - 3)) + "...";
            }
            g2.drawString(title, r.x + 12, r.y + 20);

            g2.setFont(g2.getFont().deriveFont(Font.PLAIN,
                    Math.max(8f, (float) (9 * state.zoom))));
            g2.setColor(new Color(225, 230, 235, 220));
            g2.drawString(n.type.getDisplayName(), r.x + 12, r.y + 37);

            String profile = n.profileName == null ? "" : n.profileName;
            if (!profile.isEmpty()) {
                String shown = profile.length() > 23 ? profile.substring(0, 20) + "..." : profile;
                g2.drawString(shown, r.x + 12, r.y + 53);
            } else if (n.type == NodeType.COMMENT && n.comment != null) {
                String shown = n.comment.length() > 23 ? n.comment.substring(0, 20) + "..." : n.comment;
                g2.drawString(shown, r.x + 12, r.y + 53);
            }

            if (n.type == NodeType.TIME) {
                float remaining = getRuntimeTimeRemaining(n.id);
                String countdown = remaining >= 0.0f
                        ? String.format(Locale.ROOT, "%.1f s remaining", remaining)
                        : String.format(Locale.ROOT, "Delay: %.1f s", Math.max(0.0f, n.timeSeconds));
                g2.setFont(g2.getFont().deriveFont(Font.BOLD, Math.max(8f, (float) (9 * state.zoom))));
                g2.setColor(new Color(255, 235, 145, 235));
                g2.drawString(countdown, r.x + 12, r.y + Math.max(70, r.height - 8));

                if (remaining >= 0.0f && n.timeSeconds > 0.0f) {
                    float progress = Math.max(0.0f, Math.min(1.0f, 1.0f - remaining / n.timeSeconds));
                    int barX = r.x + 12;
                    int barY = r.y + r.height - 5;
                    int barW = Math.max(20, r.width - 24);
                    g2.setColor(new Color(20, 22, 25, 180));
                    g2.fillRoundRect(barX, barY, barW, 3, 3, 3);
                    g2.setColor(new Color(245, 205, 85, 235));
                    g2.fillRoundRect(barX, barY, (int) (barW * progress), 3, 3, 3);
                }
            }

            // Output socket
            g2.setColor(new Color(230, 230, 230));
            Point out = outputSocket(n);
            g2.fillOval(out.x - 6, out.y - 6, 12, 12);

            // Input socket
            if (n.acceptsInput()) {
                Point in = inputSocket(n);
                g2.setColor(new Color(30, 30, 35));
                g2.fillOval(in.x - 6, in.y - 6, 12, 12);
                g2.setColor(new Color(230, 230, 230));
                g2.drawOval(in.x - 6, in.y - 6, 12, 12);
            }
        }

        private void showNodeEditor(Node node) {
            if (node == null) return;

            JTextField name = new JTextField(node.name == null ? "" : node.name, 20);
            name.setPreferredSize(new Dimension(300, 24));
            name.setMinimumSize(new Dimension(180, 24));
            name.setMaximumSize(new Dimension(300, 24));
            JTextArea comment = new JTextArea(node.comment == null ? "" : node.comment, 2, 16);
            comment.setPreferredSize(new Dimension(300, 48));
            comment.setLineWrap(true);
            comment.setWrapStyleWord(true);

            JComboBox<String> profile = new JComboBox<>();
            profile.setEditable(false);
            profile.addItem("");
            profile.setPreferredSize(new Dimension(300, 24));
            profile.setMinimumSize(new Dimension(180, 24));
            profile.setMaximumSize(new Dimension(300, 24));

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

            JCheckBox setPlayerHP = new JCheckBox("Player HP");
            JComboBox<String> playerHPOperation = createUIActionOperationCombo();
            JSpinner playerHP = new JSpinner(new SpinnerNumberModel(10, 0, 1000000, 1));
            JCheckBox setEnemyHealth = new JCheckBox("Enemy Health Bar");
            JComboBox<String> enemyHealthOperation = createUIActionOperationCombo();
            JSpinner enemyHealth = new JSpinner(new SpinnerNumberModel(10, 0, 1000000, 1));
            JCheckBox setScore = new JCheckBox("Total Score");
            JComboBox<String> scoreOperation = createUIActionOperationCombo();
            JSpinner totalScore = new JSpinner(new SpinnerNumberModel(10, 0, 1000000000, 1));
            JSpinner radiusSpinner = new JSpinner(new SpinnerNumberModel((double) Math.max(0.01f, node.radius), 0.01, 100000.0, 0.1));
            JSpinner timeSpinner = new JSpinner(new SpinnerNumberModel((double) Math.max(0.0f, node.timeSeconds), 0.0, 100000.0, 0.1));
            JSpinner activatorDelaySpinner = new JSpinner(new SpinnerNumberModel((double) Math.max(0.0f, node.activatorDelaySeconds), 0.0, 100000.0, 0.1));
            JSpinner respawnHealthSpinner = new JSpinner(new SpinnerNumberModel(Math.max(1, node.respawnHealth), 1, 1000000, 1));
            JCheckBox setPlayerScale = new JCheckBox("Set Scale");
            JSpinner playerScale = new JSpinner(new SpinnerNumberModel((double) Math.max(0.01f, Math.min(100.0f, node.inlinePlayerScale)), 0.01, 100.0, 0.01));
            JCheckBox hideCheck = new JCheckBox("Hide?", node.visibilityHidden);
            JCheckBox showCheck = new JCheckBox("Show?", !node.visibilityHidden);
            DefaultListModel<String> visibilityTargetModel = new DefaultListModel<>();
            JList<String> visibilityTargetList = new JList<>(visibilityTargetModel);
            visibilityTargetList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            visibilityTargetList.setVisibleRowCount(8);
            visibilityTargetList.setPreferredSize(new Dimension(300, 150));
            visibilityTargetList.setToolTipText("Ctrl-click or Shift-click to select multiple objects.");

            DefaultListModel<String> activatorTargetModel = new DefaultListModel<>();
            JList<String> activatorTargetList = new JList<>(activatorTargetModel);
            activatorTargetList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            activatorTargetList.setVisibleRowCount(8);
            activatorTargetList.setPreferredSize(new Dimension(300, 150));
            activatorTargetList.setToolTipText("Ctrl-click or Shift-click to select multiple objects to activate.");

            // Hide/Show are deliberately presented as two separate checkmarks, but
            // they represent mutually exclusive visibility commands.  This keeps
            // the behavior tree unambiguous while remaining compatible with the
            // existing runtime field (visibilityHidden).
            hideCheck.addActionListener(e -> {
                if (hideCheck.isSelected()) showCheck.setSelected(false);
                else if (!showCheck.isSelected()) hideCheck.setSelected(true);
            });
            showCheck.addActionListener(e -> {
                if (showCheck.isSelected()) hideCheck.setSelected(false);
                else if (!hideCheck.isSelected()) showCheck.setSelected(true);
            });

            if (node.type == NodeType.BEHAVIOR) {
                for (String s : behaviorProfiles.keySet()) profile.addItem(s);
                profile.setSelectedItem(node.profileName == null ? "" : node.profileName);

                BehaviorEditor.BehaviorProfile bp = behaviorProfiles.get(node.profileName);
                if (node.inlineBehaviorConfigured) {
                    isEnemy.setSelected(node.inlineIsEnemy); chase.setSelected(node.inlineChase);
                    isNPC.setSelected(node.inlineIsNPC); gameOver.setSelected(node.inlineGameOver);
                    patrol.setSelected(node.inlinePatrol); triggerZone.setSelected(node.inlineTriggerZone);
                    collision.setSelected(node.inlineCollision); physics.setSelected(node.inlinePhysics);
                    reflective.setSelected(node.inlineReflective); autoRotate.setSelected(node.inlineAutoRotate);
                } else if (bp != null) {
                    isEnemy.setSelected(bp.isEnemy); chase.setSelected(bp.chase); isNPC.setSelected(bp.isNPC);
                    gameOver.setSelected(bp.gameOver); patrol.setSelected(bp.patrol); triggerZone.setSelected(bp.triggerZone);
                    collision.setSelected(bp.collision); physics.setSelected(bp.physics);
                    reflective.setSelected(bp.reflective); autoRotate.setSelected(bp.autoRotate);
                }
            } else if (node.type == NodeType.ACTION) {
                // Action nodes no longer depend on ActionBuilder object behavior.
                // They are dedicated to setting numeric UI/game-state values.
                if (node.inlineUIActionConfigured) {
                    setPlayerHP.setSelected(node.inlineSetPlayerHP);
                    playerHPOperation.setSelectedItem(uiActionOperationSymbol(node.inlinePlayerHPOperation));
                    playerHP.setValue(Math.max(0, node.inlinePlayerHP));
                    setEnemyHealth.setSelected(node.inlineSetEnemyHealth);
                    enemyHealthOperation.setSelectedItem(uiActionOperationSymbol(node.inlineEnemyHealthOperation));
                    enemyHealth.setValue(Math.max(0, node.inlineEnemyHealth));
                    setScore.setSelected(node.inlineSetScore);
                    scoreOperation.setSelectedItem(uiActionOperationSymbol(node.inlineScoreOperation));
                    totalScore.setValue(Math.max(0, node.inlineScore));
                }
            } else if (node.type == NodeType.SOUND) {
                profile.removeAllItems();
                profile.addItem("");
                for (AudioManager.AudioEntry entry : audioLibrary) {
                    if (entry != null && entry.name != null && !entry.name.trim().isEmpty()) {
                        profile.addItem(entry.name);
                    }
                }
                profile.setSelectedItem(node.profileName == null ? "" : node.profileName);
            } else if (node.type == NodeType.OBJECT_DROPDOWN) {
                profile.removeAllItems();
                profile.addItem("");
                for (String objectName : objectNames) profile.addItem(objectName);
                profile.setSelectedItem(node.profileName == null ? "" : node.profileName);
            } else if (node.type == NodeType.ADD_ITEM) {
                profile.removeAllItems();
                profile.addItem("");
                for (String itemObjectName : itemObjectNames) profile.addItem(itemObjectName);
                profile.setSelectedItem(node.profileName == null ? "" : node.profileName);
                profile.setEnabled(!itemObjectNames.isEmpty());
            } else if (node.type == NodeType.VISIBILITY) {
                profile.setEnabled(false);
                visibilityTargetModel.addElement("Owner");
                visibilityTargetModel.addElement("Player");
                visibilityTargetModel.addElement("Any Object");
                for (String objectName : objectNames) {
                    if (objectName != null && !objectName.trim().isEmpty()) {
                        visibilityTargetModel.addElement(objectName.trim());
                    }
                }
                List<String> savedTargets = new ArrayList<>(node.visibilityTargets);
                if (savedTargets.isEmpty() && node.profileName != null && !node.profileName.trim().isEmpty()) {
                    savedTargets.add(node.profileName.trim());
                }
                if (savedTargets.isEmpty()) savedTargets.add("Owner");
                ArrayList<Integer> selectedRows = new ArrayList<>();
                for (String saved : savedTargets) {
                    for (int i = 0; i < visibilityTargetModel.size(); i++) {
                        if (saved.equalsIgnoreCase(visibilityTargetModel.getElementAt(i))) {
                            selectedRows.add(i);
                            break;
                        }
                    }
                }
                if (!selectedRows.isEmpty()) {
                    int[] rows = new int[selectedRows.size()];
                    for (int i = 0; i < selectedRows.size(); i++) rows[i] = selectedRows.get(i);
                    visibilityTargetList.setSelectedIndices(rows);
                } else {
                    visibilityTargetList.setSelectedIndex(0);
                }
            } else if (node.type == NodeType.ACTIVATOR) {
                profile.setEnabled(false);
                for (String objectName : objectNames) {
                    if (objectName != null && !objectName.trim().isEmpty()) {
                        activatorTargetModel.addElement(objectName.trim());
                    }
                }
                List<String> savedTargets = new ArrayList<>(node.activatorTargets);
                if (savedTargets.isEmpty() && node.profileName != null && !node.profileName.trim().isEmpty()) {
                    savedTargets.add(node.profileName.trim());
                }
                ArrayList<Integer> selectedRows = new ArrayList<>();
                for (String saved : savedTargets) {
                    for (int i = 0; i < activatorTargetModel.size(); i++) {
                        if (saved.equalsIgnoreCase(activatorTargetModel.getElementAt(i))) {
                            selectedRows.add(i);
                            break;
                        }
                    }
                }
                if (!selectedRows.isEmpty()) {
                    int[] rows = new int[selectedRows.size()];
                    for (int i = 0; i < selectedRows.size(); i++) rows[i] = selectedRows.get(i);
                    activatorTargetList.setSelectedIndices(rows);
                }
            } else if (node.type == NodeType.COLLISION || node.type == NodeType.RADIUS) {
                profile.removeAllItems();
                profile.addItem("Player");
                profile.addItem("Any Object");
                for (String objectName : objectNames) profile.addItem(objectName);
                profile.setSelectedItem(node.profileName == null || node.profileName.trim().isEmpty()
                        ? "Any Object" : node.profileName);
            } else if (node.type == NodeType.ON_DEATH || node.type == NodeType.DESTROY || node.type == NodeType.RESPAWN) {
                profile.removeAllItems();
                profile.addItem("Owner");
                for (String objectName : objectNames) profile.addItem(objectName);
                profile.setSelectedItem(node.profileName == null || node.profileName.trim().isEmpty()
                        ? "Owner" : node.profileName);
            } else {
                profile.setEnabled(false);
            }

            // Changing the optional profile immediately copies that profile's
            // values into the miniature node editor. After this point the node
            // owns its own values and does not depend on the external editor.
            if (node.type == NodeType.BEHAVIOR) {
                profile.addActionListener(e -> {
                    Object selected = profile.getSelectedItem();
                    if (selected == null) return;
                    BehaviorEditor.BehaviorProfile bp = behaviorProfiles.get(selected.toString());
                    if (bp == null) return;
                    isEnemy.setSelected(bp.isEnemy);
                    chase.setSelected(bp.chase);
                    isNPC.setSelected(bp.isNPC);
                    gameOver.setSelected(bp.gameOver);
                    patrol.setSelected(bp.patrol);
                    triggerZone.setSelected(bp.triggerZone);
                    collision.setSelected(bp.collision);
                    physics.setSelected(bp.physics);
                    reflective.setSelected(bp.reflective);
                    autoRotate.setSelected(bp.autoRotate);
                });
            }

            JPanel fields = new JPanel();
            fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
            fields.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            fields.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel nodeNameLabel = new JLabel("Node name:");
            nodeNameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            fields.add(nodeNameLabel);
            fields.add(name);
            fields.add(Box.createVerticalStrut(6));

            if (node.type == NodeType.BEHAVIOR) {
                JLabel behaviorLabel = new JLabel("Behavior Profile:");
                behaviorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(behaviorLabel);
                fields.add(profile);
                JPanel checks = new JPanel(new GridLayout(0, 2, 4, 2));
                checks.setBorder(BorderFactory.createTitledBorder("Behavior Settings (this node)"));
                checks.setAlignmentX(Component.CENTER_ALIGNMENT);
                checks.setMaximumSize(new Dimension(320, 190));
                checks.add(isEnemy); checks.add(chase); checks.add(isNPC); checks.add(gameOver);
                checks.add(patrol); checks.add(triggerZone); checks.add(collision); checks.add(physics);
                checks.add(reflective); checks.add(autoRotate);
                fields.add(checks);
                fields.add(new JLabel("These settings are stored on this node and do not require opening Behavior Editor."));
            } else if (node.type == NodeType.ACTION) {
                JPanel actionPanel = new JPanel();
                actionPanel.setLayout(new BoxLayout(actionPanel, BoxLayout.Y_AXIS));
                actionPanel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createTitledBorder("UI Value Settings"),
                        BorderFactory.createEmptyBorder(2, 4, 2, 4)));
                actionPanel.setMaximumSize(new Dimension(320, 100));
                actionPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
                actionPanel.add(row(setPlayerHP, playerHPOperation, playerHP));
                actionPanel.add(row(setEnemyHealth, enemyHealthOperation, enemyHealth));
                actionPanel.add(row(setScore, scoreOperation, totalScore));
                fields.add(actionPanel);
            } else if (node.type == NodeType.PLAYER) {
                JPanel playerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
                playerPanel.setBorder(BorderFactory.createTitledBorder("Player Settings"));
                playerPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
                setPlayerScale.setSelected(node.inlineSetPlayerScale);
                playerScale.setPreferredSize(new Dimension(100, 22));
                playerPanel.add(setPlayerScale);
                playerPanel.add(new JLabel("Scale:"));
                playerPanel.add(playerScale);
                fields.add(playerPanel);
                fields.add(new JLabel("Changes the Player model size when this node executes."));
            } else if (node.type == NodeType.RADIUS) {
                JLabel radiusTargetLabel = new JLabel("Target / Object:");
                radiusTargetLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(radiusTargetLabel);
                fields.add(profile);

                // Keep the Radius control as an explicit component instead of
                // routing it through the generic row() overloads.  This makes
                // the numeric input unambiguous and prevents the Radius field
                // from disappearing when other node-editor rows are changed.
                JPanel radiusPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
                radiusPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
                radiusPanel.setMaximumSize(new Dimension(300, 28));
                JLabel radiusLabel = new JLabel("Radius:");
                radiusLabel.setPreferredSize(new Dimension(90, 22));
                radiusSpinner.setPreferredSize(new Dimension(100, 22));
                radiusPanel.add(radiusLabel);
                radiusPanel.add(radiusSpinner);
                fields.add(radiusPanel);
            } else if (node.type == NodeType.TIME) {
                JLabel delayLabel = new JLabel("Delay:");
                delayLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(delayLabel);
                JPanel timePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
                timePanel.setAlignmentX(Component.CENTER_ALIGNMENT);
                timePanel.setMaximumSize(new Dimension(300, 28));
                JLabel timeLabel = new JLabel("Seconds:");
                timeLabel.setPreferredSize(new Dimension(90, 22));
                timeSpinner.setPreferredSize(new Dimension(100, 22));
                timePanel.add(timeLabel);
                timePanel.add(timeSpinner);
                fields.add(timePanel);
            } else if (node.type == NodeType.RANDOM) {
                fields.add(new JLabel("Random branch selector:"));
                fields.add(new JLabel("<html>Direct Sequence children are preferred as complete<br>"
                        + "random outcomes. Each candidate is used once before<br>"
                        + "the selection bag is reshuffled. Other child types are<br>"
                        + "supported when no Sequence children are connected.</html>"));
            } else if (node.type == NodeType.ON_CLICK) {
                fields.add(new JLabel("Item use event:"));
                fields.add(new JLabel("<html>This node succeeds only when this item is used from the player inventory.<br>"
                        + "Connect its children to define the item's effect.</html>"));
            } else if (node.type == NodeType.ADD_ITEM) {
                // AddItem gets its own compact content width so its long descriptive
                // text cannot determine the width of the entire node editor.
                // This keeps the AddItem popup centered without affecting any
                // of the other node types.
                JLabel addItemLabel = new JLabel("Item object to add to inventory:");
                addItemLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(addItemLabel);

                profile.setPreferredSize(new Dimension(240, 24));
                profile.setMinimumSize(new Dimension(200, 24));
                profile.setMaximumSize(new Dimension(240, 24));
                profile.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(profile);

                JLabel addItemInfo;
                if (itemObjectNames.isEmpty()) {
                    addItemInfo = new JLabel("<html><div style='text-align:center;'>No Item-enabled objects are available.<br>"
                            + "Enable <b>Treat this object as an Item</b> in the object's Item settings first.</div></html>");
                } else {
                    addItemInfo = new JLabel("<html><div style='text-align:center;'>Only objects with Item enabled appear here.<br>"
                            + "The selected object's Item settings supply its ID, quantity, stack size, icon, and OnClick behavior.</div></html>");
                }
                addItemInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
                addItemInfo.setPreferredSize(new Dimension(280, 52));
                addItemInfo.setMaximumSize(new Dimension(280, 52));
                fields.add(addItemInfo);
            } else if (node.type == NodeType.ACTIVATOR) {
                JLabel activatorTargetLabel = new JLabel("Object(s) to Activate:");
                activatorTargetLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(activatorTargetLabel);
                JScrollPane activatorScroll = new JScrollPane(activatorTargetList);
                activatorScroll.setPreferredSize(new Dimension(310, 150));
                activatorScroll.setMaximumSize(new Dimension(310, 150));
                activatorScroll.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(activatorScroll);
                JPanel activatorDelayPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
                activatorDelayPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
                activatorDelayPanel.setMaximumSize(new Dimension(300, 28));
                JLabel activatorDelayLabel = new JLabel("Delay (seconds):");
                activatorDelayLabel.setPreferredSize(new Dimension(125, 22));
                activatorDelaySpinner.setPreferredSize(new Dimension(100, 22));
                activatorDelayPanel.add(activatorDelayLabel);
                activatorDelayPanel.add(activatorDelaySpinner);
                fields.add(activatorDelayPanel);
                JLabel activatorInfo = new JLabel("<html><div style='text-align:center;'>"
                        + "Clears <b>Disable when hidden</b> on the selected objects.<br>"
                        + "The targets can remain hidden until another Visibility node shows them.</div></html>");
                activatorInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
                activatorInfo.setPreferredSize(new Dimension(300, 46));
                activatorInfo.setMaximumSize(new Dimension(300, 46));
                fields.add(activatorInfo);
            } else if (node.type == NodeType.VISIBILITY) {
                JLabel visibilityTargetLabel = new JLabel("Target / Object(s):");
                visibilityTargetLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(visibilityTargetLabel);
                JScrollPane targetScroll = new JScrollPane(visibilityTargetList);
                targetScroll.setPreferredSize(new Dimension(310, 115));
                targetScroll.setMaximumSize(new Dimension(310, 115));
                targetScroll.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(targetScroll);

                JPanel visibilityPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 2));
                visibilityPanel.setBorder(BorderFactory.createTitledBorder("Visibility Action"));
                visibilityPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
                hideCheck.setMargin(new Insets(0, 0, 0, 0));
                showCheck.setMargin(new Insets(0, 0, 0, 0));
                visibilityPanel.add(hideCheck);
                visibilityPanel.add(showCheck);
                fields.add(visibilityPanel);

                JLabel selectionInfo = new JLabel("<html><div style='text-align:center;'>Ctrl-click or Shift-click to select multiple targets.</div></html>");
                selectionInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
                selectionInfo.setPreferredSize(new Dimension(300, 28));
                selectionInfo.setMaximumSize(new Dimension(300, 28));
                fields.add(selectionInfo);

                JLabel visibilityInfo = new JLabel(
                        "<html><div style='text-align:center;'>"
                        + "Choose <b>Hide?</b> for an object already visible in the scene, "
                        + "or <b>Show?</b> for an object that starts hidden."
                        + "</div></html>");
                visibilityInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
                visibilityInfo.setPreferredSize(new Dimension(300, 40));
                visibilityInfo.setMaximumSize(new Dimension(300, 40));
                fields.add(visibilityInfo);
            } else if (node.type != NodeType.ON_CLICK && node.type != NodeType.ADD_ITEM) {
                JLabel genericLabel = new JLabel(node.type == NodeType.OBJECT_DROPDOWN || node.type == NodeType.COLLISION
                        ? "Target / Object:" : (node.type == NodeType.SOUND ? "Sound File:" : "Profile:"));
                genericLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                fields.add(genericLabel);
                if (node.type == NodeType.ON_DEATH) {
                    fields.add(new JLabel("Checks the selected object's internal enemyHealth variable. Children run once at 0."));
                    fields.add(new JLabel("Actions under OnDeath update game state directly (score / player HP / damage) without removing the dead object."));
                    fields.add(new JLabel("Death Target:"));
                } else if (node.type == NodeType.DESTROY) {
                    fields.add(new JLabel("Temporarily de-spawns the selected object for the rest of the current runtime."));
                    fields.add(new JLabel("The object's model, asset, and scene data are retained so it can be re-spawned later."));
                    fields.add(new JLabel("Destroy Target:"));
                } else if (node.type == NodeType.RESPAWN) {
                    fields.add(new JLabel("Reactivates a previously de-spawned object without reloading its model or asset."));
                    fields.add(new JLabel("Use this after a Time node to delay the re-spawn."));
                    fields.add(new JLabel("HP to restore:"));
                    fields.add(respawnHealthSpinner);
                    fields.add(new JLabel("This value is written back to the target enemyHealth and health."));
                    fields.add(new JLabel("Respawn Target:"));
                }
                fields.add(profile);
                if (node.type == NodeType.SOUND) {
                    fields.add(new JLabel("Plays the selected Audio Manager asset when this node executes."));
                }
            }

            fields.add(Box.createVerticalStrut(8));
            JLabel commentLabel = new JLabel("Comment:");
            commentLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            fields.add(commentLabel);
            JScrollPane commentScroll = new JScrollPane(comment);
            commentScroll.setPreferredSize(new Dimension(300, 64));
            commentScroll.setMinimumSize(new Dimension(240, 50));
            commentScroll.setMaximumSize(new Dimension(300, 64));
            commentScroll.setAlignmentX(Component.CENTER_ALIGNMENT);
            fields.add(commentScroll);

            // Give every node editor the same compact, centered presentation.
            // The vertical scroll bar only appears when a node genuinely has
            // more settings than the dialog can display; the horizontal bar is
            // disabled so controls such as Radius can never be cut off sideways.
            JScrollPane propertyScroll = new JScrollPane(fields);
            propertyScroll.setBorder(BorderFactory.createEmptyBorder());
            propertyScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            propertyScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            propertyScroll.setPreferredSize(new Dimension(360, 340));
            propertyScroll.setMinimumSize(new Dimension(340, 240));

            int answer = JOptionPane.showConfirmDialog(this, propertyScroll,
                    "Edit " + node.type.getDisplayName() + " Node",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;

            node.name = name.getText().trim();
            if (profile.isEnabled() && profile.getSelectedItem() != null) {
                node.profileName = profile.getSelectedItem().toString().trim();
            }
            node.comment = comment.getText();

            if (node.type == NodeType.BEHAVIOR) {
                node.inlineBehaviorConfigured = true;
                node.inlineIsEnemy = isEnemy.isSelected(); node.inlineChase = chase.isSelected();
                node.inlineIsNPC = isNPC.isSelected(); node.inlineGameOver = gameOver.isSelected();
                node.inlinePatrol = patrol.isSelected(); node.inlineTriggerZone = triggerZone.isSelected();
                node.inlineCollision = collision.isSelected(); node.inlinePhysics = physics.isSelected();
                node.inlineReflective = reflective.isSelected(); node.inlineAutoRotate = autoRotate.isSelected();
            } else if (node.type == NodeType.ACTION) {
                node.inlineActionConfigured = true;
                node.inlineUIActionConfigured = true;
                node.inlineSetPlayerHP = setPlayerHP.isSelected();
                node.inlinePlayerHPOperation = uiActionOperationFromSymbol(playerHPOperation);
                node.inlinePlayerHP = spinnerInt(playerHP, 10);
                node.inlineSetEnemyHealth = setEnemyHealth.isSelected();
                node.inlineEnemyHealthOperation = uiActionOperationFromSymbol(enemyHealthOperation);
                node.inlineEnemyHealth = spinnerInt(enemyHealth, 10);
                node.inlineSetScore = setScore.isSelected();
                node.inlineScoreOperation = uiActionOperationFromSymbol(scoreOperation);
                node.inlineScore = spinnerInt(totalScore, 10);
                // Explicitly clear legacy object-action flags so this node can
                // never feed Destroy/collectible/contact-damage behavior.
                node.inlineGivesPoints = false;
                node.inlineGivesHP = false;
                node.inlineAttacksPlayer = false;
                node.inlineCompleteQuest = false;
                node.inlineQuestName = "";
            } else if (node.type == NodeType.PLAYER) {
                node.inlineSetPlayerScale = setPlayerScale.isSelected();
                node.inlinePlayerScale = (float) Math.max(0.01, Math.min(100.0, spinnerDouble(playerScale, 1.0)));
            } else if (node.type == NodeType.RADIUS) {
                node.radius = (float) Math.max(0.01, spinnerDouble(radiusSpinner, 5.0));
            } else if (node.type == NodeType.TIME) {
                node.timeSeconds = (float) Math.max(0.0, spinnerDouble(timeSpinner, 1.0));
            } else if (node.type == NodeType.RESPAWN) {
                node.respawnHealth = Math.max(1, spinnerInt(respawnHealthSpinner, 100));
            } else if (node.type == NodeType.ACTIVATOR) {
                node.activatorDelaySeconds = (float) Math.max(0.0, spinnerDouble(activatorDelaySpinner, 0.0));
                node.activatorTargets.clear();
                List<String> selectedTargets = activatorTargetList.getSelectedValuesList();
                for (String target : selectedTargets) {
                    if (target != null && !target.trim().isEmpty()
                            && !node.activatorTargets.contains(target.trim())) {
                        node.activatorTargets.add(target.trim());
                    }
                }
                node.profileName = node.activatorTargets.isEmpty()
                        ? "" : node.activatorTargets.get(0);
            } else if (node.type == NodeType.VISIBILITY) {
                node.visibilityHidden = hideCheck.isSelected();
                node.visibilityTargets.clear();
                List<String> selectedTargets = visibilityTargetList.getSelectedValuesList();
                for (String target : selectedTargets) {
                    if (target != null && !target.trim().isEmpty()
                            && !node.visibilityTargets.contains(target.trim())) {
                        node.visibilityTargets.add(target.trim());
                    }
                }
                if (node.visibilityTargets.isEmpty()) node.visibilityTargets.add("Owner");
                node.profileName = node.visibilityTargets.get(0);
            }
            state.dirty = true;
            repaint();
        }

        private List<String> getQuestNamesForNodeEditor() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            try {
                List<String> quests = DialogueEditor.getQuests();
                if (quests != null) names.addAll(quests);
            } catch (Exception ignored) {}
            for (ActionBuilder.ActionProfile ap : actionProfiles.values()) {
                if (ap != null && ap.questName != null && !ap.questName.trim().isEmpty()) {
                    names.add(ap.questName.trim());
                }
            }
            return new ArrayList<>(names);
        }

        private void selectQuest(JComboBox<String> combo, String value) {
            if (value == null || value.trim().isEmpty()) {
                combo.setSelectedIndex(0);
                return;
            }
            combo.setSelectedItem(value.trim());
            if (!value.trim().equals(combo.getSelectedItem())) combo.addItem(value.trim());
            combo.setSelectedItem(value.trim());
        }

        private int spinnerInt(JSpinner spinner, int fallback) {
            try { return Math.max(0, ((Number) spinner.getValue()).intValue()); }
            catch (Exception ex) { return fallback; }
        }

        private JComboBox<String> createUIActionOperationCombo() {
            JComboBox<String> combo = new JComboBox<>(new String[]{"+", "-"});
            combo.setPrototypeDisplayValue("-");
            return combo;
        }

        private String uiActionOperationSymbol(Node.UIActionOperation operation) {
            return operation == Node.UIActionOperation.SUBTRACT ? "-" : "+";
        }

        private Node.UIActionOperation uiActionOperationFromSymbol(JComboBox<String> combo) {
            Object value = combo == null ? null : combo.getSelectedItem();
            return "-".equals(value)
                    ? Node.UIActionOperation.SUBTRACT
                    : Node.UIActionOperation.ADD;
        }

        private JPanel row(JCheckBox check, String label, JSpinner spinner) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1));
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.setMaximumSize(new Dimension(390, 24));
            if (check != null) {
                check.setMargin(new Insets(0, 0, 0, 0));
                check.setPreferredSize(new Dimension(150, 22));
                p.add(check);
            } else if (label != null && !label.isEmpty()) {
                JLabel text = new JLabel(label);
                text.setPreferredSize(new Dimension(150, 22));
                p.add(text);
            }
            if (spinner != null) {
                spinner.setPreferredSize(new Dimension(78, 22));
                p.add(spinner);
            }
            return p;
        }

        private JPanel row(JCheckBox check, JComboBox<String> operation, JSpinner spinner) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1));
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.setMaximumSize(new Dimension(390, 24));

            if (check != null) {
                check.setMargin(new Insets(0, 0, 0, 0));
                check.setPreferredSize(new Dimension(150, 22));
                p.add(check);
            }
            if (operation != null) {
                operation.setPreferredSize(new Dimension(38, 22));
                p.add(operation);
            }
            if (spinner != null) {
                spinner.setPreferredSize(new Dimension(78, 22));
                p.add(spinner);
            }
            return p;
        }

        private double spinnerDouble(JSpinner spinner, double fallback) {
            try { return ((Number) spinner.getValue()).doubleValue(); }
            catch (Exception ex) { return fallback; }
        }

        void fitToNodes() {
            if (nodes.isEmpty()) {
                centerView();
                return;
            }

            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;

            for (Node n : nodes) {
                minX = Math.min(minX, n.x);
                minY = Math.min(minY, n.y);
                maxX = Math.max(maxX, n.x + n.width);
                maxY = Math.max(maxY, n.y + n.height);
            }

            double zx = getWidth() / (double) Math.max(300, maxX - minX + 120);
            double zy = getHeight() / (double) Math.max(250, maxY - minY + 120);
            state.zoom = Math.max(0.45, Math.min(1.75, Math.min(zx, zy)));

            state.panStartX = (minX + maxX) / 2;
            state.panStartY = (minY + maxY) / 2;
        }

        void centerView() {
            if (nodes.isEmpty()) {
                state.panStartX = 0;
                state.panStartY = 0;
                state.zoom = 1.0;
                return;
            }

            int sx = 0;
            int sy = 0;
            for (Node n : nodes) {
                sx += n.x;
                sy += n.y;
            }
            state.panStartX = sx / nodes.size();
            state.panStartY = sy / nodes.size();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            lastMouse = e.getPoint();

            Node n = nodeAt(e.getPoint());

            if (SwingUtilities.isRightMouseButton(e)) {
                if (n != null) {
                    state.selected = n;
                    JPopupMenu menu = new JPopupMenu();

                    JMenuItem edit = new JMenuItem("Edit Node");
                    edit.addActionListener(ev -> showNodeEditor(n));

                    JMenuItem duplicate = new JMenuItem("Duplicate");
                    duplicate.addActionListener(ev -> {
                        Node copy = n.copy(UUID.randomUUID().toString());
                        copy.x += 35;
                        copy.y += 35;
                        copy.name = n.name + " Copy";
                        nodes.add(copy);
                        state.selected = copy;
                        state.dirty = true;
                        repaint();
                    });

                    JMenuItem delete = new JMenuItem("Delete");
                    delete.addActionListener(ev -> {
                        removeNode(nodes, connections, n.id);
                        if (state.selected == n) state.selected = null;
                        state.dirty = true;
                        repaint();
                    });

                    menu.add(edit);
                    menu.add(duplicate);
                    menu.addSeparator();
                    menu.add(delete);
                    menu.show(this, e.getX(), e.getY());
                } else {
                    draggingCanvas = true;
                }
                return;
            }

            if (SwingUtilities.isMiddleMouseButton(e) || ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0 && n == null)) {
                draggingCanvas = true;
                return;
            }

            if (n != null) {
                state.selected = n;

                Point out = outputSocket(n);
                if (near(out, e.getPoint(), 12)) {
                    state.connectionSource = n;
                    state.mouse = e.getPoint();
                    repaint();
                    return;
                }

                draggingNode = n;
                Point world = screenToWorld(e.getPoint());
                state.dragOffset = new Point(world.x - n.x, world.y - n.y);

                if (e.getClickCount() == 2) {
                    showNodeEditor(n);
                }
            } else {
                state.selected = null;
            }

            repaint();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            Point p = e.getPoint();

            if (state.connectionSource != null) {
                state.mouse = p;
                repaint();
                return;
            }

            if (draggingNode != null) {
                Point world = screenToWorld(p);
                draggingNode.x = world.x - state.dragOffset.x;
                draggingNode.y = world.y - state.dragOffset.y;
                state.dirty = true;
                repaint();
                return;
            }

            if (draggingCanvas && lastMouse != null) {
                int dx = p.x - lastMouse.x;
                int dy = p.y - lastMouse.y;
                state.panStartX -= (int) (dx / state.zoom);
                state.panStartY -= (int) (dy / state.zoom);
                lastMouse = p;
                repaint();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (state.connectionSource != null) {
                Node target = nodeAt(e.getPoint());
                if (target != null && target != state.connectionSource) {
                    if (!connect(nodes, connections,
                            state.connectionSource.id, target.id)) {
                        Toolkit.getDefaultToolkit().beep();
                    } else {
                        state.dirty = true;
                    }
                }
                state.connectionSource = null;
                state.mouse = null;
                repaint();
            }

            draggingNode = null;
            draggingCanvas = false;
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            double old = state.zoom;
            if (e.getWheelRotation() < 0) {
                state.zoom = Math.min(2.25, state.zoom * 1.1);
            } else {
                state.zoom = Math.max(0.45, state.zoom / 1.1);
            }

            // Keep the world point beneath the cursor stable while zooming.
            Point before = screenToWorld(e.getPoint());
            Point after = screenToWorld(e.getPoint());
            state.panStartX += before.x - after.x;
            state.panStartY += before.y - after.y;

            if (old != state.zoom) repaint();
        }

        @Override public void mouseMoved(MouseEvent e) {
            state.mouse = e.getPoint();
            if (state.connectionSource != null) repaint();
        }

        @Override public void mouseClicked(MouseEvent e) {}
        @Override public void mouseEntered(MouseEvent e) {}
        @Override public void mouseExited(MouseEvent e) {}

    }
}
