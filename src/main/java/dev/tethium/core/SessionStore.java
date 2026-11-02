package dev.tethium.core;

import dev.tethium.agent.AgentAdapter.Provider;
import dev.tethium.agent.AgentAdapter;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;

/** One host owns this store. Public operations serialize access to its JDBC connection. */
public final class SessionStore implements AutoCloseable {
    /** User-selected policy for this dedicated laptop; applies to every desktop session. */
    public static final String SESSION_ACCESS = "danger-full-access";
    public static final String QUEUE_SEPARATOR = "\n\n---\n\n";
    public record Session(String id, String projectId, String parentId, String title, boolean active, Path directory) {}
    public record Message(long sequence, String role, String provider, String text) {}
    public record Binding(String conversationId, long lastSequence) {}
    public record Project(String id, String root, String title) {}
    public record Job(String id, String sessionId, String sourceId, String prompt, int partsCount, boolean urgent) {}
    public record ImportedMessage(String sourceId, String role, String text) {}
    public record WorkerRun(String runId, String sessionId, String jobId, long deliveredThrough, JsonNode spec, long cursor) {}
    private final Connection connection;

    public SessionStore(Path database) throws Exception {
        Files.createDirectories(database.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (var s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY, root TEXT NOT NULL UNIQUE)");
            s.execute("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), parent_id TEXT REFERENCES sessions(id), title TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, directory TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS messages(sequence INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id), role TEXT NOT NULL, provider TEXT NOT NULL, text TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS bindings(session_id TEXT NOT NULL REFERENCES sessions(id), provider TEXT NOT NULL, conversation_id TEXT NOT NULL, last_sequence INTEGER NOT NULL, PRIMARY KEY(session_id,provider))");
            s.execute("CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), provider TEXT NOT NULL, status TEXT NOT NULL)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_running_turn ON runs(session_id) WHERE status='RUNNING'");
            s.execute("CREATE TABLE IF NOT EXISTS preferences(session_id TEXT PRIMARY KEY REFERENCES sessions(id), model TEXT, access TEXT NOT NULL DEFAULT 'danger-full-access', draft TEXT NOT NULL DEFAULT '')");
            s.execute("UPDATE preferences SET access='danger-full-access' WHERE access<>'danger-full-access'");
            s.execute("CREATE TABLE IF NOT EXISTS run_progress(run_id TEXT PRIMARY KEY REFERENCES runs(id), session_id TEXT NOT NULL, output TEXT NOT NULL DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS events(sequence INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id), run_id TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), source_id TEXT REFERENCES sessions(id), prompt TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'QUEUED')");
            s.execute("CREATE TABLE IF NOT EXISTS queue_state(session_id TEXT PRIMARY KEY REFERENCES sessions(id), paused INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS artifacts(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), path TEXT NOT NULL, title TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS imports(source_key TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id))");
            s.execute("CREATE TABLE IF NOT EXISTS imported_messages(source_key TEXT NOT NULL REFERENCES imports(source_key), source_message_id TEXT NOT NULL, PRIMARY KEY(source_key,source_message_id))");
            boolean hasEffort = false, hasImages = false;
            try (var columns = s.executeQuery("PRAGMA table_info(preferences)")) {
                while (columns.next()) { if (columns.getString("name").equals("reasoning_effort")) hasEffort = true; if (columns.getString("name").equals("draft_images")) hasImages = true; }
            }
            if (!hasEffort) s.execute("ALTER TABLE preferences ADD COLUMN reasoning_effort TEXT");
            if (!hasImages) s.execute("ALTER TABLE preferences ADD COLUMN draft_images TEXT NOT NULL DEFAULT '[]'");
            var jobColumns = new HashSet<String>();
            try (var columns = s.executeQuery("PRAGMA table_info(jobs)")) { while (columns.next()) jobColumns.add(columns.getString("name")); }
            if (!jobColumns.contains("queue_order")) s.execute("ALTER TABLE jobs ADD COLUMN queue_order INTEGER");
            if (!jobColumns.contains("prompt_parts")) s.execute("ALTER TABLE jobs ADD COLUMN prompt_parts TEXT");
            if (!jobColumns.contains("urgent")) s.execute("ALTER TABLE jobs ADD COLUMN urgent INTEGER NOT NULL DEFAULT 0");
            s.execute("UPDATE jobs SET queue_order=rowid WHERE queue_order IS NULL");
            s.execute("CREATE TABLE IF NOT EXISTS queue_returns(request_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), result TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS queue_merges(job_id TEXT PRIMARY KEY REFERENCES jobs(id), snapshot TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS queue_operations(request_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), kind TEXT NOT NULL, result TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS queue_interruptions(job_id TEXT PRIMARY KEY REFERENCES jobs(id), affected TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS session_seen(session_id TEXT PRIMARY KEY REFERENCES sessions(id), last_run INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS worker_runs(run_id TEXT PRIMARY KEY REFERENCES runs(id), session_id TEXT NOT NULL, job_id TEXT NOT NULL REFERENCES jobs(id), delivered_through INTEGER NOT NULL, spec TEXT NOT NULL, cursor INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS worker_replies(run_id TEXT NOT NULL REFERENCES worker_runs(run_id), sequence INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,sequence))");
            s.execute("CREATE TABLE IF NOT EXISTS session_autonomy(session_id TEXT PRIMARY KEY REFERENCES sessions(id),goal_id TEXT NOT NULL,goal TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS autonomy_jobs(job_id TEXT PRIMARY KEY REFERENCES jobs(id),goal_id TEXT NOT NULL,trigger_id TEXT NOT NULL,UNIQUE(goal_id,trigger_id))");
        }
    }

    public synchronized JsonNode autonomy(String id)throws SQLException{
        try(var q=connection.prepareStatement("SELECT * FROM session_autonomy WHERE session_id=?")){q.setString(1,id);try(var r=q.executeQuery()){return r.next()?Json.object("state",r.getString("state"),"goalId",r.getString("goal_id"),"goal",r.getString("goal"),"reason",r.getString("reason")):Json.object("state","off","goalId","","goal","","reason","");}}
    }
    public synchronized JsonNode autonomy(String id,String action,String goalId,String goal,String reason)throws Exception{
        return transaction(()->{get(id);var previous=autonomy(id);String state;
            if(action.equals("start")){
                if(goal==null||goal.isBlank()||goal.length()>16000)throw new IllegalArgumentException("Укажите цель автономной работы (до 16000 символов)");
                discardAutonomousQueue(id);execute("INSERT INTO session_autonomy VALUES(?,?,?,'active','',?) ON CONFLICT(session_id) DO UPDATE SET goal_id=excluded.goal_id,goal=excluded.goal,state='active',reason='',updated_at=excluded.updated_at",id,UUID.randomUUID().toString(),goal.strip(),System.currentTimeMillis());
            }else{
                if(previous.path("state").asText().equals("off"))return previous;
                if(!action.equals("pause")&&!Objects.equals(goalId,previous.path("goalId").asText()))throw new IllegalArgumentException("Цель уже изменилась; перечитайте её состояние");
                state=switch(action){case "complete"->"complete";case "blocked"->"blocked";case "pause"->"paused";case "disable"->"off";case "wait"->"waiting";case "resume"->"active";default->throw new IllegalArgumentException("Неизвестное действие автономии");};
                if(Set.of("complete","blocked","waiting").contains(state)&&(reason==null||reason.isBlank()))throw new IllegalArgumentException("Укажите результат или причину");
                execute("UPDATE session_autonomy SET state=?,reason=?,updated_at=? WHERE session_id=?",state,reason==null?"":reason,System.currentTimeMillis(),id);if(action.equals("resume"))execute("UPDATE session_autonomy SET goal_id=? WHERE session_id=?",UUID.randomUUID().toString(),id);if(!state.equals("active"))discardAutonomousQueue(id);
            }return autonomy(id);
        });
    }
    public synchronized void discardAutonomousQueue(String id)throws SQLException{execute("UPDATE jobs SET status='CANCELLED' WHERE session_id=? AND status='QUEUED' AND id IN (SELECT job_id FROM autonomy_jobs)",id);}
    public synchronized void ensureAutonomousContinuation(String id)throws Exception{
        var a=autonomy(id);if(!a.path("state").asText().equals("active")||queuePaused(id)||!get(id).active()||!queued(id).isEmpty())return;
        String trigger="start:"+a.path("goalId").asText();
        try(var q=connection.prepareStatement("SELECT id FROM jobs WHERE session_id=? AND status='COMPLETED' ORDER BY rowid DESC LIMIT 1")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next())trigger=r.getString(1);}}
        String key=trigger;transaction(()->{try(var q=connection.prepareStatement("SELECT 1 FROM autonomy_jobs WHERE goal_id=? AND trigger_id=?")){q.setString(1,a.path("goalId").asText());q.setString(2,key);try(var r=q.executeQuery()){if(r.next())return null;}}
            String job=enqueue(id,null,Autonomy.continuation());execute("INSERT INTO autonomy_jobs VALUES(?,?,?)",job,a.path("goalId").asText(),key);return null;
        });
    }
    public synchronized boolean autonomousInput(String id,String text)throws SQLException{
        try(var q=connection.prepareStatement("SELECT 1 FROM jobs JOIN autonomy_jobs ON jobs.id=autonomy_jobs.job_id WHERE session_id=? AND prompt=? AND status IN ('QUEUED','RUNNING')")){q.setString(1,id);q.setString(2,text);try(var r=q.executeQuery()){return r.next();}}
    }
    public synchronized void enableAutomaticQuota(String id)throws SQLException{execute("INSERT INTO quota_wait(session_id,auto_resume) VALUES(?,1) ON CONFLICT(session_id) DO UPDATE SET auto_resume=1",id);}
    public synchronized void wakeAutonomy(String id)throws SQLException{execute("UPDATE session_autonomy SET state='active',reason='' WHERE session_id=? AND state='waiting'",id);}

    public synchronized String project(Path directory) throws Exception {
        String root = ProjectPaths.root(directory).toString();
        try (var q = connection.prepareStatement("SELECT id FROM projects WHERE root=?")) {
            q.setString(1, root);
            try (var rows = q.executeQuery()) { if (rows.next()) return rows.getString(1); }
        }
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO projects(id,root) VALUES(?,?)", id, root);
        return id;
    }
    public synchronized Session create(String projectId, String parentId, String title, Path directory) throws Exception {
        if (parentId != null && !get(parentId).projectId().equals(projectId)) throw new IllegalArgumentException("Parent belongs to another project");
        var session = new Session(UUID.randomUUID().toString(), projectId, parentId, title, true, directory.toRealPath());
        execute("INSERT INTO sessions(id,project_id,parent_id,title,directory) VALUES(?,?,?,?,?)", session.id(), projectId, parentId, title, session.directory().toString());
        return session;
    }
    public synchronized Session get(String id) throws SQLException {
        try (var q = connection.prepareStatement("SELECT * FROM sessions WHERE id=?")) {
            q.setString(1, id);
            try (var rows = q.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown session");
                return session(rows);
            }
        }
    }
    public synchronized List<Session> list(String projectId) throws SQLException {
        var result = new ArrayList<Session>();
        try (var q = connection.prepareStatement("SELECT * FROM sessions WHERE project_id=? ORDER BY rowid")) {
            q.setString(1, projectId);
            try (var rows = q.executeQuery()) { while (rows.next()) result.add(session(rows)); }
        }
        return List.copyOf(result);
    }
    private static Session session(ResultSet row) throws SQLException {
        return new Session(row.getString("id"), row.getString("project_id"), row.getString("parent_id"), row.getString("title"), row.getBoolean("active"), Path.of(row.getString("directory")));
    }
    public synchronized void setActive(String id, boolean active) throws SQLException { execute("UPDATE sessions SET active=? WHERE id=?", active, id); }
    public synchronized JsonNode attention(String id) throws SQLException {
        try (var q=connection.prepareStatement("SELECT r.rowid,r.status,COALESCE(s.last_run,0) FROM runs r LEFT JOIN session_seen s ON s.session_id=r.session_id WHERE r.session_id=? ORDER BY r.rowid DESC LIMIT 1")) {
            q.setString(1,id); try(var row=q.executeQuery()) {
                if(!row.next()) return Json.object("version",0,"unread",false);
                boolean terminal=Set.of("COMPLETED","FAILED","INTERRUPTED").contains(row.getString(2));
                return Json.object("version",row.getLong(1),"unread",terminal&&row.getLong(1)>row.getLong(3),"status",row.getString(2));
            }
        }
    }
    public synchronized void seen(String id,long version) throws SQLException {
        long bounded=Math.max(0,Math.min(version,attention(id).path("version").asLong()));
        execute("INSERT INTO session_seen(session_id,last_run) VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET last_run=MAX(last_run,excluded.last_run)",id,bounded);
    }
    public synchronized String beginRun(String sessionId, Provider provider) throws SQLException {
        if (!get(sessionId).active()) throw new IllegalStateException("Session is deactivated");
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO runs(id,session_id,provider,status) VALUES(?,?,?,'RUNNING')", id, sessionId, provider.name());
        return id;
    }
    public synchronized void finishRun(String id, String status) throws SQLException {
        if (!Set.of("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED").contains(status)) throw new IllegalArgumentException("Invalid terminal status");
        execute("UPDATE runs SET status=? WHERE id=? AND status='RUNNING'", status, id);
    }
    public synchronized void completeRun(String runId, String sessionId, Provider provider, AgentAdapter.Result result, long deliveredThrough) throws SQLException {
        boolean owner=connection.getAutoCommit();
        if(owner)connection.setAutoCommit(false);
        try {
            if (!result.text().isEmpty()) append(sessionId, "assistant", provider.name(), result.text());
            else saveInterruptedOutput(runId, sessionId, provider);
            if (result.conversationId() != null && result.status() == AgentAdapter.Status.COMPLETED)
                bind(sessionId, provider, result.conversationId(), deliveredThrough);
            finishRun(runId, result.status().name());
            execute("DELETE FROM run_progress WHERE run_id=?", runId);
            if(owner)connection.commit();
        } catch (SQLException | RuntimeException failure) {
            if(owner)connection.rollback(); throw failure;
        } finally { if(owner)connection.setAutoCommit(true); }
    }
    /** Call only after the host has established it owns the database and no prior executor is live. */
    public synchronized void recoverInterruptedRuns() throws SQLException {
        var interrupted = new ArrayList<String[]>();
        try (var q = connection.createStatement(); var rows = q.executeQuery("SELECT id,session_id,provider FROM runs WHERE status='RUNNING' AND id NOT IN (SELECT run_id FROM worker_runs WHERE finished=0)")) {
            while (rows.next()) interrupted.add(new String[]{rows.getString(1), rows.getString(2), rows.getString(3)});
        }
        for (var row : interrupted) {
            saveInterruptedOutput(row[0], row[1], Provider.valueOf(row[2]));
            append(row[1], "system", "TETHIUM", "Запуск прерван при завершении ядра. Можно продолжить новым сообщением.");
        }
        execute("UPDATE runs SET status='INTERRUPTED' WHERE status='RUNNING' AND id NOT IN (SELECT run_id FROM worker_runs WHERE finished=0)");
        execute("UPDATE jobs SET status='INTERRUPTED' WHERE status='RUNNING' AND id NOT IN (SELECT job_id FROM worker_runs WHERE finished=0)");
    }
    public synchronized <T> T transaction(java.util.concurrent.Callable<T> action) throws Exception {
        if(!connection.getAutoCommit())return action.call();
        connection.setAutoCommit(false);
        try { T value=action.call();connection.commit();return value; }
        catch(Exception | Error failure){connection.rollback();throw failure;}
        finally{connection.setAutoCommit(true);}
    }
    public synchronized WorkerRun registerWorker(String runId,String sessionId,String jobId,long through,JsonNode spec)throws SQLException{
        execute("INSERT INTO worker_runs(run_id,session_id,job_id,delivered_through,spec) VALUES(?,?,?,?,?)",runId,sessionId,jobId,through,spec.toString());
        return new WorkerRun(runId,sessionId,jobId,through,spec,0);
    }
    public synchronized List<WorkerRun> workerRuns()throws Exception{
        var result=new ArrayList<WorkerRun>();
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT * FROM worker_runs WHERE finished=0 ORDER BY rowid")){
            while(r.next())result.add(new WorkerRun(r.getString("run_id"),r.getString("session_id"),r.getString("job_id"),r.getLong("delivered_through"),Json.parse(r.getString("spec")),r.getLong("cursor")));
        }return result;
    }
    public synchronized void workerCursor(String runId,long cursor)throws SQLException{execute("UPDATE worker_runs SET cursor=MAX(cursor,?) WHERE run_id=?",cursor,runId);}
    public synchronized void workerFinished(String runId)throws SQLException{execute("UPDATE worker_runs SET finished=1 WHERE run_id=?",runId);}
    public synchronized void workerReply(String runId,long sequence,JsonNode value)throws SQLException{
        execute("INSERT OR IGNORE INTO worker_replies(run_id,sequence,payload) VALUES(?,?,?)",runId,sequence,value.toString());
    }
    public synchronized JsonNode workerReply(String runId,long sequence)throws Exception{
        try(var q=connection.prepareStatement("SELECT payload FROM worker_replies WHERE run_id=? AND sequence=?")){
            q.setString(1,runId);q.setLong(2,sequence);try(var r=q.executeQuery()){return r.next()?Json.parse(r.getString(1)):null;}
        }
    }
    public synchronized Job job(String jobId)throws SQLException{
        try(var q=connection.prepareStatement("SELECT session_id,source_id,prompt,prompt_parts,urgent FROM jobs WHERE id=?")){
            q.setString(1,jobId);try(var r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Unknown job");return new Job(jobId,r.getString(1),r.getString(2),r.getString(3),promptParts(r.getString(3),r.getString(4)).size(),r.getBoolean(5));}
        }
    }
    public synchronized void recordEvent(String runId, String sessionId, AgentAdapter.Event event) throws SQLException {
        if (event.type().equals("text")) {
            execute("INSERT INTO run_progress(run_id,session_id,output) VALUES(?,?,?) ON CONFLICT(run_id) DO UPDATE SET output=run_progress.output || excluded.output", runId, sessionId, event.text());
        } else if (event.type().equals("conversation")) {
            execute("INSERT INTO bindings(session_id,provider,conversation_id,last_sequence) SELECT ?,provider,?,0 FROM runs WHERE id=? ON CONFLICT(session_id,provider) DO UPDATE SET conversation_id=excluded.conversation_id", sessionId, event.text(), runId);
        } else if (event.type().equals("item/started") || event.type().equals("item/completed") || event.type().equals("error") || event.type().equals("request")) {
            execute("INSERT INTO events(session_id,run_id,type,payload) VALUES(?,?,?,?)", sessionId, runId, event.type(), event.raw().toString());
        }
    }
    public synchronized void saveInterruptedOutput(String runId, String sessionId, Provider provider) throws SQLException {
        try (var q = connection.prepareStatement("SELECT output FROM run_progress WHERE run_id=?")) {
            q.setString(1, runId);
            try (var row = q.executeQuery()) { if (row.next() && !row.getString(1).isEmpty()) append(sessionId, "assistant", provider.name(), row.getString(1)); }
        }
        execute("DELETE FROM run_progress WHERE run_id=?", runId);
    }
    public synchronized List<Project> projects() throws SQLException {
        var result = new ArrayList<Project>();
        try (var q = connection.createStatement(); var rows = q.executeQuery("SELECT id,root FROM projects ORDER BY rowid")) {
            while (rows.next()) { var path = Path.of(rows.getString(2)); result.add(new Project(rows.getString(1), path.toString(), path.getFileName() == null ? path.toString() : path.getFileName().toString())); }
        }
        return List.copyOf(result);
    }
    /** Copies conversational history into a fresh Tethium session; never reuses a live external agent thread. */
    public synchronized Session importConversation(String projectId, Path directory, String title, String sourceKey, List<ImportedMessage> messages) throws Exception {
        if (sourceKey.isBlank() || title.isBlank() || messages.isEmpty()) throw new IllegalArgumentException("Импорт должен содержать источник, название и сообщения");
        for (var message : messages) if (message.sourceId().isBlank() || !Set.of("user", "assistant", "system").contains(message.role()) || message.text().isBlank())
            throw new IllegalArgumentException("Некорректное сообщение импорта");
        connection.setAutoCommit(false);
        try {
            String id = null;
            try (var q = connection.prepareStatement("SELECT session_id FROM imports WHERE source_key=?")) {
                q.setString(1, sourceKey); try (var row = q.executeQuery()) { if (row.next()) id = row.getString(1); }
            }
            Session session;
            if (id == null) {
                session = create(projectId, null, title, directory);
                execute("INSERT INTO imports(source_key,session_id) VALUES(?,?)", sourceKey, session.id());
            } else {
                session = get(id);
                if (!session.projectId().equals(projectId)) throw new IllegalArgumentException("Источник уже импортирован в другой проект");
            }
            for (var message : messages) {
                try (var q = connection.prepareStatement("INSERT OR IGNORE INTO imported_messages(source_key,source_message_id) VALUES(?,?)")) {
                    q.setString(1, sourceKey); q.setString(2, message.sourceId());
                    if (q.executeUpdate() == 1) append(session.id(), message.role(), "IMPORTED", message.text());
                }
            }
            connection.commit(); return session;
        } catch (Exception e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }
    public synchronized void rename(String id, String title) throws SQLException {
        if (title.isBlank()) throw new IllegalArgumentException("Название не может быть пустым");
        execute("UPDATE sessions SET title=? WHERE id=?", title.strip(), id);
    }
    public synchronized void directory(String id, Path path) throws Exception { execute("UPDATE sessions SET directory=? WHERE id=?", path.toRealPath().toString(), id); }
    public synchronized JsonNode preferences(String id) throws SQLException {
        try (var q = connection.prepareStatement("SELECT model,access,draft,reasoning_effort,draft_images FROM preferences WHERE session_id=?")) {
            q.setString(1, id);
            try (var row = q.executeQuery()) { return row.next() ? Json.object("model", row.getString(1), "access", SESSION_ACCESS, "draft", row.getString(3), "reasoningEffort", row.getString(4), "draftImages", parseStored(row.getString(5))) : Json.object("model", null, "access", SESSION_ACCESS, "draft", "", "reasoningEffort", null, "draftImages", List.of()); }
        }
    }
    public synchronized void preferences(String id, String model, String access) throws SQLException {
        preferences(id, model, access, preferences(id).path("reasoningEffort").asText(null));
    }
    public synchronized void preferences(String id, String model, String access, String reasoningEffort) throws SQLException {
        // Older windows may still send their former per-session mode. The host policy takes precedence.
        execute("INSERT INTO preferences(session_id,model,access,reasoning_effort) VALUES(?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET model=excluded.model,access=excluded.access,reasoning_effort=excluded.reasoning_effort", id, model == null || model.isBlank() ? null : model, SESSION_ACCESS, reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort);
    }
    public synchronized void draft(String id, String draft) throws SQLException {
        execute("INSERT INTO preferences(session_id,draft,access) VALUES(?,?,?) ON CONFLICT(session_id) DO UPDATE SET draft=excluded.draft", id, draft, SESSION_ACCESS);
    }
    public synchronized void draftImages(String id, JsonNode images) throws SQLException {
        if (!images.isArray()) throw new IllegalArgumentException("Некорректные вложения");
        execute("INSERT INTO preferences(session_id,access,draft_images) VALUES(?,?,?) ON CONFLICT(session_id) DO UPDATE SET draft_images=excluded.draft_images", id, SESSION_ACCESS, images.toString());
    }
    public synchronized String lastStatus(String id) throws SQLException {
        try (var q = connection.prepareStatement("SELECT status FROM runs WHERE session_id=? ORDER BY rowid DESC LIMIT 1")) {
            q.setString(1, id); try (var row = q.executeQuery()) { return row.next() ? row.getString(1) : "IDLE"; }
        }
    }
    public synchronized List<JsonNode> events(String id) throws Exception {
        var result = new ArrayList<JsonNode>();
        try (var q = connection.prepareStatement("SELECT payload FROM events WHERE session_id=? ORDER BY sequence DESC LIMIT 100")) {
            q.setString(1, id); try (var row = q.executeQuery()) { while (row.next()) result.add(Json.parse(row.getString(1))); }
        }
        Collections.reverse(result); return result;
    }
    public synchronized String enqueue(String target, String source, String prompt) throws SQLException {
        if (!get(target).active()) throw new IllegalStateException("Сессия деактивирована");
        if (prompt.isBlank()) throw new IllegalArgumentException("Пустое сообщение");
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO jobs(id,session_id,source_id,prompt,prompt_parts,queue_order) VALUES(?,?,?,?,?,(SELECT COALESCE(MAX(queue_order),0)+1 FROM jobs WHERE session_id=? AND status='QUEUED'))", id, target, source, prompt, Json.MAPPER.valueToTree(List.of(prompt)).toString(), target);
        return id;
    }
    /** User input appends to the queue tail unless a separate message was requested. */
    public synchronized String submit(String target, String prompt, boolean separate) throws SQLException {
        if (!get(target).active()) throw new IllegalStateException("Сессия деактивирована");
        if (prompt.isBlank()) throw new IllegalArgumentException("Пустое сообщение");
        if (!separate) {
            String id = null; List<String> parts = null;
            try (var q = connection.prepareStatement("SELECT id,prompt,prompt_parts FROM jobs WHERE session_id=? AND status='QUEUED' AND urgent=0 ORDER BY queue_order DESC,rowid DESC LIMIT 1")) {
                q.setString(1, target);
                try (var row = q.executeQuery()) { if (row.next()) { id = row.getString(1); parts = promptParts(row.getString(2), row.getString(3)); } }
            }
            if (id != null) { parts.add(prompt); updatePrompt(id, parts); return id; }
        }
        return enqueue(target, null, prompt);
    }
    public synchronized List<Job> queued(String sessionId) throws SQLException {
        var result = new ArrayList<Job>();
        try (var q = connection.prepareStatement("SELECT id,source_id,prompt,prompt_parts,urgent FROM jobs WHERE session_id=? AND status='QUEUED' ORDER BY urgent DESC,queue_order,rowid")) {
            q.setString(1, sessionId); try (var row = q.executeQuery()) { while (row.next()) result.add(new Job(row.getString(1), sessionId, row.getString(2), row.getString(3), promptParts(row.getString(3), row.getString(4)).size(), row.getBoolean(5))); }
        }
        return result;
    }
    /** Durable request receipt for reconnecting mobile clients, including appends to a queued job. */
    public synchronized JsonNode submitOnce(String target,String prompt,boolean separate,String requestId)throws SQLException{
        var previous=operationResult(target,requestId,"submit");if(previous!=null)return previous;
        connection.setAutoCommit(false);
        try{var result=Json.object("jobId",submit(target,prompt,separate));rememberOperation(target,requestId,"submit",result);connection.commit();return result;}
        catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    private List<String> promptParts(String prompt, String raw) throws SQLException {
        if (raw != null) {
            try {
                var value = Json.parse(raw); var parts = new ArrayList<String>();
                if (value.isArray()) for (var part : value) { if (!part.isTextual()) throw new SQLException("Некорректные части сообщения"); parts.add(part.asText()); }
                if (!parts.isEmpty() && String.join(QUEUE_SEPARATOR, parts).equals(prompt)) return parts;
            } catch (java.io.IOException e) { throw new SQLException("Не удалось прочитать части сообщения", e); }
        }
        // Older releases did not store submission boundaries. Preserve the entire original text.
        return new ArrayList<>(List.of(prompt));
    }
    private void updatePrompt(String id, List<String> parts) throws SQLException {
        execute("UPDATE jobs SET prompt=?,prompt_parts=? WHERE id=? AND status='QUEUED'", String.join(QUEUE_SEPARATOR, parts), Json.MAPPER.valueToTree(parts).toString(), id);
    }
    public synchronized void moveQueued(String sessionId, String jobId, String direction) throws SQLException {
        var queue = queued(sessionId); int from = -1;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).id().equals(jobId)) from = i;
        if (from < 0) throw new IllegalStateException("Сообщение уже покинуло очередь. Обновите список.");
        if(queue.get(from).urgent()) throw new IllegalStateException("Приоритетную правку можно остановить кнопкой Стоп");
        int to = switch (direction) { case "up" -> Math.max(0, from - 1); case "down" -> Math.min(queue.size() - 1, from + 1); case "first" -> 0; case "last" -> queue.size() - 1; default -> throw new IllegalArgumentException("Неизвестное направление"); };
        if (from == to) return;
        var moved = queue.remove(from); queue.add(to, moved);
        connection.setAutoCommit(false);
        try {
            for (int i = 0; i < queue.size(); i++) execute("UPDATE jobs SET queue_order=? WHERE id=? AND session_id=? AND status='QUEUED'", i + 1, queue.get(i).id(), sessionId);
            connection.commit();
        } catch (SQLException e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }
    /** Atomically removes one submission and persists it in the draft. Request IDs make retry safe. */
    public synchronized JsonNode takeQueuedPart(String sessionId, String jobId, String expectedPrompt, String currentDraft, String requestId) throws SQLException {
        return takeQueuedPart(sessionId,jobId,expectedPrompt,currentDraft,requestId,true);
    }
    public synchronized JsonNode takeQueuedPart(String sessionId, String jobId, String expectedPrompt, String currentDraft, String requestId, boolean updateDesktopDraft) throws SQLException {
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) throw new IllegalArgumentException("Не задан идентификатор возврата");
        try (var q = connection.prepareStatement("SELECT session_id,result FROM queue_returns WHERE request_id=?")) {
            q.setString(1, requestId);
            try (var row = q.executeQuery()) { if (row.next()) {
                if (!sessionId.equals(row.getString(1))) throw new IllegalArgumentException("Возврат относится к другой сессии");
                try { var result = Json.parse(row.getString(2)); if (!jobId.equals(result.path("jobId").asText())) throw new IllegalArgumentException("Другой запрос возврата"); return result; }
                catch (java.io.IOException e) { throw new SQLException(e); }
            } }
        }
        List<String> parts = null;
        try (var q = connection.prepareStatement("SELECT prompt,prompt_parts,urgent FROM jobs WHERE id=? AND session_id=? AND status='QUEUED'")) {
            q.setString(1, jobId); q.setString(2, sessionId);
            try (var row = q.executeQuery()) { if (row.next()) {
                if(row.getBoolean(3)) throw new IllegalStateException("Сначала остановите приоритетную правку");
                if (!row.getString(1).equals(expectedPrompt)) throw new IllegalStateException("Сообщение изменилось. Проверьте обновлённую очередь и повторите возврат.");
                parts = promptParts(row.getString(1), row.getString(2));
            } }
        }
        if (parts == null) throw new IllegalStateException("Сообщение уже покинуло очередь. Возврат невозможен.");
        String text = parts.removeLast(), updatedDraft = currentDraft.isEmpty() ? text : currentDraft + QUEUE_SEPARATOR + text;
        var result = Json.object("jobId", jobId, "text", text, "draft", updatedDraft, "remainingParts", parts.size());
        connection.setAutoCommit(false);
        try {
            if (parts.isEmpty()) removeQueued(sessionId, jobId); else updatePrompt(jobId, parts);
            if(updateDesktopDraft)draft(sessionId, updatedDraft);
            execute("INSERT INTO queue_returns(request_id,session_id,result) VALUES(?,?,?)", requestId, sessionId, result.toString());
            connection.commit(); return result;
        } catch (SQLException e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }
    public synchronized void jobStatus(String id, String status) throws SQLException { execute("UPDATE jobs SET status=? WHERE id=?", status, id); }
    public synchronized void cancelQueued(String sessionId) throws SQLException { execute("UPDATE jobs SET status='CANCELLED' WHERE session_id=? AND status='QUEUED'", sessionId); }
    public synchronized boolean queuePaused(String sessionId) throws SQLException {
        try (var q = connection.prepareStatement("SELECT paused FROM queue_state WHERE session_id=?")) {
            q.setString(1, sessionId); try (var row = q.executeQuery()) { return row.next() && row.getBoolean(1); }
        }
    }
    public synchronized void pauseQueue(String sessionId, boolean paused) throws SQLException {
        execute("INSERT INTO queue_state(session_id,paused) VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET paused=excluded.paused", sessionId, paused);
        if(paused) execute("UPDATE jobs SET urgent=0 WHERE session_id=? AND status='QUEUED'",sessionId);
    }
    public synchronized void removeQueued(String sessionId, String jobId) throws SQLException {
        execute("UPDATE jobs SET status='CANCELLED' WHERE id=? AND session_id=? AND status='QUEUED'", jobId, sessionId);
    }
    private static JsonNode parseStored(String text) throws SQLException {
        try { return text == null ? Json.MAPPER.nullNode() : Json.parse(text); } catch (java.io.IOException e) { throw new SQLException(e); }
    }
    private List<String> partsOf(String id) throws SQLException {
        try (var q = connection.prepareStatement("SELECT prompt,prompt_parts FROM jobs WHERE id=?")) {
            q.setString(1, id); try (var r = q.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("Сообщение не найдено"); return promptParts(r.getString(1), r.getString(2)); }
        }
    }
    private JsonNode mergeSnapshot(String id) throws SQLException {
        try (var q = connection.prepareStatement("SELECT snapshot FROM queue_merges WHERE job_id=?")) { q.setString(1,id); try (var r=q.executeQuery()) { return r.next() ? parseStored(r.getString(1)) : Json.MAPPER.nullNode(); } }
    }
    private void renumber(String sessionId, List<String> ids) throws SQLException {
        for (int i=0;i<ids.size();i++) execute("UPDATE jobs SET queue_order=? WHERE session_id=? AND id=? AND status='QUEUED'",i+1,sessionId,ids.get(i));
    }
    public synchronized JsonNode operationResult(String sessionId, String requestId, String kind) throws SQLException {
        if (requestId == null || requestId.isBlank() || requestId.length()>100) throw new IllegalArgumentException("Не задан идентификатор операции");
        try (var q=connection.prepareStatement("SELECT session_id,kind,result FROM queue_operations WHERE request_id=?")) {
            q.setString(1,requestId); try(var r=q.executeQuery()) { if(!r.next()) return null;
                if(!sessionId.equals(r.getString(1)) || !kind.equals(r.getString(2))) throw new IllegalArgumentException("Идентификатор другой операции"); return parseStored(r.getString(3)); }
        }
    }
    private void rememberOperation(String sessionId,String requestId,String kind,JsonNode result) throws SQLException {
        execute("INSERT INTO queue_operations(request_id,session_id,kind,result) VALUES(?,?,?,?)",requestId,sessionId,kind,result.toString());
    }
    /** Merges arbitrary selected rows at the earliest selected position, preserving reversible provenance. */
    public synchronized JsonNode mergeQueued(String sessionId, JsonNode selected, String requestId) throws SQLException {
        var previous=operationResult(sessionId,requestId,"merge"); if(previous!=null)return previous;
        var expected=new LinkedHashMap<String,String>(); for(var item:selected) expected.put(item.path("id").asText(),item.path("prompt").asText());
        var queue=queued(sessionId); var chosen=queue.stream().filter(j->expected.containsKey(j.id())).toList();
        if(expected.size()<2 || chosen.size()!=expected.size()) throw new IllegalStateException("Выберите хотя бы два ожидающих сообщения");
        for(var job:chosen) if(!job.prompt().equals(expected.get(job.id())) || job.urgent()) throw new IllegalStateException("Очередь изменилась или выбрана приоритетная правка. Обновите выбор.");
        var parts=new ArrayList<String>(); var entries=new ArrayList<JsonNode>();
        for(var job:chosen) { var chunks=partsOf(job.id()); parts.addAll(chunks); entries.add(Json.object("id",job.id(),"sourceId",job.sourceId(),"parts",chunks,"merge",mergeSnapshot(job.id()))); }
        String first=chosen.getFirst().id(); var snapshot=Json.object("prompt",String.join(QUEUE_SEPARATOR,parts),"entries",entries,"order",queue.stream().map(Job::id).toList());
        var result=Json.object("jobIds",List.of(first)); connection.setAutoCommit(false);
        try {
            updatePrompt(first,parts);
            for(var job:chosen) if(!job.id().equals(first)) execute("UPDATE jobs SET status='MERGED' WHERE id=?",job.id());
            execute("INSERT INTO queue_merges(job_id,snapshot) VALUES(?,?) ON CONFLICT(job_id) DO UPDATE SET snapshot=excluded.snapshot",first,snapshot.toString());
            rememberOperation(sessionId,requestId,"merge",result); connection.commit(); return result;
        } catch(SQLException|RuntimeException e) { connection.rollback(); throw e; } finally { connection.setAutoCommit(true); }
    }
    /** Restore merged rows to their original neighbours; ordinary appended submissions split in place. */
    public synchronized JsonNode splitQueued(String sessionId,String jobId,String expectedPrompt,String requestId) throws SQLException {
        var previous=operationResult(sessionId,requestId,"split"); if(previous!=null)return previous;
        var queue=queued(sessionId); var job=queue.stream().filter(j->j.id().equals(jobId)).findFirst().orElseThrow(()->new IllegalStateException("Сообщение покинуло очередь"));
        if(!job.prompt().equals(expectedPrompt) || job.urgent()) throw new IllegalStateException("Сообщение изменилось или является приоритетной правкой");
        var parts=partsOf(jobId); if(parts.size()<2) throw new IllegalStateException("В сообщении одна исходная отправка");
        var snapshot=mergeSnapshot(jobId); var ids=new ArrayList<>(queue.stream().map(Job::id).toList()); var restored=new ArrayList<String>();
        connection.setAutoCommit(false);
        try {
            if(snapshot.path("entries").size()>1) {
                var originalOrder=new ArrayList<String>(); snapshot.path("order").forEach(n->originalOrder.add(n.asText()));
                int consumed=0;
                for(var entry:snapshot.path("entries")) {
                    if(consumed>=parts.size())break;
                    String id=entry.path("id").asText(); var chunks=new ArrayList<>(parts.subList(consumed,Math.min(parts.size(),consumed+entry.path("parts").size())));consumed+=chunks.size();
                    execute("UPDATE jobs SET status='QUEUED',prompt=?,prompt_parts=? WHERE id=? AND session_id=?",String.join(QUEUE_SEPARATOR,chunks),Json.MAPPER.valueToTree(chunks).toString(),id,sessionId);
                    execute("DELETE FROM queue_merges WHERE job_id=?",id);
                    if(entry.path("merge").isObject()) execute("INSERT INTO queue_merges(job_id,snapshot) VALUES(?,?)",id,entry.path("merge").toString());
                    if(!ids.contains(id)) { int position=-1,index=originalOrder.indexOf(id);
                        for(int k=index+1;k<originalOrder.size();k++) if(ids.contains(originalOrder.get(k))) { position=ids.indexOf(originalOrder.get(k)); break; }
                        if(position<0) { for(int k=index-1;k>=0;k--) if(ids.contains(originalOrder.get(k))) { position=ids.indexOf(originalOrder.get(k))+1; break; } }
                        ids.add(position<0?ids.indexOf(jobId)+restored.size():position,id);
                    }
                    restored.add(id);
                }
                int at=ids.indexOf(restored.getLast())+1;
                for(int i=consumed;i<parts.size();i++){String extra=enqueue(sessionId,null,parts.get(i));ids.add(at++,extra);restored.add(extra);}
            } else {
                updatePrompt(jobId,List.of(parts.getFirst())); execute("DELETE FROM queue_merges WHERE job_id=?",jobId); restored.add(jobId);
                int at=ids.indexOf(jobId)+1;
                for(int i=1;i<parts.size();i++) { String id=enqueue(sessionId,job.sourceId(),parts.get(i)); ids.add(at++,id); restored.add(id); }
            }
            renumber(sessionId,ids); var result=Json.object("jobIds",restored); rememberOperation(sessionId,requestId,"split",result); connection.commit(); return result;
        } catch(SQLException|RuntimeException e) { connection.rollback(); throw e; } finally { connection.setAutoCommit(true); }
    }
    public synchronized List<String> resultRecipients(Job job) throws SQLException {
        var recipients=new LinkedHashSet<String>(); collectRecipients(mergeSnapshot(job.id()),job.partsCount(),recipients); if(job.sourceId()!=null)recipients.add(job.sourceId()); return List.copyOf(recipients);
    }
    private void collectRecipients(JsonNode snapshot,int remaining,Set<String> recipients) {
        for(var entry:snapshot.path("entries")) { if(remaining<=0)break;int count=Math.min(remaining,entry.path("parts").size()); if(entry.hasNonNull("sourceId")) recipients.add(entry.path("sourceId").asText()); collectRecipients(entry.path("merge"),count,recipients);remaining-=count; }
    }
    public synchronized JsonNode priorityCorrection(String target,List<String> affected,String prompt,String requestId) throws SQLException {
        return priorityCorrection(target,affected,prompt,requestId,true);
    }
    public synchronized JsonNode priorityCorrection(String target,List<String> affected,String prompt,String requestId,boolean updateDesktopDraft) throws SQLException {
        var previous=operationResult(target,requestId,"interrupt"); if(previous!=null)return previous;
        connection.setAutoCommit(false);
        try {
            for(String id:affected) { pauseQueue(id,true); execute("UPDATE jobs SET urgent=0 WHERE session_id=? AND status='QUEUED'",id); }
            String job=enqueue(target,null,prompt); execute("UPDATE jobs SET urgent=1 WHERE id=?",job);
            execute("INSERT INTO queue_interruptions(job_id,affected) VALUES(?,?)",job,Json.MAPPER.valueToTree(affected).toString());
            if(updateDesktopDraft){draft(target,""); draftImages(target,Json.MAPPER.createArrayNode());}
            var result=Json.object("jobId",job,"affected",affected); rememberOperation(target,requestId,"interrupt",result); connection.commit(); return result;
        } catch(SQLException|RuntimeException e) { connection.rollback(); throw e; } finally { connection.setAutoCommit(true); }
    }
    public synchronized List<String> interruptionScope(String jobId) throws SQLException {
        try(var q=connection.prepareStatement("SELECT affected FROM queue_interruptions WHERE job_id=?")) {
            q.setString(1,jobId); try(var r=q.executeQuery()) { var result=new ArrayList<String>(); if(r.next()) parseStored(r.getString(1)).forEach(n->result.add(n.asText())); return result; }
        }
    }
    public synchronized String artifact(String sessionId, Path file, String title) throws Exception {
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO artifacts(id,session_id,path,title) VALUES(?,?,?,?)", id, sessionId, file.toRealPath().toString(), title);
        return id;
    }
    public synchronized List<JsonNode> artifacts(String sessionId) throws SQLException {
        var result = new ArrayList<JsonNode>();
        try (var q = connection.prepareStatement("SELECT id,path,title FROM artifacts WHERE session_id=? ORDER BY rowid DESC")) {
            q.setString(1, sessionId); try (var row = q.executeQuery()) { while (row.next()) result.add(Json.object("id", row.getString(1), "path", row.getString(2), "title", row.getString(3))); }
        }
        return result;
    }
    public synchronized void append(String sessionId, String role, String provider, String text) throws SQLException {
        execute("INSERT INTO messages(session_id,role,provider,text) VALUES(?,?,?,?)", sessionId, role, provider, text);
    }
    public synchronized long appendInput(String sessionId, String text) throws SQLException {
        boolean autonomous=autonomousInput(sessionId,text);append(sessionId, autonomous?"system":"user", autonomous?"TETHIUM":"USER", text); return lastSequence(sessionId);
    }
    public synchronized List<Message> messages(String sessionId, long after, int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("History limit must be 1..1000");
        var result = new ArrayList<Message>();
        try (var q = connection.prepareStatement("SELECT sequence,role,provider,text FROM messages WHERE session_id=? AND sequence>? ORDER BY sequence LIMIT ?")) {
            q.setString(1, sessionId); q.setLong(2, after); q.setInt(3, limit);
            try (var rows = q.executeQuery()) { while (rows.next()) result.add(new Message(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4))); }
        }
        return List.copyOf(result);
    }
    public synchronized long lastSequence(String sessionId) throws SQLException {
        try (var q = connection.prepareStatement("SELECT COALESCE(MAX(sequence),0) FROM messages WHERE session_id=?")) {
            q.setString(1, sessionId);
            try (var row = q.executeQuery()) { row.next(); return row.getLong(1); }
        }
    }
    public synchronized Binding binding(String sessionId, Provider provider) throws SQLException {
        try (var q = connection.prepareStatement("SELECT conversation_id,last_sequence FROM bindings WHERE session_id=? AND provider=?")) {
            q.setString(1, sessionId); q.setString(2, provider.name());
            try (var row = q.executeQuery()) { return row.next() ? new Binding(row.getString(1), row.getLong(2)) : new Binding(null, 0); }
        }
    }
    public synchronized void bind(String sessionId, Provider provider, String conversationId, long lastSequence) throws SQLException {
        execute("INSERT INTO bindings(session_id,provider,conversation_id,last_sequence) VALUES(?,?,?,?) ON CONFLICT(session_id,provider) DO UPDATE SET conversation_id=excluded.conversation_id,last_sequence=excluded.last_sequence", sessionId, provider.name(), conversationId, lastSequence);
    }
    private void execute(String sql, Object... values) throws SQLException {
        try (var q = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
            q.executeUpdate();
        }
    }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
