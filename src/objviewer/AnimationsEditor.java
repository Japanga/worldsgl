package objviewer;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.MappedByteBuffer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static javax.swing.TransferHandler.MOVE;

/**
 * WGANIM viewer/editor designed specifically for the streamed WGANIM files
 * produced by FBXToWGANIM.py.
 *
 * The converter writes one independent, fully evaluated mesh per frame.  This
 * editor fully indexes and materializes every baked frame before playback or
 * scrubbing is enabled. This makes playback deterministic and eliminates
 * disk-I/O hitches while viewing an imported animation. The WGANIM Library
 * is a persistent folder shared with ThirdPersonGameNew.
 */
public class AnimationsEditor extends JFrame implements GLEventListener,
        MouseListener, MouseMotionListener, MouseWheelListener {

    public static final class Vertex {
        final float x, y, z;
        Vertex(float x, float y, float z) { this.x=x; this.y=y; this.z=z; }
    }

    public static final class Face {
        final int[] v;
        Face(int[] v) { this.v=v; }
    }

    public static final class Frame {
        final int index;
        final ArrayList<Vertex> vertices = new ArrayList<>();
        final ArrayList<Face> faces = new ArrayList<>();
        float minX=Float.POSITIVE_INFINITY, minY=Float.POSITIVE_INFINITY, minZ=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY, maxY=Float.NEGATIVE_INFINITY, maxZ=Float.NEGATIVE_INFINITY;

        Frame(int index) { this.index=index; }

        void calculateBounds() {
            minX=minY=minZ=Float.POSITIVE_INFINITY;
            maxX=maxY=maxZ=Float.NEGATIVE_INFINITY;
            for(Vertex v:vertices) {
                if(v.x<minX)minX=v.x; if(v.y<minY)minY=v.y; if(v.z<minZ)minZ=v.z;
                if(v.x>maxX)maxX=v.x; if(v.y>maxY)maxY=v.y; if(v.z>maxZ)maxZ=v.z;
            }
            if(vertices.isEmpty()) { minX=minY=minZ=-1; maxX=maxY=maxZ=1; }
        }
    }

    private static final class FrameRange {
        final long start, end;
        FrameRange(long start,long end){this.start=start;this.end=end;}
    }

    private static final class AnimationSource {
        File file;
        String name="Animation";
        int fps=30;
        boolean loop=true;
        int totalFrames=0;
        final ArrayList<FrameRange> ranges=new ArrayList<>();
    }

    private final GLJPanel viewer=new GLJPanel();
    private final GLU glu=new GLU();
    private FPSAnimator animator;
    private AnimationSource source;
    private File currentFile;
    private int currentFrame=0;
    private volatile Frame displayedFrame;
    private volatile boolean playing=false;
    private volatile boolean loadingFrame=false;
    private volatile boolean allFramesLoaded=false;
    private volatile ArrayList<Frame> allFrames=new ArrayList<>();
    private long lastFrameNanos;

    /*
     * WGANIM baking is a project/session service, not a property of whichever
     * animation happens to be selected in the tree.  A selection change must
     * NEVER cancel an unrelated bake.
     */
    // Background warming deliberately uses one worker. The previous pool could
    // occupy every worker with library-wide preloads, making the animation the
    // user actually selected wait behind unrelated bakes. Foreground loads have
    // their own worker so a manual selection stays responsive.
    private static final ExecutorService BAKE_EXECUTOR=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"WGANIM-BakeWorker");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService FOREGROUND_BAKE_EXECUTOR=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"WGANIM-ForegroundBakeWorker");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentHashMap<String,Future<?>> BAKE_TASKS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,CachedAnimation> BAKE_CACHE=new ConcurrentHashMap<>();
    private static final Object BAKE_CACHE_LOCK=new Object();
    private static volatile boolean animationsServiceRunning=true;
    private static volatile boolean explicitApplicationExit=false;
    private static final File SESSION_CACHE_DIRECTORY=new File(
            System.getProperty("java.io.tmpdir","."),"WorldsGL_WGANIM_Cache");

    private final LinkedHashMap<Integer,Frame> cache=new LinkedHashMap<Integer,Frame>(4,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Integer,Frame> e){return size()>3;}
    };
    private final Object cacheLock=new Object();

    private static final class CachedAnimation {
        final String key;
        final File file;
        final long length;
        final long modified;
        final AnimationSource source;
        final ArrayList<Frame> frames;
        CachedAnimation(String key,File file,AnimationSource source,ArrayList<Frame> frames){
            this.key=key; this.file=file; this.length=file.length(); this.modified=file.lastModified();
            this.source=source; this.frames=frames;
        }
        boolean stillValid(){return file.isFile() && file.length()==length && file.lastModified()==modified;}
    }

    private volatile boolean closingToBackground=false;
    private volatile boolean currentlyBaking=false;

    /** First-stage profile container: owns the WGANIM files shown beneath it. */
    public static final class WGAnimProfile {
        private String name;
        private final ArrayList<File> animations=new ArrayList<>();
        private final LinkedHashSet<String> childStates=new LinkedHashSet<>();
        private final LinkedHashMap<String,ArrayList<File>> stateAnimations=new LinkedHashMap<>();
        public WGAnimProfile(String name){this.name=(name==null||name.trim().isEmpty())?"WGAnimProfile":name.trim();}
        public String getName(){return name;}
        private boolean rename(String newName){
            if(newName==null)return false;
            newName=newName.trim();
            if(newName.isEmpty() || name.equals(newName))return false;
            name=newName;
            return true;
        }
        public java.util.List<File> getAnimations(){return Collections.unmodifiableList(animations);}
        private void clearAnimations(){animations.clear();}
        private void addAnimation(File f){if(f!=null&&f.isFile()&&!animations.contains(f))animations.add(f);}
        private boolean hasState(String state){return state!=null&&childStates.contains(state);}
        private boolean addState(String state){
            if(state==null||state.trim().isEmpty()||!childStates.add(state))return false;
            stateAnimations.put(state,new ArrayList<File>());
            return true;
        }
        private java.util.Set<String> getStates(){return Collections.unmodifiableSet(childStates);}
        private java.util.List<File> getStateAnimations(String state){
            ArrayList<File> list=stateAnimations.get(state);
            return list==null?Collections.emptyList():Collections.unmodifiableList(list);
        }
        private boolean isAnimationAssigned(File f){
            if(f==null)return false;
            for(ArrayList<File> list:stateAnimations.values()) if(list.contains(f))return true;
            return false;
        }
        private boolean addAnimationToState(String state,File f){
            if(!hasState(state)||f==null||!f.isFile())return false;
            ArrayList<File> list=stateAnimations.computeIfAbsent(state,k->new ArrayList<>());
            if(list.contains(f))return false;
            list.add(f);
            animations.remove(f);
            return true;
        }
    }
    private final DefaultListModel<String> libraryModel=new DefaultListModel<>();
    private static final String WGANIM_LIBRARY_FOLDER="WGANIM_Library";
    private static final String WGANIM_UNASSIGNED_FOLDER="Unassigned";

    /*
     * Permanent safety storage for baked WGANIM assets.
     * This lives outside dist/ and gives every .tpgproject its own backup folder.
     */
    private static final File WGANIM_BACKUP_ROOT = new File("C:/WGAnim_backups");

    private File resolveWGANIMBackupDirectory(File projectFile){
        String folderName=WGANIM_UNASSIGNED_FOLDER;
        if(projectFile!=null){
            String name=projectFile.getName();
            int dot=name.lastIndexOf('.');
            if(dot>0) name=name.substring(0,dot);
            name=name.replaceAll("[<>:\"/\\|?*]","_").trim();
            if(!name.isEmpty()) folderName=name;
        }
        return new File(WGANIM_BACKUP_ROOT,folderName);
    }

    private void backupWGANIMFile(File source, File projectFile){
        if(source==null || !source.isFile()) return;
        try{
            File dir=resolveWGANIMBackupDirectory(projectFile);
            if(!dir.exists()) dir.mkdirs();
            if(!dir.isDirectory()) return;
            Files.copy(source.toPath(),new File(dir,source.getName()).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ex){
            System.err.println("Could not back up WGANIM "+source.getName()+": "+ex.getMessage());
        }
    }

    private void restoreWGANIMBackupsToLibrary(File projectFile){
        if(libraryDirectory==null) return;
        File backupDir=resolveWGANIMBackupDirectory(projectFile);
        if(!backupDir.isDirectory()) return;
        try{
            if(!libraryDirectory.exists()) libraryDirectory.mkdirs();
            File[] files=backupDir.listFiles((d,n)->n.toLowerCase(Locale.ROOT).endsWith(".wganim"));
            if(files!=null){
                for(File source:files){
                    File target=new File(libraryDirectory,source.getName());
                    if(!target.isFile())
                        Files.copy(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            File backupProfiles=new File(backupDir,WGANIM_PROFILES_FILE);
            File libraryProfiles=new File(libraryDirectory,WGANIM_PROFILES_FILE);
            if(backupProfiles.isFile() && !libraryProfiles.isFile())
                Files.copy(backupProfiles.toPath(),libraryProfiles.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ex){
            status("Could not restore WGANIM backups: "+ex.getMessage());
        }
    }
    private static final String WGANIM_SCRIPT_NAME="wganim.py";
    private static final String WGANIM_PROFILES_FILE="WGANIM_Profiles.properties";
    private static final String WGANIM_PROJECT_PROFILE_PREFIX="wganim_profiles/";
    private final JList<String> libraryList=new JList<>(libraryModel);
    private final DefaultTreeModel profilingTreeModel=
            new DefaultTreeModel(new DefaultMutableTreeNode("Profiling"));
    private final JTree profilingTree=new JTree(profilingTreeModel);
    private WGAnimProfile defaultProfile;
    // Profile currently attached to the Player controls/project. The four
    // canonical state branches are mapped to the existing Idle/Movement/
    // Attacking/Jumping controls in ThirdPersonGameNew.
    private WGAnimProfile activePlayerProfile;
    private final ArrayList<WGAnimProfile> profilingProfiles=new ArrayList<>();
    private File libraryDirectory;
    // Optional .tpgproject context. When the editor is opened from the game,
    // embedded WGANIM assets are extracted into the shared library so they can
    // be browsed and edited again even after the game itself has been closed.
    private File currentProjectFile;
    private Runnable playerProfileChangedCallback;
    private final LinkedHashMap<String,String> projectAnimationEntries=new LinkedHashMap<>();
    private final JSlider timeline=new JSlider(0,0,0);
    private final JLabel frameLabel=new JLabel("Frame 0 / 0");
    private final JLabel statsLabel=new JLabel("No animation loaded");
    private final JLabel fileLabel=new JLabel("No .wganim loaded");
    private final JLabel statusLabel=new JLabel("Ready");
    private final JSpinner fpsSpinner=new JSpinner(new SpinnerNumberModel(30,1,240,1));
    private final JCheckBox loopBox=new JCheckBox("Loop",true);
    private final JCheckBox wireBox=new JCheckBox("Wireframe",false);
    private final JCheckBox pointsBox=new JCheckBox("Vertices",false);
    private final JCheckBox autoFitBox=new JCheckBox("Auto Fit",true);
    private final JButton playButton=new JButton("Play");

    private float yaw=25f,pitch=12f,distance=3f;
    private float centerX,centerY,centerZ,radius=1f;
    // Stable normalization based on the first successfully loaded frame. This keeps
    // the imported WGANIM at a predictable origin/scale across the entire animation.
    // WGANIM stores Blender world-space coordinates (Z-up). The editor is Y-up.
    // These values describe the converted, editor-space model bounds.
    private float modelCenterX,modelCenterY,modelCenterZ,modelScale=1f;
    private float modelBottomY,modelViewTargetY=1f;
    private boolean modelNormalizationReady=false;
    // Imported-model correction transform (applied only while rendering).
    private float objectX=0f, objectY=0f, objectZ=0f;
    private float objectRotX=0f, objectRotY=0f, objectRotZ=0f;
    private int lastX,lastY;
    private boolean dragging;

    /**
     * Returns the isolated persistent WGANIM storage directory for the active project.
     * Each .tpgproject gets its own folder so same-named animations/profiles from
     * different projects can never overwrite or bleed into one another.
     */
    private File resolveProjectLibraryDirectory(File projectFile){
        File root=new File(System.getProperty("user.dir","."),WGANIM_LIBRARY_FOLDER);
        String folderName=WGANIM_UNASSIGNED_FOLDER;
        if(projectFile!=null){
            String name=projectFile.getName();
            int dot=name.lastIndexOf('.');
            if(dot>0) name=name.substring(0,dot);
            name=name.replaceAll("[<>:\"/\\|?*]","_").trim();
            if(!name.isEmpty()) folderName=name;
        }
        return new File(root,folderName);
    }

    private void switchProjectLibraryDirectory(File projectFile){
        libraryDirectory=resolveProjectLibraryDirectory(projectFile);
        if(!libraryDirectory.exists()) libraryDirectory.mkdirs();
    }

    public AnimationsEditor(){this(null,null,null);}

    public AnimationsEditor(File initialFile){this(initialFile,null,null);}

    public AnimationsEditor(File initialFile, File projectFile){this(initialFile,projectFile,null);}

    /**
     * Open the editor with an optional project context and an optional callback
     * that lets the owning game editor refresh its Player WGAnim profile list
     * immediately after a profile is attached/saved.
     */
    public AnimationsEditor(File initialFile, File projectFile, Runnable playerProfileChangedCallback){
        super("Animations Editor - WGANIM");
        this.playerProfileChangedCallback=playerProfileChangedCallback;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter(){
            @Override public void windowClosing(WindowEvent e){ hideToBackground(); }
        });
        setSize(1280,800);
        setLocationByPlatform(true);
        buildUI();
        viewer.addGLEventListener(this);
        viewer.addMouseListener(this);
        viewer.addMouseMotionListener(this);
        viewer.addMouseWheelListener(this);
        animator=new FPSAnimator(viewer,60,true);
        animator.start();
        // Resolve storage only after the project context is known.
        // (The project field is assigned below for backwards-compatible startup.)
        defaultProfile=new WGAnimProfile("WGAnimProfile");
        activePlayerProfile=defaultProfile;
        profilingProfiles.add(defaultProfile);

        currentProjectFile = projectFile;
        switchProjectLibraryDirectory(currentProjectFile);
        // Recover baked assets before importing the project archive.
        restoreWGANIMBackupsToLibrary(currentProjectFile);
        if(currentProjectFile!=null && currentProjectFile.isFile()){
            importProjectWGANIMs(currentProjectFile);
            loadProfilesFromProject(currentProjectFile);
        } else {
            loadSavedProfiles();
        }
        refreshLibrary();

        File toOpen=initialFile;
        if(toOpen==null && currentProjectFile!=null){
            // Prefer the project's first stored player animation so reopening
            // the editor immediately shows real project data instead of a blank
            // viewer.
            File projectAnimation=findFirstProjectAnimationInLibrary();
            if(projectAnimation!=null) toOpen=projectAnimation;
        }
        if(toOpen!=null && toOpen.isFile()) {
            final File openFile=toOpen;
            SwingUtilities.invokeLater(()->load(openFile));
        }
    }

    private void buildUI(){
        JPanel root=new JPanel(new BorderLayout(6,6));
        root.setBorder(new EmptyBorder(6,6,6,6));
        setContentPane(root);
        root.add(buildToolbar(),BorderLayout.NORTH);
        root.add(buildLibrary(),BorderLayout.WEST);
        root.add(viewer,BorderLayout.CENTER);
        root.add(buildSettings(),BorderLayout.EAST);

        JPanel bottom=new JPanel(new BorderLayout(6,4));
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT,4,2));
        JButton first=new JButton("|<"),prev=new JButton("<"),next=new JButton(">"),last=new JButton(">|");
        first.addActionListener(e->setFrame(0));
        prev.addActionListener(e->setFrame(currentFrame-1));
        next.addActionListener(e->setFrame(currentFrame+1));
        last.addActionListener(e->setFrame(source==null?0:source.totalFrames-1));
        playButton.addActionListener(e->togglePlay());
        playButton.setEnabled(false);
        loopBox.addActionListener(e->{if(source!=null)source.loop=loopBox.isSelected();});
        wireBox.addActionListener(e->viewer.repaint());
        pointsBox.addActionListener(e->viewer.repaint());
        autoFitBox.addActionListener(e->{if(autoFitBox.isSelected())fitCurrentFrame();});
        controls.add(first);controls.add(prev);controls.add(playButton);controls.add(next);controls.add(last);
        controls.add(loopBox);controls.add(wireBox);controls.add(pointsBox);controls.add(autoFitBox);controls.add(frameLabel);
        timeline.addChangeListener(e->{if(!timeline.getValueIsAdjusting())setFrame(timeline.getValue());});
        bottom.add(controls,BorderLayout.WEST);
        bottom.add(timeline,BorderLayout.CENTER);
        bottom.add(statsLabel,BorderLayout.EAST);
        root.add(bottom,BorderLayout.SOUTH);
    }

    private JToolBar buildToolbar(){
        JToolBar bar=new JToolBar();bar.setFloatable(false);
        JButton open=new JButton("Import .wganim");
        JButton saveProfiles=new JButton("Save profiles");
        JButton script=new JButton("Open wganim.py");
        JButton refresh=new JButton("Refresh Library");
        JButton exit=new JButton("Exit Application");
        JButton reset=new JButton("Reset View"),fit=new JButton("Fit Frame");
        open.addActionListener(e->openDialog());
        saveProfiles.addActionListener(e->saveProfiles());
        script.addActionListener(e->openWganimScript());
        refresh.addActionListener(e->refreshLibrary());
        exit.addActionListener(e->exitApplication());
        reset.addActionListener(e->{yaw=25;pitch=12;fitCurrentFrame();});
        fit.addActionListener(e->fitCurrentFrame());
        bar.add(open);bar.add(saveProfiles);bar.add(script);bar.addSeparator();bar.add(refresh);bar.addSeparator();bar.add(reset);bar.add(fit);bar.addSeparator();bar.add(exit);
        return bar;
    }

    private JPanel buildLibrary(){
        JPanel p=new JPanel(new BorderLayout(4,4));
        p.setBorder(BorderFactory.createTitledBorder("WGANIM Library"));
        p.setPreferredSize(new Dimension(285,0));

        profilingTree.setRootVisible(true);
        profilingTree.setShowsRootHandles(true);
        profilingTree.setCellRenderer(new ProfilingTreeRenderer());
        profilingTree.setComponentPopupMenu(null);
        profilingTree.setDragEnabled(true);
        profilingTree.setDropMode(DropMode.ON);
        profilingTree.setTransferHandler(new ProfilingTreeTransferHandler());
        profilingTree.addMouseListener(new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){showProfilingContextMenu(e);}
            @Override public void mouseReleased(MouseEvent e){showProfilingContextMenu(e);}
        });
        profilingTree.addTreeSelectionListener(e->{
            Object o=profilingTree.getLastSelectedPathComponent();
            if(o instanceof DefaultMutableTreeNode){
                Object v=((DefaultMutableTreeNode)o).getUserObject();
                if(v instanceof File) load((File)v);
            }
        });
        p.add(new JScrollPane(profilingTree),BorderLayout.CENTER);

        JPanel buttons=new JPanel(new GridLayout(0,1,3,3));
        JButton importButton=new JButton("Import Animation...");
        JButton open=new JButton("Open Selected");
        JButton delete=new JButton("Delete Selected");
        importButton.addActionListener(e->openDialog());
        open.addActionListener(e->{
            Object o=profilingTree.getLastSelectedPathComponent();
            if(o instanceof DefaultMutableTreeNode){
                Object v=((DefaultMutableTreeNode)o).getUserObject();
                if(v instanceof File) load((File)v);
            }
        });
        delete.addActionListener(e->deleteSelectedAnimation());
        buttons.add(importButton); buttons.add(open); buttons.add(delete);
        p.add(buttons,BorderLayout.SOUTH);
        return p;
    }

    private static final class ProfilingTreeRenderer extends DefaultTreeCellRenderer{
        private final Icon icon=new WganimIcon(28);
        @Override public Component getTreeCellRendererComponent(JTree tree,Object value,
                boolean selected,boolean expanded,boolean leaf,int row,boolean focused){
            JLabel l=(JLabel)super.getTreeCellRendererComponent(tree,value,selected,expanded,leaf,row,focused);
            Object v=value instanceof DefaultMutableTreeNode
                    ?((DefaultMutableTreeNode)value).getUserObject():value;
            if(v instanceof File){l.setText(((File)v).getName());l.setIcon(icon);}
            else if(v instanceof WGAnimProfile){l.setText(((WGAnimProfile)v).getName());l.setIcon(UIManager.getIcon("FileView.directoryIcon"));}
            else if(v instanceof String){l.setText((String)v);l.setIcon(UIManager.getIcon(expanded?"FileView.directoryIcon":"FileView.directoryIcon"));}
            return l;
        }
    }

    private final class ProfilingTreeTransferHandler extends TransferHandler{
        @Override public int getSourceActions(JComponent c){return MOVE;}

        @Override protected Transferable createTransferable(JComponent c){
            TreePath path=profilingTree.getSelectionPath();
            if(path==null)return null;
            Object node=path.getLastPathComponent();
            if(!(node instanceof DefaultMutableTreeNode))return null;
            Object value=((DefaultMutableTreeNode)node).getUserObject();
            if(!(value instanceof File))return null;
            return new StringSelection(((File)value).getAbsolutePath());
        }

        @Override public boolean canImport(TransferHandler.TransferSupport support){
            if(!support.isDrop() ||
                    !(support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                      support.isDataFlavorSupported(DataFlavor.stringFlavor)))return false;
            JTree.DropLocation dl=(JTree.DropLocation)support.getDropLocation();
            TreePath path=dl.getPath();
            if(path==null)return false;
            Object node=path.getLastPathComponent();
            if(!(node instanceof DefaultMutableTreeNode))return false;
            Object value=((DefaultMutableTreeNode)node).getUserObject();
            return value instanceof String &&
                    ("Idle".equals(value)||"Movement".equals(value)||"Attacking".equals(value)||"Jumping".equals(value));
        }

        @Override public boolean importData(TransferHandler.TransferSupport support){
            if(!canImport(support))return false;
            try{
                java.util.List<File> droppedFiles=new ArrayList<>();
                Transferable transferable=support.getTransferable();
                if(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)){
                    Object data=transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    if(data instanceof java.util.List){
                        for(Object o:(java.util.List<?>)data) if(o instanceof File)droppedFiles.add((File)o);
                    }
                }else if(transferable.isDataFlavorSupported(DataFlavor.stringFlavor)){
                    String path=transferable.getTransferData(DataFlavor.stringFlavor).toString();
                    droppedFiles.add(new File(path));
                }
                if(droppedFiles.isEmpty())return false;
                JTree.DropLocation dl=(JTree.DropLocation)support.getDropLocation();
                TreePath statePath=dl.getPath();
                DefaultMutableTreeNode stateNode=(DefaultMutableTreeNode)statePath.getLastPathComponent();
                Object stateValue=stateNode.getUserObject();
                if(!(stateValue instanceof String))return false;
                TreePath profilePath=statePath.getParentPath();
                if(profilePath==null)return false;
                DefaultMutableTreeNode profileNode=(DefaultMutableTreeNode)profilePath.getLastPathComponent();
                Object profileValue=profileNode.getUserObject();
                if(!(profileValue instanceof WGAnimProfile))return false;
                WGAnimProfile profile=(WGAnimProfile)profileValue;
                boolean assigned=false;
                String state=(String)stateValue;
                for(File file:droppedFiles){
                    if(!file.isFile()||!file.getName().toLowerCase(Locale.ROOT).endsWith(".wganim"))continue;
                    File libraryFile=file;
                    if(libraryDirectory!=null){
                        File candidate=new File(libraryDirectory,file.getName());
                        if(candidate.isFile())libraryFile=candidate;
                    }
                    if(profile.addAnimationToState(state,libraryFile))assigned=true;
                }
                if(!assigned)return false;
                rebuildProfilingTree();
                persistProfileEdits("Assigned WGANIM animation(s) to "+state+" in WGAnimProfile: "+profile.getName());
                return true;
            }catch(Exception ex){
                status("Could not assign WGANIM to state: "+ex.getMessage());
                return false;
            }
        }
    }

    private void rebuildProfilingTree(){
        DefaultMutableTreeNode root=new DefaultMutableTreeNode("Profiling");
        if(profilingProfiles.isEmpty()){
            defaultProfile=new WGAnimProfile("WGAnimProfile");
            profilingProfiles.add(defaultProfile);
        }

        File[] fs=(libraryDirectory!=null && libraryDirectory.isDirectory())
                ?libraryDirectory.listFiles((d,n)->n.toLowerCase(Locale.ROOT).endsWith(".wganim"))
                :null;
        if(fs!=null)Arrays.sort(fs,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));

        // For now, every library animation belongs to the original/default
        // profile. Newly-created profiles start empty and become branches that
        // can later own their own animation/pose data.
        for(WGAnimProfile profile:profilingProfiles) profile.clearAnimations();
        for(File f:fs==null?new File[0]:fs){
            if(!defaultProfile.isAnimationAssigned(f))defaultProfile.addAnimation(f);
        }

        for(WGAnimProfile profile:profilingProfiles){
            DefaultMutableTreeNode profileNode=new DefaultMutableTreeNode(profile);
            root.add(profileNode);

            // State branches are explicit children of the profile. They are
            // created through the profile's right-click menu so the profile
            // owns only the animation states the user has actually created.
            for(String state:profile.getStates()){
                DefaultMutableTreeNode stateNode=new DefaultMutableTreeNode(state);
                profileNode.add(stateNode);
                for(File f:profile.getStateAnimations(state))
                    stateNode.add(new DefaultMutableTreeNode(f));
            }

            // Keep unassigned library animations directly under the profile.
            // Once dragged into a state, they are removed from this list and
            // become children of that state node.
            for(File f:profile.getAnimations()) profileNode.add(new DefaultMutableTreeNode(f));
        }

        profilingTreeModel.setRoot(root);
        profilingTree.expandRow(0);
        for(int i=1;i<=root.getChildCount();i++) profilingTree.expandRow(i);
    }

    private void showProfilingContextMenu(MouseEvent e){
        if(!e.isPopupTrigger())return;
        int row=profilingTree.getRowForLocation(e.getX(),e.getY());
        if(row<0)return;
        profilingTree.setSelectionRow(row);
        TreePath path=profilingTree.getPathForRow(row);
        if(path==null)return;
        Object last=path.getLastPathComponent();
        if(!(last instanceof DefaultMutableTreeNode))return;
        Object value=((DefaultMutableTreeNode)last).getUserObject();

        JPopupMenu menu=new JPopupMenu();
        if("Profiling".equals(value)){
            JMenuItem create=new JMenuItem("Create WGAnimProfile");
            create.addActionListener(ev->createWGAnimProfile());
            menu.add(create);
        }else if(value instanceof WGAnimProfile){
            WGAnimProfile profile=(WGAnimProfile)value;

            JMenu createState=new JMenu("Create Animation State");
            addStateCreationMenuItem(createState,profile,"Idle");
            addStateCreationMenuItem(createState,profile,"Movement");
            addStateCreationMenuItem(createState,profile,"Attacking");
            addStateCreationMenuItem(createState,profile,"Jumping");
            menu.add(createState);
            JMenuItem attach=new JMenuItem("Attach Profile to Player");
            attach.addActionListener(ev->attachProfileToPlayer(profile));
            menu.add(attach);

            JMenuItem rename=new JMenuItem("Rename WGAnimProfile");
            rename.addActionListener(ev->renameWGAnimProfile(profile));
            menu.add(rename);
            menu.addSeparator();

            JMenuItem delete=new JMenuItem("Delete WGAnimProfile");
            delete.addActionListener(ev->deleteWGAnimProfile(profile));
            menu.add(delete);
        }else if(value instanceof File){
            return;
        }else{
            return;
        }
        menu.show(profilingTree,e.getX(),e.getY());
    }

    private void addStateCreationMenuItem(JMenu menu,WGAnimProfile profile,String state){
        JMenuItem item=new JMenuItem(state);
        item.setEnabled(!profile.hasState(state));
        item.addActionListener(e->createWGAnimState(profile,state));
        menu.add(item);
    }

    private void createWGAnimState(WGAnimProfile profile,String state){
        if(profile==null||state==null||profile.hasState(state))return;
        if(!profile.addState(state))return;
        rebuildProfilingTree();
        persistProfileEdits("Created "+state+" animation node in WGAnimProfile: "+profile.getName());
    }

    private void createWGAnimProfile(){
        String name=JOptionPane.showInputDialog(this,
                "Enter a name for the new WGAnimProfile:",
                "Create WGAnimProfile",JOptionPane.PLAIN_MESSAGE);
        if(name==null)return;
        name=name.trim();
        if(name.isEmpty()){
            JOptionPane.showMessageDialog(this,"Profile name cannot be empty.",
                    "WGAnimProfile",JOptionPane.WARNING_MESSAGE);
            return;
        }
        for(WGAnimProfile profile:profilingProfiles){
            if(profile.getName().equalsIgnoreCase(name)){
                JOptionPane.showMessageDialog(this,"A WGAnimProfile with that name already exists.",
                        "WGAnimProfile",JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        WGAnimProfile profile=new WGAnimProfile(name);
        profilingProfiles.add(profile);
        if(activePlayerProfile==null) activePlayerProfile=profile;
        rebuildProfilingTree();
        persistProfileEdits("Created WGAnimProfile: "+name);
    }

    private void renameWGAnimProfile(WGAnimProfile profile){
        if(profile==null)return;
        String oldName=profile.getName();
        String name=JOptionPane.showInputDialog(this,
                "Enter a new name for WGAnimProfile:\n\n"+oldName,
                "Rename WGAnimProfile",JOptionPane.PLAIN_MESSAGE);
        if(name==null)return;
        name=name.trim();
        if(name.isEmpty()){
            JOptionPane.showMessageDialog(this,"Profile name cannot be empty.",
                    "Rename WGAnimProfile",JOptionPane.WARNING_MESSAGE);
            return;
        }
        for(WGAnimProfile other:profilingProfiles){
            if(other!=profile && other.getName().equalsIgnoreCase(name)){
                JOptionPane.showMessageDialog(this,"A WGAnimProfile with that name already exists.",
                        "Rename WGAnimProfile",JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        if(!profile.rename(name))return;
        rebuildProfilingTree();
        persistProfileEdits("Renamed WGAnimProfile: "+oldName+" -> "+name);
    }

    private void deleteWGAnimProfile(WGAnimProfile profile){
        if(profile==null)return;
        int choice=JOptionPane.showConfirmDialog(this,
                "Delete WGAnimProfile \""+profile.getName()+"\"?\n\n"
                +"The profile branch will be removed. The actual .wganim files will remain in the library.",
                "Delete WGAnimProfile",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
        if(choice!=JOptionPane.YES_OPTION)return;

        profilingProfiles.remove(profile);
        if(profile==activePlayerProfile) activePlayerProfile=null;
        if(profile==defaultProfile){
            if(profilingProfiles.isEmpty()){
                defaultProfile=new WGAnimProfile("WGAnimProfile");
                profilingProfiles.add(defaultProfile);
            }else{
                defaultProfile=profilingProfiles.get(0);
            }
        }
        if(activePlayerProfile==null && !profilingProfiles.isEmpty()) activePlayerProfile=profilingProfiles.get(0);
        rebuildProfilingTree();
        persistProfileEdits("Deleted WGAnimProfile: "+profile.getName());
    }

    private void persistProfileEdits(String message){
        try{
            saveProfiles();
            status(message);
            if(playerProfileChangedCallback!=null){
                try{ SwingUtilities.invokeLater(playerProfileChangedCallback); }
                catch(Throwable ignored){}
            }
        }catch(Throwable ex){
            status(message+" (save failed: "+ex.getMessage()+")");
        }
    }

    /**
     * Persist the editor's WGAnimProfile hierarchy.  When the editor was
     * opened from ThirdPersonGameNew, the profile definitions and referenced
     * WGANIM assets are written back into that .tpgproject.  A standalone
     * library copy is also written so profiles remain available outside a
     * project context.
     */
    private void attachProfileToPlayer(WGAnimProfile profile){
        if(profile==null) return;
        activePlayerProfile=profile;
        try{
            // Save immediately so the existing Player animation controls become
            // the persistent runtime representation of this profile.
            saveProfiles();
            status("Attached WGAnimProfile to Player: "+profile.getName()+
                    " (Idle / Movement / Attacking / Jumping)");
            if(playerProfileChangedCallback!=null){
                try{ SwingUtilities.invokeLater(playerProfileChangedCallback); }
                catch(Throwable ignored){}
            }
        }catch(Exception ex){
            status("Could not attach WGAnimProfile: "+ex.getMessage());
        }
    }

    private void saveProfiles(){
        try{
            if(activePlayerProfile==null && !profilingProfiles.isEmpty()) activePlayerProfile=profilingProfiles.get(0);
            if(profilingProfiles.isEmpty()){
                status("No WGAnimProfiles to save.");
                return;
            }
            saveProfilesToLibrary();
            if(currentProjectFile!=null && currentProjectFile.isFile())
                saveProfilesToProject(currentProjectFile);
            status(currentProjectFile!=null && currentProjectFile.isFile()
                    ? "Saved WGAnimProfiles to the current .tpgproject and WGANIM library."
                    : "Saved WGAnimProfiles to the WGANIM library.");
        }catch(Exception ex){
            JOptionPane.showMessageDialog(this,
                    "Could not save WGAnimProfiles:\n"+ex.getMessage(),
                    "Save WGAnimProfiles",JOptionPane.ERROR_MESSAGE);
            status("Save profiles failed");
        }
    }

    private void saveProfilesToLibrary() throws IOException{
        if(libraryDirectory==null)throw new IOException("WGANIM library directory is not available.");
        if(!libraryDirectory.exists()&&!libraryDirectory.mkdirs())
            throw new IOException("Could not create WGANIM library directory.");

        Properties p=new Properties();
        p.setProperty("format","WorldsGL WGAnim Profiles 1");
        p.setProperty("profiles.count",Integer.toString(profilingProfiles.size()));
        p.setProperty("active.profile.name", activePlayerProfile==null?"":activePlayerProfile.getName());
        for(int pi=0;pi<profilingProfiles.size();pi++){
            WGAnimProfile profile=profilingProfiles.get(pi);
            String pk="profile."+pi+".";
            p.setProperty(pk+"name",profile.getName());
            p.setProperty(pk+"states.count",Integer.toString(profile.getStates().size()));
            int si=0;
            for(String state:profile.getStates()){
                String sk=pk+"state."+si+".";
                p.setProperty(sk+"name",state);
                java.util.List<File> files=profile.getStateAnimations(state);
                p.setProperty(sk+"animations.count",Integer.toString(files.size()));
                for(int ai=0;ai<files.size();ai++)
                    p.setProperty(sk+"animation."+ai,files.get(ai).getName());
                si++;
            }
        }
        File target=new File(libraryDirectory,WGANIM_PROFILES_FILE);
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){
            p.store(out,"WorldsGL WGANIM Profiles");
        }
        if(currentProjectFile!=null && currentProjectFile.isFile())
            backupWGANIMFile(target,currentProjectFile);
    }

    private void loadSavedProfiles(){
        if(libraryDirectory==null)return;
        File file=new File(libraryDirectory,WGANIM_PROFILES_FILE);
        if(!file.isFile())return;
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))){
            Properties p=new Properties();
            p.load(in);
            loadProfilesFromProperties(p,libraryDirectory);
            String activeName=p.getProperty("active.profile.name","").trim();
            if(!activeName.isEmpty()){
                for(WGAnimProfile profile:profilingProfiles){
                    if(profile.getName().equalsIgnoreCase(activeName)){
                        activePlayerProfile=profile;
                        break;
                    }
                }
            }
        }catch(Exception ex){
            status("Could not load saved WGAnimProfiles: "+ex.getMessage());
        }
    }

    private void loadProfilesFromProject(File projectFile){
        if(projectFile==null||!projectFile.isFile())return;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(projectFile)))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if("project.properties".equals(entry.getName())){
                    Properties p=new Properties();
                    p.load(zip);
                    if(p.getProperty("player.wganimProfiles.count")!=null)
                        loadProfilesFromProjectProperties(p);
                    zip.closeEntry();
                    return;
                }
                zip.closeEntry();
            }
        }catch(Exception ex){
            status("Could not load project WGAnimProfiles: "+ex.getMessage());
        }
    }

    private void loadProfilesFromProjectProperties(Properties p){
        int count=parseInt(p.getProperty("player.wganimProfiles.count","0"),0);
        if(count<=0)return;
        ArrayList<WGAnimProfile> loaded=new ArrayList<>();
        for(int pi=0;pi<count;pi++){
            String pk="player.wganimProfiles."+pi+".";
            String name=p.getProperty(pk+"name","WGAnimProfile "+(pi+1)).trim();
            WGAnimProfile profile=new WGAnimProfile(name);
            int childCount=parseInt(p.getProperty(pk+"child.count","0"),0);
            for(int ci=0;ci<childCount;ci++){
                String ck=pk+"child."+ci+".";
                String state=p.getProperty(ck+"name","").trim();
                String entry=p.getProperty(ck+"path","").trim();
                if(state.isEmpty())continue;
                if(!profile.hasState(state))profile.addState(state);
                if(!entry.isEmpty()){
                    File resolved=resolveProjectProfileAsset(entry);
                    if(resolved!=null)profile.addAnimationToState(state,resolved);
                }
            }
            // Compatibility with older project profiles that only stored the
            // four direct fields instead of explicit child nodes.
            if(profile.getStates().isEmpty()){
                addLegacyProjectState(profile,"Idle",p.getProperty(pk+"idle",""));
                addLegacyProjectState(profile,"Movement",p.getProperty(pk+"movement",""));
                addLegacyProjectState(profile,"Attacking",p.getProperty(pk+"attack",""));
                addLegacyProjectState(profile,"Jumping",p.getProperty(pk+"jump",""));
            }
            loaded.add(profile);
        }
        if(!loaded.isEmpty()){
            profilingProfiles.clear();
            profilingProfiles.addAll(loaded);
            defaultProfile=profilingProfiles.get(0);
            String attachedName=p.getProperty("player.wganimProfile.name", "").trim();
            int selected=parseInt(p.getProperty("player.wganimProfiles.selected", "0"),0);
            activePlayerProfile=null;
            if(!attachedName.isEmpty()) for(WGAnimProfile profile:profilingProfiles)
                if(profile.getName().equalsIgnoreCase(attachedName)){activePlayerProfile=profile;break;}
            if(activePlayerProfile==null && selected>=0 && selected<profilingProfiles.size())
                activePlayerProfile=profilingProfiles.get(selected);
            if(activePlayerProfile==null) activePlayerProfile=defaultProfile;
        }
    }

    private void addLegacyProjectState(WGAnimProfile profile,String state,String entry){
        if(entry==null||entry.trim().isEmpty())return;
        profile.addState(state);
        File resolved=resolveProjectProfileAsset(entry.trim());
        if(resolved!=null)profile.addAnimationToState(state,resolved);
    }

    private File resolveProjectProfileAsset(String entry){
        if(entry==null||entry.trim().isEmpty())return null;
        String normalized=entry.replace('\\','/');
        File direct=new File(normalized);
        if(direct.isFile())return direct;
        if(libraryDirectory!=null){
            File libraryFile=new File(libraryDirectory,new File(normalized).getName());
            if(libraryFile.isFile())return libraryFile;
        }
        return null;
    }

    private int parseInt(String value,int fallback){
        try{return Integer.parseInt(value);}catch(Exception ex){return fallback;}
    }

    private void loadProfilesFromProperties(Properties p,File assetRoot){
        int count=parseInt(p.getProperty("profiles.count","0"),0);
        if(count<=0)return;
        ArrayList<WGAnimProfile> loaded=new ArrayList<>();
        for(int pi=0;pi<count;pi++){
            String pk="profile."+pi+".";
            WGAnimProfile profile=new WGAnimProfile(p.getProperty(pk+"name","WGAnimProfile "+(pi+1)));
            int stateCount=parseInt(p.getProperty(pk+"states.count","0"),0);
            for(int si=0;si<stateCount;si++){
                String sk=pk+"state."+si+".";
                String state=p.getProperty(sk+"name","").trim();
                if(state.isEmpty())continue;
                profile.addState(state);
                int animCount=parseInt(p.getProperty(sk+"animations.count","0"),0);
                for(int ai=0;ai<animCount;ai++){
                    String name=p.getProperty(sk+"animation."+ai,"").trim();
                    if(name.isEmpty())continue;
                    File f=new File(assetRoot,name);
                    if(f.isFile())profile.addAnimationToState(state,f);
                }
            }
            loaded.add(profile);
        }
        if(!loaded.isEmpty()){
            profilingProfiles.clear();
            profilingProfiles.addAll(loaded);
            defaultProfile=profilingProfiles.get(0);
            if(activePlayerProfile==null) activePlayerProfile=defaultProfile;
        }
    }

    private void saveProfilesToProject(File projectFile) throws IOException{
        File project=projectFile.getCanonicalFile();
        File temp=new File(project.getParentFile(),project.getName()+".profiles.tmp");
        LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();
        Properties manifest=new Properties();

        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(project)))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                if(!entry.isDirectory()){
                    byte[] buffer=new byte[8192];
                    int n;
                    while((n=zip.read(buffer))>=0)if(n>0)data.write(buffer,0,n);
                }
                if("project.properties".equals(entry.getName())){
                    manifest.load(new ByteArrayInputStream(data.toByteArray()));
                }else{
                    entries.put(entry.getName(),data.toByteArray());
                }
                zip.closeEntry();
            }
        }

        // Remove the old editor-managed profile asset directory so Save Profiles
        // is deterministic and does not leave stale WGANIM copies in the archive.
        entries.keySet().removeIf(k->k.startsWith(WGANIM_PROJECT_PROFILE_PREFIX));

        manifest.setProperty("player.wganimProfiles.count",Integer.toString(profilingProfiles.size()));
        int selected=activePlayerProfile==null?0:profilingProfiles.indexOf(activePlayerProfile);
        if(selected<0) selected=0;
        for(int pi=0;pi<profilingProfiles.size();pi++){
            WGAnimProfile profile=profilingProfiles.get(pi);
            String pk="player.wganimProfiles."+pi+".";
            manifest.setProperty(pk+"name",profile.getName());
            File idle=firstStateFile(profile,"Idle");
            File movement=firstStateFile(profile,"Movement");
            File attack=firstStateFile(profile,"Attacking");
            File jump=firstStateFile(profile,"Jumping");
            manifest.setProperty(pk+"idle",embedProfileAsset(entries,idle));
            manifest.setProperty(pk+"movement",embedProfileAsset(entries,movement));
            manifest.setProperty(pk+"attack",embedProfileAsset(entries,attack));
            manifest.setProperty(pk+"jump",embedProfileAsset(entries,jump));
            LinkedHashMap<String,String> statePaths=new LinkedHashMap<>();
            for(String state:profile.getStates()){
                File f=firstStateFile(profile,state);
                statePaths.put(state,embedProfileAsset(entries,f));
            }
            manifest.setProperty(pk+"child.count",Integer.toString(statePaths.size()));
            int ci=0;
            for(Map.Entry<String,String> ce:statePaths.entrySet()){
                manifest.setProperty(pk+"child."+ci+".name",ce.getKey());
                manifest.setProperty(pk+"child."+ci+".path",ce.getValue());
                ci++;
            }
        }
        // Keep the active top-level profile fields synchronized with the selected
        // profile so the existing runtime animation path logic continues
        // to work exactly as before.
        if(!profilingProfiles.isEmpty()){
            WGAnimProfile active=profilingProfiles.get(selected);
            manifest.setProperty("player.wganimProfile.name",active.getName());
            manifest.setProperty("player.wganimProfile.idle",embedProfileAsset(entries,firstStateFile(active,"Idle")));
            manifest.setProperty("player.wganimProfile.movement",embedProfileAsset(entries,firstStateFile(active,"Movement")));
            manifest.setProperty("player.wganimProfile.attack",embedProfileAsset(entries,firstStateFile(active,"Attacking")));
            manifest.setProperty("player.wganimProfile.jump",embedProfileAsset(entries,firstStateFile(active,"Jumping")));
            manifest.setProperty("player.wganimProfiles.selected",Integer.toString(selected));
            manifest.setProperty("player.anim.idle",manifest.getProperty("player.wganimProfile.idle",""));
            manifest.setProperty("player.anim.movement",manifest.getProperty("player.wganimProfile.movement",""));
            manifest.setProperty("player.anim.attack",manifest.getProperty("player.wganimProfile.attack",""));
            manifest.setProperty("player.anim.jump",manifest.getProperty("player.wganimProfile.jump",""));
        }

        ByteArrayOutputStream propBytes=new ByteArrayOutputStream();
        manifest.store(propBytes,"WorldsGL Third Person Game Project");
        entries.put("project.properties",propBytes.toByteArray());

        try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))){
            for(Map.Entry<String,byte[]> e:entries.entrySet()){
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        try{
            Files.move(temp.toPath(),project.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception atomicFailure){
            Files.move(temp.toPath(),project.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        projectAnimationEntries.clear();
        importProjectWGANIMs(project);
    }

    private File firstStateFile(WGAnimProfile profile,String state){
        java.util.List<File> files=profile.getStateAnimations(state);
        return files.isEmpty()?null:files.get(0);
    }

    private String embedProfileAsset(Map<String,byte[]> entries,File file) throws IOException{
        if(file==null||!file.isFile())return "";
        String base=file.getName().replaceAll("[^a-zA-Z0-9._-]","_");
        if(base.isEmpty())base="animation.wganim";
        String entry=WGANIM_PROJECT_PROFILE_PREFIX+base;
        byte[] bytes=Files.readAllBytes(file.toPath());
        entries.put(entry,bytes);
        return entry;
    }

    private JPanel buildSettings(){
        JPanel p=new JPanel(new BorderLayout(4,4));
        p.setBorder(BorderFactory.createTitledBorder("WGANIM Playback"));
        p.setPreferredSize(new Dimension(265,0));
        JPanel form=new JPanel();form.setLayout(new BoxLayout(form,BoxLayout.Y_AXIS));
        form.add(row("Playback FPS",fpsSpinner));
        fpsSpinner.addChangeListener(e->{if(source!=null)source.fps=(Integer)fpsSpinner.getValue();});

        JPanel transform=new JPanel();
        transform.setLayout(new BoxLayout(transform,BoxLayout.Y_AXIS));
        transform.setBorder(BorderFactory.createTitledBorder("Animation Object Transform"));
        JSpinner tx=spin(0,-100000,100000,.1), ty=spin(0,-100000,100000,.1), tz=spin(0,-100000,100000,.1);
        JSpinner rx=spin(0,-360,360,1), ry=spin(0,-360,360,1), rz=spin(0,-360,360,1);
        addTransformRow(transform,"Move X",tx,v->objectX=v); addTransformRow(transform,"Move Y",ty,v->objectY=v); addTransformRow(transform,"Move Z",tz,v->objectZ=v);
        addTransformRow(transform,"Rotate X",rx,v->objectRotX=v); addTransformRow(transform,"Rotate Y",ry,v->objectRotY=v); addTransformRow(transform,"Rotate Z",rz,v->objectRotZ=v);
        JButton resetTransform=new JButton("Reset Object Transform");
        resetTransform.addActionListener(e->{tx.setValue(0);ty.setValue(0);tz.setValue(0);rx.setValue(0);ry.setValue(0);rz.setValue(0);});
        transform.add(resetTransform);
        form.add(transform);
        JLabel help=new JLabel("<html><b>FBXToWGANIM compatible</b><br><br>Every imported WGANIM is stored in the WGANIM Library and all baked frames are fully loaded before playback or scrubbing is enabled.<br><br><b>Mouse:</b> left-drag orbit, right/middle-drag zoom, wheel zoom.<br><br>The mesh is rendered from the actual baked vertices and triangles, not as a placeholder.</html>");
        help.setBorder(new EmptyBorder(12,5,12,5));
        form.add(help);
        p.add(form,BorderLayout.NORTH);
        JPanel south=new JPanel(new BorderLayout());
        south.add(fileLabel,BorderLayout.NORTH);south.add(statusLabel,BorderLayout.SOUTH);
        p.add(south,BorderLayout.SOUTH);
        return p;
    }

    private JSpinner spin(double value,double min,double max,double step){return new JSpinner(new SpinnerNumberModel(value,min,max,step));}

    private interface FloatSetter{void set(float value);}
    private void addTransformRow(JPanel parent,String label,JSpinner spinner,FloatSetter setter){
        JPanel r=row(label,spinner); parent.add(r);
        spinner.addChangeListener(e->{setter.set(((Number)spinner.getValue()).floatValue()); viewer.repaint();});
    }

    private JPanel row(String label,JComponent c){JPanel r=new JPanel(new BorderLayout(4,4));r.add(new JLabel(label),BorderLayout.WEST);r.add(c,BorderLayout.CENTER);return r;}

    private void openDialog(){
        JFileChooser c=new JFileChooser(currentFile);
        c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("WGANIM Animation (*.wganim)","wganim"));
        if(c.showOpenDialog(this)==JFileChooser.APPROVE_OPTION) {
            File imported=importIntoLibrary(c.getSelectedFile());
            if(imported!=null) load(imported);
        }
    }

    private void chooseLibraryFolder(){
        // Project isolation is deliberate; do not let the editor switch to a shared
        // arbitrary directory and reintroduce cross-project animation collisions.
        JOptionPane.showMessageDialog(this,
                currentProjectFile!=null
                        ? "WGANIM storage is managed automatically for this project:\n\n"+libraryDirectory.getAbsolutePath()
                        : "Open a .tpgproject to use project-specific WGANIM storage.",
                "WGANIM Library",JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Extract every embedded .wganim from the supplied .tpgproject into the
     * shared editor library. This is intentionally done from the ZIP archive,
     * not from the game's temporary extraction directory, so the editor also
     * works after the game has been closed and reopened.
     */
    private void importProjectWGANIMs(File projectFile){
        projectAnimationEntries.clear();
        if(projectFile==null || !projectFile.isFile()) return;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(projectFile)))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".wganim")){
                    String fileName=new File(entry.getName()).getName();
                    if(fileName.isEmpty()){zip.closeEntry();continue;}
                    ByteArrayOutputStream data=new ByteArrayOutputStream();
                    byte[] buffer=new byte[8192];
                    int n;
                    while((n=zip.read(buffer))>=0){
                        if(n>0) data.write(buffer,0,n);
                    }
                    byte[] bytes=data.toByteArray();
                    File target=new File(libraryDirectory,fileName);
                    if(target.isFile() && target.length()!=bytes.length){
                        String base=fileName;
                        int dot=base.lastIndexOf('.');
                        String stem=dot>0?base.substring(0,dot):base;
                        String ext=dot>0?base.substring(dot):".wganim";
                        target=new File(libraryDirectory,stem+"_project"+Integer.toHexString(Arrays.hashCode(bytes))+ext);
                    }
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){
                        out.write(bytes);
                    }
                    backupWGANIMFile(target,projectFile);
                    projectAnimationEntries.put(target.getName(),entry.getName());
                }
                zip.closeEntry();
            }
            status("Loaded project WGANIM assets from " + projectFile.getName());
        }catch(Exception ex){
            status("Could not read project WGANIM assets: " + ex.getMessage());
        }
    }

    private File findFirstProjectAnimationInLibrary(){
        if(projectAnimationEntries.isEmpty()) return null;
        // Prefer the project entry order, which follows the ZIP/manifest asset
        // order written by ThirdPersonGameNew.
        for(String name:projectAnimationEntries.keySet()){
            File f=new File(libraryDirectory,name);
            if(f.isFile()) return f;
        }
        return null;
    }

    /**
     * Remove the selected animation from the current .tpgproject as well as
     * the editor library. All project.properties references that point to the
     * embedded WGANIM are cleared, including scene-specific player animation
     * properties. Other archive assets remain byte-for-byte present.
     */
    private void removeAnimationFromProject(String libraryName)throws IOException{
        if(currentProjectFile==null || !currentProjectFile.isFile()) return;
        String assetEntry=projectAnimationEntries.get(libraryName);
        if(assetEntry==null) return;

        File project=currentProjectFile.getCanonicalFile();
        File temp=new File(project.getParentFile(),project.getName()+".wganim.tmp");
        Properties manifest=new Properties();
        LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();
        LinkedHashMap<String,ZipEntry> metadata=new LinkedHashMap<>();

        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(project)))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                if(!entry.isDirectory()){
                    byte[] buffer=new byte[8192];
                    int n;
                    while((n=zip.read(buffer))>=0) if(n>0) data.write(buffer,0,n);
                }
                if("project.properties".equals(entry.getName())){
                    try(InputStream in=new ByteArrayInputStream(data.toByteArray())){manifest.load(in);}
                    continue;
                }
                if(assetEntry.equals(entry.getName())) continue;
                entries.put(entry.getName(),data.toByteArray());
                ZipEntry copy=new ZipEntry(entry.getName());
                if(entry.getMethod()==ZipEntry.STORED){
                    byte[] bytes=data.toByteArray();
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(bytes.length);
                    java.util.zip.CRC32 crc=new java.util.zip.CRC32();
                    crc.update(bytes);
                    copy.setCrc(crc.getValue());
                }
                metadata.put(entry.getName(),copy);
                zip.closeEntry();
            }
        }

        // Clear every manifest property that directly references this embedded
        // asset. This catches top-level player.anim.* and scene.N.player.anim.*.
        for(String key:new ArrayList<String>(manifest.stringPropertyNames())){
            String value=manifest.getProperty(key,"");
            if(assetEntry.equals(value)) manifest.setProperty(key,"");
        }

        ByteArrayOutputStream propsBytes=new ByteArrayOutputStream();
        try(OutputStream out=propsBytes){manifest.store(out,"WorldsGL Third Person Game Project");}

        File parent=project.getParentFile();
        if(parent!=null)parent.mkdirs();
        try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))){
            for(Map.Entry<String,byte[]> e:entries.entrySet()){
                ZipEntry z=metadata.get(e.getKey());
                out.putNextEntry(z==null?new ZipEntry(e.getKey()):z);
                out.write(e.getValue());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("project.properties"));
            out.write(propsBytes.toByteArray());
            out.closeEntry();
        }
        try{
            Files.move(temp.toPath(),project.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception atomicFailure){
            Files.move(temp.toPath(),project.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        projectAnimationEntries.remove(libraryName);
    }

    private void refreshLibrary(){
        // Refresh is non-destructive: rebuild the visible list from both the
        // persistent project archive and the working library cache.
        if(currentProjectFile!=null && currentProjectFile.isFile()) importProjectWGANIMs(currentProjectFile);
        libraryModel.clear();
        if(libraryDirectory==null)return;
        if(!libraryDirectory.exists())libraryDirectory.mkdirs();
        File[] fs=libraryDirectory.listFiles();
        if(fs==null)return;
        Arrays.sort(fs,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
        for(File f:fs)
            if(f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".wganim"))
                libraryModel.addElement(f.getName());
        rebuildProfilingTree();
        // Begin warming every project/library animation in the background.
        // Selecting another item never interrupts these jobs.
        if(animationsServiceRunning) queueAllAnimationsForBake();
    }

    private void deleteSelectedAnimation(){
        String name=null;
        Object o=profilingTree.getLastSelectedPathComponent();
        if(o instanceof DefaultMutableTreeNode){
            Object v=((DefaultMutableTreeNode)o).getUserObject();
            if(v instanceof File) name=((File)v).getName();
        }
        if(name==null || libraryDirectory==null){
            JOptionPane.showMessageDialog(this,
                    "Please select a WGANIM animation to delete.",
                    "WGANIM Library",JOptionPane.WARNING_MESSAGE);
            return;
        }

        File target=new File(libraryDirectory,name);
        if(!target.isFile()) {
            refreshLibrary();
            return;
        }

        int choice=JOptionPane.showConfirmDialog(this,
                "Delete the selected WGANIM animation?\n\n" + name +
                "\n\nThis will also remove its cached frame index.",
                "Delete WGANIM Animation",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if(choice!=JOptionPane.YES_OPTION)return;

        try{
            String key=cacheKey(target);
            Future<?> bakingTask=BAKE_TASKS.get(key);
            if(bakingTask!=null && !bakingTask.isDone()){
                JOptionPane.showMessageDialog(this,
                        "This WGANIM is currently being baked and cannot be deleted yet.\n\n" +
                        "Wait for the bake to finish, then delete it safely.",
                        "WGANIM Bake In Progress",JOptionPane.WARNING_MESSAGE);
                return;
            }
            BAKE_TASKS.remove(key);
            BAKE_CACHE.remove(key);
            deleteSessionCache(target);

            // If the animation being deleted is currently open, clear the viewer
            // before removing the file so no stale playback state remains.
            if(currentFile!=null && currentFile.getCanonicalFile().equals(target.getCanonicalFile())){
                stopPlayback();
                source=null;
                currentFile=null;
                currentFrame=0;
                displayedFrame=null;
                allFramesLoaded=false;
                loadingFrame=false;
                allFrames=new ArrayList<>();
                synchronized(cacheLock){cache.clear();}
                timeline.setEnabled(false);
                timeline.setMinimum(0);
                timeline.setMaximum(0);
                timeline.setValue(0);
                playButton.setEnabled(false);
                frameLabel.setText("Frame 0 / 0");
                statsLabel.setText("No animation loaded");
                fileLabel.setText("No .wganim loaded");
            }

            boolean removedFromProject=false;
            if(currentProjectFile!=null && projectAnimationEntries.containsKey(name)) {
                removeAnimationFromProject(name);
                removedFromProject=true;
            }
            Files.deleteIfExists(target.toPath());
            File index=new File(target.getPath()+".idx");
            Files.deleteIfExists(index.toPath());
            refreshLibrary();
            status(removedFromProject
                    ? "Deleted " + name + " from the WGANIM library and current .tpgproject"
                    : "Deleted " + name);
        }catch(Exception ex){
            JOptionPane.showMessageDialog(this,
                    "Could not delete WGANIM:\n" + ex.getMessage(),
                    "WGANIM Library",JOptionPane.ERROR_MESSAGE);
        }
    }

    private File importIntoLibrary(File file){
        if(file==null||!file.isFile())return null;
        try{
            if(!libraryDirectory.exists()&&!libraryDirectory.mkdirs()&&!libraryDirectory.isDirectory())
                throw new IOException("Could not create "+libraryDirectory.getAbsolutePath());
            File target=new File(libraryDirectory,file.getName());
            if(!file.getCanonicalFile().equals(target.getCanonicalFile()))
                Files.copy(file.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if(currentProjectFile!=null && currentProjectFile.isFile())
                backupWGANIMFile(target,currentProjectFile);
            refreshLibrary();
            return target;
        }catch(Exception ex){
            JOptionPane.showMessageDialog(this,"Could not import WGANIM:\n"+ex.getMessage(),
                    "WGANIM Library",JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private void openWganimScript(){
        File script=new File(System.getProperty("user.dir","."),WGANIM_SCRIPT_NAME);
        if(!script.isFile()) script=new File(System.getProperty("user.dir","."),"FBXToWGANIM.py");
        if(!script.isFile()){
            JFileChooser c=new JFileChooser(new File(System.getProperty("user.dir",".")));
            c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Python script (*.py)","py"));
            if(c.showOpenDialog(this)==JFileChooser.APPROVE_OPTION) script=c.getSelectedFile();
        }
        if(script!=null&&script.isFile()){
            try{
                if(!Desktop.isDesktopSupported())throw new IOException("Desktop file opening is not supported on this system.");
                Desktop.getDesktop().open(script);
            }catch(Exception ex){
                JOptionPane.showMessageDialog(this,"Could not open wganim.py:\n"+ex.getMessage(),
                        "WGANIM Converter",JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Resolve the directory that contains the running application JAR.
     * When launched from an IDE/classes directory, user.dir is also checked.
     */
    private static File applicationDirectory(){
        try{
            java.net.URL location=AnimationsEditor.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            if(location!=null){
                File code=new File(location.toURI());
                if(code.isFile()) return code.getParentFile();
                if(code.isDirectory()){
                    // A classes directory is not the JAR directory.  The
                    // working directory is the useful fallback for IDE runs.
                    File working=new File(System.getProperty("user.dir","."));
                    if(new File(working,"wganim.png").isFile()) return working;
                    return code;
                }
            }
        }catch(Exception ignored){}
        return new File(System.getProperty("user.dir","."));
    }

    private static File findWganimIconFile(){
        LinkedHashSet<File> candidates=new LinkedHashSet<>();
        File appDir=applicationDirectory();
        if(appDir!=null) candidates.add(new File(appDir,"wganim.png"));

        // Also check the process working directory. This is useful when the
        // editor is run from an IDE while the final JAR lives elsewhere.
        File working=new File(System.getProperty("user.dir","."));
        candidates.add(new File(working,"wganim.png"));

        for(File f:candidates){
            try{
                if(f.isFile() && f.canRead()) return f;
            }catch(SecurityException ignored){}
        }
        return null;
    }

    private static final class WganimIcon implements Icon{
        private final int size;
        private final BufferedImage image;

        WganimIcon(int size){
            this.size=size;
            BufferedImage loaded=null;
            File png=findWganimIconFile();
            if(png!=null){
                try{
                    BufferedImage source=ImageIO.read(png);
                    if(source!=null && source.getWidth()>0 && source.getHeight()>0){
                        loaded=source;
                    }
                }catch(IOException ignored){}
            }
            image=loaded;
        }

        public int getIconWidth(){return size;}
        public int getIconHeight(){return size;}

        public void paintIcon(Component c,Graphics g,int x,int y){
            if(image!=null){
                Graphics2D g2=(Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(image,x,y,x+size,y+size,0,0,
                        image.getWidth(),image.getHeight(),c);
                g2.dispose();
                return;
            }

            // Graceful fallback if wganim.png is missing or unreadable.
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(45,92,170));
            g2.fillRoundRect(x,y,size-1,size-1,4,4);
            g2.setColor(Color.WHITE);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD,Math.max(6,size*.28f)));
            String t="WGANIM";
            FontMetrics fm=g2.getFontMetrics();
            g2.drawString(t,x+(size-fm.stringWidth(t))/2,
                    y+(size+fm.getAscent()-fm.getDescent())/2);
            g2.dispose();
        }
    }

    private static final class WganimListRenderer extends DefaultListCellRenderer{
        private final Icon icon=new WganimIcon(34);
        @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,
                boolean selected,boolean focused){
            JLabel l=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focused);
            l.setIcon(icon);
            l.setIconTextGap(8);
            l.setBorder(new EmptyBorder(5,5,5,5));
            return l;
        }
    }

    /**
     * Select an animation without making selection responsible for its bake.
     * If it is already cached, switching is immediate. If it is baking, this
     * editor attaches the viewer to the existing job instead of restarting it.
     */
    public void load(File file){
        if(file==null||!file.isFile()||!animationsServiceRunning)return;
        File selected=file.getAbsoluteFile();
        try{ selected=file.getCanonicalFile(); }catch(IOException ignored){}
        stopPlayback();
        currentFile=selected;
        status("Preparing "+selected.getName()+"...");
        String key=cacheKey(selected);

        CachedAnimation cached=BAKE_CACHE.get(key);
        if(cached!=null && cached.stillValid()){
            activateCachedAnimation(cached);
            return;
        }

        Future<?> existing=BAKE_TASKS.get(key);
        if(existing!=null && !existing.isDone()){
            currentlyBaking=true;
            status("Baking "+selected.getName()+" in background. You can safely select another animation.");
            return;
        }
        queueAnimationBake(selected,true);
    }

    private String cacheKey(File file){
        try{return file.getCanonicalPath().toLowerCase(Locale.ROOT);}
        catch(IOException ex){return file.getAbsolutePath().toLowerCase(Locale.ROOT);}
    }

    private void queueAllAnimationsForBake(){
        if(libraryDirectory==null)return;
        File[] files=libraryDirectory.listFiles();
        if(files==null)return;
        Arrays.sort(files,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
        for(File f:files) if(f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".wganim"))
            queueAnimationBake(f,false);
    }

    private void queueAnimationBake(File file,boolean foreground){
        if(file==null||!file.isFile()||!animationsServiceRunning)return;
        final File canonical;
        try{canonical=file.getCanonicalFile();}catch(IOException ex){return;}
        final String key=cacheKey(canonical);
        CachedAnimation cached=BAKE_CACHE.get(key);
        if(cached!=null&&cached.stillValid()){
            if(foreground)activateCachedAnimation(cached);
            return;
        }

        // Rehydrate the persistent binary bake from the previous editor/worlds
        // session before touching the original ASCII WGANIM. The old system
        // wrote these files but never read them, so the disk cache provided no
        // startup performance benefit.
        CachedAnimation diskCached=readSessionCache(canonical);
        if(diskCached!=null&&diskCached.stillValid()){
            BAKE_CACHE.put(key,diskCached);
            if(foreground)activateCachedAnimation(diskCached);
            else status("Restored cached "+canonical.getName()+" from disk.");
            return;
        }

        Future<?> existing=BAKE_TASKS.get(key);
        if(existing!=null&&!existing.isDone()){
            if(foreground)status("Baking "+canonical.getName()+" in background. You can safely select another animation.");
            return;
        }

        Callable<Void> job=()->{
            try{
                SwingUtilities.invokeLater(()->{currentlyBaking=true;status("Baking "+canonical.getName()+"...");});
                AnimationSource indexed=indexFile(canonical);
                ArrayList<Frame> loaded=new ArrayList<>(indexed.totalFrames);
                for(int i=0;i<indexed.totalFrames;i++){
                    if(!animationsServiceRunning)return null;
                    Frame f=readFrame(canonical,indexed.ranges.get(i),i);
                    loaded.add(f);
                    final int done=i+1;
                    if(done==1||done==indexed.totalFrames||done%4==0) SwingUtilities.invokeLater(()->
                        status("Baking "+canonical.getName()+": "+done+" / "+indexed.totalFrames+" frames"));
                }
                CachedAnimation result=new CachedAnimation(key,canonical,indexed,loaded);
                BAKE_CACHE.put(key,result);
                writeSessionCache(result);
                SwingUtilities.invokeLater(()->{
                    if(currentFile!=null&&cacheKey(currentFile).equals(key)) activateCachedAnimation(result);
                    else status("Baked and cached "+canonical.getName()+". Ready for instant switching.");
                });
                return null;
            }catch(Exception ex){
                SwingUtilities.invokeLater(()->status("Bake failed for "+canonical.getName()+": "+ex.getMessage()));
                return null;
            }finally{
                BAKE_TASKS.remove(key);
                SwingUtilities.invokeLater(()->currentlyBaking=!BAKE_TASKS.isEmpty());
            }
        };
        ExecutorService executor=foreground?FOREGROUND_BAKE_EXECUTOR:BAKE_EXECUTOR;
        Future<?> future=executor.submit(job);
        Future<?> previous=BAKE_TASKS.putIfAbsent(key,future);
        if(previous!=null)future.cancel(false);
    }

    private void activateCachedAnimation(CachedAnimation cached){
        if(cached==null||!cached.stillValid())return;
        AnimationSource indexed=cached.source;
        source=indexed; currentFile=cached.file; currentFrame=0; displayedFrame=null;
        allFrames=cached.frames; allFramesLoaded=true; loadingFrame=false;
        objectX=objectY=objectZ=objectRotX=objectRotY=objectRotZ=0f;
        modelNormalizationReady=false;
        synchronized(cacheLock){cache.clear();for(int i=0;i<allFrames.size();i++)cache.put(i,allFrames.get(i));}
        fpsSpinner.setValue(indexed.fps);loopBox.setSelected(indexed.loop);
        timeline.setMinimum(0);timeline.setMaximum(Math.max(0,indexed.totalFrames-1));timeline.setValue(0);
        fileLabel.setText(cached.file.getName()+"  |  "+indexed.totalFrames+" cached frames");
        timeline.setEnabled(true);playButton.setEnabled(true);
        if(!allFrames.isEmpty()){displayedFrame=allFrames.get(0);fitFrame(displayedFrame);updateUIForFrame(displayedFrame);}
        status("Loaded cached "+cached.file.getName()+" instantly.");
    }

    private void writeSessionCache(CachedAnimation cached){
        if(cached==null)return;
        try{
            if(!SESSION_CACHE_DIRECTORY.exists())SESSION_CACHE_DIRECTORY.mkdirs();
            File out=new File(SESSION_CACHE_DIRECTORY,Integer.toHexString(cached.key.hashCode())+".wgcache");
            File tmp=new File(out.getPath()+".tmp");
            try(DataOutputStream data=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))){
                data.writeInt(0x57474331); data.writeLong(cached.length); data.writeLong(cached.modified);
                data.writeUTF(cached.source.name==null?"":cached.source.name); data.writeInt(cached.source.fps);
                data.writeBoolean(cached.source.loop); data.writeInt(cached.source.totalFrames);
                for(Frame f:cached.frames){
                    data.writeInt(f.vertices.size()); data.writeInt(f.faces.size());
                    for(Vertex v:f.vertices){data.writeFloat(v.x);data.writeFloat(v.y);data.writeFloat(v.z);}
                    for(Face face:f.faces){data.writeInt(face.v.length);for(int n:face.v)data.writeInt(n);}
                }
            }
            Files.move(tmp.toPath(),out.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){}
    }

    /**
     * Restores the persistent binary bake written by writeSessionCache().
     * Cache validity is tied to both file length and modification timestamp,
     * so editing/replacing the source WGANIM automatically invalidates it.
     */
    private CachedAnimation readSessionCache(File file){
        if(file==null||!file.isFile())return null;
        String key=cacheKey(file);
        File inFile=new File(SESSION_CACHE_DIRECTORY,Integer.toHexString(key.hashCode())+".wgcache");
        if(!inFile.isFile())return null;
        try(DataInputStream data=new DataInputStream(new BufferedInputStream(new FileInputStream(inFile)))){
            if(data.readInt()!=0x57474331)return null;
            long length=data.readLong();
            long modified=data.readLong();
            if(length!=file.length()||modified!=file.lastModified())return null;

            String name=data.readUTF();
            int fps=data.readInt();
            boolean loop=data.readBoolean();
            int total=data.readInt();
            if(total<0||total>1_000_000)return null;

            AnimationSource source=new AnimationSource();
            source.file=file;
            source.name=name;
            source.fps=Math.max(1,Math.min(240,fps));
            source.loop=loop;
            source.totalFrames=total;
            ArrayList<Frame> frames=new ArrayList<>(total);
            for(int i=0;i<total;i++){
                int vertexCount=data.readInt();
                int faceCount=data.readInt();
                if(vertexCount<0||vertexCount>10_000_000||faceCount<0||faceCount>10_000_000)return null;
                Frame frame=new Frame(i);
                for(int v=0;v<vertexCount;v++)
                    frame.vertices.add(new Vertex(data.readFloat(),data.readFloat(),data.readFloat()));
                for(int f=0;f<faceCount;f++){
                    int count=data.readInt();
                    if(count<0||count>10000)return null;
                    int[] indices=new int[count];
                    for(int n=0;n<count;n++)indices[n]=data.readInt();
                    frame.faces.add(new Face(indices));
                }
                frame.calculateBounds();
                frames.add(frame);
                source.ranges.add(new FrameRange(0,0));
            }
            return new CachedAnimation(key,file,source,frames);
        }catch(Exception ignored){
            return null;
        }
    }

    private void deleteSessionCache(File file){
        if(file==null)return;
        try{Files.deleteIfExists(new File(SESSION_CACHE_DIRECTORY,Integer.toHexString(cacheKey(file).hashCode())+".wgcache").toPath());}catch(Exception ignored){}
    }

    private static void clearSessionBakeCache(){
        BAKE_CACHE.clear(); BAKE_TASKS.clear();
        if(SESSION_CACHE_DIRECTORY.isDirectory()){
            File[] files=SESSION_CACHE_DIRECTORY.listFiles();
            if(files!=null)for(File f:files)try{Files.deleteIfExists(f.toPath());}catch(Exception ignored){}
            try{Files.deleteIfExists(SESSION_CACHE_DIRECTORY.toPath());}catch(Exception ignored){}
        }
    }

    private void hideToBackground(){
        if(explicitApplicationExit)return;
        closingToBackground=true;
        setVisible(false);
        status("AnimationsEditor is running in the background; WGANIM bake cache remains active.");
    }

    public void showEditor(){
        closingToBackground=false;
        setVisible(true);toFront();requestFocus();
    }

    public void exitApplication(){
        explicitApplicationExit=true;
        animationsServiceRunning=false;
        stopPlayback();
        clearSessionBakeCache();
        try{BAKE_EXECUTOR.shutdownNow();}catch(Exception ignored){}
        try{FOREGROUND_BAKE_EXECUTOR.shutdownNow();}catch(Exception ignored){}
        if(animator!=null&&animator.isStarted())animator.stop();
        dispose();
    }

    /** Call this from Worlds' actual application shutdown path. */
    public static void shutdownApplication(){
        explicitApplicationExit=true;
        animationsServiceRunning=false;
        clearSessionBakeCache();
        try{BAKE_EXECUTOR.shutdownNow();}catch(Exception ignored){}
        try{FOREGROUND_BAKE_EXECUTOR.shutdownNow();}catch(Exception ignored){}
    }

    /*
     * FAST WGANIM INDEXING
     * --------------------
     * Older versions used RandomAccessFile.readLine() for EVERY vertex/face
     * line.  That meant a large ASCII WGANIM could require millions of Java
     * String allocations and UTF-8 conversions just to discover where frames
     * begin/end.  We do not need to parse those lines during indexing.
     *
     * This version:
     *   1. Uses a memory-mapped file and scans raw bytes only for FRAME markers.
     *   2. Parses the small header without creating Strings for the whole file.
     *   3. Writes a .wganim.idx sidecar containing the frame offsets.
     *      Subsequent opens of an unchanged file therefore skip the scan
     *      completely and load the index almost immediately.
     *
     * The original WGANIM format remains fully compatible.  The sidecar is
     * optional and can safely be deleted at any time; the editor rebuilds it.
     */
    private AnimationSource indexFile(File file)throws IOException{
        AnimationSource cached=readIndexSidecar(file);
        if(cached!=null)return cached;

        AnimationSource s=new AnimationSource();
        s.file=file;
        final long fileLength=file.length();
        if(fileLength<=0)throw new IOException("WGANIM file is empty.");

        long scanStart=System.nanoTime();
        status("Fast-indexing WGANIM frames...");

        try(FileChannel channel=FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.READ)){
            if(fileLength>Integer.MAX_VALUE){
                // map() supports long-sized files, but a single Java buffer has
                // an int-sized capacity. Scan in large windows for huge files.
                scanMappedWindows(channel,fileLength,s);
            }else{
                MappedByteBuffer data=channel.map(FileChannel.MapMode.READ_ONLY,0,fileLength);
                scanMappedBufferDirect(data,0,fileLength,s);
            }
        }

        if(s.totalFrames<=0)s.totalFrames=s.ranges.size();
        if(s.totalFrames<=0)throw new IOException("This file does not contain a valid TOTAL_FRAMES value or any FRAME blocks.");
        if(s.ranges.size()<s.totalFrames){
            throw new IOException("WGANIM declares "+s.totalFrames+" frames but only "+s.ranges.size()+" frame blocks were found.");
        }
        for(int i=0;i<s.totalFrames;i++){
            if(s.ranges.get(i)==null)throw new IOException("Missing FRAME_"+i+" block.");
        }

        writeIndexSidecar(file,s);
        long elapsedMs=(System.nanoTime()-scanStart)/1_000_000L;
        status("Indexed "+s.totalFrames+" frames"+(elapsedMs>0?" in "+elapsedMs+" ms":"")+". Loading frame 0...");
        return s;
    }

    private void scanMappedWindows(FileChannel channel,long fileLength,AnimationSource s)throws IOException{
        final long window=512L*1024L*1024L;
        long base=0;
        while(base<fileLength){
            long len=Math.min(window,fileLength-base);
            MappedByteBuffer data=channel.map(FileChannel.MapMode.READ_ONLY,base,len);
            scanMappedBufferDirect(data,base,len,s);
            base+=len;
        }
    }

    private void scanMappedBufferDirect(MappedByteBuffer data,long base,long length,AnimationSource s){
        long lineStart=0;
        long i=0;
        long nextUi=8L*1024L*1024L;
        long blockStart=-1;
        int blockIndex=-1;

        while(i<length){
            if(data.get((int)i)=='\n'){
                int ls=(int)lineStart;
                int len=(int)(i-lineStart);
                if(len>0 && data.get(ls)=='-'){
                    if(len>=10 && matches(data,ls,len,"--- FRAME_")){
                        int n=parseFrameNumber(data,ls+10,len-10);
                        if(n>=0){
                            blockStart=base+i+1;
                            blockIndex=n;
                            while(s.ranges.size()<=blockIndex)s.ranges.add(null);
                        }
                    }else if(len>=14 && matches(data,ls,len,"--- END_FRAME_") && blockStart>=0){
                        s.ranges.set(blockIndex,new FrameRange(blockStart,base+i+1));
                        blockStart=-1;
                        blockIndex=-1;
                    }
                }else if(base+i<65536){
                    parseHeaderBytes(data,ls,len,s);
                }
                lineStart=i+1;
            }
            i++;
            if(i>=nextUi){
                nextUi+=8L*1024L*1024L;
                final long pos=base+i;
                final long total=base+length;
                SwingUtilities.invokeLater(()->status("Fast-indexing WGANIM frames... "+Math.min(100,(pos*100L)/Math.max(1,total))+"%"));
            }
        }
    }

    private void parseHeaderBytes(MappedByteBuffer data,int pos,int len,AnimationSource s){
        if(starts(data,pos,len,"NAME "))s.name=asciiTrim(data,pos+5,len-5);
        else if(starts(data,pos,len,"FPS ")){
            try{s.fps=Math.max(1,Math.min(240,(int)Math.round(Double.parseDouble(asciiTrim(data,pos+4,len-4)))));}catch(Exception ignored){}
        }else if(starts(data,pos,len,"LOOP ")){
            String v=asciiTrim(data,pos+5,len-5); s.loop="1".equals(v)||"true".equalsIgnoreCase(v);
        }else if(starts(data,pos,len,"TOTAL_FRAMES ")){
            try{s.totalFrames=Integer.parseInt(asciiTrim(data,pos+13,len-13));}catch(Exception ignored){}
        }
    }

    private boolean starts(MappedByteBuffer data,int pos,int len,String text){
        return len>=text.length() && matches(data,pos,len,text);
    }

    private String asciiTrim(MappedByteBuffer data,int pos,int len){
        int a=pos,b=pos+len;
        while(a<b&&(data.get(a)==' '||data.get(a)=='\r'||data.get(a)=='\t'))a++;
        while(b>a&&(data.get(b-1)==' '||data.get(b-1)=='\r'||data.get(b-1)=='\t'))b--;
        byte[] bytes=new byte[b-a];
        for(int i=0;i<bytes.length;i++)bytes[i]=data.get(a+i);
        return new String(bytes,StandardCharsets.UTF_8);
    }

    private boolean matches(MappedByteBuffer data,int pos,int len,String text){
        if(len<text.length())return false;
        for(int j=0;j<text.length();j++)if(data.get(pos+j)!=(byte)text.charAt(j))return false;
        return true;
    }

    private int parseFrameNumber(MappedByteBuffer data,int pos,int maxLen){
        int n=0; boolean any=false;
        for(int j=0;j<maxLen;j++){
            byte c=data.get(pos+j);
            if(c>='0'&&c<='9'){any=true;n=n*10+(c-'0');}
            else break;
        }
        return any?n:-1;
    }

    private AnimationSource readIndexSidecar(File file){
        File idx=new File(file.getPath()+".idx");
        if(!idx.isFile()||idx.lastModified()<file.lastModified())return null;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(idx)))){
            if(in.readInt()!=0x57474932)return null; // WGI2
            if(in.readLong()!=file.length())return null;
            if(in.readLong()!=file.lastModified())return null;
            AnimationSource s=new AnimationSource();
            s.file=file;
            s.name=in.readUTF();
            s.fps=in.readInt();
            s.loop=in.readBoolean();
            s.totalFrames=in.readInt();
            if(s.totalFrames<0||s.totalFrames>10_000_000)return null;
            for(int i=0;i<s.totalFrames;i++)s.ranges.add(new FrameRange(in.readLong(),in.readLong()));
            status("Loaded cached WGANIM index ("+s.totalFrames+" frames).");
            return s;
        }catch(Exception ignored){return null;}
    }

    private void writeIndexSidecar(File file,AnimationSource s){
        File idx=new File(file.getPath()+".idx");
        File tmp=new File(idx.getPath()+".tmp");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))){
            out.writeInt(0x57474932);
            out.writeLong(file.length());
            out.writeLong(file.lastModified());
            out.writeUTF(s.name==null?"":s.name);
            out.writeInt(s.fps);
            out.writeBoolean(s.loop);
            out.writeInt(s.totalFrames);
            for(int i=0;i<s.totalFrames;i++){
                FrameRange r=s.ranges.get(i);
                out.writeLong(r.start);
                out.writeLong(r.end);
            }
        }catch(Exception ignored){
            tmp.delete();
            return;
        }
        if(!tmp.renameTo(idx)){
            try{Files.move(tmp.toPath(),idx.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(Exception ignored){tmp.delete();}
        }
    }

    private void requestFrame(int frame,boolean immediate){
        if(source==null||source.totalFrames==0||!allFramesLoaded)return;
        frame=Math.max(0,Math.min(source.totalFrames-1,frame));
        Frame f=allFrames.get(frame);
        displayedFrame=f;
        currentFrame=frame;
        loadingFrame=false;
        updateUIForFrame(f);
        viewer.repaint();
    }

    private Frame readFrame(File file,FrameRange range,int index)throws IOException{
        Frame f=new Frame(index);
        try(RandomAccessFile raf=new RandomAccessFile(file,"r")){
            raf.seek(range.start);
            while(raf.getFilePointer()<range.end){
                String raw=raf.readLine();if(raw==null)break;
                String line=new String(raw.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8).trim();
                if(line.isEmpty()||line.startsWith("#"))continue;
                if(line.startsWith("--- END_FRAME_"))break;
                String[] p=line.split("\\s+");
                if(p.length<4)continue;
                if("v".equalsIgnoreCase(p[0])){
                    try{f.vertices.add(new Vertex(Float.parseFloat(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3])));}catch(NumberFormatException ignored){}
                }else if("f".equalsIgnoreCase(p[0])&&p.length>=4){
                    int[] idx=new int[p.length-1];boolean ok=true;
                    for(int i=1;i<p.length;i++){
                        String token=p[i];int slash=token.indexOf('/');if(slash>=0)token=token.substring(0,slash);
                        try{int n=Integer.parseInt(token);idx[i-1]=n<0?f.vertices.size()+n:n-1;if(idx[i-1]<0||idx[i-1]>=f.vertices.size())ok=false;}
                        catch(NumberFormatException ex){ok=false;}
                    }
                    if(ok&&idx.length>=3)f.faces.add(new Face(idx));
                }
            }
        }
        f.calculateBounds();
        if(f.vertices.isEmpty())throw new IOException("Frame "+index+" contains no vertices.");
        if(f.faces.isEmpty())throw new IOException("Frame "+index+" contains no faces.");
        return f;
    }

    private void updateUIForFrame(Frame f){
        if(source==null)return;
        frameLabel.setText("Frame "+currentFrame+" / "+(source.totalFrames-1));
        statsLabel.setText(f.vertices.size()+" vertices   "+f.faces.size()+" faces");
        timeline.setValue(currentFrame);
        // Never refit/reposition the camera while stepping through animation frames.
        // Doing so makes the viewport visibly jump as the character's bounds change.
    }

    private void status(String s){SwingUtilities.invokeLater(()->statusLabel.setText(s));}

    private void togglePlay(){
        if(!allFramesLoaded||source==null||source.totalFrames<2)return;
        playing=!playing;playButton.setText(playing?"Pause":"Play");lastFrameNanos=System.nanoTime();
    }
    private void stopPlayback(){playing=false;playButton.setText("Play");}

    private void setFrame(int f){
        if(!allFramesLoaded||source==null||source.totalFrames==0)return;
        if(f<0){if(source.loop)f=source.totalFrames-1;else f=0;}
        if(f>=source.totalFrames){if(source.loop)f=0;else{f=source.totalFrames-1;stopPlayback();}}
        currentFrame=f;timeline.setValue(f);requestFrame(f,false);
    }

    private void tick(){
        if(!playing||!allFramesLoaded||source==null||source.totalFrames<2)return;
        long now=System.nanoTime();double interval=1_000_000_000.0/Math.max(1,source.fps);
        if(now-lastFrameNanos>=interval){lastFrameNanos=now;setFrame(currentFrame+1);}
    }

    private void fitCurrentFrame(){Frame f=displayedFrame;if(f!=null)fitFrame(f);}
    private void fitFrame(Frame f){
        if(f==null)return;
        // Establish the imported model's origin and normalization exactly once,
        // using frame 0. Do not recenter/rescale every animation frame.
        if(!modelNormalizationReady){
            // The converter writes Blender coordinates: X right, Y depth, Z up.
            // Convert to the editor's convention: X right, Y up, Z toward the viewer.
            // In editor space the bounds are:
            //   X = source X
            //   Y = source Z
            //   Z = -source Y
            float editorMinX=f.minX, editorMaxX=f.maxX;
            float editorMinY=f.minZ, editorMaxY=f.maxZ;
            float editorMinZ=-f.maxY, editorMaxZ=-f.minY;

            modelCenterX=(editorMinX+editorMaxX)*.5f;
            modelCenterZ=(editorMinZ+editorMaxZ)*.5f;
            modelBottomY=editorMinY;

            float sx=Math.max(.000001f,editorMaxX-editorMinX);
            float sy=Math.max(.000001f,editorMaxY-editorMinY);
            float sz=Math.max(.000001f,editorMaxZ-editorMinZ);
            float maxExtent=Math.max(sx,Math.max(sy,sz));
            modelScale=2f/maxExtent;

            // Put the feet on the editor's ground plane and keep the camera aimed
            // at the middle of the normalized character, not at its feet.
            modelCenterY=modelBottomY;
            modelViewTargetY=(sy*modelScale)*.5f;
            modelNormalizationReady=true;
        }
        centerX=0f;centerY=modelViewTargetY;centerZ=0f;
        // Camera framing is established once from the first frame and remains fixed.
        // The animation itself is allowed to move inside that stable view.
        if(modelNormalizationReady){
            radius=Math.max(.0001f,modelScale);
            distance=Math.max(2.8f,radius*1.55f);
        }
        viewer.repaint();
    }

    @Override public void init(GLAutoDrawable d){
        GL2 gl=d.getGL().getGL2();
        gl.glClearColor(.055f,.06f,.075f,1f);gl.glEnable(GL.GL_DEPTH_TEST);gl.glDisable(GL2.GL_CULL_FACE);gl.glDisable(GL2.GL_LIGHTING);gl.glShadeModel(GL2.GL_SMOOTH);
    }

    @Override public void display(GLAutoDrawable d){
        tick();
        GL2 gl=d.getGL().getGL2();int w=Math.max(1,d.getSurfaceWidth()),h=Math.max(1,d.getSurfaceHeight());
        gl.glViewport(0,0,w,h);gl.glClear(GL.GL_COLOR_BUFFER_BIT|GL.GL_DEPTH_BUFFER_BIT);
        gl.glMatrixMode(GL2.GL_PROJECTION);gl.glLoadIdentity();glu.gluPerspective(45.0,(double)w/h,.001,1000000.0);
        gl.glMatrixMode(GL2.GL_MODELVIEW);gl.glLoadIdentity();glu.gluLookAt(0,modelViewTargetY,distance,0,modelViewTargetY,0,0,1,0);
        gl.glRotatef(pitch,1,0,0);gl.glRotatef(yaw,0,1,0);
        drawGrid(gl);drawFrame(gl);
    }

    private void drawGrid(GL2 gl){
        gl.glDisable(GL2.GL_LIGHTING);gl.glLineWidth(1f);gl.glBegin(GL2.GL_LINES);gl.glColor3f(.17f,.18f,.20f);
        for(int i=-10;i<=10;i++){gl.glVertex3f(i,-.001f,-10);gl.glVertex3f(i,-.001f,10);gl.glVertex3f(-10,-.001f,i);gl.glVertex3f(10,-.001f,i);}gl.glEnd();
        gl.glColor3f(.8f,.12f,.12f);gl.glBegin(GL2.GL_LINES);gl.glVertex3f(0,0,0);gl.glVertex3f(1,0,0);gl.glEnd();
        gl.glColor3f(.12f,.8f,.12f);gl.glBegin(GL2.GL_LINES);gl.glVertex3f(0,0,0);gl.glVertex3f(0,1,0);gl.glEnd();
        gl.glColor3f(.12f,.3f,.9f);gl.glBegin(GL2.GL_LINES);gl.glVertex3f(0,0,0);gl.glVertex3f(0,0,1);gl.glEnd();
    }

    private void drawFrame(GL2 gl){
        Frame f=displayedFrame;if(f==null||f.vertices.isEmpty())return;
        gl.glDisable(GL2.GL_LIGHTING);gl.glDisable(GL2.GL_TEXTURE_2D);gl.glDisable(GL2.GL_CULL_FACE);gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glPushMatrix();
        // Final transform order (right-to-left on each vertex):
        // Blender coordinates -> editor axis conversion -> ground/center offset ->
        // uniform normalization -> user rotation/translation.
        // Blender (x,y,z) -> Editor (x,z,-y) is a -90 degree X rotation.
        gl.glTranslatef(objectX,objectY,objectZ);
        gl.glRotatef(objectRotZ,0,0,1);
        gl.glRotatef(objectRotY,0,1,0);
        gl.glRotatef(objectRotX,1,0,0);
        gl.glScalef(modelScale,modelScale,modelScale);
        gl.glTranslatef(-modelCenterX,-modelCenterY,-modelCenterZ);
        gl.glRotatef(-90f,1f,0f,0f);
        gl.glPolygonMode(GL2.GL_FRONT_AND_BACK,wireBox.isSelected()?GL2.GL_LINE:GL2.GL_FILL);
        gl.glColor3f(.86f,.88f,.94f);
        gl.glBegin(GL2.GL_TRIANGLES);
        for(Face face:f.faces){
            if(face.v.length<3)continue;
            for(int k=1;k<face.v.length-1;k++){
                Vertex a=f.vertices.get(face.v[0]),b=f.vertices.get(face.v[k]),c=f.vertices.get(face.v[k+1]);
                float ux=b.x-a.x,uy=b.y-a.y,uz=b.z-a.z,vx=c.x-a.x,vy=c.y-a.y,vz=c.z-a.z;
                float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                if(len>1e-8f){nx/=len;ny/=len;nz/=len;}
                gl.glNormal3f(nx,ny,nz);gl.glVertex3f(a.x,a.y,a.z);gl.glVertex3f(b.x,b.y,b.z);gl.glVertex3f(c.x,c.y,c.z);
            }
        }
        gl.glEnd();
        gl.glPolygonMode(GL2.GL_FRONT_AND_BACK,GL2.GL_FILL);
        if(pointsBox.isSelected()){
            gl.glPointSize(3f);gl.glColor3f(1f,.35f,.05f);gl.glBegin(GL2.GL_POINTS);for(Vertex v:f.vertices)gl.glVertex3f(v.x,v.y,v.z);gl.glEnd();
        }
        gl.glPopMatrix();
    }

    @Override public void reshape(GLAutoDrawable d,int x,int y,int w,int h){}
    @Override public void dispose(GLAutoDrawable d){if(animator!=null&&animator.isStarted())animator.stop();}
    @Override public void mousePressed(MouseEvent e){dragging=true;lastX=e.getX();lastY=e.getY();}
    @Override public void mouseReleased(MouseEvent e){dragging=false;}
    @Override public void mouseDragged(MouseEvent e){if(!dragging)return;int dx=e.getX()-lastX,dy=e.getY()-lastY;lastX=e.getX();lastY=e.getY();if(SwingUtilities.isRightMouseButton(e)||SwingUtilities.isMiddleMouseButton(e)){distance=Math.max(.05f,distance+dy*.025f*radius);}else{yaw+=dx*.5f;pitch=Math.max(-89f,Math.min(89f,pitch+dy*.5f));}viewer.repaint();}
    @Override public void mouseWheelMoved(MouseWheelEvent e){distance*=Math.pow(1.12,e.getWheelRotation());distance=Math.max(.05f,Math.min(1000000f,distance));viewer.repaint();}
    @Override public void mouseClicked(MouseEvent e){}
    @Override public void mouseEntered(MouseEvent e){}
    @Override public void mouseExited(MouseEvent e){}
    @Override public void mouseMoved(MouseEvent e){}

    public static void main(String[] args){SwingUtilities.invokeLater(()->{AnimationsEditor e=new AnimationsEditor(args.length>0?new File(args[0]):null);e.setVisible(true);});}
}
