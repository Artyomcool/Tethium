package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import dev.tethium.json.Json;
import dev.tethium.link.Frames;
import dev.tethium.link.Tbin;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Paired home-LAN gateway. Each phone keeps one framed TCP link ({@link Frames}): the laptop pushes catalog deltas, new messages and
 * live text, the phone pulls pages and sends commands by request id. Plain HTTP on the second port serves files, APKs, previews and the
 * Windows remote. No TLS and no JSON on the link by decision; the desktop's administrative bearer never leaves loopback.
 */
public final class MobileGateway implements AutoCloseable {
    /** In-process access to the host: change signal plus the pieces the link streams. */
    public interface Feed {
        default dev.tethium.files.FileManager files(){return null;}
        /** Whether the session's conversation names this file: the agent linked it, so Remote may open it outside the project. */
        default boolean mentions(String session,Path file){return false;}
        long revision();
        void addListener(Runnable listener);
        void removeListener(Runnable listener);
        JsonNode get(String target) throws Exception;
        JsonNode post(String path, JsonNode body) throws Exception;
        JsonNode catalog() throws Exception;
        JsonNode conversationMeta(String session) throws Exception;
        JsonNode messagesAfter(String session, long after, int limit) throws Exception;
        JsonNode messagesBefore(String session, long before, int limit) throws Exception;
        JsonNode live(String session, long run, int offset) throws Exception;
    }
    private static final int TAIL = 100;
    private final AppCatalog appCatalog = new AppCatalog();
    private final AdbInstaller adb;
    private final PhoneServices services;
    private final UacBridge uac;
    private final Path data;
    private final Feed feed;
    private final ServerSocket link;
    private final HttpServer http;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Link> links = ConcurrentHashMap.newKeySet();
    private final Map<String,String> devices = new ConcurrentHashMap<>();
    private final Map<String,Preview> previews = new ConcurrentHashMap<>();
    private final Map<String,Preview> downloads = new ConcurrentHashMap<>();
    private record Preview(Path root,long expires) {}
    private String code;
    private long expires;
    private volatile boolean closed;
    public MobileGateway(Path data, Feed feed, int linkPort, int httpPort) throws Exception {
        this.data = Files.createDirectories(data.resolve("mobile")); this.feed = feed; adb = new AdbInstaller(this.data); services = new PhoneServices(this.data,()->feed.catalog().path("projects"));
        uac=new UacBridge(()->{for(var l:List.copyOf(links))l.kick();});
        Path deviceFile=this.data.resolve("devices.json");
        if(Files.exists(deviceFile))Json.MAPPER.readTree(deviceFile.toFile()).properties().forEach(e->devices.put(e.getKey(),e.getValue().asText()));
        link=new ServerSocket();link.setReuseAddress(true);link.bind(new InetSocketAddress("0.0.0.0",linkPort),32);
        try {http=HttpServer.create(new InetSocketAddress("0.0.0.0",httpPort),16);}
        catch(Exception e){link.close();workers.close();throw e;}
        http.setExecutor(workers);http.createContext("/",this::serve);http.start();
        workers.execute(this::accept);
        // A newer embedded phone app is pushed over adb shortly after the core starts; the store stays the fallback.
        if(adb.configured())workers.execute(()->{try{Thread.sleep(20_000);pushTethiumIfNewer();}catch(Exception ignored){}});
    }
    public int port(){return link.getLocalPort();}
    public int httpPort(){return http.getAddress().getPort();}
    /** Kept for callers of the former download port; it is the same HTTP port now. */
    public int downloadPort(){return httpPort();}
    public synchronized JsonNode pairInfo() throws Exception {
        code=random();expires=System.currentTimeMillis()+300_000;
        var addresses=new ArrayList<String>();
        for(var n:Collections.list(NetworkInterface.getNetworkInterfaces()))if(n.isUp()&&!n.isLoopback())for(var a:Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address&&a.isSiteLocalAddress())addresses.add(a.getHostAddress());
        // Prefer the interface used by the default route over virtual adapters.
        try(var socket=new DatagramSocket()){socket.connect(InetAddress.getByName("1.1.1.1"),53);String preferred=socket.getLocalAddress().getHostAddress();if(addresses.remove(preferred))addresses.addFirst(preferred);}catch(Exception ignored){}
        if(addresses.isEmpty())addresses.add("127.0.0.1");
        String address=addresses.getFirst();
        return Json.object("address",address,"addresses",addresses,"port",port(),"httpPort",httpPort(),"downloadPort",httpPort(),"download","http://"+address+":"+httpPort()+"/","pairing","tethium://pair?address="+address+":"+port()+"&http="+httpPort()+"&code="+code,"expires",expires,"devices",devices.size());
    }
    // ---- adb over Wi-Fi: silent installs when configured ----
    public JsonNode adbStatus(){return adb.status();}
    public JsonNode adbPair(JsonNode body)throws Exception{return adb.pair(body.path("address").asText(),body.path("code").asText());}
    public JsonNode adbConnect(JsonNode body)throws Exception{return adb.connect(body.path("address").asText());}
    public JsonNode adbForget()throws IOException{return adb.forget();}
    /** Installs the embedded Tethium app or a catalog app by package name right now, over adb. */
    public JsonNode adbInstall(JsonNode body)throws Exception{
        String pkg=body.path("packageName").asText("dev.tethium.mobile");
        if(pkg.equals("dev.tethium.mobile"))return adb.install(embeddedApk(),pkg);
        var catalog=appCatalog.read(feed.catalog().path("projects"));
        for(var app:catalog.path("apps"))if(app.path("packageName").asText().equals(pkg))return adb.install(appCatalog.file(app.path("sha256").asText()),pkg);
        throw new IllegalArgumentException("Приложение не опубликовано: "+pkg);
    }
    /** After app_publish: push the new build to the phone in the background. */
    public void pushAppLater(String packageName,long versionCode){
        if(!adb.configured())return;
        workers.execute(()->{try{var catalog=appCatalog.read(feed.catalog().path("projects"));for(var app:catalog.path("apps"))if(app.path("packageName").asText().equals(packageName)){adb.installLater(appCatalog.file(app.path("sha256").asText()),packageName,-1);return;}}catch(Exception ignored){}});
    }
    private void pushTethiumIfNewer()throws Exception{
        long embedded;try(var in=MobileGateway.class.getResourceAsStream("/mobile/release.json")){if(in==null)return;embedded=Json.MAPPER.readTree(in).path("versionCode").asLong();}
        long installed=adb.installedVersion("dev.tethium.mobile");
        if(installed>=0&&embedded>installed)adb.installLater(embeddedApk(),"dev.tethium.mobile",embedded);
    }
    private Path embeddedApk()throws IOException{
        Path folder=Files.createDirectories(data.resolve("releases"));String sha;
        try(var in=MobileGateway.class.getResourceAsStream("/mobile/release.json")){if(in==null)throw new IOException("Встроенный APK отсутствует");sha=Json.MAPPER.readTree(in).path("sha256").asText();}
        Path apk=folder.resolve("tethium-"+sha.substring(0,16)+".apk");
        if(!Files.isRegularFile(apk))try(var in=MobileGateway.class.getResourceAsStream("/mobile/tethium.apk")){if(in==null)throw new IOException("Встроенный APK отсутствует");Path tmp=Files.createTempFile(folder,"tethium-",".tmp");Files.copy(in,tmp,StandardCopyOption.REPLACE_EXISTING);Files.move(tmp,apk,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        return apk;
    }
    public synchronized JsonNode revoke()throws IOException{devices.clear();code=null;previews.clear();saveDevices();services.revoke();for(var l:List.copyOf(links))l.close();return Json.object("ok",true);}
    private synchronized JsonNode pair(JsonNode body) throws Exception {
        if(code==null||System.currentTimeMillis()>expires||!equal(code,body.path("code").asText()))throw new SecurityException("QR истёк. Откройте новый на ноутбуке.");
        String token=random();devices.put(sha(token.getBytes(StandardCharsets.UTF_8)),body.path("name").asText("Android"));saveDevices();code=null;
        return Json.object("token",token,"httpPort",httpPort());
    }
    private boolean authorized(String token){return token!=null&&!token.isEmpty()&&devices.containsKey(sha(token.getBytes(StandardCharsets.UTF_8)));}
    private void saveDevices()throws IOException{Path tmp=data.resolve("devices.tmp");Json.MAPPER.writeValue(tmp.toFile(),devices);Files.move(tmp,data.resolve("devices.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}

    // ---- framed link ----
    private void accept(){
        while(!closed){
            try{var socket=link.accept();socket.setTcpNoDelay(true);socket.setKeepAlive(true);workers.execute(new Link(socket));}
            catch(IOException e){if(closed)return;}
        }
    }
    private final class Link implements Runnable {
        private final Socket socket;
        private DataInputStream in; private DataOutputStream out;
        private final Tbin.Writer writer=new Tbin.Writer(); private final Tbin.Reader reader=new Tbin.Reader();
        private final Object wake=new Object(); private boolean dirty,reset;
        private volatile String watched="";
        private final Runnable listener=this::kick;
        private final Map<String,String> sentCatalog=new HashMap<>(),sentSessions=new HashMap<>(),sentMeta=new HashMap<>();
        private boolean catalogSent; private String convSession=""; private long sentSequence=-1,sentMessageRevision=-1,liveRun=-1; private int liveOffset;
        private String resumeSession; private long resumeAfter=-1,resumeRevision=-1,resumeLiveRun=-1; private int resumeLiveOffset,tail=TAIL;
        Link(Socket socket){this.socket=socket;}
        private record Frame(int kind,JsonNode body){}
        public void run(){
            try{
                socket.setSoTimeout(30_000);
                in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                var first=read();
                if(first.body.path("protocol").asInt()!=Frames.PROTOCOL){send(Frames.BYE,Json.object("error","Обновите приложение Tethium на телефоне"));return;}
                if(first.kind==Frames.PAIR){try{send(Frames.PAIRED,pair(first.body));}catch(SecurityException denied){send(Frames.BYE,Json.object("error",denied.getMessage()));}return;}
                if(first.kind!=Frames.HELLO||!authorized(first.body.path("token").asText())){send(Frames.BYE,Json.object("error","Подключите телефон по QR на ноутбуке"));return;}
                send(Frames.WELCOME,Json.object("protocol",Frames.PROTOCOL,"httpPort",httpPort()));
                if(socket.getInetAddress()!=null)adb.phone(socket.getInetAddress().getHostAddress());
                socket.setSoTimeout(45_000);links.add(this);feed.addListener(listener);kick();
                workers.execute(this::push);
                while(!closed){
                    var frame=read();
                    switch(frame.kind){
                        case Frames.REQUEST->workers.execute(()->respond(frame.body));
                        case Frames.WATCH->{var b=frame.body;watched=b.path("session").asText("");synchronized(wake){if(b.path("reset").asBoolean())reset=true;resumeSession=b.has("after")?watched:null;resumeAfter=b.path("after").asLong(-1);resumeRevision=b.path("messageRevision").asLong(-1);resumeLiveRun=b.path("liveRun").asLong(-1);resumeLiveOffset=b.path("liveOffset").asInt(0);tail=Math.max(10,Math.min(300,b.path("tail").asInt(TAIL)));dirty=true;wake.notifyAll();}}
                        case Frames.PING->send(Frames.PONG,Json.object());
                        default->{}
                    }
                }
            }catch(Exception e){ /* the phone left or the frame was invalid; the link ends */ }
            finally{feed.removeListener(listener);links.remove(this);close();}
        }
        private Frame read()throws IOException{byte[] raw=Frames.read(in);if(raw.length<2)throw new IOException("Empty frame");return new Frame(raw[0]&255,reader.decode(raw,1));}
        private void send(int kind,JsonNode body)throws IOException{synchronized(writer){Frames.write(out,writer.encode(kind,body));}}
        private void kick(){synchronized(wake){dirty=true;wake.notifyAll();}}
        void close(){try{socket.close();}catch(IOException ignored){}synchronized(wake){dirty=true;wake.notifyAll();}}
        private void respond(JsonNode request){
            int id=request.path("id").asInt();
            try{var result=api(request.path("post").asBoolean()?"POST":"GET",request.path("path").asText(),request.hasNonNull("body")?request.path("body"):null);send(Frames.RESPONSE,Json.object("id",id,"status",200,"result",result));}
            catch(Exception error){try{send(Frames.RESPONSE,Json.object("id",id,"status",status(error),"error",message(error)));}catch(IOException ignored){}}
        }
        private void push(){
            try{
                long lastPing=System.currentTimeMillis();
                while(!socket.isClosed()&&!closed){
                    boolean changed,resync;
                    synchronized(wake){
                        long until=lastPing+10_000;
                        while(!dirty&&!socket.isClosed()){long left=until-System.currentTimeMillis();if(left<=0)break;wake.wait(left);}
                        changed=dirty;resync=reset;dirty=false;reset=false;
                    }
                    if(socket.isClosed())return;
                    // Ping on a fixed cadence even while deltas flow: the pong is the only traffic a quiet phone sends,
                    // and the reader's timeout would otherwise drop a busy link every 45 seconds.
                    if(System.currentTimeMillis()-lastPing>=10_000){send(Frames.PING,Json.object("now",System.currentTimeMillis()));lastPing=System.currentTimeMillis();}
                    if(!changed)continue;
                    Thread.sleep(60); // coalesce bursts of events into one round of deltas
                    if(resync)convSession="";
                    pushCatalog();pushConversation();pushLive();
                }
            }catch(Exception e){close();}
        }
        private void pushCatalog()throws Exception{
            var catalog=(ObjectNode)feed.catalog().deepCopy();var sessions=catalog.remove("sessions");
            catalog.set("uac",uac.snapshot());
            var sessionOrder=Json.MAPPER.createArrayNode();if(sessions!=null)for(var session:sessions)sessionOrder.add(session.path("id").asText());catalog.set("sessionOrder",sessionOrder);
            var delta=Json.object();boolean any=!catalogSent;
            for(var e:catalog.properties()){String text=e.getValue().toString();if(!text.equals(sentCatalog.get(e.getKey()))){sentCatalog.put(e.getKey(),text);delta.set(e.getKey(),e.getValue());if(!e.getKey().equals("revision"))any=true;}}
            var changed=Json.MAPPER.createArrayNode();var seen=new HashSet<String>();
            if(sessions!=null)for(var s:sessions){String id=s.path("id").asText();seen.add(id);String text=s.toString();if(!text.equals(sentSessions.get(id))){sentSessions.put(id,text);changed.add(s);any=true;}}
            var removed=Json.MAPPER.createArrayNode();for(var id:List.copyOf(sentSessions.keySet()))if(!seen.contains(id)){sentSessions.remove(id);removed.add(id);any=true;}
            if(!any)return;
            if(!catalogSent){delta.put("full",true);delta.set("sessions",sessions==null?Json.MAPPER.createArrayNode():sessions);catalogSent=true;}
            else{if(!changed.isEmpty())delta.set("sessions",changed);if(!removed.isEmpty())delta.set("removedSessions",removed);}
            send(Frames.STATE,delta);
        }
        private void pushConversation()throws Exception{
            String s=watched;if(s.isEmpty()){convSession="";return;}
            if(!s.equals(convSession)){convSession=s;sentSequence=-1;sentMessageRevision=-1;sentMeta.clear();liveRun=-1;liveOffset=0;}
            JsonNode meta;try{meta=feed.conversationMeta(s);}catch(Exception unknown){return;}
            var frame=Json.object("session",s);boolean any=false;
            long revision=meta.path("messageRevision").asLong(),last=meta.path("lastSequence").asLong();
            String resume;long after,resumeRev,resumeRun;int resumeOffset,tailSize;synchronized(wake){resume=resumeSession;after=resumeAfter;resumeRev=resumeRevision;resumeRun=resumeLiveRun;resumeOffset=resumeLiveOffset;tailSize=tail;resumeSession=null;}
            // A phone that still holds the tail continues from its last sequence and live offset; only edits in place force a fresh tail.
            if(s.equals(resume)&&sentSequence<0&&after>=0&&resumeRev==revision){sentSequence=after;sentMessageRevision=revision;if(resumeRun>0){liveRun=resumeRun;liveOffset=resumeOffset;}}
            frame.put("messageRevision",revision);
            if(sentSequence<0||revision!=sentMessageRevision){
                var tail=feed.messagesBefore(s,Long.MAX_VALUE,tailSize);var messages=tail.path("messages");
                frame.put("reset",true);frame.set("messages",messages);frame.put("hasMore",tail.path("hasMore").asBoolean());
                sentSequence=Math.max(last,lastSequence(messages));sentMessageRevision=revision;any=true;
            }else if(last>sentSequence){
                var more=feed.messagesAfter(s,sentSequence,1000);frame.set("messages",more);sentSequence=Math.max(last,lastSequence(more));any=true;
            }
            for(String key:List.of("queued","requests","artifacts","media")){String text=meta.path(key).toString();if(!text.equals(sentMeta.get(key))){sentMeta.put(key,text);frame.set(key,meta.path(key));any=true;}}
            if(any)send(Frames.CONVERSATION,frame);
        }
        private void pushLive()throws Exception{
            String s=convSession;if(s.isEmpty())return;
            var live=feed.live(s,liveRun,liveOffset);long run=live.path("run").asLong();int length=live.path("length").asInt();
            if(run==liveRun&&length==liveOffset)return;
            liveRun=run;liveOffset=length;
            send(Frames.LIVE,Json.object("session",s,"run",run,"offset",live.path("offset").asInt(),"text",live.path("text").asText(),"timestamp",live.path("timestamp").asLong()));
        }
        private static long lastSequence(JsonNode messages){long last=-1;for(var m:messages)last=Math.max(last,m.path("sequence").asLong());return last;}
    }

    // ---- shared API surface: link requests and HTTP JSON for the Windows remote ----
    private JsonNode api(String method,String target,JsonNode body)throws Exception{
        var fileUri=URI.create(target);
        if(fileUri.getPath().startsWith(dev.tethium.files.FileManager.PREFIX+"/")){
            if(fileUri.getPath().equals("/file-manager/share"))throw new SecurityException("Общую папку добавляют на рабочем ноутбуке");
            if(feed.files()==null)throw new IOException("Обновите ядро Tethium");
            return feed.files().api(method,fileUri,body);
        }
        if(target==null||!target.startsWith("/")||target.startsWith("//"))throw new IllegalArgumentException("Некорректный путь");
        String path=target.split("\\?",2)[0];boolean post=method.equals("POST");
        if(path.equals("/remote/version")&&!post)return remoteVersion();
        if(path.equals("/uac")&&!post)return uac.snapshot();
        if(path.equals("/uac/answer")&&post)return uac.answer(body==null?Json.object():body);
        if(path.equals("/apps")&&!post){
            var catalog=appCatalog.read(feed.catalog().path("projects"));
            try(var in=MobileGateway.class.getResourceAsStream("/mobile/release.json")){
                var own=Json.MAPPER.readTree(in); ((ArrayNode)catalog.path("apps")).insert(0,Json.object("packageName","dev.tethium.mobile","title","Tethium","project","Tethium","versionCode",own.path("versionCode").asLong(),"versionName",own.path("versionName").asText(),"sha256",own.path("sha256").asText(),"path","/apk"));
            }
            return catalog;
        }
        if(path.equals("/attachment")&&post)return upload(body);
        if(path.equals("/service-token")&&post)return services.mint(body);
        if(path.equals("/artifact/download")&&post){
            var snapshot=feed.get("/state?session="+URLEncoder.encode(body.path("session").asText(),StandardCharsets.UTF_8)+"&history=window");JsonNode artifact=null;for(var a:snapshot.path("conversation").path("artifacts"))if(a.path("id").asText().equals(body.path("artifact").asText()))artifact=a;
            if(artifact==null)throw new IllegalArgumentException("Сборка не опубликована в этой сессии");Path file=Path.of(artifact.path("path").asText()).toRealPath();if(!Files.isRegularFile(file)||Files.size(file)>2_000_000_000L)throw new IllegalArgumentException("Нужен файл до 2 ГБ");
            String key=random();downloads.entrySet().removeIf(x->x.getValue().expires<System.currentTimeMillis());downloads.put(key,new Preview(file,System.currentTimeMillis()+3600_000));
            return Json.object("name",file.getFileName().toString(),"size",Files.size(file),"sha256",dev.tethium.remote.RemoteClient.sha(file),"path","/artifact/file/"+key);
        }
        if(path.equals("/preview")&&post){
            var snapshot=feed.get("/state?session="+URLEncoder.encode(body.path("session").asText(),StandardCharsets.UTF_8)+"&history=window");
            JsonNode artifact=null;for(var a:snapshot.path("conversation").path("artifacts"))if(a.path("id").asText().equals(body.path("artifact").asText()))artifact=a;
            Path file;
            if(body.hasNonNull("fileId")){
                JsonNode document=null;for(var candidate:snapshot.path("files"))if(candidate.path("id").asText().equals(body.path("fileId").asText()))document=candidate;
                if(document==null)throw new IllegalArgumentException("Закреплённый файл не найден");file=Path.of(document.path("path").asText()).toRealPath();
                Path root=null;for(var project:snapshot.path("projects"))if(project.path("id").asText().equals(document.path("projectId").asText()))root=Path.of(project.path("root").asText()).toRealPath();
                if(root==null||!file.startsWith(root)||!Files.isRegularFile(file))throw new IllegalArgumentException("Файл недоступен в проекте");
            }else if(artifact!=null)file=Path.of(artifact.path("path").asText()).toRealPath();
            else {
                JsonNode session=null;for(var value:snapshot.path("sessions"))if(value.path("id").asText().equals(body.path("session").asText()))session=value;
                if(session==null)throw new IllegalArgumentException("Сессия не найдена");
                String link=body.path("file").asText();if(link.isBlank())throw new IllegalArgumentException("Результат не найден");
                link=link.replaceFirst(":\\d+(?::\\d+)?$", "");
                if(link.matches("^/[A-Za-z]:/.*"))link=link.substring(1);
                Path rawPath=link.startsWith("file:")?Path.of(URI.create(link)):Path.of(URLDecoder.decode(link.replace("+","%2B"),StandardCharsets.UTF_8));
                Path directory=Path.of(session.path("directory").asText()).toRealPath();file=directory.resolve(rawPath).normalize().toRealPath();
                Path project=directory;for(var value:snapshot.path("projects"))if(value.path("id").asText().equals(session.path("projectId").asText()))project=Path.of(value.path("root").asText()).toRealPath();
                if(!file.startsWith(project)&&!file.startsWith(directory)&&!ownedMedia(file,session.path("id").asText())&&!feed.mentions(session.path("id").asText(),file))throw new IllegalArgumentException("Файл вне проекта");
                if(!file.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|html?|svg|png|jpe?g|gif|webp)$"))throw new IllegalArgumentException("Этот формат не поддерживается в просмотре");
            }
            String key=random();
            previews.entrySet().removeIf(x->x.getValue().expires<System.currentTimeMillis());previews.put(key,new Preview(file.getParent(),System.currentTimeMillis()+3600_000));
            return Json.object("path","/preview/"+key+"/"+new URI(null,null,file.getFileName().toString(),null).toASCIIString());
        }
        if(!post&&(path.equals("/state")||path.equals("/project-icon")||path.equals("/backlog")||path.equals("/reminders")||path.equals("/models")||path.equals("/resources")||path.equals("/usage")||path.equals("/history/index")||path.equals("/history/page")||path.equals("/messages")||path.equals("/trash")))return feed.get(target);
        if(post&&allowed(path)){
            var b=body instanceof ObjectNode object?object:Json.object();
            // Chat attachments use validated uploads, separate from file-manager/project paths.
            if(b.has("images")&&!b.path("images").isEmpty())throw new IllegalArgumentException("Используйте загруженные вложения");
            var images=Json.MAPPER.createArrayNode();
            if(b.path("attachments").size()>12)throw new IllegalArgumentException("Не больше 12 изображений в сообщении");
            for(var attachment:b.path("attachments")){
                String id=attachment.asText();if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Некорректное вложение");
                Path file=data.resolve("attachments").resolve(id+".png").toRealPath();
                if(!file.startsWith(data.resolve("attachments").toRealPath())||!Files.isRegularFile(file))throw new IllegalArgumentException("Вложение не найдено");
                images.add(file.toString());
            }
            b.set("images",images);b.put("preserveDesktopDraft",true);
            if(b.path("scope").asText().equals("all"))throw new IllegalArgumentException("Выберите конкретную сессию");
            return feed.post(path,b);
        }
        throw new NoSuchElementException("Unknown endpoint");
    }
    private static boolean allowed(String path){return path.matches("/projects/[a-zA-Z0-9-]+/collapsed")||path.matches("/reminders/[a-zA-Z0-9-]+/(snooze|done|remove)")||path.equals("/projects")||path.equals("/projects/order")||path.equals("/backlog")||path.equals("/backlog-settings")||path.matches("/backlog/[a-zA-Z0-9-]+/(update|remove|start)")||path.equals("/resources")||path.equals("/quota/refresh")||path.equals("/quota/reset")||path.equals("/sessions")||path.equals("/answer")||path.matches("/projects/[a-zA-Z0-9-]+/(files|active|sessions-order)")||path.matches("/files/[a-zA-Z0-9-]+/(rename|remove)")||path.matches("/sessions/[a-zA-Z0-9-]+/(run|interrupt|stop|queue-pause|queue-resume|queue-remove|queue-move|queue-take|queue-merge|queue-split|active|rename|settings|seen|attention|quota-auto|autonomy|schedule|pinned-file|trash|restore|purge)");}
    private static int status(Exception e){return e instanceof SecurityException?401:e instanceof NoSuchElementException?404:400;}
    private static String message(Exception e){return Objects.toString(e.getMessage(),"Ошибка подключения");}
    private JsonNode upload(JsonNode body)throws Exception{
        byte[] bytes=Base64.getDecoder().decode(body.path("data").asText());
        if(bytes.length==0||bytes.length>12_000_000)throw new IllegalArgumentException("Изображение должно быть меньше 12 МБ");
        int width,height;
        try(var in=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=javax.imageio.ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IllegalArgumentException("Не удалось прочитать изображение");
            var reader=readers.next();try{reader.setInput(in);width=reader.getWidth(0);height=reader.getHeight(0);
                if(!reader.getFormatName().equalsIgnoreCase("png")||width<1||height<1||width>8192||height>8192||(long)width*height>24_000_000)throw new IllegalArgumentException("Нужен PNG до 24 мегапикселей");
                if(reader.read(0)==null)throw new IllegalArgumentException("Повреждённое изображение");
            }finally{reader.dispose();}
        }
        String id=sha(bytes);Path folder=Files.createDirectories(data.resolve("attachments")),file=folder.resolve(id+".png");
        if(!Files.isRegularFile(file)||Files.size(file)!=bytes.length||!sha(Files.readAllBytes(file)).equals(id)){
            Path temp=Files.createTempFile(folder,"upload-",".tmp");
            try{Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            finally{Files.deleteIfExists(temp);}
        }
        return Json.object("id",id,"width",width,"height",height);
    }

    // ---- plain HTTP: files, APKs, previews, the download page and the Windows remote ----
    private void serve(HttpExchange e) throws IOException {
        try(e){try{
            String path=e.getRequestURI().getPath(),method=e.getRequestMethod();String raw=e.getRequestURI().getRawQuery();String target=path+(raw==null?"":"?"+raw);
            if(e.getRequestHeaders().containsKey("Origin"))throw new SecurityException("Browser API access is disabled");
            if(path.startsWith(PhoneServices.PREFIX)){services.proxy(e);return;}
            if(method.equals("GET")){
                if(path.startsWith("/preview/")){previewFile(e,path);return;}
                if(path.equals("/")){page(e);return;}
                if(path.equals("/tethium.apk")){resource(e,"/mobile/tethium.apk","application/vnd.android.package-archive");return;}
                if(path.equals("/tethium-remote.zip")){Path zip=data.getParent().resolve("releases/tethium-remote.zip");if(!Files.isRegularFile(zip)){e.sendResponseHeaders(404,-1);return;}e.getResponseHeaders().set("Content-Type","application/zip");e.getResponseHeaders().set("Content-Disposition","attachment; filename=Tethium-Remote.zip");e.sendResponseHeaders(200,Files.size(zip));Files.copy(zip,e.getResponseBody());return;}
                if(path.equals("/tethium-remote-jvm.sha256")){byte[] sha=(remoteVersion().path("jvm").asText()+"\n").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/plain");e.sendResponseHeaders(200,sha.length);e.getResponseBody().write(sha);return;}
                if(path.equals("/tethium-remote-jvm.zip")){Path zip=data.getParent().resolve("releases/tethium-remote-jvm.zip");if(!Files.isRegularFile(zip)){e.sendResponseHeaders(404,-1);return;}e.getResponseHeaders().set("Content-Type","application/zip");e.getResponseHeaders().set("Content-Disposition","attachment; filename=tethium-remote-jvm.zip");e.sendResponseHeaders(200,Files.size(zip));Files.copy(zip,e.getResponseBody());return;}
            }
            if(path.equals("/pair")&&method.equals("POST")){json(e,200,pair(body(e)));return;}
            String auth=e.getRequestHeaders().getFirst("Authorization");
            if(auth==null||!auth.startsWith("Bearer ")||!authorized(auth.substring(7)))throw new SecurityException("Подключите телефон по QR на ноутбуке");
            if(feed.files()!=null&&feed.files().binary(e))return;
            if(method.equals("GET")){
                if(path.matches("/apps/[a-f0-9]{64}\\.apk")){Path apk=appCatalog.file(path.substring(6,70));e.getResponseHeaders().set("Content-Type","application/vnd.android.package-archive");e.sendResponseHeaders(200,Files.size(apk));Files.copy(apk,e.getResponseBody());return;}
                if(path.equals("/update")){resource(e,"/mobile/release.json","application/json");return;}
                if(path.equals("/apk")){resource(e,"/mobile/tethium.apk","application/vnd.android.package-archive");return;}
                if(path.startsWith("/artifact/file/")){
                    var download=downloads.get(path.substring("/artifact/file/".length()));if(download==null||download.expires<System.currentTimeMillis())throw new IllegalArgumentException("Ссылка загрузки истекла");e.getResponseHeaders().set("Content-Type","application/octet-stream");e.sendResponseHeaders(200,Files.size(download.root));Files.copy(download.root,e.getResponseBody());return;
                }
            }
            if(!method.equals("GET")&&!method.equals("POST")){e.sendResponseHeaders(405,-1);return;}
            json(e,200,api(method,target,method.equals("POST")?body(e,path.equals("/attachment")?17_000_000:2_000_000):null));
        }catch(Exception error){json(e,status(error),Json.object("error",message(error)));}}
    }
    /** Screenshots sent with messages and images the agent made are kept by Tethium, outside the project. */
    private volatile String remoteVersionKey="",remoteVersionSha="";
    /** The SHA-256 of the plain-jar Remote archive this laptop serves; Remote compares it with its own to offer an update. */
    private JsonNode remoteVersion()throws Exception{
        Path zip=data.getParent().resolve("releases/tethium-remote-jvm.zip");if(!Files.isRegularFile(zip))return Json.object("jvm","");
        String key=Files.size(zip)+":"+Files.getLastModifiedTime(zip).toMillis();
        if(!key.equals(remoteVersionKey)){var digest=java.security.MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(zip)){byte[] buffer=new byte[1<<16];for(int n;(n=in.read(buffer))>0;)digest.update(buffer,0,n);}remoteVersionSha=java.util.HexFormat.of().formatHex(digest.digest());remoteVersionKey=key;}
        return Json.object("jvm",remoteVersionSha);
    }
    private boolean ownedMedia(Path file,String session)throws IOException{
        for(Path root:List.of(data.resolve("attachments"),data.getParent().resolve("attachments").resolve(session),data.getParent().resolve("chat-media").resolve(session)))
            if(Files.isDirectory(root)&&file.startsWith(root.toRealPath()))return true;
        return false;
    }
    private void previewFile(HttpExchange e,String path)throws Exception{
        String[] parts=path.split("/",4);var preview=parts.length==4?previews.get(parts[2]):null;
        if(preview==null||preview.expires<System.currentTimeMillis()){e.sendResponseHeaders(404,-1);return;}
        Path file=preview.root.resolve(parts[3]).normalize().toRealPath();
        if(!file.startsWith(preview.root)||!Files.isRegularFile(file)){e.sendResponseHeaders(403,-1);return;}
        byte[] rendered=dev.tethium.artifact.MarkdownPreview.supports(file)?dev.tethium.artifact.MarkdownPreview.render(file):null;
        String name=file.getFileName().toString().toLowerCase(Locale.ROOT);String type=rendered!=null?"text/html; charset=utf-8":name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":name.endsWith(".html")?"text/html; charset=utf-8":name.endsWith(".svg")?"image/svg+xml":Files.probeContentType(file);
        e.getResponseHeaders().set("Content-Type",type==null?"application/octet-stream":type);
        e.getResponseHeaders().set("Content-Security-Policy","sandbox allow-scripts; default-src 'self' data:; connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'");
        e.getResponseHeaders().set("X-Content-Type-Options","nosniff");e.sendResponseHeaders(200,rendered==null?Files.size(file):rendered.length);if(rendered==null)Files.copy(file,e.getResponseBody());else e.getResponseBody().write(rendered);
    }
    private void page(HttpExchange e)throws IOException{
        byte[] page=("<!doctype html><html lang=ru><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Tethium · клиенты</title><style>body{background:#171b22;color:#e9eef6;font:17px system-ui;max-width:500px;margin:60px auto;padding:24px;line-height:1.6}a{display:block;background:#bcd4fb;color:#152034;padding:16px;border-radius:12px;text-decoration:none;text-align:center}</style><h1>Tethium</h1><p>Телефон и ноутбук должны быть в одной домашней сети Wi-Fi.</p><a href=/tethium.apk>Скачать APK для Android</a><p>Установите приложение. Затем на ноутбуке нажмите «Телефон» и отсканируйте второй QR для привязки.</p><a href=/tethium-remote.zip>Скачать Tethium Remote для Windows</a><p>Распакуйте ZIP и запустите Tethium Remote.exe. Вставьте ссылку привязки из окна «Телефон» на ноутбуке.</p><a href=/tethium-remote-jvm.zip>Tethium Remote для Linux и macOS (Java 25)</a><p>Распакуйте и запустите ./tethium-remote/tethium-remote; другую Java можно указать через JAVA=/путь/к/java.</p><p>Вся работа остаётся на ноутбуке. После привязки приложение само проверяет обновления.</p></html>").getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");e.sendResponseHeaders(200,page.length);e.getResponseBody().write(page);
    }
    private static JsonNode body(HttpExchange e)throws IOException{return body(e,2_000_000);}
    private static JsonNode body(HttpExchange e,int limit)throws IOException{byte[] b=e.getRequestBody().readNBytes(limit+1);if(b.length>limit)throw new IOException("Сообщение слишком большое");return b.length==0?Json.object():Json.MAPPER.readTree(b);}
    private static void resource(HttpExchange e,String path,String type)throws IOException{try(var in=MobileGateway.class.getResourceAsStream(path)){if(in==null){e.sendResponseHeaders(404,-1);return;}byte[] bytes=in.readAllBytes();e.getResponseHeaders().set("Content-Type",type);e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}}
    private static void json(HttpExchange e,int status,Object value)throws IOException{byte[] bytes=Json.MAPPER.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}
    static String randomToken(){return random();}
    /** Declares a local service of a project so that its phone app can reach it through this gateway. */
    public static JsonNode publishService(Path project,String name,int port,String packageName)throws IOException{return PhoneServices.publish(project,name,port,packageName);}
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static boolean equal(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    public void close(){closed=true;try{link.close();}catch(IOException ignored){}for(var l:List.copyOf(links))l.close();http.stop(0);workers.shutdownNow();adb.close();services.close();uac.close();}
}
