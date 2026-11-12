package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public final class WorkspaceFrame extends JFrame {
    private final HostClient host;
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final JTree active = tree(), inactive = tree();
    private final JScrollPane inactiveScroll = new JScrollPane(inactive);
    private final JButton archiveToggle = Theme.button("› Неактивные", "Показать деактивированные сессии", this::toggleInactive);
    private final JLabel heading = new JLabel("Выберите сессию"), status = new JLabel(" ");
    private final QuotaPanel quota = new QuotaPanel(this::refreshQuota,this::resetQuota,this::showUsage);
    private ChatTranscript transcript;
    private JScrollPane history;
    private VirtualHistory pagedHistory;
    private ChatNavigation chatNavigation;
    private ChatView chatView;
    /** Recently shown sessions keep their rendered chat, scroll position and history index; switching back is a component swap. */
    private final LinkedHashMap<String,ChatView> chatViews=new LinkedHashMap<>(8,.75f,true);
    private static final int CACHED_CHATS=4;
    private JButton quote; private JPopupMenu quoteMenu;
    private final JTextArea input = new PromptInput();
    private final ModelSelector model = new ModelSelector(this::saveSettings);
    private final JLabel access = new JLabel("Полный доступ");
    private final JButton send = Theme.button("Отправить", "Enter — отправить; если есть очередь — дополнить последнее сообщение", this::send);
    private final JButton sendOptions = Theme.button("", "Способы отправки · Alt+Enter — отдельное сообщение", this::sendMenu);
    private final JButton stop = Theme.button("Стоп", "Остановить текущий запуск; сохранить очередь на паузе", this::stop);
    private final JButton interrupt = Theme.button("Сейчас", "Прервать работу и выполнить это сообщение сейчас · Ctrl+Enter. Очередь продолжится; ручная пауза сохранится", this::interrupt);
    private final AttachmentStrip imageStrip = new AttachmentStrip(this::openArtifact,this::removeImage);
    private final JButton results = Theme.button("Результаты", "Изображения и HTML от агента", () -> {if(this.previewOpen)closePreview();else showPreview(true);});
    private final JPanel requests = new JPanel();
    private final RunStatusPanel runStatus = new RunStatusPanel(this::pauseQueue, this::resumeQueue, this::removeQueued, this::moveQueued, this::takeQueued, this::groupQueued);
    { runStatus.setBacklogAction(this::openSessionBacklog); }
    private final JPanel chat = new JPanel(new BorderLayout());
    private final JPanel preview = new JPanel(new BorderLayout());
    private final ArtifactView viewer = new ArtifactView();
    private final JComboBox<Artifact> artifacts = new JComboBox<>();
    private final JButton getBuild=Theme.button("Получить","Скачать опубликованный файл на этот компьютер",()->receiveBuild(false));
    private final JButton runBuild=Theme.button("Получить и запустить","Скачать сборку и запустить на этом компьютере",()->receiveBuild(true));
    private final JButton expandPreview = Theme.button("", "На весь экран", this::toggleFullScreen);
    private final JButton fitPreview = Theme.button("Вписать", "Показать изображение целиком", () -> viewer.fitImage());
    private final JButton actualPreview = Theme.button("1:1", "Исходный размер изображения", () -> viewer.actualImageSize());
    private JDialog fullScreenPreview;
    private Component previewFocus;
    private int previewDivider = -1;
    private boolean scrollToLatest;
    private long chatRender;
    private final JSplitPane content = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final ChatMarkdown markdown = new ChatMarkdown();
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String,List<String>> draftImages = new HashMap<>();
    private final Map<String,Integer> writingImages = new HashMap<>();
    private final Map<String, JsonNode> settingsDrafts = new HashMap<>();
    private String catalogSession;
    private long catalogAttempt;
    private boolean catalogLoading;
    private final Map<String, JsonNode> sessions = new HashMap<>();
    private final Map<String,JsonNode> documents=new LinkedHashMap<>();
    private String panelReturnSession;
    private String selectedFile,initialDocument,documentSignature="",pinnedSignature="";
    private final JButton pinPreview=Theme.iconButton("attachment","Закрепить этот файл у сессии",this::togglePinnedPreview);
    private final JButton closePreview=Theme.iconButton("close","Закрыть просмотр",this::closePreview);
    private final Map<String,Long> acknowledged = new HashMap<>();
    private final Map<String, JsonNode> projects = new LinkedHashMap<>();
    private volatile String selected;
    private String initialSelection;
    private String selectedProject, treeSignature = "", chatSignature = "", requestSignature = "", artifactSignature = "";
    private JsonNode conversation = Json.object();
    private boolean applying, changingTree, inactiveOpen, previewOpen;
    private volatile boolean disposed;
    private volatile long lastAppliedState;
    private JsonNode pendingHandoff;
    private boolean externalUpdate;
    private final Map<String,JsonNode> sessionViews=new LinkedHashMap<>();
    private final Set<String> collapsedProjects=new HashSet<>();
    /** When this window last changed a project's folding: until the core echoes it back, older states must not undo it. */
    private final Map<String,Long> collapseSent=new HashMap<>();
    private void syncCollapsed(JsonNode state){
        boolean any=false;for(var project:state.path("projects"))any|=project.has("collapsed");if(!any)return;
        if(!sessionViews.containsKey("$collapsedShared")){
            // First run against a core that shares folding: hand over what this window kept on its own.
            for(var project:state.path("projects")){String id=project.path("id").asText();if(collapsedProjects.contains("active:"+id)&&!project.path("collapsed").asBoolean()){collapseSent.put(id,System.currentTimeMillis());action(()->host.post("/projects/"+id+"/collapsed",Json.object("collapsed",true)));}}
            sessionViews.put("$collapsedShared",Json.MAPPER.valueToTree(true));persistViews();return;
        }
        boolean changed=false;long now=System.currentTimeMillis();
        for(var project:state.path("projects")){
            String id=project.path("id").asText(),key="active:"+id;Long sent=collapseSent.get(id);
            if(sent!=null&&now-sent<5000)continue;
            boolean folded=project.path("collapsed").asBoolean();changed|=folded?collapsedProjects.add(key):collapsedProjects.remove(key);
        }
        if(changed){sessionViews.put("$collapsedProjects",Json.MAPPER.valueToTree(collapsedProjects));persistViews();treeSignature="";}
    }
    private String restoreViewFor;private boolean switchingView;
    private void rememberPreview(){
        if(selected==null||switchingView||Objects.equals(restoreViewFor,selected))return;
        int divider=previewOpen&&fullScreenPreview==null?content.getDividerLocation():previewDivider;
        sessionViews.put(selected,Json.object("open",previewOpen,"divider",divider,"path",artifacts.getSelectedItem() instanceof Artifact a?a.path.toString():"","title",artifacts.getSelectedItem() instanceof Artifact a?a.title:""));
        persistViews();
    }
    private void persistViews(){
        var copy=Json.MAPPER.valueToTree(sessionViews);io.submit(()->{try{Path file=dev.tethium.host.AppPaths.data().resolve("session-views.json");Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"views-",".tmp");try{Json.MAPPER.writeValue(temp.toFile(),copy);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}catch(Exception ignored){}});
    }
    private void restorePreview(){
        if(!Objects.equals(restoreViewFor,selected))return;restoreViewFor=null;var saved=sessionViews.get(selected);
        String pin=selected==null?"":sessions.get(selected).path("pinnedFile").asText("");if(!pin.isBlank())saved=Json.object("open",true,"divider",saved==null?-1:saved.path("divider").asInt(-1),"path",pin,"title",Path.of(pin).getFileName().toString());if(saved==null)return;
        previewDivider=saved.path("divider").asInt(-1);String path=saved.path("path").asText();
        if(!path.isBlank()){applying=true;Artifact chosen=null;for(int i=0;i<artifacts.getItemCount();i++)if(artifacts.getItemAt(i).path.toString().equals(path))chosen=artifacts.getItemAt(i);if(chosen==null){chosen=new Artifact(saved.path("title").asText(Path.of(path).getFileName().toString()),Path.of(path));artifacts.addItem(chosen);}artifacts.setSelectedItem(chosen);applying=false;}
        showPreview(saved.path("open").asBoolean());
    }
    private boolean queueEditingAvailable, takeInFlight, groupingAvailable, grouping, imagesAvailable, interruptAvailable, interruptInFlight;
    private JsonNode pendingInterrupt;
    private long interruptRetryAt;
    private long takeRetryAt;
    private JsonNode pendingTake;
    private final Runnable onClose;
    private final javax.swing.Timer draftTimer;
    record Node(String id, String title, boolean project, boolean running, String status, String path, String attention,boolean file,int pausedQueued,int backlog) {
        Node(String id,String title,boolean project,boolean running,String status,String path,String attention){this(id,title,project,running,status,path,attention,false,0,0);}
        Node(String id,String title,boolean project,boolean running,String status,String path,String attention,boolean file){this(id,title,project,running,status,path,attention,file,0,0);}
        public String toString() { return title; }
    }
    private record Artifact(String title, Path path) { public String toString() { return title; } }

    public WorkspaceFrame(HostClient host, Runnable onClose) {
        this(host, onClose, null);
    }
    public WorkspaceFrame(HostClient host, Runnable onClose, String initialSelection) {
        super("Tethium"); this.host = host; this.onClose = onClose;
        setIconImages(AppIcon.images());
        this.initialSelection = initialSelection;
        if(host.remote()){UacDialog.watch(this,host);var check=new javax.swing.Timer(8000,e->updateRemote(false));check.setRepeats(false);check.start();}
        try{Path saved=dev.tethium.host.AppPaths.data().resolve("session-views.json");if(Files.isRegularFile(saved))Json.MAPPER.readTree(saved.toFile()).properties().forEach(e->sessionViews.put(e.getKey(),e.getValue()));}catch(Exception ignored){}
        restoreProjectExpansion();
        ChatMarkdown.showThoughts=sessionViews.getOrDefault("$showThoughts",Json.MAPPER.getNodeFactory().booleanNode(true)).asBoolean(true);
        if(initialSelection==null)initialDocument=sessionViews.getOrDefault("$document",Json.MAPPER.nullNode()).asText(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); setMinimumSize(new Dimension(920, 600)); setSize(1280, 860); setLocationRelativeTo(null);
        draftTimer = new javax.swing.Timer(600, e -> saveDraft()); draftTimer.setRepeats(false);
        build();bindChat(createChatView(null));transcript.setText(page("<p class='muted'>Загружаем проекты и сессии…</p>"));
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { closeWindow(); } });
        poller.scheduleWithFixedDelay(this::poll, 0, 500, TimeUnit.MILLISECONDS);
        // Warm the single preview browser after the window settles, so the first document opens without Chromium start-up.
        var warm=new javax.swing.Timer(2500,e->viewer.warmUp());warm.setRepeats(false);warm.start();
    }
    private JsonNode resourceUsage=Json.object(),resourceControl=Json.object(),machineUsage=Json.object();
    private final ProjectWidget projectWidgets=new ProjectWidget();
    private JTree tree() {
        var tree = new JTree(new DefaultMutableTreeNode("root")) {
            @Override public boolean getScrollableTracksViewportWidth() { return true; }
            @Override protected void paintComponent(Graphics g){super.paintComponent(g);paintTreeIndicators(this,g);}
            @Override public String getToolTipText(MouseEvent event){String resource=resourceTooltip(this,event);return resource==null?super.getToolTipText(event):resource;}
        };
        tree.setRootVisible(false); tree.setShowsRootHandles(true); tree.setRowHeight(28);
        tree.setExpandsSelectedPaths(false);
        installReordering(tree);
        tree.addComponentListener(new ComponentAdapter(){int lastWidth;@Override public void componentResized(ComponentEvent event){if(lastWidth!=tree.getWidth()){lastWidth=tree.getWidth();tree.treeDidChange();}}});
        tree.setBorder(new EmptyBorder(Theme.UNIT, Theme.UNIT, Theme.UNIT, Theme.UNIT)); tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            private final ResourceBars rowPanel = new ResourceBars();
            @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                var label = (JLabel) super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                label.setIcon(null); setBackgroundNonSelectionColor(Theme.SIDEBAR); setBackgroundSelectionColor(Theme.SELECTED); setBorderSelectionColor(Theme.SELECTED);
                var user = ((DefaultMutableTreeNode) value).getUserObject();
                if (user instanceof Node n) {
                    label.setText(n.title); label.setFont(n.project?projectFont(t):t.getFont().deriveFont(Font.PLAIN));
                    label.setIcon(null);
                    label.setForeground(n.running ? Theme.ACCENT : n.status.equals("FAILED") ? Theme.RED : Theme.TEXT);
                    label.setToolTipText(n.path); label.setBorder(new EmptyBorder(0, (n.project?Theme.UNIT/2:Theme.UNIT)+22+Theme.GAP, 0, Theme.UNIT));
                    rowPanel.removeAll(); rowPanel.setBackground(selected?Theme.SELECTED:Theme.SIDEBAR); rowPanel.add(label);
                    rowPanel.add(Box.createHorizontalStrut(backlogWidth(label,shownBacklog(t,t.getPathForRow(row),n))+(n.pausedQueued>0?Theme.GUTTER*2:n.project?Theme.GAP:Theme.GUTTER)+(n.project&&expanded&&projectWidgets.available(projects.get(n.id),"right")?rightWidgetWidth(projects.get(n.id))+2+(n.pausedQueued==0&&!shownAttention(t,t.getPathForRow(row),n).isEmpty()?12:0):0)),BorderLayout.EAST);
                    // One line per fact: path, attention, paused queue, then each project widget with its error (if any).
                    var hint=new StringBuilder("<html>").append(escapeHtml(n.path));
                    if(!n.attention.isEmpty())hint.append("<br>").append(n.attention.equals("question")?"Ждёт ответа":n.attention.equals("manual")?"Отмечено: требует внимания":"Работа завершена · новый результат");
                    if(n.pausedQueued>0)hint.append("<br>Очередь на паузе: ").append(n.pausedQueued);
                    if(n.backlog>0)hint.append("<br>Отложено задач: ").append(n.backlog);
                    if(n.project){var widget=projects.get(n.id);if(widget!=null){var w=widget.path("widget");for(String slot:List.of("bottom","right")){var v=w.path(slot);if(!v.isObject())continue;
                        String description=v.path("description").asText().strip(),error=v.path("error").asText().strip();
                        if(!description.isEmpty())hint.append("<br>").append(escapeHtml(description));
                        if(!error.isEmpty())hint.append("<br><font color='#e4bd82'>").append(escapeHtml(error)).append("</font>");}}}
                    hint.append("</html>");rowPanel.setToolTipText(hint.toString());label.setToolTipText(hint.toString());
                    rowPanel.setPreferredSize(null); var size=rowPanel.getPreferredSize();
                    if(t.getWidth()>0)size.width=Math.max(48,t.getWidth()-((DefaultMutableTreeNode)value).getLevel()*treeIndent(t)-t.getInsets().left-t.getInsets().right);
                    boolean below=n.project&&expanded&&projectWidgets.available(projects.get(n.id),"bottom");
                    rowPanel.setBorder(new EmptyBorder(0,0,below?12:ResourceBars.HEIGHT+1,0));size.height=28;
                    rowPanel.setPreferredSize(size);return rowPanel;
                }
                return label;
            }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener(){
            public void treeExpanded(TreeExpansionEvent e){rememberProjectExpansion(tree,e.getPath(),false);}
            public void treeCollapsed(TreeExpansionEvent e){rememberProjectExpansion(tree,e.getPath(),true);}
        });
        ToolTipManager.sharedInstance().registerComponent(tree); return tree;
    }
    private static int treeIndent(JTree tree){return tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui?ui.getLeftChildIndent()+ui.getRightChildIndent():20;}
    private static Font projectFont(JTree tree){return tree.getFont().deriveFont(Font.BOLD,Math.max(11f,tree.getFont().getSize2D()-1));}
    private int rightWidgetWidth(JsonNode project){return Math.min(96,projectWidgets.preferredWidth(project,"right",26));}
    /** Drag and drop reorders projects among projects and sessions among their siblings; nothing moves between projects. */
    private void installReordering(JTree tree){
        tree.setDragEnabled(true);tree.setDropMode(DropMode.INSERT);
        tree.setTransferHandler(new TransferHandler(){
            @Override public int getSourceActions(JComponent c){return MOVE;}
            @Override protected java.awt.datatransfer.Transferable createTransferable(JComponent c){
                var path=tree.getSelectionPath();if(path==null)return null;
                if(!(((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject() instanceof Node n)||n.file)return null;
                return new java.awt.datatransfer.StringSelection((n.project?"project:":"session:")+n.id);
            }
            @Override public boolean canImport(TransferSupport support){return support.isDrop()&&reorderTarget(tree,support)!=null;}
            @Override public boolean importData(TransferSupport support){
                var target=reorderTarget(tree,support);if(target==null)return false;
                action(()->host.post(target[0],Json.object("ids",List.of(target[1].split(",")))));return true;
            }
        });
    }
    /** Returns {endpoint, comma-separated ids in new order} for a drop, or null when the drop would change nesting or cross projects. */
    private static String[] reorderTarget(JTree tree,TransferHandler.TransferSupport support){
        String dragged;
        try{dragged=(String)support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);}catch(Exception e){return null;}
        if(dragged==null||!dragged.contains(":"))return null;
        boolean project=dragged.startsWith("project:");String id=dragged.substring(dragged.indexOf(':')+1);
        var drop=(JTree.DropLocation)support.getDropLocation();if(drop==null||drop.getPath()==null||drop.getChildIndex()<0)return null;
        var parent=(DefaultMutableTreeNode)drop.getPath().getLastPathComponent();
        DefaultMutableTreeNode source=null;
        for(var e=((DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();e.hasMoreElements();){var n=(DefaultMutableTreeNode)e.nextElement();if(n.getUserObject() instanceof Node node&&node.project==project&&!node.file&&node.id.equals(id))source=n;}
        if(source==null||source.getParent()!=parent)return null; // only among the current siblings
        var children=new ArrayList<DefaultMutableTreeNode>();for(int i=0;i<parent.getChildCount();i++)children.add((DefaultMutableTreeNode)parent.getChildAt(i));
        int from=children.indexOf(source),to=drop.getChildIndex();if(to>from)to--;
        children.remove(source);children.add(Math.min(to,children.size()),source);
        var ids=new ArrayList<String>();
        if(project){for(var c:children)if(c.getUserObject() instanceof Node n&&n.project)ids.add(n.id);return new String[]{"/projects/order",String.join(",",ids)};}
        var projectNode=parent;while(projectNode!=null&&!(projectNode.getUserObject() instanceof Node n&&n.project))projectNode=(DefaultMutableTreeNode)projectNode.getParent();
        if(projectNode==null)return null;
        collectSessions(projectNode,parent,children,ids);
        return new String[]{"/projects/"+((Node)projectNode.getUserObject()).id+"/sessions-order",String.join(",",ids)};
    }
    /** Pre-order walk of the project's sessions with one parent's children replaced by their new order. */
    private static void collectSessions(DefaultMutableTreeNode node,DefaultMutableTreeNode reordered,List<DefaultMutableTreeNode> order,List<String> ids){
        var children=new ArrayList<DefaultMutableTreeNode>();
        if(node==reordered)children.addAll(order);else for(int i=0;i<node.getChildCount();i++)children.add((DefaultMutableTreeNode)node.getChildAt(i));
        for(var child:children){if(child.getUserObject() instanceof Node n&&!n.project&&!n.file)ids.add(n.id);collectSessions(child,reordered,order,ids);}
    }
    private static String escapeHtml(String text){return text==null?"":text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static int resourceLeft(JTree tree){return tree.getInsets().left+2*treeIndent(tree)+Theme.UNIT+22+Theme.GAP;}
    private String resourceTooltip(JTree tree,MouseEvent event){
        int row=tree.getClosestRowForLocation(event.getX(),event.getY());if(row<0)return null;
        var bounds=tree.getRowBounds(row);if(bounds==null||event.getY()<bounds.y||event.getY()>=bounds.y+bounds.height)return null;
        var path=tree.getPathForRow(row);var value=((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject();
        if(!(value instanceof Node n)||n.file||n.project&&tree.isExpanded(path))return null;
        if(!ResourceBars.share(resourceUsage,machineUsage,resourceControl,n.id,n.project).hasUsage())return null;
        if(event.getX()<resourceLeft(tree)||event.getX()>=tree.getWidth()-tree.getInsets().right-Theme.GAP)return null;
        int lane=ResourceBars.lane(event.getX(),event.getY(),resourceLeft(tree),tree.getWidth()-tree.getInsets().right-Theme.GAP-resourceLeft(tree),bounds.y+bounds.height);
        return lane<0?null:ResourceBars.tooltip(lane,resourceUsage,machineUsage,resourceControl,n.id,n.project);
    }
    private void paintTreeIndicators(JTree tree,Graphics g){
        // Paint in tree coordinates: neither nesting nor title length changes the scale or dot position.
        int x=resourceLeft(tree);
        int right=tree.getWidth()-tree.getInsets().right-Theme.GAP;
        Rectangle clip=g.getClipBounds();if(clip==null)clip=new Rectangle(0,0,tree.getWidth(),tree.getHeight());
        for(int row=Math.max(0,tree.getClosestRowForLocation(0,clip.y));row<tree.getRowCount();row++){
            Rectangle bounds=tree.getRowBounds(row);if(bounds==null||bounds.y>clip.y+clip.height)break;
            var path=tree.getPathForRow(row);var value=((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject();
            if(!(value instanceof Node n))continue;var icon=n.file?Theme.icon("attachment"):n.project?IdentityIcons.ofProject(n.id,n.title,n.path):IdentityIcons.of(false,n.title,n.path);icon.paintIcon(tree,g,bounds.x+(n.project?Theme.UNIT/2:Theme.UNIT)+(22-icon.getIconWidth())/2,bounds.y+(bounds.height-icon.getIconHeight())/2);if(n.file)continue;
            if(!n.project||!tree.isExpanded(path))ResourceBars.paint(g,x,bounds.y+bounds.height,Math.max(0,right-x),ResourceBars.share(resourceUsage,machineUsage,resourceControl,n.id,n.project));
            int deferred=shownBacklog(tree,path,n);String dotted=shownAttention(tree,path,n);
            if(deferred>0){var label=backlogBounds(tree,n,deferred,dotted,bounds);var fm=tree.getFontMetrics(tree.getFont());g.setFont(tree.getFont());g.setColor(Theme.MUTED);g.drawString("["+deferred+"]",label.x,bounds.y+(bounds.height+fm.getAscent()-fm.getDescent())/2-3);}
            if(n.project&&tree.isExpanded(path)&&projectWidgets.available(projects.get(n.id))){
                var project=projects.get(n.id);int start=bounds.x+Theme.UNIT/2+22+Theme.GAP,edge=right-(n.pausedQueued>0?28:!dotted.isEmpty()?12:0)-backlogWidth(tree,deferred);
                if(projectWidgets.available(project,"right")){
                    int textWidth=tree.getFontMetrics(projectFont(tree)).stringWidth(n.title),widgetWidth=rightWidgetWidth(project);
                    int contentWidth=Math.max(textWidth,projectWidgets.preferredWidth(project,"bottom",13));
                    int gap=widgetWidth>44?8:2;
                    int widgetX=Math.max(start,Math.min(edge-widgetWidth,start+contentWidth+gap));
                    projectWidgets.paint(g,project,"right",widgetX,bounds.y+1,Math.min(widgetWidth,edge-widgetX),26);edge=widgetX-gap;
                }
                projectWidgets.paint(g,project,"bottom",start,bounds.y+14,Math.max(0,edge-start),13);
            }
            if(!dotted.isEmpty()){
                var dot=(Graphics2D)g.create();
                try{dot.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);dot.setColor(!dotted.equals("finished")?Theme.AMBER:Theme.ACCENT);dot.fill(new java.awt.geom.Ellipse2D.Double(right-7,bounds.y+(bounds.height-7)/2.0-3,7,7));}
                finally{dot.dispose();}
            }
            if(n.pausedQueued>0){int y=bounds.y+(bounds.height-8)/2-3;g.setColor(Theme.AMBER);g.fillRect(right-22,y,2,8);g.fillRect(right-17,y,2,8);}
        }
    }
    private String expansionKey(JTree tree,String project){return (tree==inactive?"inactive:":"active:")+project;}
    private void rememberProjectExpansion(JTree tree,TreePath path,boolean collapsed){
        if(changingTree||disposed)return;
        if(((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject() instanceof Node n&&n.project){
            String key=expansionKey(tree,n.id);boolean changed=collapsed?collapsedProjects.add(key):collapsedProjects.remove(key);
            if(changed){sessionViews.put("$collapsedProjects",Json.MAPPER.valueToTree(collapsedProjects));persistViews();}
            // The active list's folding is shared through the core with Remote and the phone.
            if(changed&&tree!=inactive){String id=n.id;collapseSent.put(id,System.currentTimeMillis());action(()->host.post("/projects/"+id+"/collapsed",Json.object("collapsed",collapsed)));}
        }
        tree.repaint();
    }
    private void restoreProjectExpansion(){
        var saved=sessionViews.get("$collapsedProjects");if(saved==null||!saved.isArray())return;
        collapsedProjects.clear();saved.forEach(value->collapsedProjects.add(value.asText()));
    }
    private void build() {
        var sidebar = new JPanel(new BorderLayout()); sidebar.setBackground(Theme.SIDEBAR); sidebar.setMinimumSize(new Dimension(210, 100));
        var brand = new JPanel(new BorderLayout(Theme.GAP, 0)); brand.setOpaque(false); brand.setBorder(new EmptyBorder(Theme.INSET, Theme.GUTTER, Theme.GAP, Theme.INSET));
        var actions = new JPanel(); actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS)); actions.setOpaque(false);
        var addProject = Theme.button("Проект", "Добавить каталог проекта", this::addProject); addProject.setIcon(Theme.icon("project-add")); Theme.toolbar(addProject); addProject.setVisible(!host.remote());
        var addSession = Theme.button("Сессия", "Новая сессия в выбранном проекте", this::newSession); addSession.setIcon(Theme.icon("session-new")); Theme.toolbar(addSession);
        var phone = Theme.iconButton("phone", "Подключить телефон по Wi-Fi", () -> MobileConnectDialog.open(this)); phone.getAccessibleContext().setAccessibleName("Подключить телефон"); phone.setVisible(!host.remote());
        for (var button : List.of(addProject, addSession, phone, Theme.iconButton("more","Настройки",this::workspaceSettings))) { if (!button.isVisible()) continue; if (actions.getComponentCount() > 0) actions.add(Box.createHorizontalStrut(Theme.UNIT)); actions.add(button); }
        brand.add(actions, BorderLayout.WEST);
        // The icon of the first button, not its invisible frame, lines up with the text of the panels below.
        brand.setBorder(new EmptyBorder(Theme.INSET, Theme.GUTTER - addSession.getInsets().left, Theme.GAP, Theme.INSET));
        var sidebarHeader = new JPanel(new BorderLayout()); sidebarHeader.setOpaque(false); sidebarHeader.add(brand, BorderLayout.NORTH); sidebarHeader.add(quota); sidebar.add(sidebarHeader, BorderLayout.NORTH);
        var scroll = new JScrollPane(active); scroll.setBorder(null); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); sidebar.add(scroll);
        var archive = new JPanel(new BorderLayout()); archive.setBackground(Theme.SIDEBAR); archive.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        archiveToggle.setHorizontalAlignment(SwingConstants.LEFT); Theme.toolbar(archiveToggle); archiveToggle.setIcon(Theme.icon("archive")); archiveToggle.setIconTextGap(Theme.GAP); archiveToggle.setMargin(new Insets(Theme.GAP, Theme.GUTTER, Theme.GAP, Theme.INSET)); archive.add(archiveToggle, BorderLayout.NORTH);
        inactiveScroll.setBorder(null); inactiveScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); inactiveScroll.setPreferredSize(new Dimension(240, 180)); inactiveScroll.setVisible(false); archive.add(inactiveScroll); sidebar.add(archive, BorderLayout.SOUTH);
        for (JTree tree : List.of(active, inactive)) {
            tree.addTreeSelectionListener(e -> {
                if (changingTree) return;
                var node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent(); if (node == null || !(node.getUserObject() instanceof Node n)) return;
                if (n.project) selectedProject = n.id; else if(n.file)selectDocument(n.id);else selectSession(n.id);
            });
            tree.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if(!SwingUtilities.isLeftMouseButton(e))return;int row=tree.getClosestRowForLocation(e.getX(),e.getY());var bounds=tree.getRowBounds(row);if(bounds==null)return;
                    var rowPath=tree.getPathForRow(row);if(!(((DefaultMutableTreeNode)rowPath.getLastPathComponent()).getUserObject() instanceof Node n))return;
                    if(shownBacklog(tree,rowPath,n)>0&&backlogBounds(tree,n,shownBacklog(tree,rowPath,n),shownAttention(tree,rowPath,n),bounds).contains(e.getPoint())){if(n.project)openBacklog(n.id,sessionIn(n.id));else openBacklog(sessions.get(n.id).path("projectId").asText(),n.id);return;}
                    if(!n.project&&!n.file&&rowPath.equals(tree.getSelectionPath())&&bounds.y<=e.getY()&&e.getY()<bounds.y+bounds.height){
                        SwingUtilities.invokeLater(()->{if(Objects.equals(selected,n.id)&&selectedFile==null&&isActive()&&input.isShowing()&&input.isEnabled()){viewer.releaseFocus();input.requestFocusInWindow();}});
                    }
                }
                @Override public void mousePressed(MouseEvent e) { popup(e, tree); }
                @Override public void mouseReleased(MouseEvent e) { popup(e, tree); }
            });
        }
        var header = new JPanel(new BorderLayout(Theme.INSET, 0)); header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(Theme.UNIT, Theme.GUTTER, Theme.UNIT, Theme.GUTTER)));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        var titleGroup = new JPanel(new BorderLayout(0, Theme.UNIT)); titleGroup.add(heading); titleGroup.add(status, BorderLayout.SOUTH); header.add(titleGroup);
        var controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        status.setForeground(Theme.MUTED); status.setFont(status.getFont().deriveFont(11f));
        controls.add(model);
        results.setText(""); results.getAccessibleContext().setAccessibleName("Результаты агента");
        results.setIcon(Theme.icon("image")); Theme.toolbar(results); controls.add(results);
        controls.add(Theme.iconButton("more", "Действия сессии", this::sessionMenu)); header.add(controls, BorderLayout.EAST);
        quote = Theme.button("Цитировать", "Цитировать выделение · Ctrl+Shift+Q", this::quoteSelection);
        quote.setName("chat.quote"); quote.setVisible(false); controls.add(quote,0);
        quoteMenu = new JPopupMenu(); quoteMenu.add(new AbstractAction("Цитировать выделение") { public void actionPerformed(ActionEvent e){quoteSelection();} });
        quoteMenu.add(new AbstractAction("Копировать") { public void actionPerformed(ActionEvent e){transcript.copy();} });
        chat.setMinimumSize(new Dimension(340, 0));
        var bottom = new JPanel(new BorderLayout()); requests.setLayout(new BoxLayout(requests, BoxLayout.Y_AXIS));
        var feedback = new JPanel(new BorderLayout()); feedback.add(requests, BorderLayout.NORTH); feedback.add(runStatus); bottom.add(feedback, BorderLayout.NORTH);
        var composerWrap = new JPanel(new BorderLayout()); composerWrap.setBorder(new EmptyBorder(Theme.UNIT, Theme.GUTTER, Theme.GAP, Theme.GUTTER));
        var composer = new ComposerPanel(input); composerWrap.add(composer);
        input.setLineWrap(true); input.setWrapStyleWord(true); input.setFont(input.getFont().deriveFont(14f)); input.setBorder(new EmptyBorder(Theme.UNIT, Theme.UNIT, Theme.UNIT, Theme.UNIT)); input.putClientProperty("JTextArea.placeholderText", "Сообщение агенту…");
        input.setToolTipText("Enter — отправить или дополнить очередь; Ctrl+Enter — прервать и выполнить сейчас; Shift+Enter — новая строка; Alt+Enter — отдельное сообщение в очередь.");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        input.addFocusListener(new FocusAdapter() { @Override public void focusGained(FocusEvent e) { viewer.releaseFocus(); } });
        input.addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { viewer.releaseFocus(); input.requestFocusInWindow(); } });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), javax.swing.text.DefaultEditorKit.insertBreakAction);
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), "sendSeparate");
        input.getActionMap().put("send", new AbstractAction() { public void actionPerformed(ActionEvent e) { send(); } });
        input.getActionMap().put("sendSeparate", new AbstractAction() { public void actionPerformed(ActionEvent e) { send(true); } });
        // Windows AltGr can arrive as ALT_GRAPH or as synthetic Ctrl+Alt. Both mean separate queue entry.
        for (int modifiers : new int[]{InputEvent.ALT_GRAPH_DOWN_MASK|InputEvent.CTRL_DOWN_MASK,InputEvent.ALT_GRAPH_DOWN_MASK, InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK, InputEvent.ALT_GRAPH_DOWN_MASK|InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK, InputEvent.ALT_GRAPH_DOWN_MASK|InputEvent.ALT_DOWN_MASK})
            input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,modifiers),"sendSeparate");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.CTRL_DOWN_MASK),"interrupt");
        input.getActionMap().put("interrupt",new AbstractAction(){public void actionPerformed(ActionEvent e){interrupt();}});
        input.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() { if (!applying && selected != null) { drafts.put(selected, input.getText()); draftTimer.restart(); refreshTakeControls(); } }
            public void insertUpdate(DocumentEvent e) { changed(); } public void removeUpdate(DocumentEvent e) { changed(); } public void changedUpdate(DocumentEvent e) { changed(); }
        });
        var textTransfer = input.getTransferHandler();
        input.setTransferHandler(new TransferHandler() {
            @Override public int getSourceActions(JComponent c) { return textTransfer.getSourceActions(c); }
            @Override public void exportToClipboard(JComponent c,java.awt.datatransfer.Clipboard clipboard,int action) throws IllegalStateException { textTransfer.exportToClipboard(c,clipboard,action); }
            public boolean canImport(TransferSupport s) { return selected!=null && input.isEnabled() && input.isEditable() && (imagesAvailable && s.isDataFlavorSupported(DataFlavor.imageFlavor) || s.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || s.isDataFlavorSupported(DataFlavor.stringFlavor)); }
            public boolean importData(TransferSupport s) {
                if (!canImport(s)) return false;
                try {
                    if(imagesAvailable && s.isDataFlavorSupported(DataFlavor.imageFlavor)) pasteImage((Image)s.getTransferable().getTransferData(DataFlavor.imageFlavor));
                    else if (s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) for (Object file : (List<?>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor)) attachFile(((java.io.File)file).toPath());
                    else input.replaceSelection((String) s.getTransferable().getTransferData(DataFlavor.stringFlavor)); return true;
                } catch (Exception e) { error(e); return false; }
            }
        });
        composer.add(imageStrip,BorderLayout.NORTH);
        var inputScroll = new JScrollPane(input); inputScroll.setBorder(null); inputScroll.getViewport().setBackground(Theme.FIELD); inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); composer.add(inputScroll);
        send.setName("composer.send"); sendOptions.setName("composer.sendOptions"); sendOptions.setIcon(Theme.menuArrowIcon());
        sendOptions.getAccessibleContext().setAccessibleName("Способы отправки");
        send.setIcon(Theme.icon("send")); send.setHorizontalTextPosition(SwingConstants.LEFT); send.setIconTextGap(Theme.GAP); Theme.primary(send); Theme.toolbar(sendOptions);
        stop.setName("composer.stop"); stop.setText("Стоп"); stop.setIcon(Theme.icon("stop")); Theme.toolbar(stop); stop.getAccessibleContext().setAccessibleName("Остановить сессию и подсессии, сохранить очередь");
        interrupt.setName("composer.interrupt");Theme.toolbar(interrupt);interrupt.setForeground(Theme.AMBER);
        var submit = new JPanel(new BorderLayout(Theme.GAP, 0)); submit.setOpaque(false);
        var attach = Theme.iconButton("attachment", "Добавить пути файлов в сообщение", this::attachFiles); attach.setName("composer.attach"); submit.add(attach, BorderLayout.WEST);
        var sendGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.UNIT, 0)); sendGroup.setOpaque(false); sendGroup.add(stop);sendGroup.add(interrupt);sendGroup.add(send); sendGroup.add(sendOptions); submit.add(sendGroup, BorderLayout.EAST);
        composer.add(submit, BorderLayout.SOUTH); bottom.add(composerWrap); chat.add(bottom, BorderLayout.SOUTH);
        artifacts.setName("artifact.selector"); artifacts.setMinimumSize(new Dimension(60, 28)); artifacts.setPreferredSize(new Dimension(160, 28)); artifacts.setMaximumRowCount(12);
        artifacts.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                var label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Artifact a) label.setToolTipText(a.path.toString());
                return label;
            }
        });
        var previewBar = new JPanel(new BorderLayout(Theme.GAP, 0)); previewBar.setBorder(new EmptyBorder(Theme.UNIT, Theme.GAP, Theme.UNIT, Theme.GAP)); previewBar.add(artifacts);
        var pvActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.UNIT, 0));
        fitPreview.setIcon(Theme.icon("preview-fit")); actualPreview.setIcon(Theme.icon("preview-actual-size")); fitPreview.setVisible(false); actualPreview.setVisible(false); pvActions.add(fitPreview); pvActions.add(actualPreview);
        expandPreview.setIcon(Theme.previewIcon(true)); Theme.toolbar(expandPreview); expandPreview.setName("artifact.fullscreen"); expandPreview.getAccessibleContext().setAccessibleName("На весь экран"); pvActions.add(expandPreview); getBuild.setVisible(host.remote());runBuild.setVisible(false);pvActions.add(getBuild);pvActions.add(runBuild);
        var reload = Theme.iconButton("refresh", "Обновить результат", viewer::reload);
        pvActions.add(pinPreview);pvActions.add(reload); pvActions.add(closePreview); previewBar.add(pvActions, BorderLayout.EAST);
        preview.setMinimumSize(new Dimension(270, 0));
        preview.add(previewBar, BorderLayout.NORTH); preview.add(viewer);
        viewer.onExpand(this::enterFullScreen); viewer.onEscape(this::exitFullScreen);
        artifacts.addActionListener(e -> { if (!applying) loadArtifact(); });
        preview.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "fullscreen");
        preview.getActionMap().put("fullscreen", new AbstractAction() { public void actionPerformed(ActionEvent e) { toggleFullScreen(); } });
        content.setLeftComponent(chat); content.setRightComponent(null); content.setBorder(null); content.setDividerSize(0); content.setResizeWeight(.62);
        var workspace = new JPanel(new BorderLayout()); workspace.add(header, BorderLayout.NORTH); workspace.add(content);
        workspace.setMinimumSize(new Dimension(610, 0));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, workspace); split.setDividerLocation(260); split.setDividerSize(4); split.setBorder(null); setContentPane(split);
        model.sessionEnabled(false); access.setEnabled(false); send.setEnabled(false); stop.setEnabled(false);
    }
    private static int backlogWidth(JComponent c,int count){return count>0?c.getFontMetrics(c.getFont()).stringWidth("["+count+"]")+Theme.GAP:0;}
    private static Rectangle backlogBounds(JTree tree,Node n,int count,String dotted,Rectangle row){
        int right=tree.getWidth()-tree.getInsets().right-Theme.GAP,edge=right-(n.pausedQueued>0?28:!dotted.isEmpty()?12:0),width=backlogWidth(tree,count);
        return new Rectangle(edge-width+Theme.GAP,row.y,width,row.height);
    }
    /** Session rows under a project that are visible, or that are not: a project repeats only what no visible row shows. */
    private static List<Node> sessionsUnder(JTree tree,TreePath path,boolean visible){
        var found=new ArrayList<Node>();var top=(DefaultMutableTreeNode)path.getLastPathComponent();
        for(var e=top.depthFirstEnumeration();e.hasMoreElements();){var node=(DefaultMutableTreeNode)e.nextElement();if(node!=top&&node.getUserObject() instanceof Node m&&!m.file&&tree.isVisible(new TreePath(node.getPath()))==visible)found.add(m);}
        return found;
    }
    static int shownBacklog(JTree tree,TreePath path,Node n){
        if(!n.project||path==null)return n.backlog;
        int onRows=0;for(var m:sessionsUnder(tree,path,true))onRows+=m.backlog;
        return Math.max(0,n.backlog-onRows);
    }
    static String shownAttention(JTree tree,TreePath path,Node n){
        if(!n.project||path==null)return n.attention;
        String shown="";
        for(var m:sessionsUnder(tree,path,false)){if(m.attention.equals("question"))return "question";if(m.attention.equals("manual"))shown="manual";else if(shown.isEmpty())shown=m.attention;}
        return shown;
    }
    private String sessionIn(String project){var s=selected==null?null:sessions.get(selected);return s!=null&&s.path("projectId").asText().equals(project)?selected:null;}
    /** Asked from the menu, or quietly at start: then only a newer copy is offered. */
    void updateRemote(boolean asked){
        if(!(host instanceof dev.tethium.remote.RemoteClient remote))return;
        if(dev.tethium.remote.RemoteUpdate.installDir()==null){if(asked)JOptionPane.showMessageDialog(this,"Эта копия Remote обновляется вручную: скачайте новый архив со страницы ноутбука.","Tethium Remote",JOptionPane.INFORMATION_MESSAGE);return;}
        Thread.ofVirtual().start(()->{
            try{
                String available=dev.tethium.remote.RemoteUpdate.available(remote.connection()),installed=dev.tethium.remote.RemoteUpdate.installed();
                boolean newer=!available.isEmpty()&&!available.equals(installed);
                SwingUtilities.invokeLater(()->{
                    if(!newer){if(asked)JOptionPane.showMessageDialog(this,"Установлена последняя версия Remote.","Tethium Remote",JOptionPane.INFORMATION_MESSAGE);return;}
                    if(JOptionPane.showConfirmDialog(this,"На ноутбуке есть новая версия Tethium Remote. Обновить и перезапустить сейчас?","Tethium Remote",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
                    saveDraft();status.setText("Скачиваем обновление Remote…");
                    Thread.ofVirtual().start(()->{try{dev.tethium.remote.RemoteUpdate.prepare(remote.connection(),available);SwingUtilities.invokeLater(this::closeWindow);}
                        catch(Exception failure){SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(this,"Не удалось обновить: "+failure.getMessage(),"Tethium Remote",JOptionPane.WARNING_MESSAGE));}});
                });
            }catch(Exception failure){if(asked)SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(this,"Не удалось проверить обновление: "+failure.getMessage(),"Tethium Remote",JOptionPane.WARNING_MESSAGE));}
        });
    }
    /** Remote names its build in the title, so an update that did not arrive is visible without taking space in the window. */
    private String versionSuffix(){return host.remote()?" · Remote "+version():"";}
    static String version(){
        String build=dev.tethium.update.BuildInfo.BUILD,archive=dev.tethium.remote.RemoteUpdate.installed();
        return (build.length()>7?build.substring(0,7):build)+(archive.isEmpty()?"":" · архив "+archive.substring(0,Math.min(7,archive.length())));
    }
    private void openSessionBacklog(){var session=selected==null?null:sessions.get(selected);if(session!=null)openBacklog(session.path("projectId").asText(),selected);}
    private void openBacklog(String project,String session){BacklogDialog.open(this,host,projects,sessions,project,session);}
    private void popup(MouseEvent event, JTree tree) {
        if (!event.isPopupTrigger()) return;
        var path = tree.getPathForLocation(event.getX(), event.getY()); if (path == null) return;
        tree.setSelectionPath(path); var n = (Node) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        var menu = new JPopupMenu();
        var create = new JMenuItem("Новая сессия"); create.addActionListener(e -> newSession()); menu.add(create);
        if (!n.project&&!n.file) {
            var rename = new JMenuItem("Переименовать"); rename.addActionListener(e -> rename()); menu.add(rename);
            var state = new JMenuItem(sessions.get(n.id).path("active").asBoolean() ? "Деактивировать" : "Активировать"); state.addActionListener(e -> toggleActive()); menu.add(state);
            var attention=new JCheckBoxMenuItem("Требует внимания",sessions.get(n.id).path("attention").path("manual").asBoolean());attention.addActionListener(e->toggleAttention(n.id));menu.add(attention);
            var technical=new JCheckBoxMenuItem("Техническая сессия",sessions.get(n.id).path("technical").asBoolean());technical.setToolTipText("Без индикаторов, не ждёт ответов человека и работает даже в неактивных");technical.addActionListener(e->{String id=n.id;boolean on=technical.isSelected();action(()->{host.post("/sessions/"+id+"/technical",Json.object("enabled",on));poller.execute(this::poll);});});menu.add(technical);
            var reset=new JMenuItem("История и сброс…");reset.addActionListener(e->{var s=sessions.get(n.id);if(s!=null){SessionResetDialog.show(this,host,n.id,s.path("title").asText(),s.path("running").asBoolean());poller.execute(this::poll);}});menu.add(reset);
            var trash=new JMenuItem("Удалить сессию…");trash.addActionListener(e->trashSession(n.id));menu.add(trash);
        }
        if(n.file){
            if(documents.get(n.id).path("panel").asBoolean()){var reload=new JMenuItem("Обновить панель");reload.addActionListener(e->viewer.reload());menu.add(reload);menu.show(tree,event.getX(),event.getY());return;}
            var rename=new JMenuItem("Переименовать пункт…");rename.addActionListener(e->{String title=JOptionPane.showInputDialog(this,"Название",n.title);if(title!=null&&!title.isBlank())action(()->host.post("/files/"+n.id+"/rename",Json.object("title",title)));});menu.add(rename);
            var remove=new JMenuItem("Открепить файл");remove.addActionListener(e->unpinDocument(n.id));menu.add(remove);menu.show(tree,event.getX(),event.getY());return;
        }
        if(!n.project&&!n.file){var pinSession=new JMenuItem("Закрепить файл справа…");pinSession.addActionListener(e->choosePinnedFile(false));menu.add(pinSession);}
        if(n.project){var archive=new JMenuItem(projects.get(n.id).path("active").asBoolean(true)?"В неактивные весь проект":"Вернуть проект в активные");archive.addActionListener(e->action(()->host.post("/projects/"+n.id+"/active",Json.object("active",!projects.get(n.id).path("active").asBoolean(true)))));menu.add(archive);}
        if(n.project){var pin=new JMenuItem("Закрепить файл в проекте…");pin.addActionListener(e->choosePinnedFile(true));menu.add(pin);}
        if(n.project&&!host.remote()){var settings=new JMenuItem("Настройки проекта и GitHub…");settings.addActionListener(e->ProjectDialog.open(this,host,projects.get(n.id),value->{}));menu.add(settings);}
        var cpuItem=new JMenuItem("CPU: лимит и приоритет…");cpuItem.addActionListener(e->configureCpu(n.project?"project":"session",n.id,n.title));menu.add(cpuItem);
        var backlogItem=new JMenuItem("Отложенные задачи…");backlogItem.addActionListener(e->openBacklog(n.project?n.id:sessions.get(n.id).path("projectId").asText(),n.project?null:n.id));menu.add(backlogItem);
        var usageItem=new JMenuItem("Токены и CPU…");usageItem.addActionListener(e->UsageDialog.open(this,host,projects,sessions,n.project?n.id:sessions.get(n.id).path("projectId").asText(),n.project?null:n.id));menu.add(usageItem);
        menu.show(tree, event.getX(), event.getY());
    }
    private void sessionMenu() {
        if(selectedFile!=null){var doc=documents.get(selectedFile);if(doc==null)return;if(doc.path("panel").asBoolean()){viewer.reload();return;}var menu=new JPopupMenu();var unpin=new JMenuItem("Открепить файл от проекта");String id=selectedFile;unpin.addActionListener(e->unpinDocument(id));menu.add(unpin);menu.show(heading,0,heading.getHeight());return;}
        var menu = new JPopupMenu();
        var accessInfo = new JMenuItem("Полный доступ"); accessInfo.setEnabled(false); menu.add(accessInfo); menu.addSeparator();
        for (var entry : List.<Map.Entry<String, Runnable>>of(Map.entry("Новая сессия", this::newSession), Map.entry("Переименовать", this::rename),
                Map.entry("Активировать / деактивировать", this::toggleActive), Map.entry("Требует внимания · переключить",()->toggleAttention(selected)), Map.entry("Удалить сессию…",()->trashSession(selected)), Map.entry("Результаты агента", () -> {if(previewOpen)closePreview();else showPreview(true);}),
                Map.entry("Открыть изображение, HTML или Markdown…", this::chooseArtifact), Map.entry("Закрепить файл справа…",()->choosePinnedFile(false)), Map.entry("Периодическая задача…",this::configureSchedule), Map.entry("История и сброс…",this::resetDialog), Map.entry("Действия агента…", this::showActivity))) {
            var item = new JMenuItem(entry.getKey()); item.addActionListener(e -> entry.getValue().run()); menu.add(item);
        }
        if(selected!=null){String id=selected;var a=sessions.get(id).path("autonomy");var autonomous=new JCheckBoxMenuItem("Работать автономно до завершения цели",Set.of("active","waiting").contains(a.path("state").asText()));autonomous.addActionListener(e->{if(!autonomous.isSelected()){action(()->host.post("/sessions/"+id+"/autonomy",Json.object("action","disable")));return;}String goal=a.path("goal").asText();if(goal.isBlank()||a.path("state").asText().equals("complete")){goal="";for(var m:conversation.path("messages"))if(m.path("role").asText().equals("user"))goal=m.path("text").asText();}var editor=new JTextArea(goal,6,52);editor.setLineWrap(true);editor.setWrapStyleWord(true);if(JOptionPane.showConfirmDialog(this,new JScrollPane(editor),"Цель автономной работы",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION&&!editor.getText().isBlank())action(()->host.post("/sessions/"+id+"/autonomy",Json.object("action","start","goal",editor.getText(),"resumeQueue",true)));});menu.add(autonomous);}
        {var thoughts=new JCheckBoxMenuItem("Показывать мысли Claude в ответе",ChatMarkdown.showThoughts);thoughts.addActionListener(e->{ChatMarkdown.showThoughts=thoughts.isSelected();sessionViews.put("$showThoughts",Json.MAPPER.getNodeFactory().booleanNode(thoughts.isSelected()));persistViews();});menu.add(thoughts);}
        if(selected!=null){String id=selected;var automatic=new JCheckBoxMenuItem("Продолжать автоматически после появления квоты",sessions.get(id).path("quotaWait").path("autoResume").asBoolean());automatic.addActionListener(e->action(()->host.post("/sessions/"+id+"/quota-auto",Json.object("enabled",automatic.isSelected()))));menu.add(automatic);}
        menu.addSeparator(); var quit = new JMenuItem("Завершить ядро и все запуски"); quit.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Остановить все запуски и завершить Tethium?", "Завершение", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                action(() -> { host.post("/shutdown", Json.object()); SwingUtilities.invokeLater(this::closeWindow); });
        }); if(!host.remote())menu.add(quit);else{var reconnect=new JMenuItem("Подключить другой ноутбук…");reconnect.addActionListener(e->{saveDraft();RemoteMain.restartPairing(this);});menu.add(reconnect);var update=new JMenuItem("Обновить Tethium Remote…");update.addActionListener(e->updateRemote(true));menu.add(update);var version=new JMenuItem("Версия: "+version());version.setEnabled(false);menu.add(version);} menu.show(heading, Math.max(0, heading.getWidth() - 100), heading.getHeight());
    }
    private void workspaceSettings(){
        var menu=new JPopupMenu();var files=new JMenuItem("Файлы…");files.addActionListener(e->FileManagerDialog.open(this,host,selectedProject));menu.add(files);var allBacklog=new JMenuItem("Отложенные задачи…");allBacklog.addActionListener(e->openBacklog("",null));menu.add(allBacklog);var reminderList=new JMenuItem("Напоминания…");reminderList.addActionListener(e->{if(reminderWindow!=null)reminderWindow.openList();});menu.add(reminderList);var cpu=new JMenuItem("CPU: общий лимит и приоритет…");cpu.addActionListener(e->configureCpu("global","","Все исполнения"));menu.add(cpu);var trash=new JMenuItem("Корзина сессий…");trash.addActionListener(e->TrashDialog.open(this,host,()->poller.execute(this::poll)));menu.add(trash);menu.show(active,0,0);
    }
    private void configureCpu(String scope,String id,String title){
        action(()->{var state=host.get("/resources");var row=scope.equals("global")?state.path("global"):state.path(scope+"s").path(id);SwingUtilities.invokeLater(()->{var settings=CpuSettingsDialog.show(this,scope,title,state,row);if(settings!=null)action(()->host.post("/resources",Json.object("scope",scope,"target",id,"settings",settings)));});});
    }
    private void resetDialog(){
        if(selected==null)return;String id=selected;var session=sessions.get(id);if(session==null)return;
        SessionResetDialog.show(this,host,id,session.path("title").asText(),session.path("running").asBoolean());poller.execute(this::poll);
    }
    private void configureSchedule(){
        if(selected==null)return;String id=selected;var session=sessions.get(id);if(session==null)return;
        var command=ScheduleDialog.show(this,session.path("schedule"));if(command!=null)action(()->host.post("/sessions/"+id+"/schedule",command));
    }
    private void trashSession(String id){
        var session=sessions.get(id);if(session==null)return;
        if(JOptionPane.showConfirmDialog(this,"Удалить «"+session.path("title").asText()+"» из списка?\nИстория сохранится в настройках → корзина. Текущая работа продолжится.","Удаление сессии",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        action(()->host.post("/sessions/"+id+"/trash",Json.object("confirmed",true)));
    }
    private void choosePinnedFile(boolean projectFile){
        if(selectedProject==null||!projectFile&&selected==null)return;String pid=selectedProject,id=selected;String path;
        if(host.remote())path=JOptionPane.showInputDialog(this,"Путь к файлу на ноутбуке, относительно каталога проекта","");
        else{var picker=new JFileChooser(projects.get(pid).path("root").asText());picker.setDialogTitle(projectFile?"Закрепить файл в проекте":"Файл, открываемый справа у сессии");picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Markdown, HTML, изображения","md","markdown","html","htm","svg","png","jpg","jpeg","gif","webp"));if(picker.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;path=picker.getSelectedFile().getAbsolutePath();}
        if(path==null||path.isBlank())return;String file=path;
        action(()->{if(projectFile){var doc=host.post("/projects/"+pid+"/files",Json.object("path",file));SwingUtilities.invokeLater(()->{documents.put(doc.path("id").asText(),doc);selectDocument(doc.path("id").asText());});}else host.post("/sessions/"+id+"/pinned-file",Json.object("path",file));});
    }
    private void togglePinnedPreview(){
        if(selected==null||!(artifacts.getSelectedItem() instanceof Artifact a))return;String id=selected;boolean pinned=a.path.toString().equals(sessions.get(id).path("pinnedFile").asText());if(pinned&&!confirmUnpin())return;action(()->host.post("/sessions/"+id+"/pinned-file",Json.object("path",pinned?"":a.path.toString())));
    }
    private void updatePinButton(){
        boolean pinned=selected!=null&&sessions.containsKey(selected)&&artifacts.getSelectedItem() instanceof Artifact a&&a.path.toString().equals(sessions.get(selected).path("pinnedFile").asText());
        pinPreview.setSelected(pinned);pinPreview.setEnabled(selected!=null&&artifacts.getSelectedItem()!=null&&selectedPanel()==null);pinPreview.setForeground(pinned?Theme.ACCENT:Theme.MUTED);pinPreview.setToolTipText(pinned?"Открепить файл от сессии":"Всегда открывать этот файл справа у сессии");
    }
    public void selectDocument(String id){
        if(!documents.containsKey(id))return;if(documents.get(id).path("panel").asBoolean()&&selected!=null)panelReturnSession=selected;saveDraft();rememberPreview();exitFullScreen();selected=null;selectedFile=id;restoreViewFor=null;documentSignature="";input.setEnabled(false);sessionViews.put("$document",Json.MAPPER.getNodeFactory().textNode(id));persistViews();renderDocument();selectTreeEntry(id);treeSignature="";poller.execute(this::poll);
    }
    private void renderDocument(){
        var document=documents.get(selectedFile);if(document==null)return;selectedProject=document.path("projectId").asText();
        heading.setText(document.path("title").asText());status.setText(document.path("path").asText());status.setToolTipText(status.getText());setTitle(projects.get(selectedProject).path("title").asText()+": "+heading.getText()+versionSuffix());
        model.setVisible(false);results.setVisible(false);pinPreview.setVisible(false);closePreview.setVisible(true);content.setLeftComponent(null);
        String signature=document.toString();if(!signature.equals(documentSignature)){documentSignature=signature;applying=true;artifacts.removeAllItems();artifacts.addItem(new Artifact(document.path("title").asText(),Path.of(document.path("path").asText())));applying=false;previewOpen=false;showPreview(true);}content.setDividerSize(0);content.setDividerLocation(0);
    }
    public void selectSession(String id) {
        if (Objects.equals(selected, id)) return;
        var session = sessions.get(id); if(session==null)return;
        saveDraft();rememberPreview();exitFullScreen();switchingView=true;selectedFile=null;initialDocument=null;sessionViews.remove("$document");persistViews();content.setLeftComponent(chat);model.setVisible(true);results.setVisible(true);pinPreview.setVisible(true);closePreview.setVisible(true);selected = id;restoreViewFor=id;pinnedSignature="";
        selectedProject = session.path("projectId").asText(); requestSignature = ""; artifactSignature = ""; conversation = Json.object();
        boolean cached=chatViews.containsKey(id);var previous=chatView;bindChat(chatViewFor(id));if(previous!=null&&previous!=chatView&&!chatViews.containsValue(previous))previous.close();
        if(!cached){chatSignature = ""; scrollToLatest = true; chatRender++; chatNavigation.reset();pagedHistory.reset(id);}
        applying = true;
        input.setText(drafts.getOrDefault(id, session.path("preferences").path("draft").asText()));
        draftImages.computeIfAbsent(id,key->strings(session.path("preferences").path("draftImages"))); refreshImages();
        var preferences = settingsDrafts.getOrDefault(id, session.path("preferences"));
        model.selection(preferences.path("provider").asText(null), preferences.path("model").asText(null), preferences.path("reasoningEffort").asText(null));
        model.loading(); catalogSession = null; catalogAttempt = 0;
        applying = false; renderHeader(session); requests.removeAll(); if(!cached)transcript.setText(page("<p class='muted'>Загружаем историю…</p>"));
        showPreview(false); viewer.clear(); applying = true; artifacts.removeAllItems(); applying = false;switchingView=false;
        runStatus.update(session, conversation);
        selectTreeEntry(id);treeSignature="";input.requestFocusInWindow(); poller.execute(this::poll);
    }
    private void selectTreeEntry(String id){
        boolean previous=changingTree;changingTree=true;
        try{for(var tree:List.of(active,inactive)){var nodes=((DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();while(nodes.hasMoreElements()){var node=nodes.nextElement();if(node instanceof DefaultMutableTreeNode value&&value.getUserObject() instanceof Node n&&n.id.equals(id)){tree.setSelectionPath(new TreePath(value.getPath()));return;}}}}finally{changingTree=previous;}
    }
    private String executorTitle(JsonNode session) {
        if(session.path("running").asBoolean()&&!session.path("runProvider").asText("").isBlank())return session.path("runProvider").asText().equals("CLAUDE")?"Claude":"Codex";
        var draft = settingsDrafts.get(session.path("id").asText());
        String provider = (draft != null ? draft : session.path("preferences")).path("provider").asText("");
        return provider.equalsIgnoreCase("CLAUDE") ? "Claude" : "Codex";
    }
    private void renderHeader(JsonNode session) {
        boolean enabled = session.path("active").asBoolean(); boolean busy = session.path("running").asBoolean();
        String executor = executorTitle(session); input.putClientProperty("JTextArea.placeholderText", "Сообщение " + executor + "…");
        heading.setText(session.path("title").asText()); heading.setToolTipText(session.path("directory").asText());
        var project = projects.get(session.path("projectId").asText());
        setTitle((project == null ? "Tethium" : project.path("title").asText("Tethium")) + ": " + session.path("title").asText() + versionSuffix());
        status.setText(busy ? session.path("phase").asText("Работает") : !enabled ? "Неактивна" : session.path("status").asText().equals("FAILED") ? "Ошибка" : executor);
        int queued = session.path("queued").asInt(); if (queued > 0) status.setText(status.getText() + " · очередь " + queued);
        status.setToolTipText(status.getText());
        boolean paused = session.path("queuePaused").asBoolean();
        if (!busy && paused) status.setText("На паузе");
        else if(session.path("quotaWait").path("waiting").asBoolean()){status.setText(session.path("quotaWait").path("autoResume").asBoolean()?"Ждём квоту · продолжим автоматически":"Нет квоты · автопродолжение выключено");status.setForeground(Theme.AMBER);}else status.setForeground(Theme.MUTED);
        var autonomy=session.path("autonomy");String autonomyState=autonomy.path("state").asText();
        if(autonomyState.equals("active")){status.setText(status.getText()+" · автономно");status.setToolTipText(autonomy.path("goal").asText());}else if(!busy&&autonomyState.equals("waiting")){status.setText("Автономно · ждём результат подсессии");}else if(!busy&&autonomyState.equals("blocked")){status.setText("Автономная работа ждёт вашего участия");status.setForeground(Theme.AMBER);status.setToolTipText(autonomy.path("reason").asText());}
        var timer=session.path("schedule");
        if(timer.path("state").asText().equals("active")){
            if(!busy&&!paused&&!session.path("quotaWait").path("waiting").asBoolean())status.setText("По расписанию · "+ScheduleDialog.nextTime(timer));
            status.setToolTipText("Следующий запуск: "+ScheduleDialog.nextTime(timer)+" · "+timer.path("prompt").asText());
        }else if(!busy&&timer.path("state").asText().equals("paused"))status.setText("Расписание на паузе");
        boolean taking = pendingTake != null && session.path("id").asText().equals(pendingTake.path("sessionId").asText()) || pendingInterrupt!=null && session.path("id").asText().equals(pendingInterrupt.path("sessionId").asText());
        input.setEnabled(enabled); input.setEditable(!taking); model.sessionEnabled(true); access.setEnabled(true); send.setEnabled(enabled && !taking); sendOptions.setEnabled(enabled && !taking);
        boolean writing=writingImages.getOrDefault(selected,0)>0;
        if(writing) {send.setEnabled(false);sendOptions.setEnabled(false);}
        boolean relatedWork=relatedWork(session.path("id").asText());
        interrupt.setVisible(relatedWork || queued>0 || paused);
        interrupt.setEnabled(interruptAvailable&&enabled&&!taking&&!writing&&(!input.getText().isBlank()||!draftImages.getOrDefault(selected,List.of()).isEmpty()||queued>0));
        stop.setEnabled((relatedWork||timer.path("state").asText().equals("active")) && !session.path("stopping").asBoolean());
        send.setText(queued > 0 ? "Дополнить" : busy || paused ? "В очередь" : "Отправить");
        send.setToolTipText(queued > 0 ? "Enter — добавить часть после разделителя --- в последнее сообщение очереди" : "Enter — отправить; если есть очередь — дополнить последнее сообщение");
    }
    private boolean relatedWork(String id) {
        var related=new HashSet<String>();related.add(id);boolean changed;
        do{changed=false;for(var session:sessions.values())if(related.contains(session.path("parentId").asText()))changed|=related.add(session.path("id").asText());}while(changed);
        for(String target:related){var session=sessions.get(target);if(session!=null&&(session.path("running").asBoolean()||session.path("queued").asInt()>0&&!session.path("queuePaused").asBoolean()))return true;}
        return false;
    }
    private void poll() {
        if (disposed) return;
        String target = selected;
        try { var state = host.get("/state" + (target == null ? "" : "?session=" + target+"&history=window")); SwingUtilities.invokeLater(() -> apply(state, target)); }
        catch (Exception e) { SwingUtilities.invokeLater(() -> { if (!disposed) { status.setText("Нет связи с ядром"); status.setToolTipText(e.getMessage()); } }); }
    }
    private ReminderWindow reminderWindow;
    private void apply(JsonNode state, String target) {
        if (disposed) return;
        if(reminderWindow==null)reminderWindow=new ReminderWindow(this,host);reminderWindow.update(state.path("reminders"));
        quota.update(state.path("quota"));
        quota.machineUsage(state.path("machineUsage"));
        if(!machineUsage.equals(state.path("machineUsage"))){machineUsage=state.path("machineUsage");active.repaint();inactive.repaint();}
        if(!resourceControl.equals(state.path("resources"))){resourceControl=state.path("resources");active.repaint();inactive.repaint();}
        if(!resourceUsage.equals(state.path("resourceUsage"))){resourceUsage=state.path("resourceUsage");active.repaint();inactive.repaint();}
        queueEditingAvailable = state.path("queueEditing").asBoolean();
        groupingAvailable=state.path("queueGrouping").asBoolean();imagesAvailable=state.path("imageAttachments").asBoolean();interruptAvailable=state.path("priorityInterrupt").asBoolean();
        runStatus.setGroupingAvailable(groupingAvailable);runStatus.setQueueEditing(queueEditingAvailable, pendingTake != null || grouping);
        projects.clear(); for (var p : state.path("projects")) projects.put(p.path("id").asText(), p);
        for (var p : state.path("projects")) { String pid = p.path("id").asText(), version = p.path("icon").asText();
            if (IdentityIcons.projectIconStale(pid, version)) io.submit(() -> { try { var icon = host.get("/project-icon?project=" + pid); IdentityIcons.registerProjectIcon(pid, icon.path("version").asText(), icon.path("png").asText()); SwingUtilities.invokeLater(() -> { active.repaint(); inactive.repaint(); }); } catch (Exception e) { IdentityIcons.registerProjectIcon(pid, version, ""); } }); }
        projectWidgets.update(projects.values());active.repaint();inactive.repaint();
        sessions.clear(); for (var s : state.path("sessions")) sessions.put(s.path("id").asText(), s);
        documents.clear();for(var d:state.path("files"))documents.put(d.path("id").asText(),d);
        if(!host.remote())for(var project:projects.values())if(project.path("panel").isObject()){
            var panel=project.path("panel");String pid=project.path("id").asText(),id="panel-"+pid;
            documents.put(id,Json.object("id",id,"projectId",pid,"title",panel.path("title"),"path",panel.path("path"),"panel",true,"config",panel));
        }
        syncCollapsed(state);
        String signature = state.path("files").toString()+state.path("projects").toString() + state.path("sessions").findValues("id") + state.path("sessions").findValues("title") + state.path("projects").findValues("backlog") + state.path("sessions").findValues("backlog") + state.path("sessions").findValues("active") + state.path("sessions").findValues("technical") + state.path("sessions").findValues("running") + state.path("sessions").findValues("status") + state.path("sessions").findValues("attention");
        signature+=state.path("sessions").findValues("queuePaused").toString()+state.path("sessions").findValues("queued");
        if (!signature.equals(treeSignature)) { rebuildTrees(state); treeSignature = signature; }
        if (selectedProject == null && !projects.isEmpty()) selectedProject = projects.keySet().iterator().next();
        if(initialDocument!=null){String id=initialDocument;initialDocument=null;if(documents.containsKey(id))selectDocument(id);}
        if(selectedFile!=null){
            if(documents.containsKey(selectedFile)){renderDocument();lastAppliedState=System.currentTimeMillis();finishHandoff();retryQueueTake();retryInterrupt();return;}
            selectedFile=null;documentSignature="";sessionViews.remove("$document");persistViews();content.setLeftComponent(chat);model.setVisible(true);results.setVisible(true);pinPreview.setVisible(true);closePreview.setVisible(true);showPreview(false);viewer.clear();
        }
        if(selected!=null&&!sessions.containsKey(selected)){var gone=chatViews.remove(selected);if(gone!=null&&gone!=chatView)gone.close();pagedHistory.legacy();chatNavigation.reset();input.setEnabled(false);send.setEnabled(false);sendOptions.setEnabled(false);stop.setEnabled(false);interrupt.setEnabled(false);selected=null;restoreViewFor=null;chatSignature="";input.setText("");showPreview(false);viewer.clear();transcript.setText(page("<p>Сессия убрана из списка. Восстановить её можно в настройках → корзина.</p>"));}
        if (selected == null && !sessions.isEmpty()) {
            String first = initialSelection != null && sessions.containsKey(initialSelection) ? initialSelection : state.path("sessions").get(0).path("id").asText();
            initialSelection = null; selectSession(first); treeSignature = ""; return;
        }
        if (selected != null && sessions.containsKey(selected)) renderHeader(sessions.get(selected));
        if (!Objects.equals(target, selected)) return;
        conversation = state.path("conversation");
        if (selected != null && sessions.containsKey(selected)) runStatus.update(sessions.get(selected), conversation);
        String pin=selected==null?"":sessions.get(selected).path("pinnedFile").asText("");if(!pin.equals(pinnedSignature)){pinnedSignature=pin;if(!pin.isBlank())restoreViewFor=selected;}
        renderChat(); renderRequests(); renderArtifacts();restorePreview();updatePinButton();
        SwingUtilities.invokeLater(this::acknowledgeVisibleResult);
        if (sessions.isEmpty()) transcript.setText(page("<h2>Начнём с проекта</h2><p>Добавьте каталог кнопкой <b>+ Проект</b>, затем создайте сессию.</p><p class='muted'>Codex и Claude работают на этом компьютере. История сохраняется автоматически.</p>"));
        lastAppliedState = System.currentTimeMillis();
        loadModelCatalog();
        boolean updating = externalUpdate || state.path("updating").asBoolean(); runStatus.setUpdating(updating);
        if (updating) { status.setText("Обновление ядра · текущая работа продолжается"); status.setToolTipText("Очередь продолжится после проверки новой версии ядра"); }
        finishHandoff();
        retryQueueTake();retryInterrupt();
    }
    private void finishHandoff(){
        if (pendingHandoff != null) {
            var saved = pendingHandoff; pendingHandoff = null;
            saved.path("drafts").fields().forEachRemaining(e -> drafts.put(e.getKey(), e.getValue().asText()));
            saved.path("draftImages").fields().forEachRemaining(e->draftImages.put(e.getKey(),strings(e.getValue())));refreshImages();
            applying = true; input.setText(drafts.getOrDefault(selected, input.getText()));
            model.selection(saved.path("provider").asText(model.provider()), saved.path("model").asText(model.model()), saved.path("reasoningEffort").asText(model.effort())); applying = false;
            if (saved.path("queueTake").isObject()) { pendingTake = saved.path("queueTake"); takeRetryAt = 0; }
            if(saved.path("priorityInterrupt").isObject()) { pendingInterrupt=saved.path("priorityInterrupt");interruptRetryAt=0; }
            if (saved.path("inactiveOpen").asBoolean() != inactiveOpen) toggleInactive();
            if (saved.path("previewOpen").asBoolean() && artifacts.getItemCount() > 0) {
                showPreview(true);
                for (int i = 0; i < artifacts.getItemCount(); i++) if (artifacts.getItemAt(i).path.toString().equals(saved.path("artifact").asText())) artifacts.setSelectedIndex(i);
                content.setDividerLocation(selectedFile==null?saved.path("previewDivider").asInt(content.getDividerLocation()):0);
            }
            runStatus.restoreQueueExpanded(saved.path("queueExpanded").asBoolean());
            SwingUtilities.invokeLater(() -> {var position=saved.has("chatPosition")?saved.path("chatPosition"):saved;if(pagedHistory.enabled())pagedHistory.restorePosition(position);else chatNavigation.restorePosition(position);});
        }
    }
    private void loadModelCatalog() {
        if (selected == null || catalogLoading || Objects.equals(catalogSession, selected) && System.currentTimeMillis() - catalogAttempt < 30_000) return;
        String id = selected; catalogSession = id; catalogAttempt = System.currentTimeMillis(); catalogLoading = true;
        Thread.ofVirtual().name("model-catalog").start(() -> {
            JsonNode result;
            try { result = host.get("/models?session=" + id); }
            catch (Exception e) { result = Json.object("available", false, "error", "Каталог моделей пока недоступен. Повторяем подключение…"); }
            JsonNode loaded = result;
            SwingUtilities.invokeLater(() -> {
                catalogLoading = false;
                if (!disposed && Objects.equals(selected, id)) {
                    model.catalog(loaded);
                    if (loaded.path("available").asBoolean()) catalogAttempt = System.currentTimeMillis() + 570_000;
                }
            });
        });
    }
    private void rebuildTrees(JsonNode state) {
        changingTree = true;
        try {
            for (boolean archived : new boolean[]{false, true}) {
                JTree tree = archived ? inactive : active;
                var root = new DefaultMutableTreeNode("root"); TreePath selection = null;
                for (var project : projects.values()) {
                    String pid = project.path("id").asText();
                    var matching = new ArrayList<JsonNode>(); for (var s : state.path("sessions")) if (s.path("projectId").asText().equals(pid) && (s.path("active").asBoolean()&&project.path("active").asBoolean(true)) != archived) matching.add(s);
                    boolean projectArchived=!project.path("active").asBoolean(true);if(!archived&&projectArchived||archived&&matching.isEmpty()&&!projectArchived)continue;
                    var noticed=matching.stream().filter(s->!s.path("technical").asBoolean()).toList();
                    boolean running=noticed.stream().anyMatch(s->s.path("running").asBoolean());
                    String attention=noticed.stream().anyMatch(s->s.path("attention").path("kind").asText().equals("question"))?"question":noticed.stream().anyMatch(s->s.path("attention").path("manual").asBoolean())?"manual":noticed.stream().anyMatch(s->!s.path("attention").path("kind").asText().isEmpty())?"finished":"";
                    int pausedQueued=noticed.stream().filter(s->s.path("queuePaused").asBoolean()).mapToInt(s->Math.max(0,s.path("queued").asInt())).sum();
                    var projectNode = new DefaultMutableTreeNode(new Node(pid, project.path("title").asText(), true, running, "", project.path("root").asText(),attention,false,pausedQueued,project.path("backlog").asInt())); root.add(projectNode);
                    if(archived==projectArchived)for(var document:documents.values())if(document.path("projectId").asText().equals(pid)){var node=new DefaultMutableTreeNode(new Node(document.path("id").asText(),document.path("title").asText(),false,false,"",document.path("path").asText(),"",true));projectNode.add(node);if(document.path("id").asText().equals(selectedFile))selection=new TreePath(node.getPath());}
                    var nodes = new LinkedHashMap<String, DefaultMutableTreeNode>();
                    for (var s : matching) nodes.put(s.path("id").asText(), new DefaultMutableTreeNode(new Node(s.path("id").asText(), s.path("title").asText(), false, s.path("running").asBoolean()&&!s.path("technical").asBoolean(), s.path("technical").asBoolean()?"":s.path("status").asText(), s.path("directory").asText(),s.path("attention").path("kind").asText(),false,0,s.path("technical").asBoolean()?0:s.path("backlog").asInt())));
                    for (var s : matching) { var n = nodes.get(s.path("id").asText()); var parent = nodes.get(s.path("parentId").asText()); (parent == null ? projectNode : parent).add(n); }
                    for (var entry : nodes.entrySet()) if (entry.getKey().equals(selected)) selection = new TreePath(entry.getValue().getPath());
                }
                tree.setModel(new DefaultTreeModel(root));
                for (int i = 0; i < tree.getRowCount(); i++) { var path = tree.getPathForRow(i); var n = (DefaultMutableTreeNode) path.getLastPathComponent(); if (n.getUserObject() instanceof Node node && (!node.project||!collapsedProjects.contains(expansionKey(tree,node.id)))) tree.expandPath(path); }
                if (selection != null) tree.setSelectionPath(selection);
            }
            long count = sessions.values().stream().filter(s -> !s.path("active").asBoolean()||!projects.get(s.path("projectId").asText()).path("active").asBoolean(true)).count(); archiveToggle.setText((inactiveOpen ? "⌄ " : "› ") + "Неактивные  " + count);
        } finally { changingTree = false; }
    }
    private ChatView createChatView(String id){
        var view=new ChatView(id,host);
        view.transcript.addHyperlinkListener(e -> { if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) openLink(e.getDescription()); });
        view.transcript.addCaretListener(e -> { if(transcript==view.transcript) quote.setVisible(view.transcript.getSelectedText()!=null&&!view.transcript.getSelectedText().isBlank()); });
        view.transcript.imageCopyMenu(quoteMenu,this::copyChatImage);
        view.transcript.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Q,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK),"quote");
        view.transcript.getActionMap().put("quote",new AbstractAction(){public void actionPerformed(ActionEvent e){quoteSelection();}});
        return view;
    }
    /** Makes a view the visible chat: fields the rest of the window uses point at its components. */
    private void bindChat(ChatView view){
        if(chatView==view)return;
        if(chatView!=null){chatView.chatSignature=chatSignature;chatView.scrollToLatest=scrollToLatest;chatView.chatRender=chatRender;chat.remove(chatView.area);chat.remove(chatView.navigation);}
        chatView=view;transcript=view.transcript;history=view.history;chatNavigation=view.navigation;pagedHistory=view.paged;
        chatSignature=view.chatSignature;scrollToLatest=view.scrollToLatest;chatRender=view.chatRender;
        chat.add(view.area,BorderLayout.CENTER);chat.add(view.navigation,BorderLayout.NORTH);chat.revalidate();chat.repaint();
        if(quote!=null)quote.setVisible(transcript.getSelectedText()!=null&&!transcript.getSelectedText().isBlank());
    }
    /** Cached view for a session, or a fresh one; the least recently shown beyond the cache size is released. */
    private ChatView chatViewFor(String id){
        var view=chatViews.get(id);if(view!=null)return view;
        view=createChatView(id);chatViews.put(id,view);
        var stale=new ArrayList<ChatView>();for(var it=chatViews.entrySet().iterator();chatViews.size()-stale.size()>CACHED_CHATS&&it.hasNext();){var entry=it.next();if(entry.getValue()==view)continue;stale.add(entry.getValue());it.remove();}
        for(var old:stale)if(old!=chatView)old.close();
        return view;
    }
    private void renderChat() {
        if(conversation.path("windowed").asBoolean()&&selected!=null){pagedHistory.update(selected,conversation.path("historyVersion").asText(),scrollToLatest);scrollToLatest=false;return;}
        if(!conversation.path("messages").isArray())return;
        pagedHistory.legacy();
        String key = conversation.path("messages").toString() + conversation.path("live").asText();
        if (key.equals(chatSignature)) return;
        if (transcript.isFocusOwner() && transcript.getSelectedText() != null) return;
        chatSignature = key; var html = new StringBuilder();
        var tasks = ChatNavigation.tasks(conversation.path("messages")); int taskIndex = 0;
        for (var message : conversation.path("messages")) {
            String role = message.path("role").asText(); String who = role.equals("user") ? "ВЫ" : role.equals("assistant") ? ChatTheme.executorLabel(message.path("provider").asText()) : role.equals("session") ? "ДРУГАЯ СЕССИЯ" : "TETHIUM";
            if (message.path("provider").asText().equals("IMPORTED")) who += " · ИМПОРТ";
            html.append("<div class='").append(role.equals("user") ? "user" : "message").append("'");
            if (role.equals("user")) html.append(" id='").append(tasks.get(taskIndex++).anchor()).append("'");
            html.append("><p class='label'>").append(who).append(ChatTime.label(message.path("timestamp").asLong())).append("</p>").append(markdown(message.path("text").asText())).append("</div>");
        }
        String live = conversation.path("live").asText(); if (!live.isEmpty()) html.append("<div class='message'><p class='label'><span class='live'>"+executorTitle(sessions.getOrDefault(selected,Json.object()))+" · РАБОТАЕТ</span>"+ChatTime.label(conversation.path("liveTimestamp").asLong())+"</p>").append(markdown(live)).append("</div>");
        if (html.isEmpty()) html.append("<h2>Новая сессия</h2><p class='muted'>Опишите задачу. Агент увидит файлы проекта и соседние сессии.</p>");
        var position = chatNavigation.beforeChange(scrollToLatest);
        scrollToLatest = false; long render = ++chatRender;
        transcript.setText(page(html.toString()));
        chatNavigation.setTasks(tasks);
        SwingUtilities.invokeLater(() -> {
            if (render != chatRender || disposed) return;
            validate(); history.getViewport().doLayout();
            chatNavigation.afterChange(position);
        });
    }
    private void acknowledgeVisibleResult() {
        if(disposed||!isActive()||selected==null||!sessions.containsKey(selected)||fullScreenPreview!=null)return;
        var attention=sessions.get(selected).path("attention");
        if(!attention.path("kind").asText().equals("finished"))return;
        var bar=history.getVerticalScrollBar(); if(bar.getValue()+bar.getVisibleAmount()<bar.getMaximum()-40)return;
        String id=selected; long version=attention.path("version").asLong();
        if(acknowledged.getOrDefault(id,0L)>=version)return; acknowledged.put(id,version);
        io.submit(()->{try{host.post("/sessions/"+id+"/seen",Json.object("version",version));}catch(Exception failure){SwingUtilities.invokeLater(()->acknowledged.remove(id,version));}});
    }
    private String markdown(String text) { return markdown.render(text); }
    private String page(String body) { return ChatTheme.page(body); }
    private void renderRequests() {
        String signature = conversation.path("requests").toString(); if (signature.equals(requestSignature)) return; requestSignature = signature;
        requests.removeAll();
        QuestionCard firstCard = null;
        for (var r : conversation.path("requests")) {
            if (r.path("method").asText().equals("item/tool/requestUserInput") && r.path("params").path("questions").isArray() && r.path("params").path("questions").size() > 0) {
                var card = new QuestionCard(r, response -> answer(r, response)); requests.add(card); if (firstCard == null) firstCard = card; continue;
            }
            var panel = new JPanel(new BorderLayout(Theme.GAP, Theme.GAP)); panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER), new EmptyBorder(Theme.UNIT, Theme.GUTTER, Theme.UNIT, Theme.GUTTER)));
            String method = r.path("method").asText(); var params = r.path("params");
            String details = params.path("command").asText(params.path("reason").asText(params.path("message").asText("Агент ждёт вашего ответа")));
            if (method.equals("item/permissions/requestApproval")) details += "\n" + params.path("permissions").toPrettyString();
            var description = new JTextArea(details);
            description.setEditable(false); description.setLineWrap(true); description.setWrapStyleWord(true); description.setRows(Math.min(3, description.getLineCount() + 1)); panel.add(description);
            var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
            if (method.equals("item/permissions/requestApproval")) {
                actions.add(Theme.button("Разрешить", "Предоставить указанные права на этот запуск", () -> answer(r, Json.object("permissions", params.path("permissions"), "scope", "turn"))));
                actions.add(Theme.button("Отклонить", "Не предоставлять дополнительные права", () -> answer(r, Json.object("permissions", Json.object(), "scope", "turn"))));
            } else if (method.endsWith("requestApproval")) {
                actions.add(Theme.button("Разрешить", "Разрешить это действие", () -> answer(r, Json.object("decision", "accept"))));
                actions.add(Theme.button("Для сессии", "Разрешить в рамках этой сессии", () -> answer(r, Json.object("decision", "acceptForSession"))));
                actions.add(Theme.button("Отклонить", "Отказать в действии", () -> answer(r, Json.object("decision", "decline"))));
            } else actions.add(Theme.button("Ответить…", "Открыть вопросы агента", () -> answerQuestions(r)));
            panel.add(actions, BorderLayout.SOUTH); requests.add(panel);
        }
        requests.revalidate(); requests.repaint();
        if (firstCard != null && !input.isFocusOwner()) { var card = firstCard; SwingUtilities.invokeLater(card::focusFirst); }
    }
    private void answerQuestions(JsonNode request) {
        String method = request.path("method").asText(); var params = request.path("params");
        if (method.equals("item/tool/requestUserInput")) {
            var panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); var fields = new LinkedHashMap<String, JTextArea>();
            for (var q : params.path("questions")) {
                panel.add(new JLabel("<html><body style='width:420px'>" + Theme.escape(q.path("question").asText()) + "</body></html>"));
                StringBuilder options = new StringBuilder(); for (var o : q.path("options")) options.append(o.path("label").asText()).append(" — ").append(o.path("description").asText()).append('\n');
                if (!options.isEmpty()) { var hint = new JTextArea(options.toString()); hint.setEditable(false); hint.setLineWrap(true); hint.setWrapStyleWord(true); panel.add(hint); }
                var text = new JTextArea(2, 35); text.setLineWrap(true); fields.put(q.path("id").asText(), text); panel.add(new JScrollPane(text));
            }
            if (JOptionPane.showConfirmDialog(this, panel, "Вопросы агента", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                var answers = Json.object(); fields.forEach((id, text) -> answers.set(id, Json.object("answers", List.of(text.getText())))); answer(request, Json.object("answers", answers));
            }
        } else {
            var schema = params.path("requestedSchema"); var panel = new JPanel(new GridLayout(0, 1, 4, 4)); var fields = new LinkedHashMap<String, JTextField>();
            panel.add(new JLabel(params.path("message").asText("Введите данные")));
            schema.path("properties").fields().forEachRemaining(entry -> { panel.add(new JLabel(entry.getValue().path("title").asText(entry.getKey()))); var text = new JTextField(); fields.put(entry.getKey(), text); panel.add(text); });
            if (fields.isEmpty()) panel.add(new JLabel(params.path("url").asText("Форма не содержит полей")));
            int choice = JOptionPane.showConfirmDialog(this, panel, "Запрос инструмента", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            var values = Json.object(); fields.forEach((key, text) -> values.put(key, text.getText()));
            answer(request, Json.object("action", choice == JOptionPane.OK_OPTION ? "accept" : "cancel", "content", choice == JOptionPane.OK_OPTION ? values : null));
        }
    }
    private void answer(JsonNode request, JsonNode response) { action(() -> host.post("/answer", Json.object("id", request.path("id").asText(), "answer", response))); }
    private void renderArtifacts() {
        String signature = conversation.path("artifacts").toString()+projects.getOrDefault(selectedProject,Json.object()).path("panel"); if (signature.equals(artifactSignature)) return; artifactSignature = signature;
        applying = true; Object previous = artifacts.getSelectedItem(); artifacts.removeAllItems();
        if(!host.remote()){var panel=projects.getOrDefault(selectedProject,Json.object()).path("panel");if(panel.isObject())artifacts.addItem(new Artifact(panel.path("title").asText(),Path.of(panel.path("path").asText())));}
        for (var a : conversation.path("artifacts")) artifacts.addItem(new Artifact(a.path("title").asText(), Path.of(a.path("path").asText())));
        if (previous instanceof Artifact old) for (int i = 0; i < artifacts.getItemCount(); i++) {
            if (artifacts.getItemAt(i).path.equals(old.path)) { artifacts.setSelectedIndex(i); break; }
        }
        if(previous instanceof Artifact old){boolean found=false;for(int i=0;i<artifacts.getItemCount();i++)if(artifacts.getItemAt(i).path.equals(old.path))found=true;if(!found){artifacts.addItem(old);artifacts.setSelectedItem(old);}}
        results.setText(artifacts.getItemCount() == 0 ? "" : String.valueOf(artifacts.getItemCount()));
        results.setToolTipText("Результаты агента · " + artifacts.getItemCount());
        applying = false;
        if (previewOpen) loadArtifact();
    }
    private void toggleInactive() { inactiveOpen = !inactiveOpen; inactiveScroll.setVisible(inactiveOpen); treeSignature = ""; revalidate(); }
    private void addProject() {
        if(host.remote()){JOptionPane.showMessageDialog(this,"Добавьте каталог проекта на ноутбуке.");return;}
        ProjectDialog.open(this,host,null,project->action(()->{var session=host.post("/sessions",Json.object("projectId",project.path("id").asText(),"title","Новая сессия"));SwingUtilities.invokeLater(()->{sessions.put(session.path("id").asText(),session);selectSession(session.path("id").asText());});}));
    }
    private void newSession() {
        if (selectedProject == null) { addProject(); return; }
        String pid = selectedProject;
        action(() -> { var created = host.post("/sessions", Json.object("projectId", pid, "title", "Новая сессия")); SwingUtilities.invokeLater(() -> { sessions.put(created.path("id").asText(), created); selectSession(created.path("id").asText()); }); });
    }
    private void rename() {
        if (selected == null) return; String id = selected;
        String name = JOptionPane.showInputDialog(this, "Название сессии", heading.getText()); if (name != null && !name.isBlank()) action(() -> host.post("/sessions/" + id + "/rename", Json.object("title", name)));
    }
    private void toggleActive() { if (selected != null) { String id = selected; boolean value = !sessions.get(id).path("active").asBoolean(); action(() -> host.post("/sessions/" + id + "/active", Json.object("active", value))); } }
    private void saveSettings() {
        if (applying || selected == null) return; String id = selected;
        var value = Json.object("provider", model.provider(), "model", model.model(), "reasoningEffort", model.effort(), "access", accessValue()); settingsDrafts.put(id, value);
        action(() -> host.post("/sessions/" + id + "/settings", value));
    }
    private String accessValue() { return "danger-full-access"; }
    private void sendMenu() {
        var menu = new JPopupMenu();
        var separate = new JMenuItem("Отдельным сообщением в очередь"); separate.setName("composer.sendSeparate");
        separate.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK));
        separate.setEnabled(send.isEnabled() && !input.getText().isBlank()); separate.addActionListener(e -> send(true)); menu.add(separate);
        var urgent=new JMenuItem("Прервать и выполнить сейчас");urgent.setEnabled(interrupt.isEnabled());urgent.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.CTRL_DOWN_MASK));urgent.addActionListener(e->interrupt());menu.add(urgent);
        menu.show(sendOptions, sendOptions.getWidth() - menu.getPreferredSize().width, -menu.getPreferredSize().height);
    }
    public void send() { send(false); }
    private void send(boolean separate) {
        if (selected == null || input.getText().isBlank() && draftImages.getOrDefault(selected,List.of()).isEmpty() || !send.isEnabled()) return;
        String id = selected, prompt = input.getText(), modelName = model.model(), effort = model.effort(), mode = accessValue(), executor = model.provider();
        var images=draftImages.getOrDefault(id,List.of());draftImages.put(id,List.of());refreshImages();
        draftTimer.stop(); applying = true; input.setText(""); drafts.put(id, ""); applying = false;
        action(() -> {
            try { host.post("/sessions/" + id + "/settings", Json.object("provider", executor, "model", modelName, "reasoningEffort", effort, "access", mode)); var result=host.post("/sessions/" + id + "/run", Json.object("prompt", prompt, "images",images,"separate", separate));
                SwingUtilities.invokeLater(() -> { if (Objects.equals(selected,id)) runStatus.focusJob(result.path("jobId").asText()); }); }
            catch (Exception e) { SwingUtilities.invokeLater(() -> { String current=drafts.getOrDefault(id,"");String restored=prompt+(current.isEmpty()?"":"\n\n"+current); drafts.put(id,restored);var restoredImages=new ArrayList<>(images);restoredImages.addAll(draftImages.getOrDefault(id,List.of()));draftImages.put(id,restoredImages);if (Objects.equals(selected, id)) {input.setText(restored);refreshImages();} }); throw e; }
        }); input.requestFocusInWindow();
    }
    private void stop() { if (selected != null) { String id = selected; action(() -> host.post("/sessions/" + id + "/stop", Json.object())); } }
    private void showUsage(){UsageDialog.open(this,host,projects,sessions,null,null);}
    private void resetQuota() {
        if(JOptionPane.showConfirmDialog(this,"Использовать один доступный сброс лимита Codex?\nЭто потратит один сброс аккаунта.","Подтверждение сброса",JOptionPane.OK_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE)!=JOptionPane.OK_OPTION)return;
        action(()->{Path pending=dev.tethium.host.AppPaths.data().resolve("quota-reset-pending.json");String key=Files.exists(pending)?Json.MAPPER.readTree(pending.toFile()).path("requestId").asText():UUID.randomUUID().toString();Files.createDirectories(pending.getParent());Files.writeString(pending,Json.object("requestId",key).toString());var result=host.post("/quota/reset",Json.object("requestId",key,"confirmed",true));Files.deleteIfExists(pending);SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(this,switch(result.path("outcome").asText()){case "reset","alreadyRedeemed"->"Сброс применён. Обновляем квоту.";case "nothingToReset"->"Сейчас нет подходящего лимита для сброса.";case "noCredit"->"Доступные сбросы закончились.";default->"Ответ сервиса: "+result.path("outcome").asText();}));});
    }
    private void refreshQuota() { action(() -> host.post("/quota/refresh", Json.object())); }
    private void groupQueued(boolean merge,JsonNode selectedJobs) {
        if(selected==null||grouping||!groupingAvailable)return;String id=selected;grouping=true;refreshTakeControls();
        var first=selectedJobs.get(0);var body=merge?Json.object("selected",selectedJobs,"requestId",UUID.randomUUID().toString()):Json.object("jobId",first.path("id").asText(),"expectedPrompt",first.path("prompt").asText(),"requestId",UUID.randomUUID().toString());
        action(()->{try{host.post("/sessions/"+id+(merge?"/queue-merge":"/queue-split"),body);}finally{SwingUtilities.invokeLater(()->{grouping=false;refreshTakeControls();});}});
    }
    private void toggleAttention(String id){if(id!=null&&sessions.containsKey(id)){boolean mark=!sessions.get(id).path("attention").path("manual").asBoolean();action(()->host.post("/sessions/"+id+"/attention",Json.object("marked",mark)));}}
    private void interrupt() {
        if(selected==null||!interrupt.isEnabled()||pendingInterrupt!=null)return;
        draftTimer.stop();saveDraft();pendingInterrupt=Json.object("sessionId",selected,"provider",model.provider(),"model",model.model(),"effort",model.effort(),"body",Json.object("prompt",input.getText(),"images",draftImages.getOrDefault(selected,List.of()),"requestId",UUID.randomUUID().toString(),"queueHead",input.getText().isBlank()&&draftImages.getOrDefault(selected,List.of()).isEmpty()));
        interruptRetryAt=0;refreshTakeControls();retryInterrupt();
    }
    private void retryInterrupt() {
        if(pendingInterrupt==null||interruptInFlight||disposed||System.currentTimeMillis()<interruptRetryAt||!interruptAvailable)return;
        var operation=pendingInterrupt;String id=operation.path("sessionId").asText();interruptInFlight=true;
        io.submit(()->{try {
            host.post("/sessions/"+id+"/settings",Json.object("provider",operation.path("provider").asText(model.provider()),"model",operation.path("model"),"reasoningEffort",operation.path("effort"),"access",accessValue()));
            host.post("/sessions/"+id+"/interrupt",operation.path("body"));
            SwingUtilities.invokeLater(()->{interruptInFlight=false;pendingInterrupt=null;drafts.put(id,"");draftImages.put(id,List.of());if(Objects.equals(selected,id)){applying=true;input.setText("");applying=false;refreshImages();}refreshTakeControls();if(!disposed)poller.execute(this::poll);});
        }catch(HostClient.ApiException rejected){SwingUtilities.invokeLater(()->{interruptInFlight=false;pendingInterrupt=null;refreshTakeControls();error(rejected);});}
        catch(Exception lost){SwingUtilities.invokeLater(()->{interruptInFlight=false;interruptRetryAt=System.currentTimeMillis()+2000;refreshTakeControls();});}});
    }
    private void pauseQueue() { if (selected != null) { String id = selected; action(() -> host.post("/sessions/" + id + "/queue-pause", Json.object())); } }
    private void resumeQueue() { if (selected != null) { String id = selected; action(() -> host.post("/sessions/" + id + "/queue-resume", Json.object())); } }
    private void removeQueued(String jobId) {
        if (selected == null) return; String id = selected;
        if (JOptionPane.showConfirmDialog(this, "Удалить выбранное сообщение из очереди? Все его части будут удалены.", "Удалить сообщение", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return;
        action(() -> host.post("/sessions/" + id + "/queue-remove", Json.object("jobId", jobId)));
    }
    private void moveQueued(String jobId, String direction) {
        if (selected != null && queueEditingAvailable) { String id = selected; action(() -> host.post("/sessions/" + id + "/queue-move", Json.object("jobId", jobId, "direction", direction))); }
    }
    private void takeQueued(String jobId, String expectedPrompt) {
        if (selected == null || pendingTake != null || !queueEditingAvailable) return;
        draftTimer.stop(); saveDraft();
        pendingTake = Json.object("sessionId", selected, "body", Json.object("jobId", jobId, "expectedPrompt", expectedPrompt, "draft", input.getText(), "requestId", UUID.randomUUID().toString()));
        takeRetryAt = 0; refreshTakeControls(); retryQueueTake();
    }
    private void refreshTakeControls() {
        if (selected != null && sessions.containsKey(selected)) renderHeader(sessions.get(selected));
        runStatus.setQueueEditing(queueEditingAvailable, pendingTake != null || grouping);
    }
    private void retryQueueTake() {
        if (pendingTake == null || takeInFlight || disposed || System.currentTimeMillis() < takeRetryAt) return;
        var operation = pendingTake; String id = operation.path("sessionId").asText(); takeInFlight = true;
        io.submit(() -> {
            try {
                var result = host.post("/sessions/" + id + "/queue-take", operation.path("body"));
                SwingUtilities.invokeLater(() -> {
                    takeInFlight = false; pendingTake = null;
                    String text = result.path("draft").asText(); drafts.put(id, text);
                    if (Objects.equals(selected, id)) { applying = true; input.setText(text); input.setCaretPosition(text.length()); applying = false; input.requestFocusInWindow(); }
                    refreshTakeControls(); if (!disposed) poller.execute(this::poll);
                });
            } catch (HostClient.ApiException rejected) {
                SwingUtilities.invokeLater(() -> { takeInFlight = false; pendingTake = null; refreshTakeControls(); error(rejected); if (!disposed) poller.execute(this::poll); });
            } catch (Exception connectionLost) {
                // The host may have committed the return before the reply was lost. Reuse the same request ID.
                SwingUtilities.invokeLater(() -> { takeInFlight = false; takeRetryAt = System.currentTimeMillis() + 2000; refreshTakeControls(); });
            }
        });
    }
    private void saveDraft() {
        if (selected == null || disposed) return; String id = selected, value = input.getText(); drafts.put(id, value);
        if (writingImages.getOrDefault(id,0) > 0) return;
        if (pendingTake != null && id.equals(pendingTake.path("sessionId").asText())) return;
        if(pendingInterrupt!=null&&id.equals(pendingInterrupt.path("sessionId").asText()))return;
        var images=draftImages.getOrDefault(id,List.of());
        io.submit(() -> { try { host.post("/sessions/" + id + "/draft", Json.object("draft", value,"images",images)); } catch (Exception ignored) {} });
    }
    private void attachFiles() {
        if (selected == null || !input.isEnabled() || !input.isEditable()) return;
        var chooser = new JFileChooser(sessions.get(selected).path("directory").asText());
        chooser.setDialogTitle("Добавить файлы в сообщение"); chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (var file : chooser.getSelectedFiles()) attachFile(file.toPath());
            input.requestFocusInWindow();
        }
    }
    private static List<String> strings(JsonNode value) { var result=new ArrayList<String>();value.forEach(n->result.add(n.asText()));return result; }
    private void refreshImages() {imageStrip.update(draftImages.getOrDefault(selected,List.of()).stream().map(Path::of).toList(),writingImages.getOrDefault(selected,0));refreshTakeControls();}
    private void removeImage(Path path) { if(selected==null||!input.isEditable())return;var images=new ArrayList<>(draftImages.getOrDefault(selected,List.of()));images.remove(path.toString());draftImages.put(selected,images);refreshImages();saveDraft(); }
    private void attachFile(Path path) {
        if(imagesAvailable&&path.toString().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp)$")){var images=new ArrayList<>(draftImages.getOrDefault(selected,List.of()));images.add(path.toAbsolutePath().toString());draftImages.put(selected,images);refreshImages();saveDraft();}
        else input.append((input.getText().isEmpty()?"":"\n")+path.toAbsolutePath());
    }
    private void pasteImage(Image image) {
        String id=selected;writingImages.merge(id,1,Integer::sum);refreshImages();
        io.submit(()->{try {
            var path=ClipboardImages.save(image,dev.tethium.host.AppPaths.data(),id);
            var saved=new JsonNode[1];
            SwingUtilities.invokeAndWait(()->{writingImages.compute(id,(k,n)->Math.max(0,n-1));var images=new ArrayList<>(draftImages.getOrDefault(id,List.of()));images.add(path.toString());draftImages.put(id,images);
                saved[0]=Json.object("draft",drafts.getOrDefault(id,""),"images",images); if(!disposed&&Objects.equals(selected,id))refreshImages();});
            host.post("/sessions/"+id+"/draft",saved[0]);
        }catch(Exception failure){SwingUtilities.invokeLater(()->{writingImages.compute(id,(k,n)->Math.max(0,n-1));refreshImages();error(failure);});}});
    }
    private void chooseArtifact() {
        if (selected == null) return;
        if(host.remote()){String path=JOptionPane.showInputDialog(this,"Путь к файлу внутри проекта на ноутбуке");if(path!=null&&!path.isBlank())openLink(path);return;}
        var chooser = new JFileChooser(sessions.get(selected).path("directory").asText()); chooser.setDialogTitle("Открыть изображение, HTML или Markdown");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openArtifact(chooser.getSelectedFile().toPath());
    }
    private void quoteSelection() {
        String text=transcript.getSelectedText();if(text==null||text.isBlank()||selected==null)return;
        String quote=text.strip().lines().map(line->"> "+line).collect(java.util.stream.Collectors.joining("\n"));
        input.replaceSelection((input.getCaretPosition()>0?"\n\n":"")+quote+"\n\n"); input.requestFocusInWindow();
    }
    private void copyChatImage(String source){
        String id=selected;Path directory=id!=null&&sessions.containsKey(id)?Path.of(sessions.get(id).path("directory").asText()):Path.of(".");
        boolean localDraft=id!=null&&draftImages.getOrDefault(id,List.of()).stream().anyMatch(p->Path.of(p).toUri().toString().equals(source));
        ClipboardImages.copy(this,()->host instanceof dev.tethium.remote.RemoteClient remote&&!localDraft&&!source.matches("^(https?://|data:).*" )?remote.readImage(ClipboardImages.localPath(source,directory),id):ClipboardImages.read(source,directory));
    }
    private void openLink(String link) {
        if (link == null || link.isBlank() || link.startsWith("#")) return;
        try {
            if (link.startsWith("https://") || link.startsWith("http://")) { Desktop.getDesktop().browse(URI.create(link)); return; }
            String clean = link.replaceFirst(":\\d+(?::\\d+)?$", "");
            Path path;
            if (clean.startsWith("file:")) path = Path.of(URI.create(clean));
            else {
                clean = java.net.URLDecoder.decode(clean.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
                path = Path.of(clean.startsWith("/") && clean.length() > 3 && clean.charAt(2) == ':' ? clean.substring(1) : clean);
            }
            if (!path.isAbsolute() && selected != null) path = Path.of(sessions.get(selected).path("directory").asText()).resolve(path);
            openArtifact(path);
        } catch (Exception e) { error(e); }
    }
    private void openArtifact(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.matches(".*\\.(html?|md|markdown|svg|png|jpe?g|gif|bmp|webp)$") || host.remote()&&name.matches(".*\\.(jar|zip|exe)$")) {
            applying = true;
            Artifact chosen = null;
            for (int i = 0; i < artifacts.getItemCount(); i++) if (artifacts.getItemAt(i).path.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())) { chosen = artifacts.getItemAt(i); break; }
            if (chosen == null) { chosen = new Artifact(path.getFileName().toString(), path); artifacts.addItem(chosen); }
            artifacts.setSelectedItem(chosen); applying = false;
            showPreview(true);
        }
        else try { Desktop.getDesktop().open(path.toFile()); } catch (Exception e) { error(e); }
    }
    private boolean viewingPinnedFile(){return selectedFile!=null||(selected!=null&&sessions.containsKey(selected)&&artifacts.getSelectedItem() instanceof Artifact a&&a.path.toString().equals(sessions.get(selected).path("pinnedFile").asText()));}
    private boolean confirmUnpin(){return JOptionPane.showConfirmDialog(this,"Закрыть и открепить файл? Сам файл останется на диске.","Закреплённый файл",JOptionPane.YES_NO_OPTION,JOptionPane.QUESTION_MESSAGE)==JOptionPane.YES_OPTION;}
    private void unpinDocument(String id){if(confirmUnpin())action(()->host.post("/files/"+id+"/remove",Json.object()));}
    private JsonNode selectedPanel(){
        if(artifacts.getSelectedItem() instanceof Artifact a)for(var project:projects.values()){
            var panel=project.path("panel");if(panel.isObject()&&a.path.toString().equals(panel.path("path").asText()))return panel;
        }
        return null;
    }
    private void closePreview(){
        if(selectedFile!=null&&documents.getOrDefault(selectedFile,Json.object()).path("panel").asBoolean()){
            String back=panelReturnSession;
            if(back==null||!sessions.containsKey(back))back=sessions.values().stream().filter(s->s.path("projectId").asText().equals(selectedProject)).map(s->s.path("id").asText()).findFirst().orElse(null);
            if(back!=null)selectSession(back);return;
        }
        if(selectedFile!=null){unpinDocument(selectedFile);return;}
        if(selected!=null&&sessions.containsKey(selected)&&artifacts.getSelectedItem() instanceof Artifact a&&a.path.toString().equals(sessions.get(selected).path("pinnedFile").asText())){
            if(!confirmUnpin())return;String id=selected;action(()->{host.post("/sessions/"+id+"/pinned-file",Json.object("path",""));SwingUtilities.invokeLater(()->{if(id.equals(selected))showPreview(false);});});
        }else showPreview(false);
    }
    private void showPreview(boolean show) {
        if(selectedFile!=null)show=true;
        var position = chatNavigation.beforeChange(false);
        if (!show) {
            exitFullScreen();
            if (previewOpen) previewDivider = content.getDividerLocation();
            content.setRightComponent(null); content.setDividerSize(0);viewer.suspendBrowser();
        } else if (!previewOpen) {
            content.setRightComponent(preview); content.setDividerSize(4);
            int desired = previewDivider < 0 ? (int) (content.getWidth() * .62) : previewDivider;
            content.setDividerLocation(Math.max(340, Math.min(desired, content.getWidth() - 274)));
        }
        previewOpen = show; results.setSelected(show);if(selectedFile!=null)content.setDividerLocation(0);
        if (show) loadArtifact();
        rememberPreview();
        content.revalidate(); content.repaint();
        SwingUtilities.invokeLater(() -> { if (!disposed) { validate(); history.getViewport().doLayout(); chatNavigation.afterChange(position); } });
    }
    private void loadArtifact() {
        rememberPreview();updatePinButton();
        if (artifacts.getSelectedItem() instanceof Artifact a) {
            artifacts.setToolTipText(a.title + " — " + a.path);
            var panel=selectedPanel();
            if(panel!=null){
                getBuild.setVisible(false);runBuild.setVisible(false);fitPreview.setVisible(false);actualPreview.setVisible(false);expandPreview.setEnabled(true);
                if(panel.hasNonNull("error"))viewer.showNotice("Панель проекта: "+panel.path("error").asText());
                else if(panel.hasNonNull("url"))viewer.openRemote(URI.create(panel.path("url").asText()));
                else viewer.open(Path.of(panel.path("entry").asText()));
                return;
            }
            String filename=a.path.getFileName().toString().toLowerCase(Locale.ROOT);boolean runnable=filename.matches(".*\\.(jar|exe|zip)$");runBuild.setVisible(host.remote()&&runnable);getBuild.setVisible(host.remote());
            if(runnable&&host.remote())viewer.showNotice("Опубликованная сборка · "+a.path.getFileName()+"\nПолучите её или запустите на этом компьютере.");
            else if(host instanceof dev.tethium.remote.RemoteClient remote&&selectedFile!=null){String id=selectedFile;action(()->{var url=remote.previewDocument(id);SwingUtilities.invokeLater(()->{if(Objects.equals(selectedFile,id))viewer.openRemote(url);});});}
            else if(host instanceof dev.tethium.remote.RemoteClient remote&&filename.matches(".*\\.(png|jpe?g|gif|bmp)$")&&!draftImages.getOrDefault(selected,List.of()).contains(a.path.toString())){String sid=selected;action(()->{var picture=remote.readImage(a.path,sid);SwingUtilities.invokeLater(()->{if(artifacts.getSelectedItem()!=a)return;if(picture==null)viewer.showNotice("Не удалось прочитать изображение");else viewer.showImage(picture);});});}
            else if(host instanceof dev.tethium.remote.RemoteClient remote&&!draftImages.getOrDefault(selected,List.of()).contains(a.path.toString())){action(()->{var url=remote.preview(a.path);SwingUtilities.invokeLater(()->{if(artifacts.getSelectedItem()==a)viewer.openRemote(url);});});}
            else viewer.open(a.path); expandPreview.setEnabled(!runnable);
            boolean image = !a.path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(html?|md|markdown|svg)$");
            fitPreview.setVisible(fullScreenPreview != null && image); actualPreview.setVisible(fullScreenPreview != null && image);
        } else { viewer.clear(); expandPreview.setEnabled(false); fitPreview.setIcon(Theme.icon("preview-fit")); actualPreview.setIcon(Theme.icon("preview-actual-size")); fitPreview.setVisible(false); actualPreview.setVisible(false); }
    }
    private void receiveBuild(boolean launch){
        if(!(host instanceof dev.tethium.remote.RemoteClient remote)||!(artifacts.getSelectedItem() instanceof Artifact artifact))return;
        getBuild.setEnabled(false);runBuild.setEnabled(false);
        action(()->{try{Path downloaded=remote.download(artifact.path);if(!launch){SwingUtilities.invokeLater(()->{try{Desktop.getDesktop().open(downloaded.getParent().toFile());}catch(Exception e){error(e);}});return;}
            var entries=dev.tethium.remote.ArtifactLauncher.entries(downloaded);if(entries.isEmpty())throw new IllegalArgumentException("В сборке нет JAR или EXE");
            Path[] selectedEntry={entries.size()==1?entries.getFirst():null};if(entries.size()>1)SwingUtilities.invokeAndWait(()->selectedEntry[0]=(Path)JOptionPane.showInputDialog(this,"Что запустить?","Сборка",JOptionPane.QUESTION_MESSAGE,null,entries.toArray(),entries.getFirst()));
            if(selectedEntry[0]!=null){var process=dev.tethium.remote.ArtifactLauncher.launch(selectedEntry[0]);SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(this,"Сборка запущена на этом компьютере · PID "+process.pid()+"\nЖурнал: "+selectedEntry[0].getParent().resolve("tethium-launch.log")));}
        }finally{SwingUtilities.invokeLater(()->{getBuild.setEnabled(true);runBuild.setEnabled(true);});}});
    }
    private void toggleFullScreen() { if (fullScreenPreview == null) enterFullScreen(); else exitFullScreen(); }
    private void enterFullScreen() {
        if (fullScreenPreview != null || artifacts.getSelectedItem() == null) return;
        previewFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        previewDivider = content.getDividerLocation();
        var dialog = new JDialog(this, "Результат — Tethium", false);
        fullScreenPreview = dialog; dialog.setName("artifact.fullscreen.window"); dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { exitFullScreen(); } });
        content.setRightComponent(null); content.setDividerSize(0);
        dialog.setContentPane(preview); viewer.setFullScreen(true);
        expandPreview.setText(viewingPinnedFile()?"":"Вернуться · Esc"); expandPreview.setToolTipText(viewingPinnedFile()?"Вернуться в окно · Esc":"Вернуться к чату");expandPreview.setIcon(Theme.previewIcon(false));
        dialog.getRootPane().registerKeyboardAction(e -> exitFullScreen(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setBounds(getGraphicsConfiguration().getBounds());
        loadArtifact(); dialog.setVisible(true); expandPreview.requestFocusInWindow();
    }
    private void exitFullScreen() {
        if (fullScreenPreview == null) return;
        var dialog = fullScreenPreview; fullScreenPreview = null;
        dialog.setContentPane(new JPanel());
        content.setRightComponent(preview); content.setDividerSize(selectedFile==null?4:0); content.setDividerLocation(selectedFile==null?previewDivider:0);
        viewer.setFullScreen(false);expandPreview.setIcon(Theme.previewIcon(true)); expandPreview.setText(""); expandPreview.setToolTipText("На весь экран");
        fitPreview.setIcon(Theme.icon("preview-fit")); actualPreview.setIcon(Theme.icon("preview-actual-size")); fitPreview.setVisible(false); actualPreview.setVisible(false);
        dialog.dispose(); content.revalidate(); content.repaint();
        if (previewFocus != null && previewFocus.isShowing()) previewFocus.requestFocusInWindow();
    }
    private void showActivity() {
        var text = new StringBuilder();
        for (var event : conversation.path("events")) {
            var item = event.path("params").path("item");
            if (item.path("type").asText().equals("agentMessage")) continue;
            text.append(event.path("method").asText()).append('\n').append(item.isMissingNode() ? event.path("params").toPrettyString() : item.toPrettyString()).append("\n\n");
        }
        var area = new JTextArea(text.isEmpty() ? "Пока нет действий" : text.toString(), 24, 80); area.setEditable(false); area.setFont(new Font("Consolas", Font.PLAIN, 12));
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "Действия агента", JOptionPane.PLAIN_MESSAGE);
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void action(Work work) { if (!disposed) io.submit(() -> { try { work.run(); poller.execute(this::poll); } catch (Exception e) { SwingUtilities.invokeLater(() -> error(e)); } }); }
    private void error(Exception e) { if (!disposed) JOptionPane.showMessageDialog(this, e.getMessage(), "Tethium", JOptionPane.ERROR_MESSAGE); }
    public JTextArea inputArea() { return input; }
    public String selectedSession() { return selected; }
    private DesktopTray desktopTray;
    private boolean handoffTrayHidden;
    void installTray(DesktopTray tray){desktopTray=tray;}
    public boolean minimizeToTray(){return desktopTray!=null&&desktopTray.hideWindow();}
    public JsonNode reloadReadiness() {
        return Json.object("ready", !disposed && lastAppliedState > System.currentTimeMillis() - 5000 && pendingHandoff == null,
                "visible", isShowing(), "tray", desktopTray!=null&&desktopTray.hidden(), "trayAvailable",desktopTray!=null,"hostPid", host.hostPid(), "sessionId", selected, "lastStateAt", lastAppliedState);
    }
    public CompletableFuture<JsonNode> prepareHandoff() {
        handoffTrayHidden=desktopTray!=null&&desktopTray.hidden();
        setEnabled(false); draftTimer.stop(); saveSettings(); saveDraft();rememberPreview();
        var result = new CompletableFuture<JsonNode>();
        io.submit(() -> SwingUtilities.invokeLater(() -> {
            var scroll = history.getVerticalScrollBar(); var bounds = getBounds();
            result.complete(Json.object("fileId",selectedFile,"sessionId", selected, "drafts", drafts, "draftImages",draftImages,"priorityInterrupt",pendingInterrupt,"provider", model.provider(), "model", model.model(), "reasoningEffort", model.effort(), "queueTake", pendingTake, "chatPosition", chatNavigation.savePosition(),
                    "x", bounds.x, "y", bounds.y, "width", bounds.width, "height", bounds.height, "extendedState", getExtendedState(),"trayHidden",handoffTrayHidden,
                    "sessionViews",sessionViews,"inactiveOpen", inactiveOpen, "previewOpen", previewOpen, "previewDivider", content.getDividerLocation(),
                    "artifact", artifacts.getSelectedItem() instanceof Artifact a ? a.path.toString() : "", "queueExpanded", runStatus.queueExpanded(),
                    "scroll", scroll.getValue(), "tail", scroll.getValue() + scroll.getVisibleAmount() >= scroll.getMaximum() - 60));
        }));
        return result;
    }
    public void adoptHandoff(JsonNode saved) {
        handoffTrayHidden=saved.path("trayHidden").asBoolean();
        saved.path("sessionViews").properties().forEach(e->sessionViews.put(e.getKey(),e.getValue()));
        restoreProjectExpansion();persistViews();treeSignature="";
        initialDocument=saved.path("fileId").asText(null);String id = saved.path("sessionId").asText(null);
        if (initialDocument==null&&id != null && !Objects.equals(id, selected)) selectSession(id);
        if (saved.has("width")) { setBounds(saved.path("x").asInt(), saved.path("y").asInt(), Math.max(920, saved.path("width").asInt()), Math.max(600, saved.path("height").asInt())); setExtendedState(saved.path("extendedState").asInt()); }
        pendingHandoff = saved; lastAppliedState = 0; poller.execute(this::poll);
    }
    public void activateAfterHandoff() { handoffTrayHidden=false;setEnabled(true);if(desktopTray!=null)desktopTray.showWindow();else{setExtendedState(getExtendedState()&~Frame.ICONIFIED);setVisible(true);toFront();}if(selectedFile==null)input.requestFocusInWindow(); }
    public void completeHandoff(){boolean hidden=handoffTrayHidden;handoffTrayHidden=false;setEnabled(true);if(!hidden||!minimizeToTray())activateAfterHandoff();}
    public void setUpdating(boolean value) { externalUpdate = value; poller.execute(this::poll); }
    public void closeWindow() {closeWindow(true);}
    private void closeWindow(boolean notify) {
        if (disposed) return; saveDraft();rememberPreview(); disposed = true;if(reminderWindow!=null)reminderWindow.close(); exitFullScreen(); draftTimer.stop(); poller.shutdownNow();for(var view:chatViews.values())view.close();if(chatView!=null&&!chatViews.containsValue(chatView))chatView.close(); viewer.close(); io.shutdown();if(desktopTray!=null)desktopTray.close();dispose();
        Thread.ofVirtual().start(() -> { try { io.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} host.close(); if(notify)onClose.run(); });
    }
}

