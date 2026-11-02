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
    private final ChatTranscript transcript = new ChatTranscript();
    private final JScrollPane history = new JScrollPane(transcript);
    private final ChatNavigation chatNavigation = new ChatNavigation(transcript, history);
    private final JTextArea input = new PromptInput();
    private final ModelSelector model = new ModelSelector(this::saveSettings);
    private final JLabel access = new JLabel("Полный доступ");
    private final JButton send = Theme.button("Отправить", "Enter — отправить; если есть очередь — дополнить последнее сообщение", this::send);
    private final JButton sendOptions = Theme.button("", "Способы отправки · Alt+Enter — отдельное сообщение", this::sendMenu);
    private final JButton stop = Theme.button("Стоп", "Остановить текущий запуск; сохранить очередь на паузе", this::stop);
    private final JButton interrupt = Theme.button("Сейчас", "Прервать работу и выполнить это сообщение сейчас · Shift+Enter. Очередь сохранится на паузе", this::interrupt);
    private final AttachmentStrip imageStrip = new AttachmentStrip(this::openArtifact,this::removeImage);
    private final JButton results = Theme.button("Результаты", "Изображения и HTML от агента", () -> showPreview(!this.previewOpen));
    private final JPanel requests = new JPanel();
    private final RunStatusPanel runStatus = new RunStatusPanel(this::resumeQueue, this::removeQueued, this::moveQueued, this::takeQueued, this::groupQueued);
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
    private String restoreViewFor;private boolean switchingView;
    private void rememberPreview(){
        if(selected==null||switchingView)return;
        int divider=previewOpen&&fullScreenPreview==null?content.getDividerLocation():previewDivider;
        sessionViews.put(selected,Json.object("open",previewOpen,"divider",divider,"path",artifacts.getSelectedItem() instanceof Artifact a?a.path.toString():"","title",artifacts.getSelectedItem() instanceof Artifact a?a.title:""));
        var copy=Json.MAPPER.valueToTree(sessionViews);io.submit(()->{try{Path file=dev.tethium.host.AppPaths.data().resolve("session-views.json");Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"views-",".tmp");try{Json.MAPPER.writeValue(temp.toFile(),copy);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}catch(Exception ignored){}});
    }
    private void restorePreview(){
        if(!Objects.equals(restoreViewFor,selected))return;restoreViewFor=null;var saved=sessionViews.get(selected);if(saved==null)return;
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
    private record Node(String id, String title, boolean project, boolean running, String status, String path, String attention) { public String toString() { return title; } }
    private record Artifact(String title, Path path) { public String toString() { return title; } }

    public WorkspaceFrame(HostClient host, Runnable onClose) {
        this(host, onClose, null);
    }
    public WorkspaceFrame(HostClient host, Runnable onClose, String initialSelection) {
        super("Tethium"); this.host = host; this.onClose = onClose;
        setIconImages(AppIcon.images());
        this.initialSelection = initialSelection;
        try{Path saved=dev.tethium.host.AppPaths.data().resolve("session-views.json");if(Files.isRegularFile(saved))Json.MAPPER.readTree(saved.toFile()).properties().forEach(e->sessionViews.put(e.getKey(),e.getValue()));}catch(Exception ignored){}
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); setMinimumSize(new Dimension(920, 600)); setSize(1280, 860); setLocationRelativeTo(null);
        draftTimer = new javax.swing.Timer(600, e -> saveDraft()); draftTimer.setRepeats(false);
        build();
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { closeWindow(); } });
        poller.scheduleWithFixedDelay(this::poll, 0, 500, TimeUnit.MILLISECONDS);
    }
    private JsonNode resourceUsage=Json.object();
    private JTree tree() {
        var tree = new JTree(new DefaultMutableTreeNode("root")) { @Override public boolean getScrollableTracksViewportWidth() { return true; } };
        tree.setRootVisible(false); tree.setShowsRootHandles(true); tree.setRowHeight(32);
        tree.setBorder(new EmptyBorder(Theme.GAP, Theme.GAP, Theme.GAP, Theme.GAP)); tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            private final ResourceBars rowPanel = new ResourceBars();
            private final JLabel dot = new JLabel("●");
            @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                var label = (JLabel) super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                label.setIcon(null); setBackgroundNonSelectionColor(Theme.SIDEBAR); setBackgroundSelectionColor(Theme.SELECTED); setBorderSelectionColor(Theme.SELECTED);
                var user = ((DefaultMutableTreeNode) value).getUserObject();
                if (user instanceof Node n) {
                    label.setText(n.title); label.setFont(t.getFont().deriveFont(n.project ? Font.BOLD : Font.PLAIN));
                    label.setIcon(IdentityIcons.of(n.project, n.title, n.path)); label.setIconTextGap(Theme.GAP);
                    label.setForeground(n.running ? Theme.ACCENT : n.status.equals("FAILED") ? Theme.RED : Theme.TEXT);
                    label.setToolTipText(n.path); label.setBorder(new EmptyBorder(0, Theme.UNIT, 0, Theme.UNIT));
                    rowPanel.removeAll(); rowPanel.setBackground(selected?Theme.SELECTED:Theme.SIDEBAR); rowPanel.add(label);
                    dot.setName("session.attention"); dot.setForeground(n.attention.equals("question")?Theme.AMBER:Theme.ACCENT);
                    dot.setVisible(!n.attention.isEmpty()); dot.setBorder(new EmptyBorder(0,0,0,Theme.GAP));
                    dot.setToolTipText(n.attention.equals("question")?"Ждёт ответа":"Работа завершена · новый результат");
                    rowPanel.add(dot,BorderLayout.EAST); rowPanel.setToolTipText(n.path+(n.attention.isEmpty()?"":" · "+dot.getToolTipText()));
                    String pid=n.project?n.id:sessions.getOrDefault(n.id,Json.object()).path("projectId").asText();
                    rowPanel.share=ResourceBars.share(resourceUsage,n.id,n.project,pid);
                    String hint=n.path+" · "+rowPanel.share.tooltip()+" · с последнего сброса; CPU измеряется с момента включения учёта";
                    rowPanel.setToolTipText(hint);label.setToolTipText(hint);
                    rowPanel.setPreferredSize(null); var size=rowPanel.getPreferredSize();
                    if(t.getWidth()>0)size.width=Math.max(48,t.getWidth()-((DefaultMutableTreeNode)value).getLevel()*20-Theme.GUTTER);
                    rowPanel.setPreferredSize(size);return rowPanel;
                }
                return label;
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tree); return tree;
    }
    private void build() {
        var sidebar = new JPanel(new BorderLayout()); sidebar.setBackground(Theme.SIDEBAR); sidebar.setMinimumSize(new Dimension(210, 100));
        var brand = new JPanel(new BorderLayout(Theme.GAP, 0)); brand.setOpaque(false); brand.setBorder(new EmptyBorder(Theme.INSET, Theme.GUTTER, Theme.GAP, Theme.INSET));
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.UNIT, 0)); actions.setOpaque(false);
        var addProject = Theme.button("Проект", "Добавить каталог проекта", this::addProject); addProject.setIcon(Theme.icon("project-add")); Theme.toolbar(addProject); addProject.setVisible(!host.remote()); actions.add(addProject);
        var addSession = Theme.button("Сессия", "Новая сессия в выбранном проекте", this::newSession); addSession.setIcon(Theme.icon("session-new")); Theme.toolbar(addSession); actions.add(addSession);
        var phone = Theme.iconButton("phone", "Подключить телефон по Wi-Fi", () -> MobileConnectDialog.open(this)); phone.getAccessibleContext().setAccessibleName("Подключить телефон"); phone.setVisible(!host.remote()); actions.add(phone); brand.add(actions, BorderLayout.WEST);
        var sidebarHeader = new JPanel(new BorderLayout()); sidebarHeader.setOpaque(false); sidebarHeader.add(brand, BorderLayout.NORTH); sidebarHeader.add(quota); sidebar.add(sidebarHeader, BorderLayout.NORTH);
        var scroll = new JScrollPane(active); scroll.setBorder(null); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); sidebar.add(scroll);
        var archive = new JPanel(new BorderLayout()); archive.setBackground(Theme.SIDEBAR); archive.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        archiveToggle.setHorizontalAlignment(SwingConstants.LEFT); Theme.toolbar(archiveToggle); archiveToggle.setIcon(Theme.icon("archive")); archiveToggle.setIconTextGap(Theme.GAP); archiveToggle.setMargin(new Insets(Theme.GAP, Theme.GUTTER, Theme.GAP, Theme.INSET)); archive.add(archiveToggle, BorderLayout.NORTH);
        inactiveScroll.setBorder(null); inactiveScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); inactiveScroll.setPreferredSize(new Dimension(240, 180)); inactiveScroll.setVisible(false); archive.add(inactiveScroll); sidebar.add(archive, BorderLayout.SOUTH);
        for (JTree tree : List.of(active, inactive)) {
            tree.addTreeSelectionListener(e -> {
                if (changingTree) return;
                var node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent(); if (node == null || !(node.getUserObject() instanceof Node n)) return;
                if (n.project) selectedProject = n.id; else selectSession(n.id);
            });
            tree.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { popup(e, tree); }
                @Override public void mouseReleased(MouseEvent e) { popup(e, tree); }
            });
        }
        var header = new JPanel(new BorderLayout(Theme.INSET, 0)); header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(Theme.GAP, Theme.GUTTER, Theme.GAP, Theme.GUTTER)));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        var titleGroup = new JPanel(new BorderLayout(0, Theme.UNIT)); titleGroup.add(heading); titleGroup.add(status, BorderLayout.SOUTH); header.add(titleGroup);
        var controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        status.setForeground(Theme.MUTED); status.setFont(status.getFont().deriveFont(11f));
        controls.add(model);
        results.setText(""); results.getAccessibleContext().setAccessibleName("Результаты агента");
        results.setIcon(Theme.icon("image")); Theme.toolbar(results); controls.add(results);
        controls.add(Theme.iconButton("more", "Действия сессии", this::sessionMenu)); header.add(controls, BorderLayout.EAST);
        transcript.addHyperlinkListener(e -> { if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) openLink(e.getDescription()); });
        var quote = Theme.button("Цитировать", "Цитировать выделение · Ctrl+Shift+Q", this::quoteSelection);
        quote.setName("chat.quote"); quote.setVisible(false); controls.add(quote,0);
        transcript.addCaretListener(e -> quote.setVisible(transcript.getSelectedText()!=null&&!transcript.getSelectedText().isBlank()));
        var quoteMenu = new JPopupMenu(); quoteMenu.add(new AbstractAction("Цитировать выделение") { public void actionPerformed(ActionEvent e){quoteSelection();} });
        quoteMenu.add(new AbstractAction("Копировать") { public void actionPerformed(ActionEvent e){transcript.copy();} }); transcript.setComponentPopupMenu(quoteMenu);
        transcript.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Q,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK),"quote");
        transcript.getActionMap().put("quote",new AbstractAction(){public void actionPerformed(ActionEvent e){quoteSelection();}});
        history.setName("chat.history"); history.setBorder(null); history.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        history.getVerticalScrollBar().setUnitIncrement(24);
        var historyArea = new JPanel(new BorderLayout()); historyArea.add(history); historyArea.add(chatNavigation.requestMap(), BorderLayout.EAST);
        chat.add(historyArea); chat.add(chatNavigation, BorderLayout.NORTH);
        chat.setMinimumSize(new Dimension(340, 0));
        var bottom = new JPanel(new BorderLayout()); requests.setLayout(new BoxLayout(requests, BoxLayout.Y_AXIS));
        var feedback = new JPanel(new BorderLayout()); feedback.add(requests, BorderLayout.NORTH); feedback.add(runStatus); bottom.add(feedback, BorderLayout.NORTH);
        var composerWrap = new JPanel(new BorderLayout()); composerWrap.setBorder(new EmptyBorder(Theme.GAP, Theme.GUTTER, Theme.INSET, Theme.GUTTER));
        var composer = new ComposerPanel(input); composerWrap.add(composer);
        input.setLineWrap(true); input.setWrapStyleWord(true); input.setFont(input.getFont().deriveFont(14f)); input.setBorder(new EmptyBorder(Theme.UNIT, Theme.UNIT, Theme.UNIT, Theme.UNIT)); input.putClientProperty("JTextArea.placeholderText", "Сообщение Codex…");
        input.setToolTipText("Enter — отправить или дополнить очередь; Shift+Enter — прервать и выполнить сейчас; Ctrl+Enter — новая строка; Alt+Enter — отдельное сообщение в очередь.");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        input.addFocusListener(new FocusAdapter() { @Override public void focusGained(FocusEvent e) { viewer.releaseFocus(); } });
        input.addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { viewer.releaseFocus(); input.requestFocusInWindow(); } });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), javax.swing.text.DefaultEditorKit.insertBreakAction);
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), "sendSeparate");
        input.getActionMap().put("send", new AbstractAction() { public void actionPerformed(ActionEvent e) { send(); } });
        input.getActionMap().put("sendSeparate", new AbstractAction() { public void actionPerformed(ActionEvent e) { send(true); } });
        // Windows AltGr can arrive as ALT_GRAPH or as synthetic Ctrl+Alt. Both mean separate queue entry.
        for (int modifiers : new int[]{InputEvent.ALT_GRAPH_DOWN_MASK|InputEvent.CTRL_DOWN_MASK,InputEvent.ALT_GRAPH_DOWN_MASK, InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK, InputEvent.ALT_GRAPH_DOWN_MASK|InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK, InputEvent.ALT_GRAPH_DOWN_MASK|InputEvent.ALT_DOWN_MASK})
            input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,modifiers),"sendSeparate");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.SHIFT_DOWN_MASK),"interrupt");
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
        var previewBar = new JPanel(new BorderLayout(Theme.GAP, 0)); previewBar.setBorder(new EmptyBorder(Theme.GAP, Theme.GAP, Theme.GAP, Theme.GAP)); previewBar.add(artifacts);
        var pvActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.UNIT, 0));
        fitPreview.setIcon(Theme.icon("preview-fit")); actualPreview.setIcon(Theme.icon("preview-actual-size")); fitPreview.setVisible(false); actualPreview.setVisible(false); pvActions.add(fitPreview); pvActions.add(actualPreview);
        expandPreview.setIcon(Theme.previewIcon(true)); Theme.toolbar(expandPreview); expandPreview.setName("artifact.fullscreen"); expandPreview.getAccessibleContext().setAccessibleName("На весь экран"); pvActions.add(expandPreview); getBuild.setVisible(host.remote());runBuild.setVisible(false);pvActions.add(getBuild);pvActions.add(runBuild);
        var reload = Theme.iconButton("refresh", "Обновить результат", viewer::reload);
        pvActions.add(reload); pvActions.add(Theme.iconButton("close", "Закрыть просмотр", () -> showPreview(false))); previewBar.add(pvActions, BorderLayout.EAST);
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
    private void popup(MouseEvent event, JTree tree) {
        if (!event.isPopupTrigger()) return;
        var path = tree.getPathForLocation(event.getX(), event.getY()); if (path == null) return;
        tree.setSelectionPath(path); var n = (Node) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        var menu = new JPopupMenu();
        var create = new JMenuItem("Новая сессия"); create.addActionListener(e -> newSession()); menu.add(create);
        if (!n.project) {
            var rename = new JMenuItem("Переименовать"); rename.addActionListener(e -> rename()); menu.add(rename);
            var state = new JMenuItem(sessions.get(n.id).path("active").asBoolean() ? "Деактивировать" : "Активировать"); state.addActionListener(e -> toggleActive()); menu.add(state);
        }
        if(n.project&&!host.remote()){var settings=new JMenuItem("Настройки проекта и GitHub…");settings.addActionListener(e->ProjectDialog.open(this,host,projects.get(n.id),value->{}));menu.add(settings);}
        var usageItem=new JMenuItem("Токены и история квоты…");usageItem.addActionListener(e->UsageDialog.open(this,host,projects,sessions,n.project?n.id:sessions.get(n.id).path("projectId").asText(),n.project?null:n.id));menu.add(usageItem);
        menu.show(tree, event.getX(), event.getY());
    }
    private void sessionMenu() {
        var menu = new JPopupMenu();
        var accessInfo = new JMenuItem("Полный доступ"); accessInfo.setEnabled(false); menu.add(accessInfo); menu.addSeparator();
        for (var entry : List.<Map.Entry<String, Runnable>>of(Map.entry("Новая сессия", this::newSession), Map.entry("Переименовать", this::rename),
                Map.entry("Активировать / деактивировать", this::toggleActive), Map.entry("Результаты агента", () -> showPreview(!previewOpen)),
                Map.entry("Открыть изображение, HTML или Markdown…", this::chooseArtifact), Map.entry("Действия Codex…", this::showActivity))) {
            var item = new JMenuItem(entry.getKey()); item.addActionListener(e -> entry.getValue().run()); menu.add(item);
        }
        if(selected!=null){String id=selected;var a=sessions.get(id).path("autonomy");var autonomous=new JCheckBoxMenuItem("Работать автономно до завершения цели",Set.of("active","waiting").contains(a.path("state").asText()));autonomous.addActionListener(e->{if(!autonomous.isSelected()){action(()->host.post("/sessions/"+id+"/autonomy",Json.object("action","disable")));return;}String goal=a.path("goal").asText();if(goal.isBlank()||a.path("state").asText().equals("complete")){goal="";for(var m:conversation.path("messages"))if(m.path("role").asText().equals("user"))goal=m.path("text").asText();}var editor=new JTextArea(goal,6,52);editor.setLineWrap(true);editor.setWrapStyleWord(true);if(JOptionPane.showConfirmDialog(this,new JScrollPane(editor),"Цель автономной работы",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION&&!editor.getText().isBlank())action(()->host.post("/sessions/"+id+"/autonomy",Json.object("action","start","goal",editor.getText(),"resumeQueue",true)));});menu.add(autonomous);}
        if(selected!=null){String id=selected;var automatic=new JCheckBoxMenuItem("Продолжать автоматически после появления квоты",sessions.get(id).path("quotaWait").path("autoResume").asBoolean());automatic.addActionListener(e->action(()->host.post("/sessions/"+id+"/quota-auto",Json.object("enabled",automatic.isSelected()))));menu.add(automatic);}
        menu.addSeparator(); var quit = new JMenuItem("Завершить ядро и все запуски"); quit.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Остановить все запуски и завершить Tethium?", "Завершение", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                action(() -> { host.post("/shutdown", Json.object()); SwingUtilities.invokeLater(this::closeWindow); });
        }); if(!host.remote())menu.add(quit);else{var reconnect=new JMenuItem("Подключить другой ноутбук…");reconnect.addActionListener(e->{saveDraft();RemoteMain.restartPairing(this);});menu.add(reconnect);} menu.show(heading, Math.max(0, heading.getWidth() - 100), heading.getHeight());
    }
    public void selectSession(String id) {
        if (Objects.equals(selected, id)) return;
        var session = sessions.get(id); if(session==null)return;
        saveDraft();rememberPreview();switchingView=true; selected = id;restoreViewFor=id;
        selectedProject = session.path("projectId").asText(); chatSignature = ""; requestSignature = ""; artifactSignature = ""; conversation = Json.object();
        scrollToLatest = true; chatRender++; chatNavigation.reset();
        applying = true;
        input.setText(drafts.getOrDefault(id, session.path("preferences").path("draft").asText()));
        draftImages.computeIfAbsent(id,key->strings(session.path("preferences").path("draftImages"))); refreshImages();
        var preferences = settingsDrafts.getOrDefault(id, session.path("preferences"));
        model.selection(preferences.path("model").asText(null), preferences.path("reasoningEffort").asText(null));
        model.loading(); catalogSession = null; catalogAttempt = 0;
        applying = false; renderHeader(session); requests.removeAll(); transcript.setText(page("<p class='muted'>Загружаем историю…</p>"));
        showPreview(false); viewer.clear(); applying = true; artifacts.removeAllItems(); applying = false;switchingView=false;
        runStatus.update(session, conversation);
        input.requestFocusInWindow(); poller.execute(this::poll);
    }
    private void renderHeader(JsonNode session) {
        boolean enabled = session.path("active").asBoolean(); boolean busy = session.path("running").asBoolean();
        heading.setText(session.path("title").asText()); heading.setToolTipText(session.path("directory").asText());
        var project = projects.get(session.path("projectId").asText());
        setTitle((project == null ? "Tethium" : project.path("title").asText("Tethium")) + ": " + session.path("title").asText());
        status.setText(busy ? session.path("phase").asText("Работает") : !enabled ? "Неактивна" : session.path("status").asText().equals("FAILED") ? "Ошибка" : "Codex");
        int queued = session.path("queued").asInt(); if (queued > 0) status.setText(status.getText() + " · очередь " + queued);
        status.setToolTipText(status.getText());
        boolean paused = session.path("queuePaused").asBoolean();
        if (!busy && paused) status.setText("На паузе");
        else if(session.path("quotaWait").path("waiting").asBoolean()){status.setText(session.path("quotaWait").path("autoResume").asBoolean()?"Ждём квоту · продолжим автоматически":"Нет квоты · автопродолжение выключено");status.setForeground(Theme.AMBER);}else status.setForeground(Theme.MUTED);
        var autonomy=session.path("autonomy");String autonomyState=autonomy.path("state").asText();
        if(autonomyState.equals("active")){status.setText(status.getText()+" · автономно");status.setToolTipText(autonomy.path("goal").asText());}else if(!busy&&autonomyState.equals("waiting")){status.setText("Автономно · ждём результат подсессии");}else if(!busy&&autonomyState.equals("blocked")){status.setText("Автономная работа ждёт вашего участия");status.setForeground(Theme.AMBER);status.setToolTipText(autonomy.path("reason").asText());}
        boolean taking = pendingTake != null && session.path("id").asText().equals(pendingTake.path("sessionId").asText()) || pendingInterrupt!=null && session.path("id").asText().equals(pendingInterrupt.path("sessionId").asText());
        input.setEnabled(enabled); input.setEditable(!taking); model.sessionEnabled(true); access.setEnabled(true); send.setEnabled(enabled && !taking); sendOptions.setEnabled(enabled && !taking);
        boolean writing=writingImages.getOrDefault(selected,0)>0;
        if(writing) {send.setEnabled(false);sendOptions.setEnabled(false);}
        boolean relatedWork=relatedWork(session.path("id").asText());
        interrupt.setVisible(relatedWork || queued>0 || paused);
        interrupt.setEnabled(interruptAvailable&&enabled&&!taking&&!writing&&(!input.getText().isBlank()||!draftImages.getOrDefault(selected,List.of()).isEmpty()));
        stop.setEnabled(relatedWork && !session.path("stopping").asBoolean());
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
        try { var state = host.get("/state" + (target == null ? "" : "?session=" + target)); SwingUtilities.invokeLater(() -> apply(state, target)); }
        catch (Exception e) { SwingUtilities.invokeLater(() -> { if (!disposed) { status.setText("Нет связи с ядром"); status.setToolTipText(e.getMessage()); } }); }
    }
    private void apply(JsonNode state, String target) {
        if (disposed) return;
        quota.update(state.path("quota"));
        if(!resourceUsage.equals(state.path("resourceUsage"))){resourceUsage=state.path("resourceUsage");active.repaint();inactive.repaint();}
        queueEditingAvailable = state.path("queueEditing").asBoolean();
        groupingAvailable=state.path("queueGrouping").asBoolean();imagesAvailable=state.path("imageAttachments").asBoolean();interruptAvailable=state.path("priorityInterrupt").asBoolean();
        runStatus.setGroupingAvailable(groupingAvailable);runStatus.setQueueEditing(queueEditingAvailable, pendingTake != null || grouping);
        projects.clear(); for (var p : state.path("projects")) projects.put(p.path("id").asText(), p);
        sessions.clear(); for (var s : state.path("sessions")) sessions.put(s.path("id").asText(), s);
        String signature = state.path("projects").toString() + state.path("sessions").findValues("id") + state.path("sessions").findValues("title") + state.path("sessions").findValues("active") + state.path("sessions").findValues("running") + state.path("sessions").findValues("status") + state.path("sessions").findValues("attention");
        if (!signature.equals(treeSignature)) { rebuildTrees(state); treeSignature = signature; }
        if (selectedProject == null && !projects.isEmpty()) selectedProject = projects.keySet().iterator().next();
        if (selected == null && !sessions.isEmpty()) {
            String first = initialSelection != null && sessions.containsKey(initialSelection) ? initialSelection : state.path("sessions").get(0).path("id").asText();
            initialSelection = null; selectSession(first); treeSignature = ""; return;
        }
        if (selected != null && sessions.containsKey(selected)) renderHeader(sessions.get(selected));
        if (!Objects.equals(target, selected)) return;
        conversation = state.path("conversation");
        if (selected != null && sessions.containsKey(selected)) runStatus.update(sessions.get(selected), conversation);
        renderChat(); renderRequests(); renderArtifacts();restorePreview();
        SwingUtilities.invokeLater(this::acknowledgeVisibleResult);
        if (sessions.isEmpty()) transcript.setText(page("<h2>Начнём с проекта</h2><p>Добавьте каталог кнопкой <b>+ Проект</b>, затем создайте сессию.</p><p class='muted'>Codex работает на этом компьютере. История сохраняется автоматически.</p>"));
        lastAppliedState = System.currentTimeMillis();
        loadModelCatalog();
        boolean updating = externalUpdate || state.path("updating").asBoolean(); runStatus.setUpdating(updating);
        if (updating) { status.setText("Обновление ядра · текущая работа продолжается"); status.setToolTipText("Очередь продолжится после проверки новой версии ядра"); }
        if (pendingHandoff != null) {
            var saved = pendingHandoff; pendingHandoff = null;
            saved.path("drafts").fields().forEachRemaining(e -> drafts.put(e.getKey(), e.getValue().asText()));
            saved.path("draftImages").fields().forEachRemaining(e->draftImages.put(e.getKey(),strings(e.getValue())));refreshImages();
            applying = true; input.setText(drafts.getOrDefault(selected, input.getText()));
            model.selection(saved.path("model").asText(model.model()), saved.path("reasoningEffort").asText(model.effort())); applying = false;
            if (saved.path("queueTake").isObject()) { pendingTake = saved.path("queueTake"); takeRetryAt = 0; }
            if(saved.path("priorityInterrupt").isObject()) { pendingInterrupt=saved.path("priorityInterrupt");interruptRetryAt=0; }
            if (saved.path("inactiveOpen").asBoolean() != inactiveOpen) toggleInactive();
            if (saved.path("previewOpen").asBoolean() && artifacts.getItemCount() > 0) {
                showPreview(true);
                for (int i = 0; i < artifacts.getItemCount(); i++) if (artifacts.getItemAt(i).path.toString().equals(saved.path("artifact").asText())) artifacts.setSelectedIndex(i);
                content.setDividerLocation(saved.path("previewDivider").asInt(content.getDividerLocation()));
            }
            runStatus.restoreQueueExpanded(saved.path("queueExpanded").asBoolean());
            SwingUtilities.invokeLater(() -> chatNavigation.restorePosition(saved.has("chatPosition") ? saved.path("chatPosition") : saved));
        }
        retryQueueTake();retryInterrupt();
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
                var collapsed = new HashSet<String>(); var old = (DefaultMutableTreeNode) tree.getModel().getRoot();
                for (int i = 0; i < old.getChildCount(); i++) { var node = (DefaultMutableTreeNode) old.getChildAt(i); if (!tree.isExpanded(new TreePath(node.getPath()))) collapsed.add(((Node) node.getUserObject()).id); }
                var root = new DefaultMutableTreeNode("root"); TreePath selection = null;
                for (var project : projects.values()) {
                    String pid = project.path("id").asText();
                    var matching = new ArrayList<JsonNode>(); for (var s : state.path("sessions")) if (s.path("projectId").asText().equals(pid) && s.path("active").asBoolean() != archived) matching.add(s);
                    if (archived && matching.isEmpty()) continue;
                    var projectNode = new DefaultMutableTreeNode(new Node(pid, project.path("title").asText() + "  " + matching.size(), true, false, "", project.path("root").asText(),"")); root.add(projectNode);
                    var nodes = new LinkedHashMap<String, DefaultMutableTreeNode>();
                    for (var s : matching) nodes.put(s.path("id").asText(), new DefaultMutableTreeNode(new Node(s.path("id").asText(), s.path("title").asText(), false, s.path("running").asBoolean(), s.path("status").asText(), s.path("directory").asText(),s.path("attention").path("kind").asText())));
                    for (var s : matching) { var n = nodes.get(s.path("id").asText()); var parent = nodes.get(s.path("parentId").asText()); (parent == null ? projectNode : parent).add(n); }
                    for (var entry : nodes.entrySet()) if (entry.getKey().equals(selected)) selection = new TreePath(entry.getValue().getPath());
                }
                tree.setModel(new DefaultTreeModel(root));
                for (int i = 0; i < tree.getRowCount(); i++) { var path = tree.getPathForRow(i); var n = (DefaultMutableTreeNode) path.getLastPathComponent(); if (n.getUserObject() instanceof Node node && !collapsed.contains(node.id)) tree.expandPath(path); }
                if (selection != null) tree.setSelectionPath(selection);
            }
            long count = sessions.values().stream().filter(s -> !s.path("active").asBoolean()).count(); archiveToggle.setText((inactiveOpen ? "⌄ " : "› ") + "Неактивные  " + count);
        } finally { changingTree = false; }
    }
    private void renderChat() {
        String key = conversation.path("messages").toString() + conversation.path("live").asText();
        if (key.equals(chatSignature)) return;
        if (transcript.isFocusOwner() && transcript.getSelectedText() != null) return;
        chatSignature = key; var html = new StringBuilder();
        var tasks = ChatNavigation.tasks(conversation.path("messages")); int taskIndex = 0;
        for (var message : conversation.path("messages")) {
            String role = message.path("role").asText(); String who = role.equals("user") ? "ВЫ" : role.equals("assistant") ? "CODEX" : role.equals("session") ? "ДРУГАЯ СЕССИЯ" : "TETHIUM";
            if (message.path("provider").asText().equals("IMPORTED")) who += " · ИМПОРТ";
            html.append("<div class='").append(role.equals("user") ? "user" : "message").append("'");
            if (role.equals("user")) html.append(" id='").append(tasks.get(taskIndex++).anchor()).append("'");
            html.append("><p class='label'>").append(who).append("</p>").append(markdown(message.path("text").asText())).append("</div>");
        }
        String live = conversation.path("live").asText(); if (!live.isEmpty()) html.append("<div class='message'><p class='label'><span class='live'>CODEX · РАБОТАЕТ</span></p>").append(markdown(live)).append("</div>");
        if (html.isEmpty()) html.append("<h2>Новая сессия</h2><p class='muted'>Опишите задачу. Codex увидит файлы проекта и соседние сессии.</p>");
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
        for (var r : conversation.path("requests")) {
            var panel = new JPanel(new BorderLayout(Theme.GAP, Theme.GAP)); panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER), new EmptyBorder(Theme.GAP, Theme.GUTTER, Theme.GAP, Theme.GUTTER)));
            String method = r.path("method").asText(); var params = r.path("params");
            String details = params.path("command").asText(params.path("reason").asText(params.path("message").asText("Codex ждёт вашего ответа")));
            if (method.equals("item/permissions/requestApproval")) details += "\n" + params.path("permissions").toPrettyString();
            var description = new JTextArea(details);
            description.setEditable(false); description.setLineWrap(true); description.setWrapStyleWord(true); description.setRows(Math.min(3, description.getLineCount() + 1)); panel.add(description);
            var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
            if (method.equals("item/permissions/requestApproval")) {
                actions.add(Theme.button("Разрешить", "Предоставить указанные права на этот запуск", () -> answer(r, Json.object("permissions", params.path("permissions"), "scope", "turn"))));
                actions.add(Theme.button("Отклонить", "Не предоставлять дополнительные права", () -> answer(r, Json.object("permissions", Json.object(), "scope", "turn"))));
            } else if (method.endsWith("requestApproval")) {
                actions.add(Theme.button("Разрешить", "Разрешить это действие", () -> answer(r, Json.object("decision", "accept"))));
                actions.add(Theme.button("Для сессии", "Разрешить в рамках сессии Codex", () -> answer(r, Json.object("decision", "acceptForSession"))));
                actions.add(Theme.button("Отклонить", "Отказать в действии", () -> answer(r, Json.object("decision", "decline"))));
            } else actions.add(Theme.button("Ответить…", "Открыть вопросы Codex", () -> answerQuestions(r)));
            panel.add(actions, BorderLayout.SOUTH); requests.add(panel);
        }
        requests.revalidate(); requests.repaint();
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
            if (JOptionPane.showConfirmDialog(this, panel, "Вопросы Codex", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
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
        String signature = conversation.path("artifacts").toString(); if (signature.equals(artifactSignature)) return; artifactSignature = signature;
        applying = true; Object previous = artifacts.getSelectedItem(); artifacts.removeAllItems();
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
        var value = Json.object("model", model.model(), "reasoningEffort", model.effort(), "access", accessValue()); settingsDrafts.put(id, value);
        action(() -> host.post("/sessions/" + id + "/settings", value));
    }
    private String accessValue() { return "danger-full-access"; }
    private void sendMenu() {
        var menu = new JPopupMenu();
        var separate = new JMenuItem("Отдельным сообщением в очередь"); separate.setName("composer.sendSeparate");
        separate.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK));
        separate.setEnabled(send.isEnabled() && !input.getText().isBlank()); separate.addActionListener(e -> send(true)); menu.add(separate);
        var urgent=new JMenuItem("Прервать и выполнить сейчас");urgent.setEnabled(interrupt.isEnabled());urgent.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.SHIFT_DOWN_MASK));urgent.addActionListener(e->interrupt());menu.add(urgent);
        menu.show(sendOptions, sendOptions.getWidth() - menu.getPreferredSize().width, -menu.getPreferredSize().height);
    }
    public void send() { send(false); }
    private void send(boolean separate) {
        if (selected == null || input.getText().isBlank() && draftImages.getOrDefault(selected,List.of()).isEmpty() || !send.isEnabled()) return;
        String id = selected, prompt = input.getText(), modelName = model.model(), effort = model.effort(), mode = accessValue();
        var images=draftImages.getOrDefault(id,List.of());draftImages.put(id,List.of());refreshImages();
        draftTimer.stop(); applying = true; input.setText(""); drafts.put(id, ""); applying = false;
        action(() -> {
            try { host.post("/sessions/" + id + "/settings", Json.object("model", modelName, "reasoningEffort", effort, "access", mode)); var result=host.post("/sessions/" + id + "/run", Json.object("prompt", prompt, "images",images,"separate", separate));
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
    private void interrupt() {
        if(selected==null||!interrupt.isEnabled()||pendingInterrupt!=null)return;
        draftTimer.stop();saveDraft();pendingInterrupt=Json.object("sessionId",selected,"model",model.model(),"effort",model.effort(),"body",Json.object("prompt",input.getText(),"images",draftImages.getOrDefault(selected,List.of()),"requestId",UUID.randomUUID().toString()));
        interruptRetryAt=0;refreshTakeControls();retryInterrupt();
    }
    private void retryInterrupt() {
        if(pendingInterrupt==null||interruptInFlight||disposed||System.currentTimeMillis()<interruptRetryAt||!interruptAvailable)return;
        var operation=pendingInterrupt;String id=operation.path("sessionId").asText();interruptInFlight=true;
        io.submit(()->{try {
            host.post("/sessions/"+id+"/settings",Json.object("model",operation.path("model"),"reasoningEffort",operation.path("effort"),"access",accessValue()));
            host.post("/sessions/"+id+"/interrupt",operation.path("body"));
            SwingUtilities.invokeLater(()->{interruptInFlight=false;pendingInterrupt=null;drafts.put(id,"");draftImages.put(id,List.of());if(Objects.equals(selected,id)){applying=true;input.setText("");applying=false;refreshImages();}refreshTakeControls();if(!disposed)poller.execute(this::poll);});
        }catch(HostClient.ApiException rejected){SwingUtilities.invokeLater(()->{interruptInFlight=false;pendingInterrupt=null;refreshTakeControls();error(rejected);});}
        catch(Exception lost){SwingUtilities.invokeLater(()->{interruptInFlight=false;interruptRetryAt=System.currentTimeMillis()+2000;refreshTakeControls();});}});
    }
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
    private void showPreview(boolean show) {
        var position = chatNavigation.beforeChange(false);
        if (!show) {
            exitFullScreen();
            if (previewOpen) previewDivider = content.getDividerLocation();
            content.setRightComponent(null); content.setDividerSize(0);
        } else if (!previewOpen) {
            content.setRightComponent(preview); content.setDividerSize(4);
            int desired = previewDivider < 0 ? (int) (content.getWidth() * .62) : previewDivider;
            content.setDividerLocation(Math.max(340, Math.min(desired, content.getWidth() - 274)));
        }
        previewOpen = show; results.setSelected(show);
        if (show) loadArtifact();
        rememberPreview();
        content.revalidate(); content.repaint();
        SwingUtilities.invokeLater(() -> { if (!disposed) { validate(); history.getViewport().doLayout(); chatNavigation.afterChange(position); } });
    }
    private void loadArtifact() {
        rememberPreview();
        if (artifacts.getSelectedItem() instanceof Artifact a) {
            artifacts.setToolTipText(a.title + " — " + a.path);
            String filename=a.path.getFileName().toString().toLowerCase(Locale.ROOT);boolean runnable=filename.matches(".*\\.(jar|exe|zip)$");runBuild.setVisible(host.remote()&&runnable);getBuild.setVisible(host.remote());
            if(runnable&&host.remote())viewer.showNotice("Опубликованная сборка · "+a.path.getFileName()+"\nПолучите её или запустите на этом компьютере.");
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
        expandPreview.setText("Вернуться · Esc"); expandPreview.setToolTipText("Вернуться к чату");
        dialog.getRootPane().registerKeyboardAction(e -> exitFullScreen(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setBounds(getGraphicsConfiguration().getBounds());
        loadArtifact(); dialog.setVisible(true); expandPreview.requestFocusInWindow();
    }
    private void exitFullScreen() {
        if (fullScreenPreview == null) return;
        var dialog = fullScreenPreview; fullScreenPreview = null;
        dialog.setContentPane(new JPanel());
        content.setRightComponent(preview); content.setDividerSize(4); content.setDividerLocation(previewDivider);
        viewer.setFullScreen(false); expandPreview.setText(""); expandPreview.setToolTipText("На весь экран");
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
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "Действия Codex", JOptionPane.PLAIN_MESSAGE);
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void action(Work work) { if (!disposed) io.submit(() -> { try { work.run(); poller.execute(this::poll); } catch (Exception e) { SwingUtilities.invokeLater(() -> error(e)); } }); }
    private void error(Exception e) { if (!disposed) JOptionPane.showMessageDialog(this, e.getMessage(), "Tethium", JOptionPane.ERROR_MESSAGE); }
    public JTextArea inputArea() { return input; }
    public String selectedSession() { return selected; }
    public JsonNode reloadReadiness() {
        return Json.object("ready", !disposed && lastAppliedState > System.currentTimeMillis() - 5000 && pendingHandoff == null,
                "visible", isShowing(), "hostPid", host.hostPid(), "sessionId", selected, "lastStateAt", lastAppliedState);
    }
    public CompletableFuture<JsonNode> prepareHandoff() {
        setEnabled(false); draftTimer.stop(); saveSettings(); saveDraft();rememberPreview();
        var result = new CompletableFuture<JsonNode>();
        io.submit(() -> SwingUtilities.invokeLater(() -> {
            var scroll = history.getVerticalScrollBar(); var bounds = getBounds();
            result.complete(Json.object("sessionId", selected, "drafts", drafts, "draftImages",draftImages,"priorityInterrupt",pendingInterrupt,"model", model.model(), "reasoningEffort", model.effort(), "queueTake", pendingTake, "chatPosition", chatNavigation.savePosition(),
                    "x", bounds.x, "y", bounds.y, "width", bounds.width, "height", bounds.height, "extendedState", getExtendedState(),
                    "sessionViews",sessionViews,"inactiveOpen", inactiveOpen, "previewOpen", previewOpen, "previewDivider", content.getDividerLocation(),
                    "artifact", artifacts.getSelectedItem() instanceof Artifact a ? a.path.toString() : "", "queueExpanded", runStatus.queueExpanded(),
                    "scroll", scroll.getValue(), "tail", scroll.getValue() + scroll.getVisibleAmount() >= scroll.getMaximum() - 60));
        }));
        return result;
    }
    public void adoptHandoff(JsonNode saved) {
        saved.path("sessionViews").properties().forEach(e->sessionViews.put(e.getKey(),e.getValue()));
        String id = saved.path("sessionId").asText(null);
        if (id != null && !Objects.equals(id, selected)) selectSession(id);
        if (saved.has("width")) { setBounds(saved.path("x").asInt(), saved.path("y").asInt(), Math.max(920, saved.path("width").asInt()), Math.max(600, saved.path("height").asInt())); setExtendedState(saved.path("extendedState").asInt()); }
        pendingHandoff = saved; lastAppliedState = 0; poller.execute(this::poll);
    }
    public void activateAfterHandoff() { setEnabled(true); setVisible(true); toFront(); input.requestFocusInWindow(); }
    public void setUpdating(boolean value) { externalUpdate = value; poller.execute(this::poll); }
    public void closeWindow() {closeWindow(true);}
    private void closeWindow(boolean notify) {
        if (disposed) return; saveDraft();rememberPreview(); disposed = true; exitFullScreen(); draftTimer.stop(); poller.shutdownNow(); viewer.close(); io.shutdown(); dispose();
        Thread.ofVirtual().start(() -> { try { io.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} host.close(); if(notify)onClose.run(); });
    }
}

