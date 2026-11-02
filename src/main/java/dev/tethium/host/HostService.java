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
    private final ScheduledExecutorService quotaMonitor=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private long quotaObserved;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(16);
    private final String token = UUID.randomUUID() + "-" + UUID.randomUUID();
    private final AgentAdapter adapter;
    private final boolean detachedEnabled;
    private final CodexQuotaService quota;
    private final CodexModelCatalog models;
    private final Path data;
    private dev.tethium.mobile.MobileGateway mobile;
    private final AtomicLong revision = new AtomicLong();
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
        volatile boolean inProcessStarted;
        volatile SessionStore.WorkerRun workerRun;
        volatile WorkerExecution worker;
        volatile String phase = "В очереди";
    }
    private record Pending(String id, String sessionId, String method, JsonNode params, CompletableFuture<JsonNode> answer, WorkerExecution worker, long sequence) {}

    public HostService(Path data) throws Exception { this(data, new CodexAdapter(), new CodexQuotaService(new CodexAccountClient(data)::readRateLimits,new CodexAccountClient(data)::consumeReset)); }
    public HostService(Path data, AgentAdapter adapter) throws Exception { this(data, adapter, new CodexQuotaService(null)); }
    public HostService(Path data, AgentAdapter adapter, CodexQuotaService quota) throws Exception {
        this(data, adapter, quota, false);
    }
    public HostService(Path data, AgentAdapter adapter, CodexQuotaService quota, boolean standby) throws Exception {
        this(data, adapter, quota, standby, new CodexModelCatalog(adapter instanceof CodexAdapter && !Boolean.getBoolean("tethium.probe")
                ? directory -> new CodexAccountClient(directory).readModels() : null));
    }
    public HostService(Path data, AgentAdapter adapter, CodexQuotaService quota, boolean standby, CodexModelCatalog models) throws Exception {
        this.adapter = adapter; this.quota = quota; this.draining = standby; this.models = models; this.data = data;
        detachedEnabled=adapter instanceof CodexAdapter&&!Boolean.getBoolean("tethium.probe");
        store = new SessionStore(data.resolve("tethium.db"));
        store.recoverInterruptedRuns();
        usage=new UsageLedger(data.resolve("tethium.db"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle); server.setExecutor(workers); server.start();
        if(adapter instanceof CodexAdapter && !Boolean.getBoolean("tethium.probe") && Files.exists(data.resolve("mobile/enabled"))) {
            try { startMobile(); } catch(Exception e) { System.err.println("Mobile gateway: " + e); }
        }
        if(adapter instanceof CodexAdapter&&!Boolean.getBoolean("tethium.probe"))quotaMonitor.scheduleWithFixedDelay(()->{try{String codexHome=System.getenv("CODEX_HOME");NativeUsageImport.read(store,usage,(codexHome==null?Path.of(System.getProperty("user.home"),".codex"):Path.of(codexHome)).resolve("sessions"));}catch(Exception e){if(!closed)System.err.println("Usage import: "+e.getMessage());}},0,5,TimeUnit.MINUTES);
        if(!Boolean.getBoolean("tethium.probe"))for(var saved:store.workerRuns()){
            var run=new Running(saved.spec().path("startedAt").asLong(System.currentTimeMillis()));run.workerRun=saved;running.put(saved.sessionId(),run);
            workers.submit(()->observeRestoredWorker(saved.sessionId(),run));
        }
        for (var project : store.projects()) for (var session : store.list(project.id())) schedule(session.id());
        quotaMonitor.scheduleWithFixedDelay(this::observeQuota,0,15,TimeUnit.SECONDS);
        if(detachedEnabled)cpu=new CpuSampler(usage,()->{var roots=new ArrayList<CpuSampler.Root>();for(var entry:running.entrySet())try{var worker=entry.getValue().worker;if(worker!=null){var root=worker.cpuRoot(store.get(entry.getKey()).projectId());if(root!=null)roots.add(root);}}catch(Exception ignored){}return roots;});
    }
    public JsonNode descriptor() { return Json.object("port", server.getAddress().getPort(), "token", token, "version", VERSION, "protocol", BuildInfo.PROTOCOL, "build", BuildInfo.BUILD, "hostBuild", BuildInfo.HOST, "pid", ProcessHandle.current().pid()); }
    private JsonNode health() { return Json.object("version", VERSION, "protocol", BuildInfo.PROTOCOL, "build", BuildInfo.BUILD, "hostBuild", BuildInfo.HOST, "draining", draining, "running", running.size(), "workerProtocol",dev.tethium.worker.WorkerJournal.PROTOCOL,"handoffReady",running.values().stream().noneMatch(r->r.inProcessStarted)); }
    public void onShutdown(Runnable action) { shutdown = action; }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8))) { send(exchange, 401, Json.object("error", "Unauthorized")); return; }
            if (exchange.getRequestHeaders().containsKey("Origin")) { send(exchange, 403, Json.object("error", "Browser origins are not allowed")); return; }
            String path = exchange.getRequestURI().getPath();
            try {
                Object result;
                if (exchange.getRequestMethod().equals("GET")) {
                    result = switch (path) {
                        case "/health" -> health();
                        case "/state" -> snapshot(query(exchange.getRequestURI(), "session"));
                        case "/project" -> GitHubProjects.status(Path.of(store.projects().stream().filter(p->p.id().equals(query(exchange.getRequestURI(),"project"))).findFirst().orElseThrow().root()));
                        case "/usage" -> usage.report(query(exchange.getRequestURI(),"period"),query(exchange.getRequestURI(),"project"),query(exchange.getRequestURI(),"session"));
                        case "/models" -> models.get(store.get(query(exchange.getRequestURI(), "session")).directory());
                        default -> throw new IllegalArgumentException("Unknown endpoint");
                    };
                } else if (exchange.getRequestMethod().equals("POST")) {
                    byte[] bytes = exchange.getRequestBody().readNBytes(2_000_001);
                    if (bytes.length > 2_000_000) { send(exchange, 413, Json.object("error", "Message is too large")); return; }
                    var body = bytes.length == 0 ? Json.object() : Json.MAPPER.readTree(bytes);
                    result = command(path, body);
                    revision.incrementAndGet();
                } else { send(exchange, 405, Json.object("error", "Method not allowed")); return; }
                send(exchange, 200, result);
            } catch (Exception e) { send(exchange, 400, Json.object("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
        }
    }
    private final Object resetLock=new Object();
    private Object command(String path,JsonNode body)throws Exception{
        if(path.equals("/quota/reset")){synchronized(resetLock){return commandBody(path,body);}}
        if(path.equals("/projects")||path.startsWith("/projects/"))return commandBody(path,body);
        synchronized(this){return commandBody(path,body);}
    }
    private Object commandBody(String path, JsonNode body) throws Exception {
        if(path.equals("/mobile/pair")) { startMobile(); return mobile.pairInfo(); }
        if(path.equals("/mobile/revoke")) { startMobile(); return mobile.revoke(); }
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
            return Json.object("id", store.project(directory));
        }
        if(path.matches("/projects/[a-zA-Z0-9-]+/settings")){
            String pid=path.split("/")[2];Path root=Path.of(store.projects().stream().filter(p->p.id().equals(pid)).findFirst().orElseThrow().root());return GitHubProjects.configure(root,body);
        }
        if (path.equals("/sessions")) {
            String projectId = body.path("projectId").asText();
            var project = store.projects().stream().filter(p -> p.id().equals(projectId)).findFirst().orElseThrow();
            return sessionJson(store.create(projectId, null, body.path("title").asText("Новая сессия"), Path.of(project.root())));
        }
        if (path.equals("/imports/conversation")) {
            Path directory = ProjectPaths.root(Path.of(body.path("projectPath").asText()));
            String projectId = store.project(directory);
            var messages = new ArrayList<SessionStore.ImportedMessage>();
            for (var message : body.path("messages")) messages.add(new SessionStore.ImportedMessage(message.path("sourceId").asText(), message.path("role").asText(), message.path("text").asText()));
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
            case "autonomy" -> {var result=store.autonomy(id,body.path("action").asText(),body.path("goalId").asText(),body.path("goal").asText(),body.path("reason").asText());if(body.path("resumeQueue").asBoolean())store.pauseQueue(id,false);if(result.path("state").asText().equals("active")){usage.autoResume(id,true);answerAutonomousQuestions(id);}schedule(id);return result;}
            case "quota-auto" -> usage.autoResume(id,body.path("enabled").asBoolean());
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
                for(String target:interruptionScope(id,body.path("scope").asText())) { store.pauseQueue(target,true); store.autonomy(target,"pause","","","Остановлено пользователем");usage.waiting(target,false); stopRunning(target); }
            }
            case "interrupt" -> {
                String requestId=body.path("requestId").asText(); var previous=store.operationResult(id,requestId,"interrupt"); if(previous!=null)return previous;
                store.discardAutonomousQueue(id);
                var affected=interruptionScope(id,body.path("scope").asText());
                var result=store.priorityCorrection(id,affected,ImageAttachments.append(body.path("prompt").asText(),body.path("images")),requestId,!body.path("preserveDesktopDraft").asBoolean());
                for(String target:affected){store.autonomy(target,"pause","","","Приоритетная правка пользователя");usage.waiting(target,false);stopRunning(target);}
                schedule(id); return result;
            }
            case "queue-resume" -> {var a=store.autonomy(id);if(a.path("state").asText().equals("paused"))store.autonomy(id,"resume",a.path("goalId").asText(),"",""); usage.waiting(id,false); store.pauseQueue(id, false); schedule(id); }
            case "queue-remove" -> store.removeQueued(id, body.path("jobId").asText());
            case "queue-move" -> store.moveQueued(id, body.path("jobId").asText(), body.path("direction").asText());
            case "queue-take" -> { return store.takeQueuedPart(id, body.path("jobId").asText(), body.path("expectedPrompt").asText(), body.path("draft").asText(), body.path("requestId").asText(),!body.path("preserveDesktopDraft").asBoolean()); }
            case "queue-merge" -> { return store.mergeQueued(id,body.path("selected"),body.path("requestId").asText()); }
            case "queue-split" -> { return store.splitQueued(id,body.path("jobId").asText(),body.path("expectedPrompt").asText(),body.path("requestId").asText()); }
            case "active" -> { store.setActive(id, body.path("active").asBoolean()); schedule(id); }
            case "rename" -> store.rename(id, body.path("title").asText());
            case "draft" -> { store.draft(id, body.path("draft").asText()); if(body.has("images"))store.draftImages(id,body.path("images")); }
            case "settings" -> store.preferences(id, body.path("model").asText(null), SessionStore.SESSION_ACCESS,
                    body.has("reasoningEffort") ? body.path("reasoningEffort").asText(null) : store.preferences(id).path("reasoningEffort").asText(null));
            case "artifact" -> { return publish(id, body); }
            default -> throw new IllegalArgumentException("Unknown session action");
        }
        return Json.object("ok", true);
    }
    private JsonNode sessionJson(SessionStore.Session s) throws Exception {
        var run = running.get(s.id());
        var attention=store.attention(s.id()); boolean question=pending.values().stream().anyMatch(p->p.sessionId.equals(s.id())&&!p.answer.isDone());
        ((com.fasterxml.jackson.databind.node.ObjectNode)attention).put("kind",question?"question":attention.path("unread").asBoolean()?"finished":"");
        return Json.object("id", s.id(), "projectId", s.projectId(), "parentId", s.parentId(), "title", s.title(), "active", s.active(), "directory", s.directory().toString(),
                "running", run != null, "phase", run == null ? "" : run.phase, "stopping", run != null && run.stopping,
                "progress", run == null ? null : run.activity.snapshot(), "queuePaused", store.queuePaused(s.id()),
                "autonomy",store.autonomy(s.id()),"quotaWait",usage.waitState(s.id()),"status", store.lastStatus(s.id()), "queued", store.queued(s.id()).size(), "preferences", store.preferences(s.id()),"attention",attention);
    }
    private Object snapshot(String selected) throws Exception {
        var sessions = new ArrayList<JsonNode>();
        var projects = store.projects();
        for (var project : projects) for (var session : store.list(project.id())) sessions.add(sessionJson(session));
        var result = Json.object("resourceUsage",usage.sidebar(),"revision", revision.get(), "projects", projects, "sessions", sessions, "quota", quota.snapshot(), "updating", draining, "queueEditing", true, "queueGrouping", true, "priorityInterrupt", true, "imageAttachments", true);
        if (selected != null && sessions.stream().anyMatch(s -> s.path("id").asText().equals(selected))) {
            var messages = new ArrayList<SessionStore.Message>(); long cursor = 0;
            while (true) { var page = store.messages(selected, cursor, 1000); messages.addAll(page); if (page.size() < 1000) break; cursor = page.getLast().sequence(); }
            var live = running.get(selected);
            result.set("conversation", Json.object("messages", messages, "live", live == null ? "" : live.text.toString(), "events", store.events(selected),
                    "queued", store.queued(selected), "artifacts", store.artifacts(selected), "requests", pending.values().stream().filter(p -> p.sessionId.equals(selected))
                            .map(p -> Json.object("id", p.id, "method", p.method, "params", p.params)).toList()));
        }
        return result;
    }
    private synchronized void schedule(String id) throws Exception {
        if (closed || draining || running.containsKey(id) || usage.waitState(id).path("waiting").asBoolean() || !store.get(id).active()) return;
        store.ensureAutonomousContinuation(id);
        var queue=store.queued(id); if(queue.isEmpty() || store.queuePaused(id) && !queue.getFirst().urgent()) return;
        if(queue.getFirst().urgent() && store.interruptionScope(queue.getFirst().id()).stream().anyMatch(running::containsKey)) return;
        var run = new Running(); running.put(id, run);
        workers.submit(() -> execute(id, run));
    }
    private void execute(String id, Running run) {
        boolean acquired = false;
        SessionStore.Job job = null;
        try {
            while (!slots.tryAcquire(250, TimeUnit.MILLISECONDS)) if (closed || run.cancellation.isCancelled()) return;
            acquired = true;
            synchronized (this) {
                var queue = store.queued(id);
                if (closed || draining || run.stopping || run.cancellation.isCancelled() || !store.get(id).active() || queue.isEmpty() || store.queuePaused(id) && !queue.getFirst().urgent()) return;
                job = queue.getFirst();
                if(detachedEnabled){
                    final var selected=job;
                    run.workerRun=store.transaction(()->{
                        var prefs=store.preferences(id);
                        var prepared=new SessionRunner(store).prepareWorker(id,adapter.provider(),selected.prompt(),prefs.path("model").asText(null),prefs.path("access").asText(),prefs.path("reasoningEffort").asText(null));
                        var spec=Json.object("protocol",dev.tethium.worker.WorkerJournal.PROTOCOL,"startedAt",run.activity.snapshot().path("startedAt").asLong(),"request",dev.tethium.worker.WorkerMain.requestJson(prepared.request()),"executable",((CodexAdapter)adapter).executable(),"tools",SessionToolsServer.catalog(tools(id)));
                        store.jobStatus(selected.id(),"RUNNING");
                        return store.registerWorker(prepared.runId(),id,selected.id(),prepared.deliveredThrough(),spec);
                    });
                }else{run.inProcessStarted=true;store.jobStatus(job.id(),"RUNNING");}
                run.activity.preparingContext(); run.phase = "Подготовка контекста"; revision.incrementAndGet();
            }
            if(run.workerRun!=null){observeWorker(id,run);return;}
            try (var tools = new SessionToolsServer(store, id, tools(id))) {
                var preferences = store.preferences(id);
                var result = new SessionRunner(store).run(id, adapter, job.prompt(), tools.uri(), tools.token(), event -> {
                    if (event.type().equals("text")) run.text.append(event.text());
                    if((event.type().equals("error")||event.type().equals("finished"))&&quotaError(event.raw().toString()))run.quotaBlocked=true;
                    if(event.type().equals("thread/tokenUsage/updated")){try{var payload=event.raw().path("params");var tokenUsage=payload.path("tokenUsage");usage.sample(id,store.get(id).projectId(),payload.path("threadId").asText(),System.currentTimeMillis(),tokenUsage.path("total"),tokenUsage.path("last"));}catch(Exception e){System.err.println("Usage sample: "+e.getMessage());}}
                    String phase = run.activity.accept(event);
                    if (!run.stopping && phase != null) run.phase = phase;
                    revision.incrementAndGet();
                }, run.cancellation, preferences.path("model").asText(null), preferences.path("access").asText(), (method, params, cancel) -> request(id, run, method, params, cancel), preferences.path("reasoningEffort").asText(null));
                if(!run.stopping&&!run.cancellation.isCancelled()&&result.status()==AgentAdapter.Status.FAILED&&(run.quotaBlocked||quotaError(result.text()))){usage.waiting(id,true);store.jobStatus(job.id(),"QUEUED");quota.refresh();return;}
                store.jobStatus(job.id(), result.status().name());
                if(result.status()==AgentAdapter.Status.FAILED&&!run.stopping)blockAutonomy(id,"Запуск завершился с ошибкой: "+result.text());
                for(String recipient:store.resultRecipients(job)){store.append(recipient, "session", "TETHIUM", "Результат сессии «" + store.get(id).title() + "» (" + result.status() + "):\n" + result.text());store.wakeAutonomy(recipient);}
            }
        } catch (Exception e) {
            try {
                if (job != null) {boolean blocked=!run.stopping&&!run.cancellation.isCancelled()&&(run.quotaBlocked||quotaError(e.getMessage()));if(blocked){usage.waiting(id,true);quota.refresh();}store.jobStatus(job.id(), blocked?"QUEUED":run.cancellation.isCancelled() ? "CANCELLED" : "FAILED");}
                if(!run.stopping&&!run.cancellation.isCancelled()&&!usage.waitState(id).path("waiting").asBoolean())blockAutonomy(id,"Ошибка исполнения: "+e.getMessage());
                store.append(id, "system", "TETHIUM", run.cancellation.isCancelled() ? "Запуск остановлен." : "Ошибка Codex: " + e.getMessage());
            } catch (Exception ignored) { }
        } finally {
            if (acquired) slots.release();
            running.remove(id,run); revision.incrementAndGet();
            try { for(var project:store.projects())for(var session:store.list(project.id()))schedule(session.id()); } catch (Exception ignored) { }
        }
    }
    private static boolean quotaError(String text){return text!=null&&(text.contains("usageLimitExceeded")||text.toLowerCase(Locale.ROOT).contains("usage limit")||text.toLowerCase(Locale.ROOT).contains("weekly limit"));}
    private void observeRestoredWorker(String id,Running run){
        boolean acquired=false;
        try{while(!slots.tryAcquire(100,TimeUnit.MILLISECONDS))if(closed)return;acquired=true;observeWorker(id,run);}
        catch(Exception e){if(!closed)System.err.println("Worker recovery: "+e);}
        finally{if(acquired)slots.release();running.remove(id,run);revision.incrementAndGet();try{for(var project:store.projects())for(var session:store.list(project.id()))schedule(session.id());}catch(Exception ignored){}}
    }
    private void observeWorker(String id,Running run)throws Exception{
        try{
            while(!closed){
                try{
                    if(run.worker==null)run.worker=new WorkerExecution(data,store,run.workerRun,this,new WorkerExecution.Listener(){
                        public void event(AgentAdapter.Event event){
                            if(event.type().equals("text"))run.text.append(event.text());
                            if((event.type().equals("error")||event.type().equals("finished"))&&quotaError(event.raw().toString()))run.quotaBlocked=true;
                            if(event.type().equals("thread/tokenUsage/updated")){try{var p=event.raw().path("params");var u=p.path("tokenUsage");usage.sample(id,store.get(id).projectId(),p.path("threadId").asText(),System.currentTimeMillis(),u.path("total"),u.path("last"));}catch(Exception e){System.err.println("Usage sample: "+e.getMessage());}}
                            String phase=run.activity.accept(event);if(!run.stopping&&phase!=null)run.phase=phase;revision.incrementAndGet();
                        }
                        public Object tool(JsonNode params)throws Exception{return SessionToolsServer.dispatch(store,id,tools(id),params);}
                        public void question(WorkerExecution worker,long sequence,JsonNode value)throws Exception{
                            var automatic=automaticAnswer(id,value.path("method").asText(),value.path("params"));if(automatic!=null){worker.answer(sequence,automatic);return;}
                            String key=worker.run.runId()+":"+sequence;
                            pending.putIfAbsent(key,new Pending(key,id,value.path("method").asText(),value.path("params"),new CompletableFuture<>(),worker,sequence));
                            run.phase="Ждёт ответа";revision.incrementAndGet();
                        }
                    });
                    var result=run.worker.await(()->closed,()->!draining);
                    if(result==null)return;
                    boolean quotaBlocked=!run.stopping&&result.status()==AgentAdapter.Status.FAILED&&(run.quotaBlocked||quotaError(result.text()));
                    if(quotaBlocked){usage.waiting(id,true);quota.refresh();}
                    synchronized(this){
                        if(closed)return;
                        store.transaction(()->{
                            var saved=run.workerRun;
                            store.completeRun(saved.runId(),id,AgentAdapter.Provider.CODEX,result,saved.deliveredThrough());
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
    private JsonNode automaticAnswer(String id,String method,JsonNode params)throws Exception{
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
        pending.put(p.id, p); run.phase = "Ждёт ответа"; revision.incrementAndGet();
        try {
            while (!closed && !cancellation.isCancelled()) {
                try { return p.answer.get(250, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { }
            }
            if (method.equals("item/permissions/requestApproval")) return Json.object("permissions", Json.object(), "scope", "turn");
            if (method.endsWith("requestApproval")) return Json.object("decision", "cancel");
            if (method.equals("item/tool/requestUserInput")) return Json.object("answers", Json.object());
            return Json.object("action", "cancel", "content", null);
        } finally { pending.remove(p.id); run.phase = "Работает"; revision.incrementAndGet(); }
    }
    private Object publish(String sessionId, JsonNode args) throws Exception {
        var session = store.get(sessionId);
        Path file = session.directory().resolve(args.path("path").asText()).toRealPath();
        var project = store.projects().stream().filter(p -> p.id().equals(session.projectId())).findFirst().orElseThrow();
        if (!(file.startsWith(session.directory()) || file.startsWith(Path.of(project.root()).toRealPath())) || !Files.isRegularFile(file)) throw new IllegalArgumentException("Файл должен находиться в рабочем каталоге проекта");
        String id = store.artifact(sessionId, file, args.path("title").asText(file.getFileName().toString()));
        revision.incrementAndGet(); return Json.object("id", id, "path", file.toString());
    }
    private SessionToolsServer.Extension tools(String caller) {
        return new SessionToolsServer.Extension() {
            public List<JsonNode> tools() {
                return List.of(Autonomy.tool(),tool("session_spawn", "Create and start a Codex child session in this project. Returns its id immediately. Result is delivered to the parent history. Do not create worktrees automatically.", "title", "prompt"),
                        tool("session_delegate", "Queue work for an active session in the same project. Returns immediately; result is delivered to your history.", "session_id", "prompt"),
                        tool("session_message", "Send an informational message to another session in this project without starting a run.", "session_id", "text"),
                        tool("artifact_publish", "Publish HTML, Markdown, images, or a runnable build (JAR, EXE, or ZIP containing it). Remote clients can download builds and launch them on their computer. Paths are relative to your working directory or absolute.", "path", "title"),
                        tool("session_rename", "Give your session a concise descriptive title.", "title"),
                        tool("session_directory", "Set this session's working directory for subsequent runs to an existing checkout in the same Git project. Use only when the user requested a worktree. Does not create worktrees.", "path"));
            }
            public Object call(String name, JsonNode args) throws Exception {
                var source = store.get(caller);
                Object response;
                switch (name) {
                    case "session_autonomy" -> {response=store.autonomy(caller,args.path("action").asText(),args.path("goalId").asText(),args.path("goal").asText(),args.path("reason").asText());if(((JsonNode)response).path("state").asText().equals("active")){store.enableAutomaticQuota(caller);answerAutonomousQuestions(caller);}schedule(caller);}
                    case "artifact_publish" -> response = publish(caller, args);
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
                        var prefs = store.preferences(caller); store.preferences(child.id(), prefs.path("model").asText(null), prefs.path("access").asText(), prefs.path("reasoningEffort").asText(null));
                        store.enqueue(child.id(), caller, prompt); schedule(child.id()); response = Json.object("session_id", child.id());
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
                revision.incrementAndGet(); return response;
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
        if(mobile==null) mobile=new dev.tethium.mobile.MobileGateway(data,new HostClient(descriptor()),Integer.getInteger("tethium.mobile.port",8765),Integer.getInteger("tethium.mobile.downloadPort",8766));
        Files.writeString(Files.createDirectories(data.resolve("mobile")).resolve("enabled"),"true");
    }
    @Override public void close() throws Exception {
        closed = true; quotaMonitor.shutdownNow(); quota.close();
        if(mobile!=null)mobile.close();
        if(!handoff){for(String id:running.keySet())stopRunning(id);}
        server.stop(0); workers.shutdown();
        if (!workers.awaitTermination(15, TimeUnit.SECONDS)) workers.shutdownNow();
        if(cpu!=null)cpu.close();usage.close();store.close();
    }
}
