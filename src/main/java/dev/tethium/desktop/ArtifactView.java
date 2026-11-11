package dev.tethium.desktop;

import dev.tethium.artifact.ArtifactServer;
import dev.tethium.host.AppPaths;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.*;
import org.cef.browser.*;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefKeyboardHandlerAdapter;
import org.cef.handler.CefFocusHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.network.CefRequest;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preview pane. One Chromium browser is created once and kept warm for the whole window: switching documents only navigates it,
 * hiding the pane only detaches its component, and reopening an unchanged file re-attaches without a reload.
 */
public final class ArtifactView extends JPanel implements AutoCloseable {
    private final ExecutorService loader = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicLong generation = new AtomicLong();
    private volatile CefApp app;
    private CefClient client;
    private CefBrowser browser;
    private ArtifactServer server;
    private Path current;
    private String shownSignature;
    private java.net.URI pendingUrl;
    private volatile String allowedBase = "about:blank";
    private final java.util.Set<CefBrowser> createdBrowsers=new java.util.HashSet<>();
    private final java.util.Set<CefBrowser> closingBrowsers=new java.util.HashSet<>();
    private int rendererRecoveries;
    private Runnable pendingBrowserOpen;
    private ArtifactServer pendingResources;
    private void cancelPending(){pendingBrowserOpen=null;if(pendingResources!=null){pendingResources.close();pendingResources=null;}}
    public int browserResourceCount(){return closingBrowsers.size()+(browser==null?0:1);}
    /** True while a warm browser is kept for the next document. */
    public boolean browserWarm(){return browser!=null;}
    /** Hides the pane: the browser stays alive and keeps its page, so the next show of the same file is instant. */
    public void suspendBrowser(){if(browser!=null||pendingBrowserOpen!=null){cancelPending();detach();message("Просмотр приостановлен");}}
    private Runnable expand = () -> {}, escape = () -> {};
    private volatile boolean fullScreen;
    private ImagePanel imagePanel;
    private volatile boolean closed;
    private final java.beans.PropertyChangeListener focusListener = event -> {
        if (event.getNewValue() instanceof Component component && !SwingUtilities.isDescendingFrom(component, this)) releaseFocus();
    };
    public ArtifactView() {
        super(new BorderLayout()); setMinimumSize(new Dimension(0, 0)); message("Изображения и HTML от агента");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", focusListener);
    }
    public void releaseFocus() {
        // Swing owns the keyboard; never clear its focus owner while returning from Chromium.
        if(browser!=null)browser.setFocus(false);
    }
    public void onExpand(Runnable action) { expand = action; }
    public void onEscape(Runnable action) { escape = action; }
    public void setFullScreen(boolean value) { fullScreen = value; if (imagePanel != null) imagePanel.updateCursor(); }
    private void message(String text) {
        removeAll(); var label = new JLabel("<html><div style='text-align:center'>" + Theme.escape(text).replace("\n","<br>") + "</div></html>", SwingConstants.CENTER);
        label.setForeground(Theme.MUTED); label.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12)); add(label); revalidate(); repaint();
    }
    private boolean attached(){return browser!=null&&getComponentCount()==1&&getComponent(0)==browser.getUIComponent();}
    private void attach(){
        if(browser==null||attached())return;
        var component=browser.getUIComponent();
        // The canvas is a heavyweight: shown before layout it flashes at its previous bounds (full width after document mode).
        component.setVisible(false);component.setBounds(0,0,Math.max(1,getWidth()),Math.max(1,getHeight()));
        removeAll();add(component,BorderLayout.CENTER);revalidate();repaint();
        SwingUtilities.invokeLater(()->{if(browser==null||browser.getUIComponent()!=component||getComponentCount()!=1||getComponent(0)!=component)return;var root=SwingUtilities.getRoot(this);if(root!=null)root.validate();component.setVisible(true);});
    }
    private void detach(){if(attached()){removeAll();revalidate();repaint();}}
    public void showNotice(String text){clear();message(text);}
    /** An image fetched from the laptop, shown like a local one: no browser is needed for it. */
    public void showImage(java.awt.image.BufferedImage image){if(closed)return;remoteUrl=null;current=null;shownSignature=null;cancelPending();generation.incrementAndGet();detach();imagePanel=new ImagePanel(image);removeAll();add(imagePanel);revalidate();repaint();}
    private static String signature(Path path){try{var attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);return path+"|"+attributes.size()+"|"+attributes.lastModifiedTime().toMillis();}catch(Exception e){return path+"|missing";}}
    /** Initializes Chromium and opens a blank page in the background so the first document appears without the startup cost. */
    public void warmUp(){
        if(closed||app!=null)return;
        loader.submit(()->{try{
            ensureApp();
            // A real local page, not about:blank: the renderer, compositor and site process start now instead of on the first document.
            Path blank=Files.createDirectories(AppPaths.data().resolve("preview-warmup"));Files.writeString(blank.resolve("blank.html"),"<!doctype html><meta charset=utf-8><title>Tethium</title><body style='background:#111318'>");
            var resources=new ArtifactServer(blank);
            SwingUtilities.invokeLater(()->{if(closed||browser!=null||!closingBrowsers.isEmpty()||pendingBrowserOpen!=null){resources.close();return;}server=resources;allowedBase=resources.baseUri().toString();createBrowser(resources.uri("blank.html"));});
        }catch(Exception e){System.err.println("Preview warm-up: "+e.getMessage());}});
    }
    public void open(Path path) {
        path = path.toAbsolutePath().normalize();
        if (closed) return;
        if (path.equals(current) && remoteUrl == null) {
            // Same file: reuse the rendered page unless the file changed on disk.
            if (shownSignature != null && shownSignature.equals(signature(path))) { if (imagePanel != null) { removeAll(); add(imagePanel); revalidate(); repaint(); } else attach(); return; }
        }
        load(path);
    }
    private void load(Path path) {
        if (closed) return;
        remoteUrl=null;cancelPending(); current = path; shownSignature = signature(path); imagePanel = null; rendererRecoveries=0; long expected = generation.incrementAndGet();
        if (browser == null || !attached()) message("Открываем " + path.getFileName() + "…");
        loader.submit(() -> {
            try {
                String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (dev.tethium.artifact.MarkdownPreview.supports(path)||name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".svg")) {
                    var resources=new ArtifactServer(path.getParent());
                    try{showBrowser(resources.uri(path.getFileName().toString()),resources.baseUri().toString(),expected,resources);}catch(Exception error){resources.close();throw error;}
                } else {
                    var image = ImageIO.read(path.toFile());
                    if (image == null) throw new IllegalArgumentException("Этот формат пока не поддерживается");
                    SwingUtilities.invokeLater(() -> {
                        if (closed || generation.get() != expected) return;
                        detach(); imagePanel = new ImagePanel(image); removeAll(); add(imagePanel); revalidate(); repaint();
                    });
                }
            } catch (Exception e) { SwingUtilities.invokeLater(() -> { if (!closed && generation.get() == expected) { detach(); message("Не удалось открыть: " + e.getMessage()); } }); }
        });
    }
    private java.net.URI remoteUrl;
    public void openRemote(java.net.URI url){if(closed)return;if(url.equals(remoteUrl)){attach();return;}current=null;shownSignature=null;remoteUrl=url;imagePanel=null;rendererRecoveries=0;cancelPending();long expected=generation.incrementAndGet();if(browser==null||!attached())message("Открываем результат с ноутбука…");loader.submit(()->{if(isDocument(url)&&showDocument(url,expected,null))return;try{showBrowser(url,url.resolve(".").toString(),expected,null);}catch(Exception e){
            // Without the embedded browser the page still opens: the laptop's file is served locally, any browser can show it.
            if(browserFailure!=null&&url.getPath().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(md|markdown|html?)$")&&(showDocument(url,expected,browserFailure)||showSimple(url,expected)))return;
            boolean outside=browserFailure!=null&&openOutside(url);
            SwingUtilities.invokeLater(()->{if(generation.get()==expected){detach();message(outside?"Открыто в браузере системы: "+browserFailure:"Не удалось открыть: "+e.getMessage());}});}});}
    /** Markdown never needs a browser; an HTML page may need its scripts, so it goes to Chromium first. */
    private static boolean isDocument(java.net.URI url){return url.getPath().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(md|markdown)$");}
    /** A document drawn in pure Java: the same page the laptop renders, styles and images included. */
    private boolean showDocument(java.net.URI url,long expected,String note){
        try{
            String html;try(var in=url.toURL().openStream()){html=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
            var failure=new boolean[1];
            SwingUtilities.invokeAndWait(()->{
                if(closed||generation.get()!=expected)return;
                try{
                    var view=DocumentView.create(html,url,link->{if(link.getScheme()!=null&&link.getScheme().startsWith("http")&&!"127.0.0.1".equals(link.getHost()))openOutside(link);});
                    var panel=new JPanel(new java.awt.BorderLayout());
                    if(note!=null){var label=new JLabel("<html><body style='width:520px'>"+Theme.escape(note).replace("\n","<br>")+"</body></html>");label.setForeground(Theme.MUTED);label.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));panel.add(label,java.awt.BorderLayout.NORTH);}
                    panel.add(view);detach();removeAll();add(panel);revalidate();repaint();
                }catch(RuntimeException error){failure[0]=true;System.err.println("Document view: "+error);}
            });
            return !failure[0];
        }catch(Exception failure){return false;}
    }
    /** Without the embedded browser a document is still readable: its HTML is shown the way the chat is, scripts and inline images aside. */
    private boolean showSimple(java.net.URI url,long expected){
        try{
            String html;try(var in=url.toURL().openStream()){html=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
            var body=java.util.regex.Pattern.compile("(?is)<body[^>]*>(.*)</body>").matcher(html);
            String content=(body.find()?body.group(1):html).replaceAll("(?is)<script.*?</script>","").replaceAll("(?is)<style.*?</style>","").replaceAll("(?is)<img[^>]*src=\"data:[^\"]*\"[^>]*>","<em>[изображение]</em>");
            SwingUtilities.invokeLater(()->{
                if(closed||generation.get()!=expected)return;
                var pane=new ChatTranscript();pane.setText(ChatTheme.page(content));pane.setCaretPosition(0);
                var note=new JLabel("<html><body style='width:520px'>Упрощённый вид. "+Theme.escape(browserFailure).replace("\n","<br>")+"</body></html>");note.setForeground(Theme.MUTED);note.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
                var panel=new JPanel(new java.awt.BorderLayout());panel.add(note,java.awt.BorderLayout.NORTH);var scroll=new JScrollPane(pane);scroll.setBorder(null);panel.add(scroll);
                detach();removeAll();add(panel);revalidate();repaint();
            });
            return true;
        }catch(Exception failure){return false;}
    }
    private static boolean openOutside(java.net.URI url){
        try{if(java.awt.Desktop.isDesktopSupported()&&java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)){java.awt.Desktop.getDesktop().browse(url);return true;}}catch(Exception ignored){}
        try{new ProcessBuilder("xdg-open",url.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();return true;}catch(Exception ignored){return false;}
    }
    /** Why the embedded browser cannot start on this machine, once known; it is not retried. */
    private static volatile String browserFailure;
    /** A missing system library is named with the package that provides it, instead of failing silently. */
    /** Ubuntu/Debian packages of the libraries Chromium needs that a desktop may lack; names on 24.04 first. */
    private static final java.util.Map<String,String> PACKAGES=java.util.Map.ofEntries(
        java.util.Map.entry("libgbm.so.1","libgbm1"),java.util.Map.entry("libxkbcommon.so.0","libxkbcommon0"),java.util.Map.entry("libnss3.so","libnss3"),java.util.Map.entry("libnssutil3.so","libnss3"),
        java.util.Map.entry("libsmime3.so","libnss3"),java.util.Map.entry("libnspr4.so","libnspr4"),java.util.Map.entry("libasound.so.2","libasound2t64"),java.util.Map.entry("libatk-1.0.so.0","libatk1.0-0t64"),
        java.util.Map.entry("libatk-bridge-2.0.so.0","libatk-bridge2.0-0t64"),java.util.Map.entry("libatspi.so.0","libatspi2.0-0t64"),java.util.Map.entry("libcups.so.2","libcups2t64"),java.util.Map.entry("libdrm.so.2","libdrm2"),
        java.util.Map.entry("libXcomposite.so.1","libxcomposite1"),java.util.Map.entry("libXdamage.so.1","libxdamage1"),java.util.Map.entry("libXrandr.so.2","libxrandr2"),java.util.Map.entry("libXfixes.so.3","libxfixes3"),
        java.util.Map.entry("libpango-1.0.so.0","libpango-1.0-0"),java.util.Map.entry("libcairo.so.2","libcairo2"),java.util.Map.entry("libxshmfence.so.1","libxshmfence1"),java.util.Map.entry("libdbus-1.so.3","libdbus-1-3"),
        java.util.Map.entry("libexpat.so.1","libexpat1"),java.util.Map.entry("libgtk-3.so.0","libgtk-3-0t64"),java.util.Map.entry("libglib-2.0.so.0","libglib2.0-0t64"),java.util.Map.entry("libxcb.so.1","libxcb1"));
    /** Every library the downloaded Chromium cannot find on this system, as ldd reports them; libjawt comes with Java itself. */
    static java.util.List<String> missingLibraries(java.nio.file.Path installDir){
        var missing=new java.util.TreeSet<String>();
        try{
            var files=new java.util.ArrayList<String>(java.util.List.of("ldd"));
            for(String name:java.util.List.of("libcef.so","libjcef.so","jcef_helper"))if(java.nio.file.Files.isRegularFile(installDir.resolve(name)))files.add(installDir.resolve(name).toString());
            if(files.size()==1)return java.util.List.of();
            var builder=new ProcessBuilder(files).redirectErrorStream(true);builder.environment().put("LD_LIBRARY_PATH",installDir.toString());
            var process=builder.start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);
            var line=java.util.regex.Pattern.compile("(?m)^\\s*(\\S+) => not found").matcher(output);
            while(line.find())if(!line.group(1).startsWith("libjawt"))missing.add(line.group(1));
        }catch(Exception ignored){}
        return java.util.List.copyOf(missing);
    }
    static String describe(Throwable failure){return describe(failure,null);}
    /** Names every missing system library with the command that installs them, or says that the browser itself could not be downloaded. */
    static String describe(Throwable failure,java.nio.file.Path installDir){
        String message=String.valueOf(failure.getMessage());
        var library=java.util.regex.Pattern.compile("(lib[\\w.+-]+\\.so[\\w.]*): cannot open").matcher(message);
        if(failure instanceof UnsatisfiedLinkError&&library.find()){
            var missing=new java.util.TreeSet<String>(installDir==null?java.util.List.of():missingLibraries(installDir));missing.add(library.group(1));
            var packages=new java.util.TreeSet<String>();var unknown=new java.util.ArrayList<String>();
            for(String name:missing){String pkg=PACKAGES.get(name);if(pkg!=null)packages.add(pkg);else unknown.add(name);}
            return "Встроенному браузеру не хватает системных библиотек: "+String.join(", ",missing)+"."
                +(packages.isEmpty()?"":"\nУстановите: sudo apt install "+String.join(" ",packages))
                +(unknown.isEmpty()?"":"\nПакеты для "+String.join(", ",unknown)+" подскажет apt-file search.");
        }
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof java.net.UnknownHostException||cause instanceof java.net.ConnectException)
                return "Встроенный браузер скачивается из интернета при первом запуске, а интернета на этом компьютере нет: "+cause.getMessage();
        return message;
    }
    private void ensureApp() throws Exception {
        if (app != null) return;
        if (browserFailure != null) throw new IllegalStateException(browserFailure);
        try { buildApp(); } catch (Throwable failure) { browserFailure = describe(failure, installDir()); throw new IllegalStateException(browserFailure, failure); }
    }
    private static Path installDir() {
        Path cache = Path.of(".local", "preview", "jcef").toAbsolutePath();
        return Files.isDirectory(cache) ? cache : AppPaths.data().resolve("jcef");
    }
    private void buildApp() throws Exception {
        var builder = new CefAppBuilder();
        Path cache = installDir();
        builder.setInstallDir(cache.toFile()); builder.getCefSettings().windowless_rendering_enabled = true;
        // Chromium's process singleton must not redirect a new GUI/probe into the old GUI during reload.
        builder.getCefSettings().root_cache_path = Files.createDirectories(AppPaths.data().resolve("browser-profiles").resolve(Long.toString(ProcessHandle.current().pid()))).toString();
        builder.getCefSettings().log_file = AppPaths.data().resolve("browser.log").toString();
        builder.setProgressHandler((state, percent) -> {});
        var built = builder.build();
        if (closed) { built.dispose(); return; }
        app = built;
    }
    private void showBrowser(java.net.URI url,String allowed,long expected,ArtifactServer resources)throws Exception{
        ensureApp();
        if (closed || generation.get() != expected) {if(resources!=null)resources.close();return;}
        SwingUtilities.invokeLater(() -> {
            if (closed || generation.get() != expected) { if(resources!=null)resources.close(); return; }
            Runnable install=()->{
                pendingResources=null;
                if(closed||generation.get()!=expected){if(resources!=null)resources.close();return;}
                if(server!=null&&server!=resources){server.close();}
                server = resources; allowedBase = allowed;
                // A navigation issued before the native browser exists is dropped by JCEF; keep it until onAfterCreated.
                if(browser==null)createBrowser(url);else if(!createdBrowsers.contains(browser))pendingUrl=url;else{pendingUrl=null;browser.loadURL(url.toString());}
                attach();
            };
            if(closingBrowsers.isEmpty())install.run();else{cancelPending();pendingResources=resources;pendingBrowserOpen=install;}
        });
    }
    /** Creates the single long-lived browser; the component is attached by callers that want it visible. */
    private void createBrowser(java.net.URI url){
        if(app==null||browser!=null)return;
        client = app.createClient();final CefClient ownedClient=client;
        client.addContextMenuHandler(new org.cef.handler.CefContextMenuHandlerAdapter(){
            private static final int COPY_IMAGE=org.cef.callback.CefMenuModel.MenuId.MENU_ID_USER_FIRST+1;
            @Override public void onBeforeContextMenu(CefBrowser b,CefFrame f,org.cef.callback.CefContextMenuParams params,org.cef.callback.CefMenuModel menu){
                if(params.hasImageContents()){menu.insertItemAt(0,COPY_IMAGE,"Копировать изображение");menu.insertSeparatorAt(1);}
            }
            @Override public boolean onContextMenuCommand(CefBrowser b,CefFrame f,org.cef.callback.CefContextMenuParams params,int command,int flags){
                if(command!=COPY_IMAGE)return false;String source=params.getSourceUrl();
                SwingUtilities.invokeLater(()->ClipboardImages.copy(ArtifactView.this,()->copyBrowserImage(b,source)));return true;
            }
        });
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter(){
            @Override public void onAfterCreated(CefBrowser b){SwingUtilities.invokeLater(()->{
                createdBrowsers.add(b);
                if(closed||browser!=b){b.close(true);return;}
                if(pendingUrl!=null){var next=pendingUrl;pendingUrl=null;b.loadURL(next.toString());}
            });}
            @Override public void onBeforeClose(CefBrowser b){SwingUtilities.invokeLater(()->{
                if(b.getUIComponent() instanceof com.jogamp.opengl.awt.GLCanvas canvas)try{canvas.destroy();}catch(RuntimeException error){System.err.println("Browser surface cleanup: "+error.getMessage());}
                createdBrowsers.remove(b);closingBrowsers.remove(b);ownedClient.dispose();
                if(closed){if(closingBrowsers.isEmpty()&&app!=null)app.dispose();}
                else if(closingBrowsers.isEmpty()&&pendingBrowserOpen!=null){var next=pendingBrowserOpen;pendingBrowserOpen=null;next.run();}
            });}
        });
        client.addFocusHandler(new CefFocusHandlerAdapter() {
            @Override public boolean onSetFocus(CefBrowser b, FocusSource source) {
                var owner=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                return source==FocusSource.FOCUS_SOURCE_NAVIGATION||owner==null||!SwingUtilities.isDescendingFrom(owner,ArtifactView.this);
            }
            @Override public void onTakeFocus(CefBrowser b, boolean next) {
                SwingUtilities.invokeLater(() -> { b.setFocus(false); if (next) b.getUIComponent().transferFocus(); else b.getUIComponent().transferFocusBackward(); });
            }
        });
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override public boolean onBeforeBrowse(CefBrowser b, CefFrame f, CefRequest r, boolean gesture, boolean redirect) {
                return !r.getURL().startsWith(allowedBase) && !r.getURL().equals("about:blank");
            }
            @Override public void onRenderProcessTerminated(CefBrowser b,TerminationStatus status,int code,String detail){
                SwingUtilities.invokeLater(()->{
                    if(closed||browser!=b)return;
                    System.err.println("Preview renderer terminated: "+status+" ("+code+") "+detail);
                    if(rendererRecoveries++==0){
                        // A killed renderer leaves a live browser with a blank surface. One retry;
                        // repeated failure must not turn memory pressure into a restart loop.
                        var retry=new javax.swing.Timer(250,event->{if(!closed&&browser==b)b.reload();});
                        retry.setRepeats(false);retry.start();
                    }else{
                        disposeBrowser();shownSignature=null;message("Процесс просмотра остановился. Нажмите «Обновить», чтобы открыть файл снова.");
                    }
                });
            }
        });
        client.addKeyboardHandler(new CefKeyboardHandlerAdapter() {
            @Override public boolean onPreKeyEvent(CefBrowser b, CefKeyEvent e, org.cef.misc.BoolRef shortcut) {
                if (e.windows_key_code == KeyEvent.VK_ESCAPE && fullScreen) {
                    if (e.type == CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN || e.type == CefKeyEvent.EventType.KEYEVENT_KEYDOWN)
                        SwingUtilities.invokeLater(escape);
                    return true;
                }
                return false;
            }
        });
        browser = client.createBrowser(url.toString(), true, false);
        browser.getUIComponent().addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent e){e.getComponent().requestFocusInWindow();}});
        browser.createImmediately();
    }
    public void reload() { shownSignature=null; if(remoteUrl!=null){var uri=remoteUrl;remoteUrl=null;openRemote(uri);}else if (current != null) load(current); }
    static Image copyBrowserImage(CefBrowser browser,String source)throws Exception{
        // Decode the original first (not a screenshot or a scaled preview).
        try{var image=ClipboardImages.read(source,Path.of("."));if(image!=null)return image;}catch(Exception ignored){}
        // Chromium can rasterize SVG/WebP/blob images that ImageIO cannot decode.
        String expression="(()=>{const src="+dev.tethium.json.Json.MAPPER.writeValueAsString(source)+";const img=[...document.images].find(i=>i.currentSrc===src||i.src===src);if(!img||!img.complete||!img.naturalWidth)throw Error('Изображение ещё не загружено');const canvas=document.createElement('canvas');canvas.width=img.naturalWidth;canvas.height=img.naturalHeight;canvas.getContext('2d').drawImage(img,0,0);return canvas.toDataURL('image/png');})()";
        var result=dev.tethium.json.Json.parse(browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",dev.tethium.json.Json.object("expression",expression,"returnByValue",true).toString()).get(10,TimeUnit.SECONDS));
        String data=result.path("result").path("value").asText();if(data.isEmpty())throw new java.io.IOException("Браузер не смог прочитать пиксели изображения");return ClipboardImages.read(data,Path.of("."));
    }
    public void fitImage() { if (imagePanel != null) imagePanel.fit(); }
    public void actualImageSize() { if (imagePanel != null) imagePanel.actualSize(); }
    /** Forgets the current document but keeps the warm browser for the next one. */
    public void clear() {
        current = null; shownSignature = null; pendingUrl = null; remoteUrl=null; imagePanel = null;cancelPending(); generation.incrementAndGet(); detach(); message("Изображения и HTML от агента");
    }
    private void disposeBrowser() {
        if (browser != null) {
            var old=browser;browser=null;closingBrowsers.add(old);detachComponent(old);
            // JCEF marks close(true) as final even when its native browser does not exist yet.
            // Closing before onAfterCreated then loses onBeforeClose and blocks every next preview.
            // Keep the close pending until the native creation callback can actually close it.
            if(createdBrowsers.contains(old))old.close(true);
        }
        client=null;
        if (server != null) { server.close(); server = null; }
    }
    private void detachComponent(CefBrowser old){if(getComponentCount()==1&&getComponent(0)==old.getUIComponent()){removeAll();revalidate();repaint();}}
    @Override public void close() { closed = true; KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", focusListener); generation.incrementAndGet();cancelPending(); disposeBrowser(); loader.shutdownNow(); if (app != null&&closingBrowsers.isEmpty()) app.dispose(); }
    private final class ImagePanel extends JPanel {
        private final BufferedImage image;
        private double zoom = 1, panX, panY;
        private Point dragStart;
        private double dragX, dragY;
        private boolean dragged;
        ImagePanel(BufferedImage image) {
            this.image = image; setName("artifact.image"); setFocusable(true); updateCursor();
            var mouse = new MouseAdapter() {
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    double next = Math.clamp(zoom * Math.pow(1.15, -e.getPreciseWheelRotation()), .1, 32);
                    double ratio = next / zoom;
                    panX = e.getX() - getWidth() / 2.0 - (e.getX() - getWidth() / 2.0 - panX) * ratio;
                    panY = e.getY() - getHeight() / 2.0 - (e.getY() - getHeight() / 2.0 - panY) * ratio;
                    zoom = next; constrainPan(); updateCursor(); repaint();
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    requestFocusInWindow(); dragStart = e.getPoint(); dragX = panX; dragY = panY; dragged = false;
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStart == null) return;
                    if (e.getPoint().distance(dragStart) > 4) dragged = true;
                    if (!dragged) return;
                    panX = dragX + e.getX() - dragStart.x; panY = dragY + e.getY() - dragStart.y;
                    constrainPan(); repaint();
                }
                @Override public void mouseReleased(MouseEvent e) { dragStart = null; }
                @Override public void mouseClicked(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e) || dragged) return;
                    if (!fullScreen) expand.run(); else if (e.getClickCount() == 2) fit();
                }
            };
            addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse);
            var menu = new JPopupMenu();
            var copy=new AbstractAction("Копировать изображение"){public void actionPerformed(ActionEvent e){ClipboardImages.copy(ImagePanel.this,()->image);}};
            menu.add(copy);menu.addSeparator();
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C,InputEvent.CTRL_DOWN_MASK),"copyImage");getActionMap().put("copyImage",copy);
            menu.add(new AbstractAction("По размеру окна") { public void actionPerformed(ActionEvent e) { fit(); } });
            menu.add(new AbstractAction("Исходный размер · 100%") { public void actionPerformed(ActionEvent e) { actualSize(); } });
            setComponentPopupMenu(menu);
        }
        private double fitScale() { return Math.max(.001, Math.min(1, Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight()))); }
        private void fit() { zoom = 1; panX = panY = 0; updateCursor(); repaint(); }
        private void actualSize() { zoom = 1 / fitScale(); panX = panY = 0; updateCursor(); repaint(); }
        private void constrainPan() {
            double xLimit = Math.max(0, (image.getWidth() * fitScale() * zoom - getWidth()) / 2);
            double yLimit = Math.max(0, (image.getHeight() * fitScale() * zoom - getHeight()) / 2);
            panX = Math.clamp(panX, -xLimit, xLimit); panY = Math.clamp(panY, -yLimit, yLimit);
        }
        private void updateCursor() {
            setCursor(Cursor.getPredefinedCursor(fullScreen ? Cursor.MOVE_CURSOR : Cursor.HAND_CURSOR));
            setToolTipText(image.getWidth() + " × " + image.getHeight() + " · " + (fullScreen ? "двойной клик: вписать · Esc: вернуться" : "клик: на весь экран") + " · колесо: масштаб · перетаскивание: перемещение");
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g); var graphics = (Graphics2D) g.create();
            constrainPan(); double scale = fitScale() * zoom;
            int width = (int) (image.getWidth() * scale), height = (int) (image.getHeight() * scale);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, (int) ((getWidth() - width) / 2.0 + panX), (int) ((getHeight() - height) / 2.0 + panY), width, height, null); graphics.dispose();
        }
    }
}

