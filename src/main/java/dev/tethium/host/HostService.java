package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import dev.tethium.agent.*;
import dev.tethium.core.*;
import dev.tethium.json.Json;
import dev.tethium.tools.SessionToolsServer;
import dev.tethium.update.BuildInfo;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class HostService implements AutoCloseable {
    public static final String VERSION = "0.2.1";
    private final SessionStore store;
    private final UsageLedger usage;
    private CpuSampler cpu;
    private final ProjectWidgets projectWidgets=new ProjectWidgets();
    private final ProjectPanels projectPanels=new ProjectPanels();
    private final ResourceController resources;
    private final MediaCapture media;
    private final dev.tethium.files.FileManager fileManager;
    private AwakeGuard awake;
    private final ScheduledExecutorService quotaMonitor=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final ScheduledExecutorService scheduleMonitor=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("session-schedules-",0).factory());
    private long quotaObserved;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private Backlog backlog;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(16);
    private final String token = UUID.randomUUID() + "-" + UUID.randomUUID();
    private final AgentAdapter adapter;
    private final Map<AgentAdapter.Provider, AgentAdapter> adapters = new EnumMap<>(AgentAdapter.Provider.class);
    private final boolean detachedEnabled;
    private final CodexQuotaService quota;
    private final CodexModelCatalog models;
    private final Path data;
    private dev.tethium.mobile.MobileGateway mobile;
    private final AtomicLong revision = new AtomicLong();
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private volatile boolean draining;
    private volatile boolean handoff;
    private volatile Runnable shutdown = () -> {};
    private static final class Running {
        final Cancellation cancellation = new Cancellation();
        final StringBuffer text = new StringBuffer();
        final RunActivity activity;
        Running(){this(System.currentTimeMillis());}
        Running(long startedAt){activity=new RunActivity(startedAt);}
        volatile boolean stopping, quotaBlocked;
        /** Characters of the open thought already shown in the live text; -1 when no thought is open. */
        final dev.tethium.core.Narrator narrator = new dev.tethium.core.Narrator();
        volatile boolean inProcessStarted;
        volatile AgentAdapter.Provider provider;
        volatile SessionStore.WorkerRun workerRun;
        volatile WorkerExecution worker;
        volatile String phase = "В очереди";
    }
    private record Pending(String id, String sessionId, String method, JsonNode params, CompletableFuture<JsonNode> answer, WorkerExecution worker, long sequence) {}

    public HostService(Path data) throws Exception { this(data, List.of(new CodexAdapter(), new ClaudeAdapter()), new CodexQuotaService(new CodexAccountClient(data)::readRateLimits,new CodexAccountClient(data)::consumeReset,new ClaudeAccountClient()::readUsage), false, null); }
    public HostService(Path data, AgentAdapter adapter) throws Exception { this(data, adapter, new CodexQuotaService(null)); }
    public HostService(Path data, AgentAdapter adapter, CodexQuotaService quota) throws Exception {
        this(data, adapter, quota, false);
    }
    public HostService(Path data, AgentAdapter adapter, CodexQuotaService quota, boolean standby) throws Exception {
        this(data, List.of(adapter), quota, standby, null);
    }
    public HostService(Path data, AgentAdapter adapter, CodexQuotaService quota, boolean standby, CodexModelCatalog models) throws Exception {
        this(data, List.of(adapter), quota, standby, models);
    }
    /** The first adapter is the default executor and decides detached execution; sessions choose any listed provider. */
    public HostService(Path data, List<AgentAdapter> executors, CodexQuotaService quota, boolean standby, CodexModelCatalog models) throws Exception {
        if (executors.isEmpty()) throw new IllegalArgumentException("At least one executor is required");
        var adapter = executors.getFirst();
        for (var executor : executors) if (adapters.putIfAbsent(executor.provider(), executor) != null) throw new IllegalArgumentException("Duplicate executor for " + executor.provider());
        if (models == null) models = new CodexModelCatalog(adapter instanceof CodexAdapter && !Boolean.getBoolean("tethium.probe") ? directory -> new CodexAccountClient(directory).readModels() : null);
        this.adapter = adapter; this.quota = quota; this.draining = standby; this.models = models; this.data = data;
        detachedEnabled=adapter instanceof CodexAdapter&&!Boolean.getBoolean("tethium.probe");
        store = new SessionStore(data.resolve("tethium.db"));
        fileManager=new dev.tethium.files.FileManager(data,()->{try{return Json.MAPPER.valueToTree(store.projects());}catch(Exception e){throw new IllegalStateException(e);}});
        store.recoverInterruptedRuns();
        usage=new UsageLedger(data.resolve("tethium.db"));
        resources=new ResourceController(store,data,detachedEnabled);
        media=new MediaCapture(store,data);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle); server.setExecutor(workers); server.start();
        if(adapter instanceof CodexAdapter && !Boolean.getBoolean("tethium.probe") && Files.exists(data.resolve("mobile/enabled"))) {
            try { startMobile(); } catch(Exception e) { System.err.println("Mobile gateway: " + e); }
        }
        if(adapter instanceof CodexAdapter&&!Boolean.getBoolean("tethium.probe"))quotaMonitor.scheduleWithFixedDelay(()->{try{String codexHome=System.getenv("CODEX_HOME");NativeUsageImport.read(store,usage,(codexHome==null?Path.of(System.getProperty("user.home"),".codex"):Path.of(codexHome)).resolve("sessions"));}catch(Exception e){if(!closed)System.err.println("Usage import: "+e.getMessage());}},0,5,TimeUnit.MINUTES);
        if(!Boolean.getBoolean("tethium.probe"))for(var saved:store.workerRuns()){
            var run=new Running(saved.spec().path("startedAt").asLong(System.currentTimeMillis()));run.workerRun=saved;run.provider=AgentAdapter.Provider.of(saved.spec().path("provider").asText(null));running.put(saved.sessionId(),run);
            workers.submit(()->observeRestoredWorker(saved.sessionId(),run));
        }
        for (var project : store.projects()) for (var session : store.list(project.id())) schedule(session.id());
        backlog=new Backlog(store,quota::snapshot,()->cpu==null?Json.object():cpu.machineUsage(),this::sessionBusy,this::bump,adapters.keySet(),id->modelsForBacklog(id));
        quotaMonitor.scheduleWithFixedDelay(this::observeQuota,0,15,TimeUnit.SECONDS);
        quotaMonitor.scheduleWithFixedDelay(this::observeBacklog,15,15,TimeUnit.SECONDS);
        scheduleMonitor.scheduleWithFixedDelay(()->observeSchedules(System.currentTimeMillis()),1,1,TimeUnit.SECONDS);
        if(detachedEnabled)awake=new AwakeGuard(()->{try{return !closed&&store.autonomousWorkPending();}catch(Exception e){return false;}});
        if(detachedEnabled)cpu=new CpuSampler(usage,()->{var roots=new ArrayList<CpuSampler.Root>();for(var entry:running.entrySet())try{var worker=entry.getValue().worker;if(worker!=null){var root=worker.cpuRoot(store.get(entry.getKey()).projectId());if(root!=null)roots.add(root);}}catch(Exception ignored){}try{for(var p:store.claimedProcesses())roots.add(new CpuSampler.Root(p.path("pid").asLong(),p.path("started").asLong(),new CpuSampler.Owner(p.path("session").asText(),p.path("project").asText())));}catch(Exception ignored){}return roots;},store,resources);
    }
    private String modelsForBacklog(String id){
        try { var catalog=models.get(store.get(id).directory());String configured=catalog.path("configuredModel").asText("");if(!configured.isBlank())return configured;
            for(var m:catalog.path("data"))if(m.path("isDefault").asBoolean())return m.path("model").asText(null);
        }catch(Exception ignored){}return null;
    }
    private boolean sessionBusy(String id){
        try{return running.containsKey(id)||!store.queued(id).isEmpty()||store.queuePaused(id)||usage.waitState(id).path("waiting").asBoolean();}catch(Exception e){return true;}
    }
    void observeBacklog(){
        if(closed||draining)return;
        try{for(String session:backlog.tick())schedule(session);}catch(Exception e){System.err.println("Backlog: "+e.getMessage());}
    }
    public JsonNode descriptor() { return Json.object("port", server.getAddress().getPort(), "token", token, "version", VERSION, "protocol", BuildInfo.PROTOCOL, "build", BuildInfo.BUILD, "hostBuild", BuildInfo.HOST, "pid", ProcessHandle.current().pid()); }
    private JsonNode health() { return Json.object("version", VERSION, "protocol", BuildInfo.PROTOCOL, "build", BuildInfo.BUILD, "hostBuild", BuildInfo.HOST, "draining", draining, "running", running.size(), "restartProtocol",1,"resourceControlProtocol",1,"workerProtocol",dev.tethium.worker.WorkerJournal.PROTOCOL,"handoffReady",running.values().stream().noneMatch(r->r.inProcessStarted)); }
    public void onShutdown(Runnable action) { shutdown = action; }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8))) { send(exchange, 401, Json.object("error", "Unauthorized")); return; }
            if (exchange.getRequestHeaders().containsKey("Origin")) { send(exchange, 403, Json.object("error", "Browser origins are not allowed")); return; }
            try { if(fileManager.binary(exchange))return; }
            catch(Exception e){send(exchange,400,Json.object("error",Objects.toString(e.getMessage(),"Ошибка передачи")));return;}
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("POST")) { send(exchange, 405, Json.object("error", "Method not allowed")); return; }
            JsonNode body = null;
            if (method.equals("POST")) {
                byte[] bytes = exchange.getRequestBody().readNBytes(2_000_001);
                if (bytes.length > 2_000_000) { send(exchange, 413, Json.object("error", "Message is too large")); return; }
                body = bytes.length == 0 ? Json.object() : Json.MAPPER.readTree(bytes);
            }
            try { send(exchange, 200, dispatch(method, exchange.getRequestURI(), body)); }
            catch (Exception e) { send(exchange, 400, Json.object("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
        }
    }
    /** One entry point for loopback HTTP and the in-process mobile gateway; callers are already authenticated. */
    Object dispatch(String method, URI uri, JsonNode body) throws Exception {
        String path = uri.getPath();
        if(path.equals("/file-manager/share")&&method.equals("POST"))return fileManager.share(Path.of(body.path("path").asText()));
        if(path.startsWith(dev.tethium.files.FileManager.PREFIX+"/"))return fileManager.api(method,uri,body);
        if (method.equals("GET")) return switch (path) {
            case "/health" -> health();
            case "/resources" -> resources.snapshot();
            case "/backlog" -> backlog.state(query(uri,"project"));
            case "/state" -> snapshot(query(uri, "session"),"window".equals(query(uri,"history")));
            case "/history/index" -> historyIndex(query(uri,"session"),"true".equals(query(uri,"gallery")));
            case "/history/page" -> historyPage(query(uri,"session"),Integer.parseInt(query(uri,"first")),Integer.parseInt(query(uri,"count")),"true".equals(query(uri,"gallery")));
            case "/messages" -> messagesBefore(query(uri,"session"),query(uri,"before")==null?Long.MAX_VALUE:Long.parseLong(query(uri,"before")),query(uri,"count")==null?100:Integer.parseInt(query(uri,"count")));
            case "/trash" -> trashList();
            case "/project-icon" -> ProjectIcons.read(Path.of(store.projects().stream().filter(p->p.id().equals(query(uri,"project"))).findFirst().orElseThrow().root()));
            case "/project" -> GitHubProjects.status(Path.of(store.projects().stream().filter(p->p.id().equals(query(uri,"project"))).findFirst().orElseThrow().root()));
            case "/usage" -> usage.report(query(uri,"period"),query(uri,"project"),query(uri,"session"));
            case "/models" -> modelCatalog(query(uri, "session"));
            default -> throw new IllegalArgumentException("Unknown endpoint");
        };
        var result = command(path, body == null ? Json.object() : body);
        bump();
        return result;
    }
    private void bump() { revision.incrementAndGet(); for (var listener : changeListeners) { try { listener.run(); } catch (RuntimeException ignored) { } } }
    private JsonNode messagesBefore(String id, long before, int count) throws Exception {
        if (count < 1 || count > 1000) throw new IllegalArgumentException("History limit must be 1..1000");
        store.get(id); var page = store.messagesBefore(id, before, count + 1);
        boolean more = page.size() > count; if (more) page = page.subList(1, page.size());
        return Json.object("messages", page, "hasMore", more);
    }
    /** Push feed for the LAN gateway: change signal, catalog, conversation tails and live text, all in-process. */
    public dev.tethium.mobile.MobileGateway.Feed feed() {
        return new dev.tethium.mobile.MobileGateway.Feed() {
            public dev.tethium.files.FileManager files(){return fileManager;}
            public long revision() { return revision.get(); }
            public void addListener(Runnable listener) { changeListeners.add(listener); }
            public void removeListener(Runnable listener) { changeListeners.remove(listener); }
            public JsonNode get(String target) throws Exception { return Json.MAPPER.valueToTree(dispatch("GET", URI.create(target), null)); }
            public JsonNode post(String path, JsonNode body) throws Exception { return Json.MAPPER.valueToTree(dispatch("POST", URI.create(path), body)); }
            public JsonNode catalog() throws Exception { return Json.MAPPER.valueToTree(snapshot(null, false)); }
            public JsonNode conversationMeta(String id) throws Exception {
                store.get(id);
                return Json.object("queued", store.queued(id), "artifacts", store.artifacts(id), "messageRevision", store.messageRevision(id), "lastSequence", store.lastSequence(id),
                        "requests", pending.values().stream().filter(p -> p.sessionId.equals(id)).map(p -> Json.object("id", p.id, "method", p.method, "params", p.params)).toList());
            }
            public JsonNode messagesAfter(String id, long after, int limit) throws Exception { return Json.MAPPER.valueToTree(store.messages(id, after, limit)); }
            public JsonNode messagesBefore(String id, long before, int limit) throws Exception { return HostService.this.messagesBefore(id, before, limit); }
            public JsonNode live(String id, long run, int offset) {
                var live = running.get(id);
                if (live == null) return Json.object("run", 0, "offset", 0, "length", 0, "text", "", "timestamp", 0);
                long started = live.activity.snapshot().path("startedAt").asLong();
                int length = live.text.length(); int from = run == started && offset >= 0 && offset <= length ? offset : 0;
                return Json.object("run", started, "offset", from, "length", length, "text", live.text.substring(from, length), "timestamp", started);
            }
        };
    }
    private final Object resetLock=new Object();
    private Object command(String path,JsonNode body)throws Exception{
        if(path.equals("/quota/reset")){synchronized(resetLock){return commandBody(path,body);}}
        if(path.equals("/projects")||path.startsWith("/projects/"))return commandBody(path,body);
        synchronized(this){return commandBody(path,body);}
    }
    private Object commandBody(String path, JsonNode body) throws Exception {
        if(path.equals("/backlog"))return backlog.add(body,body.path("sourceId").asText(null));
        if(path.equals("/backlog-settings"))return backlog.settings(body);
        if(path.startsWith("/backlog/")){
            var parts=path.split("/");if(parts.length!=4)throw new IllegalArgumentException("Unknown endpoint");
            return switch(parts[3]){
                case "update"->backlog.update(parts[2],body);
                case "remove"->backlog.remove(parts[2]);
                case "start"->{String started=backlog.start(parts[2]);schedule(started);yield Json.object("session",started);}
                default->throw new IllegalArgumentException("Unknown endpoint");
            };
        }
        if(path.equals("/resources")){store.cpuControl(body.path("scope").asText(),body.path("target").asText(),body.path("settings"));resources.refresh();return resources.snapshot();}
        if(path.equals("/mobile/pair")) { startMobile(); return mobile.pairInfo(); }
        if(path.equals("/mobile/revoke")) { startMobile(); return mobile.revoke(); }
        if(path.equals("/mobile/adb/status")) { startMobile(); return mobile.adbStatus(); }
        if(path.equals("/mobile/adb/pair")) { startMobile(); return mobile.adbPair(body); }
        if(path.equals("/mobile/adb/connect")) { startMobile(); return mobile.adbConnect(body); }
        if(path.equals("/mobile/adb/forget")) { startMobile(); return mobile.adbForget(); }
        if(path.equals("/mobile/adb/install")) { startMobile(); return mobile.adbInstall(body); }
        if(path.equals("/updates/restart/prepare")){if(!body.path("confirmed").asBoolean())throw new IllegalArgumentException("Требуется явное разрешение на остановку задач");return prepareRestart(body.path("id").asText());}
        if(path.equals("/updates/restart/stop")){var plan=WorkloadRestart.read(data,body.path("id").asText());if(plan==null||plan.path("completed").asBoolean()||!draining||!body.path("confirmed").asBoolean())throw new IllegalArgumentException("Нет подготовленного перезапуска");for(String id:List.copyOf(running.keySet()))stopRunning(id);return health();}
        if(path.equals("/updates/restart/restore")){if(!draining)throw new IllegalStateException("Восстановление выполняется до возобновления очередей");return WorkloadRestart.restore(store,data,body.path("id").asText());}
        if(path.equals("/updates/apply"))return Json.object("pid",dev.tethium.update.UpdateRequests.launch(data));
        if (path.equals("/updates/drain")) { draining = true; return health(); }
        if(path.equals("/updates/handoff")){
            if(running.values().stream().anyMatch(r->r.inProcessStarted))throw new IllegalStateException("Есть запуски старого типа; дождитесь их завершения");
            draining=true;handoff=true;requestShutdown();return Json.object("ok",true,"preserved",running.size());
        }
        if (path.equals("/updates/resume")) {
            draining = false;
            for (var project : store.projects()) for (var session : store.list(project.id())) schedule(session.id());
            return health();
        }
        if (path.equals("/updates/shutdown")) {
            if (!draining || !running.isEmpty()) throw new IllegalStateException("Ядро ещё выполняет работу");
            requestShutdown(); return Json.object("ok", true);
        }
        if(path.equals("/quota/reset")){
            if(!body.path("confirmed").asBoolean())throw new IllegalArgumentException("Подтвердите расход одного сброса");
            String key=body.path("requestId").asText();UUID.fromString(key);var previous=usage.redemption(key);
            if(previous!=null&&!previous.path("pending").asBoolean())return previous;
            if(previous==null){if(!CodexQuotaService.canReset(quota.snapshot()))throw new IllegalStateException("Сброс доступен только при остатке ≤5%, наличии сбросов и свежей квоте");usage.beginRedemption(key);}
            var result=quota.consume(key);if(Set.of("reset","alreadyRedeemed").contains(result.path("outcome").asText()))usage.resetBoundary(key);usage.finishRedemption(key,result);return result;
        }
        if (path.equals("/quota/refresh")) { quota.refresh(); return quota.snapshot(); }
        if (path.equals("/projects")) {
            Path directory = ProjectPaths.root(Files.createDirectories(Path.of(body.path("path").asText())).toRealPath());
            if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Выберите каталог проекта");
            if(body.has("frequentPush")||body.path("createRepository").asBoolean())GitHubProjects.configure(directory,body);
            String id=store.project(directory);resources.refresh();return Json.object("id",id);
        }
        if(path.equals("/projects/order")){var ids=new ArrayList<String>();for(var id:body.path("ids"))ids.add(id.asText());store.reorderProjects(ids);return Json.object("ok",true);}
        if(path.matches("/projects/[a-zA-Z0-9-]+/sessions-order")){var ids=new ArrayList<String>();for(var id:body.path("ids"))ids.add(id.asText());store.reorderSessions(path.split("/")[2],ids);return Json.object("ok",true);}
        if(path.matches("/projects/[a-zA-Z0-9-]+/settings")){
            String pid=path.split("/")[2];Path root=Path.of(store.projects().stream().filter(p->p.id().equals(pid)).findFirst().orElseThrow().root());return GitHubProjects.configure(root,body);
        }
        if(path.matches("/projects/[a-zA-Z0-9-]+/active")){store.projectActive(path.split("/")[2],body.path("active").asBoolean());return Json.object("ok",true);}
        if(path.matches("/projects/[a-zA-Z0-9-]+/cross-project")){store.crossProject(path.split("/")[2],body.path("enabled").asBoolean());return Json.object("ok",true);}
        if(path.matches("/projects/[a-zA-Z0-9-]+/app-publish")){
            Path root=projectRoot(path.split("/")[2]);return dev.tethium.mobile.ApkPublisher.publish(root,root.resolve(body.path("path").asText()),body.path("title").asText());
        }
        if(path.matches("/projects/[a-zA-Z0-9-]+/files")){
            String projectId=path.split("/")[2];Path root=projectRoot(projectId);Path file=pinPath(root,root,body.path("path").asText());String title=body.path("title").asText().strip();
            return store.pinProjectFile(projectId,file.toString(),title.isBlank()?file.getFileName().toString():title);
        }
        if(path.matches("/files/[a-zA-Z0-9-]+/(rename|remove)")){
            var parts=path.split("/");if(parts[3].equals("remove"))store.removeProjectFile(parts[2]);else store.renameProjectFile(parts[2],body.path("title").asText());return Json.object("ok",true);
        }
        if (path.equals("/sessions")) {
            String projectId = body.path("projectId").asText();
            var project = store.projects().stream().filter(p -> p.id().equals(projectId)).findFirst().orElseThrow();
            var created=store.create(projectId, null, body.path("title").asText("Новая сессия"), Path.of(project.root()));resources.refresh();return sessionJson(created);
        }
        if (path.equals("/imports/conversation")) {
            Path directory = ProjectPaths.root(Path.of(body.path("projectPath").asText()));
            String projectId = store.project(directory);
            var messages = new ArrayList<SessionStore.ImportedMessage>();
            for (var message : body.path("messages")) messages.add(new SessionStore.ImportedMessage(message.path("sourceId").asText(), message.path("role").asText(), message.path("text").asText(),message.path("timestamp").asLong()));
            return sessionJson(store.importConversation(projectId, directory, body.path("title").asText(), body.path("sourceKey").asText(), messages));
        }
        if (path.equals("/answer")) {
            var question = pending.get(body.path("id").asText());
            if (question == null) throw new IllegalStateException("Этот запрос уже закрыт");
            if(question.worker!=null)question.worker.answer(question.sequence,body.path("answer"));
            if(!question.answer.complete(body.path("answer")))throw new IllegalStateException("Этот запрос уже закрыт");
            if(question.worker!=null){pending.remove(question.id);var run=running.get(question.sessionId);if(run!=null)run.phase="Работает";}
            return Json.object("ok", true);
        }
        if (path.equals("/shutdown")) { requestShutdown(); return Json.object("ok", true); }
        String[] parts = path.split("/");
        if (parts.length != 4 || !parts[1].equals("sessions")) throw new IllegalArgumentException("Unknown endpoint");
        String id = parts[2]; store.get(id);
        switch (parts[3]) {
            case "trash" -> {if(!body.path("confirmed").asBoolean())throw new IllegalArgumentException("Подтвердите удаление из списка");store.trash(id,true);}
            case "restore" -> store.trash(id,false);
            case "purge" -> {
                if(!body.path("confirmed").asBoolean())throw new IllegalArgumentException("Подтвердите окончательное удаление");
                if(interruptionScope(id,"tree").stream().anyMatch(running::containsKey))throw new IllegalStateException("Сначала остановите сессию и её подсессии");
                var removed=store.purge(id);usage.publishSidebar();for(String run:removed)removeOwnedDirectory("workers",run);removeOwnedDirectory("attachments",id);removeOwnedDirectory("chat-media",id);return Json.object("ok",true);
            }
            case "pinned-file" -> {String link=body.path("path").asText();var session=store.get(id);store.pinnedFile(id,link.isBlank()?null:pinPath(projectRoot(session.projectId()),session.directory(),link).toString());}
            case "autonomy" -> {var result=store.autonomy(id,body.path("action").asText(),body.path("goalId").asText(),body.path("goal").asText(),body.path("reason").asText());if(body.path("resumeQueue").asBoolean())store.pauseQueue(id,false);if(result.path("state").asText().equals("active")){usage.autoResume(id,true);answerAutonomousQuestions(id);}schedule(id);return result;}
            case "quota-auto" -> usage.autoResume(id,body.path("enabled").asBoolean());
            case "schedule" -> {return configureSchedule(id,body);}
            case "attention" -> store.marked(id,body.path("marked").asBoolean());
            case "technical" -> store.technical(id,body.path("enabled").asBoolean());
            case "process-track" -> {resources.checkOwner(body.path("pid").asLong(),id);return store.claimProcess(id,body.path("pid").asLong());}
            case "seen" -> store.seen(id,body.path("version").asLong());
            case "run" -> {
                String requestId=body.path("requestId").asText();
                if(!requestId.isBlank()){var previous=store.operationResult(id,requestId,"submit");if(previous!=null)return previous;}
                store.discardAutonomousQueue(id);
                String prompt=ImageAttachments.append(body.path("prompt").asText(),body.path("images"));
                String job = requestId.isBlank()?store.submit(id,prompt,body.path("separate").asBoolean(false)):store.submitOnce(id,prompt,body.path("separate").asBoolean(false),requestId).path("jobId").asText();
                if(!body.path("preserveDesktopDraft").asBoolean()) { store.draft(id, ""); store.draftImages(id,Json.MAPPER.createArrayNode()); }
                schedule(id);
                return Json.object("jobId", job);
            }
            case "stop" -> {
                for(String target:interruptionScope(id,body.path("scope").asText())) { store.pauseQueue(target,true); store.periodicSchedule(target,"pause",0,"",System.currentTimeMillis());store.autonomy(target,"pause","","","Остановлено пользователем");usage.waiting(target,false); stopRunning(target); }
            }
            case "interrupt" -> {
                String requestId=body.path("requestId").asText(); var previous=store.operationResult(id,requestId,"interrupt"); if(previous!=null)return previous;
                store.discardAutonomousQueue(id);
                var affected=interruptionScope(id,body.path("scope").asText());
                var result=body.path("queueHead").asBoolean()?store.interruptQueueHead(id,affected,requestId):store.priorityCorrection(id,affected,ImageAttachments.append(body.path("prompt").asText(),body.path("images")),requestId,!body.path("preserveDesktopDraft").asBoolean());
                for(String target:affected){store.periodicSchedule(target,"pause",0,"",System.currentTimeMillis());store.autonomy(target,"pause","","","Приоритетная правка пользователя");usage.waiting(target,false);stopRunning(target);}
                schedule(id); return result;
            }
            case "queue-pause" -> store.pauseQueue(id,true);
            case "queue-resume" -> {var a=store.autonomy(id);if(a.path("state").asText().equals("paused"))store.autonomy(id,"resume",a.path("goalId").asText(),"","");if(store.periodicSchedule(id).path("state").asText().equals("paused"))store.periodicSchedule(id,"resume",0,"",System.currentTimeMillis()); usage.waiting(id,false); store.pauseQueue(id, false); schedule(id); }
            case "queue-remove" -> store.removeQueued(id, body.path("jobId").asText());
            case "queue-move" -> store.moveQueued(id, body.path("jobId").asText(), body.path("direction").asText());
            case "queue-take" -> { return store.takeQueuedPart(id, body.path("jobId").asText(), body.path("expectedPrompt").asText(), body.path("draft").asText(), body.path("requestId").asText(),!body.path("preserveDesktopDraft").asBoolean()); }
            case "queue-merge" -> { return store.mergeQueued(id,body.path("selected"),body.path("requestId").asText()); }
            case "queue-split" -> { return store.splitQueued(id,body.path("jobId").asText(),body.path("expectedPrompt").asText(),body.path("requestId").asText()); }
            case "active" -> { store.setActive(id, body.path("active").asBoolean()); schedule(id); }
            case "rename" -> store.rename(id, body.path("title").asText());
            case "reset-context" -> {
                if (running.containsKey(id)) throw new IllegalStateException("Сначала остановите сессию");
                int removed = store.unbind(id);
                store.append(id, "system", "TETHIUM", "Контекст исполнителя сброшен: следующий запуск начнёт новый разговор, прежняя память модели не используется.");
                return Json.object("removed", removed);
            }
            case "history-delete" -> {
                if (running.containsKey(id)) throw new IllegalStateException("Сначала остановите сессию");
                int deleted;
                if (body.has("after")) deleted = store.deleteMessagesAfter(id, body.path("after").asLong());
                else { var sequences = new ArrayList<Long>(); for (var s : body.path("sequences")) sequences.add(s.asLong()); deleted = store.deleteMessages(id, sequences); }
                if (body.path("resetContext").asBoolean()) store.unbind(id);
                return Json.object("deleted", deleted);
            }
            case "reset-state" -> { store.resetState(id); return Json.object("ok", true); }
            case "memory" -> {
                Path memory = ClaudeMemory.directory(ClaudeMemory.defaultHome(), store.get(id).directory());
                if (!body.path("action").asText("list").equals("delete")) return ClaudeMemory.list(memory);
                var names = new ArrayList<String>(); for (var n : body.path("names")) names.add(n.asText());
                return ClaudeMemory.delete(memory, names, data.resolve("trash").resolve("memory"));
            }
            case "draft" -> { store.draft(id, body.path("draft").asText()); if(body.has("images"))store.draftImages(id,body.path("images")); }
            case "settings" -> {
                var current = store.preferences(id);
                var provider = body.has("provider") ? AgentAdapter.Provider.of(body.path("provider").asText(null)) : AgentAdapter.Provider.of(current.path("provider").asText(null));
                if (!adapters.containsKey(provider)) throw new IllegalArgumentException("Исполнитель " + provider.title + " недоступен в этой сборке");
                boolean switched = !provider.name().equals(current.path("provider").asText());
                store.preferences(id, body.path("model").asText(null), SessionStore.SESSION_ACCESS,
                        body.has("reasoningEffort") ? body.path("reasoningEffort").asText(null) : switched ? null : current.path("reasoningEffort").asText(null), provider.name());
            }
            case "artifact" -> { return publish(id, body); }
            default -> throw new IllegalArgumentException("Unknown session action");
        }
        return Json.object("ok", true);
    }
    private JsonNode prepareRestart(String restartId)throws Exception{
        var previous=WorkloadRestart.read(data,restartId);if(previous!=null)return previous;
        draining=true;var processes=new LinkedHashMap<Long,JsonNode>();
        if(cpu!=null)for(var row:cpu.restartInventory())WorkloadRestart.process(processes,row.path("pid").asLong(),row.path("started").asLong(),row.path("session").asText(),row.path("command").asText());
        for(var row:store.claimedProcesses())WorkloadRestart.process(processes,row.path("pid").asLong(),row.path("started").asLong(),row.path("session").asText(),ProcessHandle.of(row.path("pid").asLong()).flatMap(p->p.info().commandLine()).orElse(""));
        for(var entry:running.entrySet()){var worker=entry.getValue().worker;if(worker!=null){var root=worker.cpuRoot(store.get(entry.getKey()).projectId());if(root!=null)WorkloadRestart.process(processes,root.pid(),root.started(),entry.getKey(),"Tethium worker");}}
        var entries=new ArrayList<JsonNode>();for(var project:store.projects())for(var session:store.list(project.id())){
            String id=session.id();var run=running.get(id);var owned=processes.values().stream().filter(p->p.path("session").asText().equals(id)).toList();if(run==null&&owned.isEmpty())continue;
            var job=store.runningJob(id);String live=run==null?"":run.text.toString();var row=(com.fasterxml.jackson.databind.node.ObjectNode)sessionJson(session);
            row.put("resume",run!=null&&!run.stopping&&job!=null);row.put("prompt",job==null?"":job.prompt());row.put("source",job==null?null:job.sourceId());row.put("detail",run==null?"":run.activity.snapshot().path("detail").asText());row.put("liveTail",live.substring(Math.max(0,live.length()-6000)));row.set("processes",Json.MAPPER.valueToTree(owned));entries.add(row);
        }
        var keepers=new ArrayList<JsonNode>();for(String name:List.of("resource-keeper","resource-keeper-v2")){Path file=data.resolve(name).resolve("ready.json");if(Files.exists(file)){var row=Json.MAPPER.readTree(file.toFile());long start=java.time.Instant.parse(row.path("start").asText()).toEpochMilli();if(WorkloadRestart.alive(row.path("pid").asLong(),start))keepers.add(Json.object("pid",row.path("pid").asLong(),"started",start));}}
        var plan=Json.object("id",restartId,"createdAt",System.currentTimeMillis(),"completed",false,"state",snapshot(null,true),"sessions",entries,"processes",processes.values(),"keepers",keepers);WorkloadRestart.save(data,restartId,plan);return plan;
    }
    private JsonNode sessionJson(SessionStore.Session s) throws Exception {
        var run = running.get(s.id());
        var queue = store.queued(s.id()); JsonNode hold = null;
        if (run == null && !queue.isEmpty() && !queue.getFirst().urgent()) {
            String blocker = store.interruptionBlocker(s.id(), queue.getFirst().id());
            if (blocker != null && !blocker.equals(s.id())) { String title = blocker; try { title = store.get(blocker).title(); } catch (Exception ignored) { } hold = Json.object("sessionId", blocker, "title", title); }
        }
        var attention=store.attention(s.id()); boolean question=pending.values().stream().anyMatch(p->p.sessionId.equals(s.id())&&!p.answer.isDone());
        ((com.fasterxml.jackson.databind.node.ObjectNode)attention).put("manual",store.marked(s.id()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)attention).put("kind",question?"question":attention.path("manual").asBoolean()?"manual":attention.path("unread").asBoolean()?"finished":"");
        boolean technical=store.technical(s.id());
        if(technical){var quiet=(com.fasterxml.jackson.databind.node.ObjectNode)attention;quiet.put("kind","");quiet.put("manual",false);quiet.put("unread",false);}
        return Json.object("id", s.id(), "projectId", s.projectId(), "parentId", s.parentId(), "title", s.title(), "backlog", store.backlogWaiting(s), "active", s.active(), "technical", technical, "directory", s.directory().toString(),
                "running", run != null, "phase", run == null ? "" : run.phase, "stopping", run != null && run.stopping,
                "progress", run == null ? null : run.activity.snapshot(), "queuePaused", store.queuePaused(s.id()),
                "pinnedFile",store.pinnedFile(s.id()),"autonomy",store.autonomy(s.id()),"schedule",store.periodicSchedule(s.id()),"quotaWait",usage.waitState(s.id()),"runProvider",run==null||run.provider==null?null:run.provider.name(),"status", store.lastStatus(s.id()), "queued", queue.size(), "preferences", store.preferences(s.id()),"attention",attention,"hold",hold);
    }
    private Object snapshot(String selected,boolean window) throws Exception {
        var sessions = new ArrayList<JsonNode>();
        var projects = store.projects();
        for (var project : projects) for (var session : store.list(project.id())) if(store.trashedAt(session.id())==0)sessions.add(sessionJson(session));
        var backlogWaiting=store.backlogWaiting();var projectValues=new ArrayList<JsonNode>();for(var p:projects)projectValues.add(Json.object("backlog",backlogWaiting.getOrDefault(p.id(),0),"id",p.id(),"root",p.root(),"title",p.title(),"active",store.projectActive(p.id()),"crossProject",store.crossProject(p.id()),"icon",ProjectIcons.version(Path.of(p.root())),"panel",projectPanels.read(Path.of(p.root())),"widget",projectWidgets.read(Path.of(p.root()))));
        var result = Json.object("machineUsage",cpu==null?Json.object():cpu.machineUsage(),"resources",resources.snapshot(),"files",store.projectFiles(),"resourceUsage",usage.sidebar(),"revision", revision.get(), "projects", projectValues, "sessions", sessions, "quota", quota.snapshot(), "updating", draining, "queueEditing", true, "queueGrouping", true, "priorityInterrupt", true, "imageAttachments", true);
        if (selected != null && sessions.stream().anyMatch(s -> s.path("id").asText().equals(selected))) {
            var messages = new ArrayList<SessionStore.Message>(); long cursor = 0;
            while (!window) { var page = store.messages(selected, cursor, 1000); messages.addAll(page); if (page.size() < 1000) break; cursor = page.getLast().sequence(); }
            var live = running.get(selected);
            result.set("conversation", Json.object("messages", messages, "live", window||live == null ? "" : live.text.toString(), "liveTimestamp",live==null?0:live.activity.snapshot().path("startedAt").asLong(),"historyVersion",historyVersion(selected),"windowed",window,"events", store.events(selected),
                    "queued", store.queued(selected), "artifacts", store.artifacts(selected), "requests", pending.values().stream().filter(p -> p.sessionId.equals(selected))
                            .map(p -> Json.object("id", p.id, "method", p.method, "params", p.params)).toList()));
        }
        return result;
    }
    private String historyVersion(String id)throws Exception{var live=running.get(id);return store.lastSequence(id)+"-"+store.messageRevision(id)+"-"+store.mediaRevision(id)+":"+(live==null?"done":System.identityHashCode(live)+":"+live.text.length());}
    private JsonNode historyIndex(String id,boolean gallery)throws Exception{
        store.get(id);var entries=new ArrayList<JsonNode>(store.historyIndex(id));var live=running.get(id);
        if(live!=null){int length;synchronized(live.text){length=live.text.codePointCount(0,live.text.length());}for(int offset=0;offset<length;offset+=SessionStore.HISTORY_CHUNK)entries.add(Json.object("sequence",-1,"role","assistant","provider",live.provider==null?"CODEX":live.provider.name(),"offset",offset,"length",Math.min(SessionStore.HISTORY_CHUNK,length-offset),"lines",10,"title","","timestamp",live.activity.snapshot().path("startedAt").asLong(),"live",true));}
        entries.addAll(store.mediaHistory(id));entries.sort(Comparator.comparingLong((JsonNode row)->{long seq=row.has("anchor")?row.path("anchor").asLong():row.path("sequence").asLong();return seq==-1?Long.MAX_VALUE:seq;}).thenComparingInt(row->row.has("outputOffset")?row.path("outputOffset").asInt():row.path("offset").asInt()).thenComparingInt(row->row.path("kind").asText().equals("image")?1:0));
        return Json.object("version",historyVersion(id),"entries",gallery?HistoryImages.group(entries):entries);
    }
    private JsonNode historyPage(String id,int first,int count,boolean gallery)throws Exception{
        if(first<0||count<1||count>12)throw new IllegalArgumentException("History page must contain 1..12 chunks");
        var index=historyIndex(id,gallery);var entries=index.path("entries");var result=Json.MAPPER.createArrayNode();
        for(int i=first;i<Math.min(entries.size(),first+count);i++){
            var entry=(com.fasterxml.jackson.databind.node.ObjectNode)entries.get(i).deepCopy();int offset=entry.path("offset").asInt(),length=entry.path("length").asInt();String text;
            if(entry.path("kind").asText().equals("gallery")){for(var image:entry.path("images"))((com.fasterxml.jackson.databind.node.ObjectNode)image).put("thumbnail",Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(image.path("thumbnailFile").asText()))));text="";}
            else if(entry.path("kind").asText().equals("image")){entry.put("thumbnail",Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(entry.path("thumbnailFile").asText()))));text="";}
            else if(entry.path("live").asBoolean()){var live=running.get(id);if(live==null)return Json.object("stale",true);synchronized(live.text){int points=live.text.codePointCount(0,live.text.length());text=live.text.substring(live.text.offsetByCodePoints(0,Math.min(offset,points)),live.text.offsetByCodePoints(0,Math.min(offset+length,points)));}}
            else text=store.historyChunk(id,entry.path("sequence").asLong(),offset,length);
            entry.put("index",i);entry.put("text",text);result.add(entry);
        }return Json.object("version",index.path("version"),"entries",result);
    }
    private Object trashList()throws Exception{var result=new ArrayList<JsonNode>();for(var p:store.projects())for(var session:store.list(p.id())){long deleted=store.trashedAt(session.id());if(deleted>0){var row=(com.fasterxml.jackson.databind.node.ObjectNode)sessionJson(session);row.put("deletedAt",deleted);row.put("projectTitle",p.title());result.add(row);}}return result;}
    private void removeOwnedDirectory(String kind,String id)throws Exception{
        UUID.fromString(id);Path root=data.resolve(kind).toAbsolutePath().normalize(),target=root.resolve(id).normalize();
        if(!target.startsWith(root)||target.equals(root))throw new IllegalArgumentException("Invalid service path");
        if(Files.exists(target))try(var paths=Files.walk(target)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private JsonNode configureSchedule(String id,JsonNode args)throws Exception{
        String action=args.path("action").asText("status");
        if(action.equals("set")&&(!args.path("intervalSeconds").isIntegralNumber()||!args.path("intervalSeconds").canConvertToLong()))throw new IllegalArgumentException("Интервал задаётся целым числом секунд");
        var result=store.periodicSchedule(id,action,args.path("intervalSeconds").asLong(),args.path("prompt").asText(),System.currentTimeMillis());
        if(!action.equals("status")){
            if(result.path("state").asText().equals("active")){usage.autoResume(id,true);schedule(id);}
            bump();
        }
        return result;
    }
    synchronized void observeSchedules(long now){
        if(closed||draining)return;
        try{for(String id:store.dueSchedules(now)){
            try{
                if(running.containsKey(id)||usage.waitState(id).path("waiting").asBoolean())continue;
                if(store.enqueueScheduled(id,now)){bump();schedule(id);}
            }catch(Exception e){System.err.println("Session schedule "+id+": "+e.getMessage());}
        }}catch(Exception e){System.err.println("Session schedules: "+e.getMessage());}
    }
    private synchronized void schedule(String id) throws Exception {
        if (closed || draining || running.containsKey(id) || usage.waitState(id).path("waiting").asBoolean() || !store.acceptsWork(id)) return;
        store.ensureAutonomousContinuation(id);
        var queue=store.queued(id); if(queue.isEmpty() || store.queuePaused(id) && !queue.getFirst().urgent()) return;
        // Another session's urgent correction holds this queue, but the user's own "now" in this session outranks that hold.
        if(!queue.getFirst().urgent()&&store.interruptionBlocks(id,queue.getFirst().id()))return;
        if(queue.getFirst().urgent() && store.interruptionScope(queue.getFirst().id()).stream().anyMatch(running::containsKey)) return;
        var run = new Running(); running.put(id, run);
        workers.submit(() -> execute(id, run));
    }
    private void execute(String id, Running run) {
        boolean acquired = false;
        SessionStore.Job job = null; AgentAdapter executor = null;
        try {
            while (!slots.tryAcquire(250, TimeUnit.MILLISECONDS)) if (closed || run.cancellation.isCancelled()) return;
            acquired = true;
            synchronized (this) {
                var queue = store.queued(id);
                if (closed || draining || run.stopping || run.cancellation.isCancelled() || !store.acceptsWork(id) || queue.isEmpty() || store.queuePaused(id) && !queue.getFirst().urgent() || store.interruptionBlocks(id,queue.getFirst().id())) return;
                job = queue.getFirst();
                var provider=AgentAdapter.Provider.of(store.executionPreferences(id,job.id()).path("provider").asText(null));
                executor=adapters.get(provider);
                if(executor==null){store.jobStatus(job.id(),"FAILED");store.append(id,"system","TETHIUM","Исполнитель "+provider.title+" недоступен в этой сборке. Выберите другого исполнителя в шапке сессии.");return;}
                run.provider=provider;
                if(detachedEnabled){
                    final var selected=job;final var chosen=executor;
                    run.workerRun=store.transaction(()->{
                        var prefs=store.executionPreferences(id,selected.id());
                        var prepared=new SessionRunner(store).prepareWorker(id,provider,selected.prompt(),prefs.path("model").asText(null),prefs.path("access").asText(),prefs.path("reasoningEffort").asText(null));
                        resources.refresh();
                        var spec=Json.object("resources",resources.spec(id),"protocol",dev.tethium.worker.WorkerJournal.PROTOCOL,"startedAt",run.activity.snapshot().path("startedAt").asLong(),"request",dev.tethium.worker.WorkerMain.requestJson(prepared.request()),"provider",provider.name(),"executable",chosen.executable(),"tools",SessionToolsServer.catalog(tools(id,run)));
                        store.jobStatus(selected.id(),"RUNNING");
                        return store.registerWorker(prepared.runId(),id,selected.id(),prepared.deliveredThrough(),spec);
                    });
                }else{run.inProcessStarted=true;store.jobStatus(job.id(),"RUNNING");}
                run.activity.preparingContext(); run.phase = "Подготовка контекста"; bump();
            }
            if(run.workerRun!=null){observeWorker(id,run);return;}
            try (var tools = new SessionToolsServer(store, id, tools(id,run))) {
                var preferences = store.executionPreferences(id,job.id());
                var result = new SessionRunner(store).run(id, executor, job.prompt(), tools.uri(), tools.token(), event -> observe(id, run, event), run.cancellation, preferences.path("model").asText(null), preferences.path("access").asText(), (method, params, cancel) -> request(id, run, method, params, cancel), preferences.path("reasoningEffort").asText(null));
                if(!run.stopping&&!run.cancellation.isCancelled()&&result.status()==AgentAdapter.Status.FAILED&&codexQuota(run)&&(run.quotaBlocked||quotaError(result.text()))){usage.waiting(id,true);store.jobStatus(job.id(),"QUEUED");quota.refresh();return;}
                store.jobStatus(job.id(), result.status().name());
                if(result.status()==AgentAdapter.Status.FAILED&&!run.stopping)blockAutonomy(id,"Запуск завершился с ошибкой: "+result.text());
                for(String recipient:store.resultRecipients(job)){store.append(recipient, "session", "TETHIUM", "Результат сессии «" + store.get(id).title() + "» (" + result.status() + "):\n" + result.text());store.wakeAutonomy(recipient);}
            }
        } catch (Exception e) {
            try {
                if (job != null) {boolean blocked=!run.stopping&&!run.cancellation.isCancelled()&&codexQuota(run)&&(run.quotaBlocked||quotaError(e.getMessage()));if(blocked){usage.waiting(id,true);quota.refresh();}store.jobStatus(job.id(), blocked?"QUEUED":run.cancellation.isCancelled() ? "CANCELLED" : "FAILED");}
                if(!run.stopping&&!run.cancellation.isCancelled()&&!usage.waitState(id).path("waiting").asBoolean())blockAutonomy(id,"Ошибка исполнения: "+e.getMessage());
                store.append(id, "system", "TETHIUM", run.cancellation.isCancelled() ? "Запуск остановлен." : "Ошибка " + (run.provider == null ? "исполнителя" : run.provider.title) + ": " + e.getMessage());
            } catch (Exception ignored) { }
        } finally {
            if (acquired) slots.release();
            running.remove(id,run); bump();
            try { for(var project:store.projects())for(var session:store.list(project.id()))schedule(session.id()); } catch (Exception ignored) { }
        }
    }
    private static boolean quotaError(String text){return text!=null&&(text.contains("usageLimitExceeded")||text.toLowerCase(Locale.ROOT).contains("usage limit")||text.toLowerCase(Locale.ROOT).contains("weekly limit"));}
    private static void narrate(Running run, AgentAdapter.Event event) {
        synchronized (run.text) { run.text.append(run.narrator.piece(event)); }
    }
    /** Only the Codex account quota is observed by the host; other executors report limit errors as ordinary failures. */
    private static boolean codexQuota(Running run){return run.provider==null||run.provider==AgentAdapter.Provider.CODEX;}
    private void observe(String id,Running run,AgentAdapter.Event event){
        narrate(run, event);
        if(codexQuota(run)&&(event.type().equals("error")||event.type().equals("finished"))&&quotaError(event.raw().toString()))run.quotaBlocked=true;
        if(event.type().equals("thread/tokenUsage/updated")){try{var payload=event.raw().path("params");var tokenUsage=payload.path("tokenUsage");usage.sample(id,store.get(id).projectId(),payload.path("threadId").asText(),System.currentTimeMillis(),tokenUsage.path("total"),tokenUsage.path("last"),run.provider==null?null:run.provider.name());}catch(Exception e){System.err.println("Usage sample: "+e.getMessage());}}
        if(event.type().equals("rate_limit_event")){try{var windows=Json.MAPPER.createArrayNode();event.raw().path("rate_limit_info").path("unifiedWindows").properties().forEach(w->{long minutes=w.getKey().equals("seven_day")?10080:w.getKey().equals("five_hour")?300:0;if(minutes>0&&w.getValue().path("resetsAt").asLong()>0)windows.add(Json.object("kind",w.getKey(),"durationMinutes",minutes,"resetsAt",w.getValue().path("resetsAt").asLong()));});if(!windows.isEmpty())usage.snapshot(Json.object("updatedAt",System.currentTimeMillis(),"buckets",List.of(Json.object("id","claude","label","Claude","windows",windows))));}catch(Exception e){System.err.println("Rate limit event: "+e.getMessage());}}
        String phase=run.activity.accept(event);if(!run.stopping&&phase!=null)run.phase=phase;bump();
    }
    /** One merged catalog: every available executor contributes its models, each entry tagged with its provider. */
    private JsonNode modelCatalog(String sessionId)throws Exception{
        var session=store.get(sessionId);var current=AgentAdapter.Provider.of(store.preferences(sessionId).path("provider").asText(null));
        var providers=Json.object();var data=Json.MAPPER.createArrayNode();boolean any=false;var problems=new StringJoiner(" ");
        for(var provider:AgentAdapter.Provider.values()){
            if(!adapters.containsKey(provider))continue;
            JsonNode catalog=provider==AgentAdapter.Provider.CLAUDE?ClaudeModelCatalog.get(true):models.get(session.directory());
            boolean available=catalog.path("available").asBoolean();any|=available;
            providers.set(provider.name(),Json.object("title",provider.title,"available",available,"error",catalog.path("error").asText(""),"configuredModel",catalog.path("configuredModel"),"configuredEffort",catalog.path("configuredEffort")));
            if(!available){problems.add(catalog.path("error").asText(provider.title+": каталог недоступен"));continue;}
            for(var entry:catalog.path("data")){var row=(com.fasterxml.jackson.databind.node.ObjectNode)entry.deepCopy();row.put("provider",provider.name());data.add(row);}
        }
        var active=providers.path(current.name());
        return Json.object("available",any,"provider",current.name(),"providers",providers,"data",data,"configuredModel",active.path("configuredModel"),"configuredEffort",active.path("configuredEffort"),"error",problems.toString());
    }
    private void observeRestoredWorker(String id,Running run){
        boolean acquired=false;
        try{while(!slots.tryAcquire(100,TimeUnit.MILLISECONDS))if(closed)return;acquired=true;observeWorker(id,run);}
        catch(Exception e){if(!closed)System.err.println("Worker recovery: "+e);}
        finally{if(acquired)slots.release();running.remove(id,run);bump();try{for(var project:store.projects())for(var session:store.list(project.id()))schedule(session.id());}catch(Exception ignored){}}
    }
    private void observeWorker(String id,Running run)throws Exception{
        try{
            while(!closed){
                try{
                    if(run.worker==null)run.worker=new WorkerExecution(data,store,run.workerRun,this,new WorkerExecution.Listener(){
                        public void event(AgentAdapter.Event event){observe(id,run,event);}
                        public Object tool(JsonNode params)throws Exception{return SessionToolsServer.dispatch(store,id,tools(id,run),params);}
                        public void question(WorkerExecution worker,long sequence,JsonNode value)throws Exception{
                            var automatic=automaticAnswer(id,value.path("method").asText(),value.path("params"));if(automatic!=null){worker.answer(sequence,automatic);return;}
                            String key=worker.run.runId()+":"+sequence;
                            pending.putIfAbsent(key,new Pending(key,id,value.path("method").asText(),value.path("params"),new CompletableFuture<>(),worker,sequence));
                            run.phase="Ждёт ответа";bump();
                        }
                    });
                    var result=run.worker.await(()->closed,()->!draining);
                    if(result==null)return;
                    boolean quotaBlocked=!run.stopping&&result.status()==AgentAdapter.Status.FAILED&&codexQuota(run)&&(run.quotaBlocked||quotaError(result.text()));
                    if(quotaBlocked){usage.waiting(id,true);quota.refresh();}
                    synchronized(this){
                        if(closed)return;
                        store.transaction(()->{
                            var saved=run.workerRun;
                            store.completeRun(saved.runId(),id,AgentAdapter.Provider.of(saved.spec().path("provider").asText(null)),result,saved.deliveredThrough());
                            store.jobStatus(saved.jobId(),quotaBlocked?"QUEUED":result.status().name());
                            if(!quotaBlocked&&!run.stopping&&result.status()==AgentAdapter.Status.FAILED)blockAutonomy(id,"Запуск завершился с ошибкой: "+result.text());
                            if(!quotaBlocked)for(String recipient:store.resultRecipients(store.job(saved.jobId()))){store.append(recipient,"session","TETHIUM","Результат сессии «"+store.get(id).title()+"» ("+result.status()+"):\n"+result.text());store.wakeAutonomy(recipient);}
                            store.workerFinished(saved.runId());return null;
                        });
                    }
                    return;
                }catch(Exception e){if(closed)return;run.phase="Переподключение к исполнителю";System.err.println("Worker "+run.workerRun.runId()+": "+e);Thread.sleep(1000);}
            }
        }finally{
            pending.entrySet().removeIf(e->e.getValue().sessionId.equals(id)&&e.getValue().worker!=null);
            if(run.worker!=null)run.worker.close();
        }
    }
    synchronized void observeQuota(){try{
        var snapshot=quota.snapshot();long updated=snapshot.path("updatedAt").asLong();if(updated>quotaObserved){usage.snapshot(snapshot);quotaObserved=updated;}
        if(closed||draining)return;
        for(var project:store.projects())for(var session:store.list(project.id())){var wait=usage.waitState(session.id());if(wait.path("autoResume").asBoolean()&&wait.path("waiting").asBoolean()&&!store.queuePaused(session.id())&&CodexQuotaService.available(snapshot,wait.path("blockedAt").asLong())){usage.waiting(session.id(),false);schedule(session.id());}}
    }catch(Exception e){if(!closed)System.err.println("Quota monitor: "+e.getMessage());}}
    private void blockAutonomy(String id,String reason)throws Exception{var a=store.autonomy(id);if(a.path("state").asText().equals("active"))store.autonomy(id,"blocked",a.path("goalId").asText(),"",reason.length()>1600?reason.substring(0,1600):reason);}
    /** Nobody watches a technical session, so a question must not hang it: the agent gets a refusal and goes on by itself. */
    private static JsonNode technicalAnswer(String method){
        return switch(method){
            case "item/tool/requestUserInput" -> Json.object("answers",Json.object());
            case "item/permissions/requestApproval" -> Json.object("permissions",Json.object(),"scope","turn");
            case "mcpServer/elicitation/request" -> Json.object("action","cancel");
            default -> Json.object("decision","decline");
        };
    }
    private JsonNode automaticAnswer(String id,String method,JsonNode params)throws Exception{
        if(store.technical(id))return technicalAnswer(method);
        if(store.queuePaused(id))return null;var answer=Autonomy.answer(method,params,store.autonomy(id));if(answer!=null)store.append(id,"system","TETHIUM","Автономный режим: агенту предложено самостоятельно выбрать обратимое решение. Если требуется ваше участие, он должен отметить блокер.");return answer;
    }
    private void answerAutonomousQuestions(String id)throws Exception{for(var p:List.copyOf(pending.values()))if(p.sessionId.equals(id)){var answer=automaticAnswer(id,p.method,p.params);if(answer!=null){if(p.worker!=null)p.worker.answer(p.sequence,answer);p.answer.complete(answer);pending.remove(p.id);}}}
    private void stopRunning(String id) {
        var run=running.get(id); if(run!=null) { run.stopping=true; run.phase="Останавливается";
            if(run.workerRun!=null){try(var journal=new dev.tethium.worker.WorkerJournal(data.resolve("workers").resolve(run.workerRun.runId()))){journal.state("cancel","true");}catch(Exception e){throw new IllegalStateException("Не удалось передать Stop исполнителю",e);}}
            workers.submit(run.cancellation::cancel);
        }
    }
    private List<String> interruptionScope(String id,String scope) throws Exception {
        var all=new ArrayList<SessionStore.Session>();
        if(scope.equals("all")) { for(var project:store.projects())all.addAll(store.list(project.id())); return all.stream().map(SessionStore.Session::id).toList(); }
        all.addAll(store.list(store.get(id).projectId())); var ids=new LinkedHashSet<String>(); ids.add(id);
        boolean added; do { added=false; for(var session:all) if(ids.contains(session.parentId()))added|=ids.add(session.id()); }while(added);
        return List.copyOf(ids);
    }
    private JsonNode request(String id, Running run, String method, JsonNode params, Cancellation cancellation) throws Exception {
        if (!(method.endsWith("requestApproval") || method.equals("item/tool/requestUserInput") || method.equals("mcpServer/elicitation/request"))) return null;
        var automatic=automaticAnswer(id,method,params);if(automatic!=null)return automatic;
        var p = new Pending(UUID.randomUUID().toString(), id, method, params, new CompletableFuture<>(),null,0);
        pending.put(p.id, p); run.phase = "Ждёт ответа"; bump();
        try {
            while (!closed && !cancellation.isCancelled()) {
                try { return p.answer.get(250, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { }
            }
            if (method.equals("item/permissions/requestApproval")) return Json.object("permissions", Json.object(), "scope", "turn");
            if (method.endsWith("requestApproval")) return Json.object("decision", "cancel");
            if (method.equals("item/tool/requestUserInput")) return Json.object("answers", Json.object());
            return Json.object("action", "cancel", "content", null);
        } finally { pending.remove(p.id); run.phase = "Работает"; bump(); }
    }
    private Path projectRoot(String id)throws Exception{return Path.of(store.projects().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Проект не найден")).root()).toRealPath();}
    private static Path pinPath(Path root,Path directory,String link)throws Exception{
        if(link.isBlank())throw new IllegalArgumentException("Укажите файл");Path path=directory.resolve(link).toRealPath();
        if(!Files.isRegularFile(path)||!path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|html?|svg|png|jpe?g|gif|webp)$"))throw new IllegalArgumentException("Выберите Markdown, HTML или изображение");
        if(!path.startsWith(root)&&!path.startsWith(directory.toRealPath()))throw new IllegalArgumentException("Файл должен находиться внутри проекта или рабочего каталога сессии");return path;
    }
    private Object publish(String sessionId, JsonNode args) throws Exception {
        var session = store.get(sessionId);
        Path file = session.directory().resolve(args.path("path").asText()).toRealPath();
        var project = store.projects().stream().filter(p -> p.id().equals(session.projectId())).findFirst().orElseThrow();
        if (!(file.startsWith(session.directory()) || file.startsWith(Path.of(project.root()).toRealPath())) || !Files.isRegularFile(file)) throw new IllegalArgumentException("Файл должен находиться в рабочем каталоге проекта");
        String id = store.artifact(sessionId, file, args.path("title").asText(file.getFileName().toString()));
        bump(); return Json.object("id", id, "path", file.toString());
    }
    private SessionToolsServer.Extension tools(String caller,Running owner) {
        return new SessionToolsServer.Extension() {
            public List<JsonNode> tools() {
                var list=new ArrayList<>(Backlog.tools());list.addAll(List.of(Autonomy.tool(),SessionSchedule.tool(),tool("process_track","Attribute CPU of an existing or detached background process and its descendants to this session. Register long-lived helper services you start or reuse that were launched outside this session. PID reuse is guarded by start time.","pid"),tool("session_spawn", "Create and start a child session in this project; it inherits your executor, model and settings. Returns its id immediately. Result is delivered to the parent history. Do not create worktrees automatically.", "title", "prompt"),
                        tool("session_delegate", "Queue work for an active session in the same project. Returns immediately; result is delivered to your history.", "session_id", "prompt"),
                        tool("session_message", "Send an informational message to another session in this project without starting a run.", "session_id", "text"),
                        tool("artifact_publish", "Publish HTML, Markdown, images, or a runnable build (JAR, EXE, or ZIP containing it). Remote clients can download builds and launch them on their computer. Paths are relative to your working directory or absolute.", "path", "title"),
                        tool("app_publish", "Publish a signed standalone APK from this project to the user's local Android app store. Reads package/version from the APK, verifies signing, freezes the build and updates .tethium-apps.json. Phones can install or update it in Apps. Keep the signing key and increase versionCode for automatic updates; same-version builds can be reinstalled manually. Does not install anything on a device. Path relative to the session directory or absolute; title can be empty to use the APK label.", "path", "title"),
                        tool("phone_service_publish", "Make a local service of this project reachable from its Android app through Tethium, with no port, firewall rule or typed address of its own. Writes .tethium/service.json and returns the client source for the app; Tethium on the phone handles pairing, address and tokens. Fields: name (lowercase letters, digits, hyphen), port (the service listens on 127.0.0.1), package (the Android package name).", "name", "port", "package"),
                        tool("session_rename", "Give your session a concise descriptive title.", "title"),
                        tool("session_directory", "Set this session's working directory for subsequent runs to an existing checkout in the same Git project. Use only when the user requested a worktree. Does not create worktrees.", "path")));
                try{if(store.crossProject(store.get(caller).projectId())){
                    list.add(tool("cross_project_sessions","List the active sessions of other projects, for cross_project_delegate. Allowed for this project by the user."));
                    list.add(tool("cross_project_delegate","Queue work for an active session of another project. Returns immediately; the result is delivered to your history. For sessions of this project use session_delegate.","session_id","prompt"));
                }}catch(Exception ignored){}
                return list;
            }
            public Object call(String name, JsonNode args) throws Exception {
                var source = store.get(caller);
                Object response;
                switch (name) {
                    case "backlog_add" -> response=backlog.addFromAgent(caller,source.projectId(),args);
                    case "backlog_list" -> response=backlog.state(source.projectId());
                    case "backlog_remove" -> response=backlog.removeInProject(source.projectId(),args.path("id").asText());
                    case "session_autonomy" -> {
                        synchronized(HostService.this){
                            // Serialize with Stop and reject calls from a cancelled or superseded run.
                            if(closed||running.get(caller)!=owner||owner.stopping||owner.cancellation.isCancelled())throw new IllegalStateException("Запуск уже остановлен; он не может менять автономию");
                            response=store.autonomy(caller,args.path("action").asText(),args.path("goalId").asText(),args.path("goal").asText(),args.path("reason").asText());
                            if(((JsonNode)response).path("state").asText().equals("active")){store.enableAutomaticQuota(caller);answerAutonomousQuestions(caller);}
                            schedule(caller);
                        }
                    }
                    case "session_schedule" -> {
                        synchronized(HostService.this){
                            if(closed||running.get(caller)!=owner||owner.stopping||owner.cancellation.isCancelled())throw new IllegalStateException("Запуск уже остановлен; он не может менять расписание");
                            response=configureSchedule(caller,args);
                        }
                    }
                    case "artifact_publish" -> response = publish(caller, args);
                    case "app_publish" -> { var published=dev.tethium.mobile.ApkPublisher.publish(projectRoot(source.projectId()),source.directory().resolve(args.path("path").asText()),args.path("title").asText()); response=published; if(mobile!=null) mobile.pushAppLater(published.path("packageName").asText(),published.path("versionCode").asLong()); }
                    case "phone_service_publish" -> response=dev.tethium.mobile.MobileGateway.publishService(projectRoot(source.projectId()),args.path("name").asText(),Integer.parseInt(args.path("port").asText().strip()),args.path("package").asText());
                    case "process_track" -> {resources.checkOwner(args.path("pid").asLong(),caller);response=store.claimProcess(caller,args.path("pid").asLong());}
                    case "session_rename" -> { store.rename(caller, args.path("title").asText()); response = Json.object("ok", true); }
                    case "session_directory" -> {
                        Path target = source.directory().resolve(args.path("path").asText()).toRealPath();
                        var project = store.projects().stream().filter(p -> p.id().equals(source.projectId())).findFirst().orElseThrow();
                        if (!ProjectPaths.root(target).equals(ProjectPaths.root(Path.of(project.root())))) throw new IllegalArgumentException("Каталог другого Git-проекта");
                        store.directory(caller, target); response = Json.object("directory", target.toString(), "applies", "next run");
                    }
                    case "session_spawn" -> {
                        long count = store.list(source.projectId()).stream().filter(s -> Objects.equals(s.parentId(), caller)).count();
                        if (count >= 20) throw new IllegalStateException("У сессии уже 20 подсессий");
                        String prompt = args.path("prompt").asText(); if (prompt.isBlank()) throw new IllegalArgumentException("Укажите поручение");
                        var child = store.create(source.projectId(), caller, args.path("title").asText("Подсессия"), source.directory());
                        var parentJob=store.runningJob(caller);var prefs = parentJob==null?store.preferences(caller):store.executionPreferences(caller,parentJob.id()); store.preferences(child.id(), prefs.path("model").asText(null), prefs.path("access").asText(), prefs.path("reasoningEffort").asText(null),prefs.path("provider").asText(null));
                        store.enqueue(child.id(), caller, prompt); schedule(child.id()); response = Json.object("session_id", child.id());
                    }
                    case "cross_project_sessions" -> {
                        if (!store.crossProject(source.projectId())) throw new IllegalArgumentException("Для этого проекта делегирование в другие проекты выключено");
                        var result = Json.MAPPER.createArrayNode();
                        for (var project : store.projects()) {
                            if (project.id().equals(source.projectId()) || !store.projectActive(project.id())) continue;
                            var sessions = Json.MAPPER.createArrayNode();
                            for (var s : store.list(project.id())) if (s.active() && store.trashedAt(s.id()) == 0) sessions.add(Json.object("id", s.id(), "title", s.title(), "running", running.containsKey(s.id()), "queued", store.queued(s.id()).size()));
                            result.add(Json.object("title", project.title(), "root", project.root(), "sessions", sessions));
                        }
                        response = result;
                    }
                    case "cross_project_delegate" -> {
                        if (!store.crossProject(source.projectId())) throw new IllegalArgumentException("Для этого проекта делегирование в другие проекты выключено");
                        String target = args.path("session_id").asText(); var recipient = store.get(target);
                        if (recipient.projectId().equals(source.projectId())) throw new IllegalArgumentException("Сессия этого же проекта: используйте session_delegate");
                        String origin = store.projects().stream().filter(p -> p.id().equals(source.projectId())).findFirst().orElseThrow().title();
                        response = Json.object("job_id", store.enqueue(target, caller, "Задача из проекта «" + origin + "», сессия «" + source.title() + "». Результат вернётся туда автоматически.\n\n" + args.path("prompt").asText()));
                        schedule(target);
                    }
                    case "session_delegate", "session_message" -> {
                        String target = args.path("session_id").asText();
                        if (!store.get(target).projectId().equals(source.projectId())) throw new IllegalArgumentException("Сессия другого проекта");
                        if (target.equals(caller)) throw new IllegalArgumentException("Используйте другую сессию");
                        if (name.equals("session_delegate")) { response = Json.object("job_id", store.enqueue(target, caller, args.path("prompt").asText())); schedule(target); }
                        else { store.append(target, "session", "TETHIUM", "От «" + source.title() + "»:\n" + args.path("text").asText()); response = Json.object("ok", true); }
                    }
                    default -> throw new IllegalArgumentException("Unknown tool");
                }
                bump(); return response;
            }
        };
    }
    private static JsonNode tool(String name, String description, String... fields) {
        var properties = Json.object(); for (String field : fields) properties.set(field, Json.object("type", "string"));
        return Json.object("name", name, "description", description, "inputSchema", Json.object("type", "object", "properties", properties, "required", fields, "additionalProperties", false),
                "annotations", Json.object("readOnlyHint", false, "destructiveHint", false, "openWorldHint", false));
    }
    private static String query(URI uri, String name) {
        if (uri.getRawQuery() == null) return null;
        for (String pair : uri.getRawQuery().split("&")) { var p = pair.split("=", 2); if (p.length == 2 && p[0].equals(name)) return URLDecoder.decode(p[1], StandardCharsets.UTF_8); }
        return null;
    }
    private static void send(HttpExchange e, int status, Object value) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(value); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes);
    }
    private void requestShutdown() { Thread.ofVirtual().start(() -> { try { Thread.sleep(150); } catch (InterruptedException ignored) {} shutdown.run(); }); }
    private void startMobile() throws Exception {
        if(mobile==null) mobile=new dev.tethium.mobile.MobileGateway(data,feed(),Integer.getInteger("tethium.mobile.port",8765),Integer.getInteger("tethium.mobile.downloadPort",8766));
        Files.writeString(Files.createDirectories(data.resolve("mobile")).resolve("enabled"),"true");
    }
    @Override public void close() throws Exception {
        closed = true; scheduleMonitor.shutdownNow();quotaMonitor.shutdownNow(); quota.close();
        if(mobile!=null)mobile.close();
        if(!handoff){for(String id:running.keySet())stopRunning(id);}
        server.stop(0); workers.shutdown();
        if (!workers.awaitTermination(15, TimeUnit.SECONDS)) workers.shutdownNow();
        media.close();if(cpu!=null)cpu.close();resources.close();if(awake!=null)awake.close();usage.close();store.close();
    }
}
