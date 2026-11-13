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
    public record Message(long sequence, String role, String provider, String text,long timestamp) {
        public Message(long sequence,String role,String provider,String text){this(sequence,role,provider,text,0);}
    }
    public record Binding(String conversationId, long lastSequence) {}
    public record Project(String id, String root, String title) {}
    public record ProjectGroup(String id, String title, int position, boolean collapsed) {}
    public record ProjectParent(String kind, String id) {}
    public record Job(String id, String sessionId, String sourceId, String prompt, int partsCount, boolean urgent) {}
    public record ImportedMessage(String sourceId, String role, String text,long timestamp) {
        public ImportedMessage(String sourceId,String role,String text){this(sourceId,role,text,0);}
    }
    public record WorkerRun(String runId, String sessionId, String jobId, long deliveredThrough, JsonNode spec, long cursor) {}
    public record FailedWorkerRun(String runId, String sessionId, String jobId) {}
    private final Connection connection;
    /** For tables kept next to the store's (core.Reminders): used only while holding this store's lock, so they join its transactions. */
    Connection sharedConnection(){return connection;}

    public SessionStore(Path database) throws Exception {
        Files.createDirectories(database.toAbsolutePath().getParent());
        // Transactions take the write lock at BEGIN. A deferred one reads first and, if the usage ledger's own connection wrote in
        // between, fails to upgrade with SQLITE_BUSY at once: WAL cannot wait there, so busy_timeout never helps.
        var config = new org.sqlite.SQLiteConfig(); config.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties());
        try (var s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=15000");
            s.execute("CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY, root TEXT NOT NULL UNIQUE)");
            s.execute("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), parent_id TEXT REFERENCES sessions(id), title TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, directory TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS messages(sequence INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id), role TEXT NOT NULL, provider TEXT NOT NULL, text TEXT NOT NULL)");
            boolean hasTimestamp=false;try(var columns=s.executeQuery("PRAGMA table_info(messages)")){while(columns.next())hasTimestamp|=columns.getString("name").equals("created_at");}
            if(!hasTimestamp)s.execute("ALTER TABLE messages ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0");
            s.execute("CREATE TABLE IF NOT EXISTS bindings(session_id TEXT NOT NULL REFERENCES sessions(id), provider TEXT NOT NULL, conversation_id TEXT NOT NULL, last_sequence INTEGER NOT NULL, PRIMARY KEY(session_id,provider))");
            s.execute("CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), provider TEXT NOT NULL, status TEXT NOT NULL)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_running_turn ON runs(session_id) WHERE status='RUNNING'");
            s.execute("CREATE TABLE IF NOT EXISTS preferences(session_id TEXT PRIMARY KEY REFERENCES sessions(id), model TEXT, access TEXT NOT NULL DEFAULT 'danger-full-access', draft TEXT NOT NULL DEFAULT '')");
            s.execute("UPDATE preferences SET access='danger-full-access' WHERE access<>'danger-full-access'");
            s.execute("CREATE TABLE IF NOT EXISTS run_progress(run_id TEXT PRIMARY KEY REFERENCES runs(id), session_id TEXT NOT NULL, output TEXT NOT NULL DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS project_collapsed(project_id TEXT PRIMARY KEY)");
            s.execute("CREATE TABLE IF NOT EXISTS cascade_pauses(session_id TEXT PRIMARY KEY,origin TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS events(sequence INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id), run_id TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), source_id TEXT REFERENCES sessions(id), prompt TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'QUEUED')");
            s.execute("CREATE TABLE IF NOT EXISTS queue_state(session_id TEXT PRIMARY KEY REFERENCES sessions(id), paused INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS artifacts(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), path TEXT NOT NULL, title TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS imports(source_key TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id))");
            s.execute("CREATE TABLE IF NOT EXISTS imported_messages(source_key TEXT NOT NULL REFERENCES imports(source_key), source_message_id TEXT NOT NULL, PRIMARY KEY(source_key,source_message_id))");
            boolean hasEffort = false, hasImages = false, hasProvider = false;
            try (var columns = s.executeQuery("PRAGMA table_info(preferences)")) {
                while (columns.next()) { if (columns.getString("name").equals("reasoning_effort")) hasEffort = true; if (columns.getString("name").equals("draft_images")) hasImages = true; if (columns.getString("name").equals("provider")) hasProvider = true; }
            }
            if (!hasEffort) s.execute("ALTER TABLE preferences ADD COLUMN reasoning_effort TEXT");
            if (!hasImages) s.execute("ALTER TABLE preferences ADD COLUMN draft_images TEXT NOT NULL DEFAULT '[]'");
            if (!hasProvider) s.execute("ALTER TABLE preferences ADD COLUMN provider TEXT");
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
            s.execute("CREATE TABLE IF NOT EXISTS run_messages(run_id TEXT PRIMARY KEY,session_id TEXT NOT NULL,input_sequence INTEGER NOT NULL DEFAULT 0,result_sequence INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS media_jobs(event_id INTEGER PRIMARY KEY,run_id TEXT NOT NULL,session_id TEXT NOT NULL,item_id TEXT NOT NULL,output_offset INTEGER NOT NULL,input_sequence INTEGER NOT NULL,payload TEXT NOT NULL,done INTEGER NOT NULL DEFAULT 0,UNIQUE(run_id,item_id))");
            s.execute("CREATE TABLE IF NOT EXISTS session_media(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL,run_id TEXT NOT NULL,item_id TEXT NOT NULL,output_offset INTEGER NOT NULL,input_sequence INTEGER NOT NULL,path TEXT NOT NULL,thumbnail TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,title TEXT NOT NULL,UNIQUE(run_id,item_id))");
            s.execute("CREATE INDEX IF NOT EXISTS media_session ON session_media(session_id,id)");
            s.execute("CREATE TABLE IF NOT EXISTS media_migrations(name TEXT PRIMARY KEY)");
            s.execute("CREATE TABLE IF NOT EXISTS session_marks(session_id TEXT PRIMARY KEY REFERENCES sessions(id),marked INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS cpu_claims(process_key TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),pid INTEGER NOT NULL,started INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS cpu_controls(scope TEXT NOT NULL,target TEXT NOT NULL,settings TEXT NOT NULL,PRIMARY KEY(scope,target))");
            s.execute("CREATE TABLE IF NOT EXISTS session_seen(session_id TEXT PRIMARY KEY REFERENCES sessions(id), last_run INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS worker_runs(run_id TEXT PRIMARY KEY REFERENCES runs(id), session_id TEXT NOT NULL, job_id TEXT NOT NULL REFERENCES jobs(id), delivered_through INTEGER NOT NULL, spec TEXT NOT NULL, cursor INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS worker_replies(run_id TEXT NOT NULL REFERENCES worker_runs(run_id), sequence INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,sequence))");
            s.execute("CREATE TABLE IF NOT EXISTS project_files(id TEXT PRIMARY KEY,project_id TEXT NOT NULL REFERENCES projects(id),path TEXT NOT NULL,title TEXT NOT NULL,UNIQUE(project_id,path))");
            s.execute("CREATE INDEX IF NOT EXISTS messages_session_sequence ON messages(session_id,sequence)");
            s.execute("CREATE TABLE IF NOT EXISTS project_visibility(project_id TEXT PRIMARY KEY REFERENCES projects(id),active INTEGER NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE IF NOT EXISTS session_trash(session_id TEXT PRIMARY KEY REFERENCES sessions(id),deleted_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS session_files(session_id TEXT PRIMARY KEY REFERENCES sessions(id),path TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS session_autonomy(session_id TEXT PRIMARY KEY REFERENCES sessions(id),goal_id TEXT NOT NULL,goal TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS autonomy_jobs(job_id TEXT PRIMARY KEY REFERENCES jobs(id),goal_id TEXT NOT NULL,trigger_id TEXT NOT NULL,UNIQUE(goal_id,trigger_id))");
            s.execute("CREATE TABLE IF NOT EXISTS session_schedules(session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,schedule_id TEXT NOT NULL,state TEXT NOT NULL,interval_seconds INTEGER NOT NULL,prompt TEXT NOT NULL,next_at INTEGER NOT NULL,last_at INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS schedule_jobs(job_id TEXT PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,schedule_id TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS compaction_notices(run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,item_id TEXT NOT NULL,phase TEXT NOT NULL,PRIMARY KEY(run_id,item_id,phase))");
            s.execute("CREATE TABLE IF NOT EXISTS compaction_messages(run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,item_id TEXT NOT NULL,message_sequence INTEGER NOT NULL REFERENCES messages(sequence) ON DELETE CASCADE,started_at INTEGER NOT NULL,PRIMARY KEY(run_id,item_id))");
            s.execute("CREATE TABLE IF NOT EXISTS message_revisions(session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,revision INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS session_updates(target_session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,update_key TEXT NOT NULL,message_sequence INTEGER NOT NULL REFERENCES messages(sequence) ON DELETE CASCADE,PRIMARY KEY(target_session_id,update_key))");
            s.execute("CREATE TABLE IF NOT EXISTS project_cross_project(project_id TEXT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,enabled INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS session_technical(session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,enabled INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS backlog_tasks(id TEXT PRIMARY KEY,project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,title TEXT NOT NULL,prompt TEXT NOT NULL,priority INTEGER NOT NULL,min_quota INTEGER,min_free_cpu INTEGER,target TEXT NOT NULL,session_id TEXT,session_title TEXT,source_id TEXT,state TEXT NOT NULL,job_id TEXT,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS backlog_execution(task_id TEXT PRIMARY KEY REFERENCES backlog_tasks(id) ON DELETE CASCADE,auto_switch INTEGER NOT NULL DEFAULT 1,provider TEXT,model TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS job_execution(job_id TEXT PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,provider TEXT NOT NULL,model TEXT,effort TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS backlog_settings(id INTEGER PRIMARY KEY CHECK(id=1),max_running INTEGER NOT NULL,min_quota INTEGER NOT NULL,min_free_cpu INTEGER NOT NULL,paused INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS project_order(project_id TEXT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,position INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS session_order(session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,position INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS project_groups(id TEXT PRIMARY KEY,title TEXT NOT NULL,position INTEGER NOT NULL,collapsed INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS project_hierarchy(project_id TEXT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,parent_kind TEXT NOT NULL,parent_id TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS project_tree_order(node_key TEXT PRIMARY KEY,position INTEGER NOT NULL)");
            // Position stays nullable so the previous core can still insert favorites after a rollback.
            s.execute("CREATE TABLE IF NOT EXISTS project_favorites(project_id TEXT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,session_id TEXT REFERENCES sessions(id) ON DELETE CASCADE,position INTEGER)");
            boolean favoriteSession=false,favoritePosition=false;try(var columns=s.executeQuery("PRAGMA table_info(project_favorites)")){while(columns.next()){favoriteSession|=columns.getString("name").equals("session_id");favoritePosition|=columns.getString("name").equals("position");}}
            if(!favoriteSession)s.execute("ALTER TABLE project_favorites ADD COLUMN session_id TEXT REFERENCES sessions(id) ON DELETE CASCADE");
            if(!favoritePosition)s.execute("ALTER TABLE project_favorites ADD COLUMN position INTEGER");
            s.execute("UPDATE project_favorites SET position=rowid WHERE position IS NULL");
        }
    }

    /** Tasks put off for better times, most important first; the rules for starting them are in host.Backlog. */
    public synchronized List<JsonNode> backlog()throws SQLException{
        var rows=new ArrayList<JsonNode>();
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT b.*,COALESCE(e.auto_switch,1) auto_switch,e.provider pinned_provider,e.model pinned_model FROM backlog_tasks b LEFT JOIN backlog_execution e ON e.task_id=b.id ORDER BY b.priority,b.created_at,b.rowid")){while(r.next()){
            Integer quota=r.getInt("min_quota");if(r.wasNull())quota=null;Integer cpu=r.getInt("min_free_cpu");if(r.wasNull())cpu=null;
            rows.add(Json.object("id",r.getString("id"),"projectId",r.getString("project_id"),"title",r.getString("title"),"prompt",r.getString("prompt"),"priority",switch(r.getInt("priority")){case 0->"high";case 2->"low";default->"normal";},"minQuota",quota,"minFreeCpu",cpu,"target",r.getString("target"),"sessionId",r.getString("session_id"),"sessionTitle",r.getString("session_title"),"sourceId",r.getString("source_id"),"state",r.getString("state"),"jobId",r.getString("job_id"),"createdAt",r.getLong("created_at"),"autoSwitch",r.getBoolean("auto_switch"),"pinnedProvider",r.getString("pinned_provider"),"pinnedModel",r.getString("pinned_model")));
        }}
        return rows;
    }
    public synchronized String backlogAdd(String project,String title,String prompt,int priority,Integer minQuota,Integer minFreeCpu,String target,String session,String sessionTitle,String source)throws SQLException{
        String id=UUID.randomUUID().toString();
        execute("INSERT INTO backlog_tasks(id,project_id,title,prompt,priority,min_quota,min_free_cpu,target,session_id,session_title,source_id,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'waiting',?)",id,project,title,prompt,priority,minQuota,minFreeCpu,target,session,sessionTitle,source,System.currentTimeMillis());
        return id;
    }
    public synchronized void backlogUpdate(String id,String title,String prompt,int priority,Integer minQuota,Integer minFreeCpu,String target,String session,String sessionTitle)throws SQLException{
        execute("UPDATE backlog_tasks SET title=?,prompt=?,priority=?,min_quota=?,min_free_cpu=?,target=?,session_id=?,session_title=?,state='waiting',job_id=NULL WHERE id=?",title,prompt,priority,minQuota,minFreeCpu,target,session,sessionTitle,id);
    }
    /** From here on the task belongs to this session, so a repeat after a failure goes on where it stopped. */
    public synchronized void backlogStarted(String id,String session,String job)throws SQLException{execute("UPDATE backlog_tasks SET state='running',target='session',session_id=?,job_id=? WHERE id=?",session,job,id);}
    public synchronized void backlogFailed(String id)throws SQLException{execute("UPDATE backlog_tasks SET state='failed',job_id=NULL WHERE id=?",id);}
    public synchronized void backlogRemove(String id)throws SQLException{execute("DELETE FROM backlog_tasks WHERE id=?",id);}
    public synchronized Map<String,Integer> backlogWaiting()throws SQLException{
        var counts=new HashMap<String,Integer>();
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT project_id,COUNT(*) FROM backlog_tasks WHERE state<>'running' GROUP BY 1")){while(r.next())counts.put(r.getString(1),r.getInt(2));}
        return counts;
    }
    /** Tasks that will run in this session: named directly, or shared under its title. */
    public synchronized int backlogWaiting(Session session)throws SQLException{
        try(var q=connection.prepareStatement("SELECT COUNT(*) FROM backlog_tasks WHERE state<>'running' AND project_id=? AND ((target='session' AND session_id=?) OR (target='named' AND session_title=?))")){
            q.setString(1,session.projectId());q.setString(2,session.id());q.setString(3,session.title());try(var r=q.executeQuery()){return r.next()?r.getInt(1):0;}
        }
    }
    public synchronized JsonNode backlogSettings()throws SQLException{
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT * FROM backlog_settings WHERE id=1")){
            if(r.next())return Json.object("maxRunning",r.getInt("max_running"),"minQuota",r.getInt("min_quota"),"minFreeCpu",r.getInt("min_free_cpu"),"paused",r.getBoolean("paused"));
        }
        return Json.object("maxRunning",3,"minQuota",80,"minFreeCpu",40,"paused",false);
    }
    public synchronized void backlogSettings(int maxRunning,int minQuota,int minFreeCpu,boolean paused)throws SQLException{
        execute("INSERT INTO backlog_settings VALUES(1,?,?,?,?) ON CONFLICT(id) DO UPDATE SET max_running=excluded.max_running,min_quota=excluded.min_quota,min_free_cpu=excluded.min_free_cpu,paused=excluded.paused",maxRunning,minQuota,minFreeCpu,paused);
    }
    public synchronized void backlogExecution(String id,boolean autoSwitch,String provider,String model)throws SQLException{
        execute("INSERT INTO backlog_execution VALUES(?,?,?,?) ON CONFLICT(task_id) DO UPDATE SET auto_switch=excluded.auto_switch,provider=excluded.provider,model=excluded.model",id,autoSwitch,provider,model);
    }
    public synchronized void jobExecution(String job,String provider,String model,String effort)throws SQLException{
        execute("INSERT INTO job_execution VALUES(?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET provider=excluded.provider,model=excluded.model,effort=excluded.effort",job,provider,model,effort);
    }
    /** A deferred task may explicitly prohibit substitution, or pin one executor. Ordinary jobs may always fail over. */
    public synchronized boolean executorFailoverAllowed(String job)throws SQLException{
        try(var q=connection.prepareStatement("SELECT COALESCE(e.auto_switch,1),e.provider FROM backlog_tasks b LEFT JOIN backlog_execution e ON e.task_id=b.id WHERE b.job_id=?")){
            q.setString(1,job);try(var r=q.executeQuery()){return !r.next()||(r.getBoolean(1)&&(r.getString(2)==null||r.getString(2).isBlank()));}
        }
    }
    public synchronized boolean backlogJob(String job)throws SQLException{
        try(var q=connection.prepareStatement("SELECT 1 FROM backlog_tasks WHERE job_id=?")){q.setString(1,job);try(var r=q.executeQuery()){return r.next();}}
    }
    /** A deferred task's choice belongs to its job, never to the user's session preferences. */
    public synchronized JsonNode executionPreferences(String session,String job)throws SQLException{
        var prefs=(com.fasterxml.jackson.databind.node.ObjectNode)preferences(session).deepCopy();
        try(var q=connection.prepareStatement("SELECT * FROM job_execution WHERE job_id=?")){q.setString(1,job);try(var r=q.executeQuery()){if(r.next()){prefs.put("provider",r.getString("provider"));prefs.put("model",r.getString("model"));prefs.put("reasoningEffort",r.getString("effort"));}}}
        return prefs;
    }
    public synchronized String jobState(String id)throws SQLException{
        try(var q=connection.prepareStatement("SELECT status FROM jobs WHERE id=?")){q.setString(1,id);try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    public synchronized boolean has(String session)throws SQLException{
        try(var q=connection.prepareStatement("SELECT 1 FROM sessions WHERE id=?")){q.setString(1,session);try(var r=q.executeQuery()){return r.next();}}
    }
    /** A technical session serves a program: it lights no indicators, never waits for a person, and works even when listed as inactive. */
    public synchronized boolean technical(String id)throws SQLException{
        try(var q=connection.prepareStatement("SELECT enabled FROM session_technical WHERE session_id=?")){q.setString(1,id);try(var r=q.executeQuery()){return r.next()&&r.getBoolean(1);}}
    }
    public synchronized void technical(String id,boolean enabled)throws SQLException{
        get(id);execute("INSERT INTO session_technical VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET enabled=excluded.enabled",id,enabled);
    }
    public synchronized boolean acceptsWork(String id)throws SQLException{return get(id).active()||technical(id);}
    /** Whether this project's sessions may queue work in other projects. Off unless the user turned it on for the project. */
    public synchronized boolean crossProject(String id)throws SQLException{
        try(var q=connection.prepareStatement("SELECT enabled FROM project_cross_project WHERE project_id=?")){q.setString(1,id);try(var r=q.executeQuery()){return r.next()&&r.getBoolean(1);}}
    }
    public synchronized void crossProject(String id,boolean enabled)throws SQLException{
        execute("INSERT INTO project_cross_project VALUES(?,?) ON CONFLICT(project_id) DO UPDATE SET enabled=excluded.enabled",id,enabled);
    }
    /** Projects folded in the sidebar, shared by the window, Remote and the phone. */
    public synchronized Set<String> collapsedProjects()throws SQLException{var result=new HashSet<String>();try(var q=connection.createStatement();var rows=q.executeQuery("SELECT project_id FROM project_collapsed")){while(rows.next())result.add(rows.getString(1));}return result;}
    public synchronized void projectCollapsed(String id,boolean collapsed)throws SQLException{if(collapsed)execute("INSERT OR IGNORE INTO project_collapsed VALUES(?)",id);else execute("DELETE FROM project_collapsed WHERE project_id=?",id);}
    /** Whether the session's messages, or the answer being written, name the file by its full path (with either slash). */
    public synchronized boolean mentions(String session,Path file)throws SQLException{
        String back=file.toAbsolutePath().normalize().toString(),forward=back.replace('\\','/');
        try(var q=connection.prepareStatement("SELECT 1 FROM messages WHERE session_id=? AND (instr(lower(text),lower(?))>0 OR instr(lower(text),lower(?))>0) UNION ALL SELECT 1 FROM run_progress WHERE session_id=? AND (instr(lower(output),lower(?))>0 OR instr(lower(output),lower(?))>0) LIMIT 1")){
            q.setString(1,session);q.setString(2,back);q.setString(3,forward);q.setString(4,session);q.setString(5,back);q.setString(6,forward);
            try(var r=q.executeQuery()){return r.next();}
        }
    }
    public synchronized boolean projectActive(String id)throws SQLException{
        try(var q=connection.prepareStatement("SELECT active FROM project_visibility WHERE project_id=?")){q.setString(1,id);try(var r=q.executeQuery()){return !r.next()||r.getBoolean(1);}}
    }
    public synchronized void projectActive(String id,boolean active)throws SQLException{
        execute("INSERT INTO project_visibility VALUES(?,?) ON CONFLICT(project_id) DO UPDATE SET active=excluded.active",id,active);
    }
    public synchronized long trashedAt(String id)throws SQLException{
        try(var q=connection.prepareStatement("SELECT deleted_at FROM session_trash WHERE session_id=?")){q.setString(1,id);try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}
    }
    public synchronized void trash(String id,boolean hidden)throws SQLException{
        get(id);if(hidden)execute("INSERT OR IGNORE INTO session_trash VALUES(?,?)",id,System.currentTimeMillis());else execute("DELETE FROM session_trash WHERE session_id=?",id);
    }
    /** Remove only Tethium-owned metadata. Project files and other sessions remain intact. */
    public synchronized List<String> purge(String id)throws Exception{
        if(trashedAt(id)==0)throw new IllegalStateException("Сначала переместите сессию в корзину");
        try(var q=connection.prepareStatement("SELECT 1 FROM runs WHERE session_id=? AND status='RUNNING'")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next())throw new IllegalStateException("Сначала остановите работу сессии");}}
        return transaction(()->{
            var runs=new ArrayList<String>();try(var q=connection.prepareStatement("SELECT id FROM runs WHERE session_id=?")){q.setString(1,id);try(var r=q.executeQuery()){while(r.next())runs.add(r.getString(1));}}
            execute("UPDATE sessions SET parent_id=NULL WHERE parent_id=?",id);execute("UPDATE jobs SET source_id=NULL WHERE source_id=?",id);
            execute("DELETE FROM worker_replies WHERE run_id IN (SELECT run_id FROM worker_runs WHERE session_id=?)",id);
            execute("DELETE FROM worker_runs WHERE session_id=?",id);
            execute("DELETE FROM backlog_tasks WHERE session_id=? AND state='running'",id);execute("UPDATE backlog_tasks SET target='new',session_id=NULL WHERE session_id=?",id);execute("UPDATE backlog_tasks SET source_id=NULL WHERE source_id=?",id);
            for(String table:List.of("autonomy_jobs","queue_merges","queue_interruptions"))execute("DELETE FROM "+table+" WHERE job_id IN (SELECT id FROM jobs WHERE session_id=?)",id);
            execute("DELETE FROM imported_messages WHERE source_key IN (SELECT source_key FROM imports WHERE session_id=?)",id);
            for(String table:List.of("imports","messages","run_messages","media_jobs","session_media","bindings","run_progress","events","runs","jobs","preferences","queue_state","queue_returns","queue_operations","session_seen","session_marks","cpu_claims","session_files","session_autonomy","artifacts","session_trash"))execute("DELETE FROM "+table+" WHERE session_id=?",id);
            for(String table:List.of("token_totals","cpu_totals","quota_wait")){try(var q=connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){q.setString(1,table);try(var r=q.executeQuery()){if(r.next())execute("DELETE FROM "+table+" WHERE session_id=?",id);}}}
            execute("DELETE FROM sessions WHERE id=?",id);historyIndexes.remove(id);return runs;
        });
    }
    public static final int HISTORY_CHUNK=8000;
    private record HistoryIndex(long last,long revision,List<JsonNode> entries){}
    private final Map<String,HistoryIndex> historyIndexes=new LinkedHashMap<>();
    /** Piece boundaries of long messages, kept while the message keeps its length: an index rebuild re-reads only new ones. */
    private final LinkedHashMap<String,List<int[]>> pieceCache=new LinkedHashMap<>(256,.75f,true);
    private List<int[]> pieces(String session,long sequence,int length,long revision)throws SQLException{
        String key=session+":"+sequence+":"+length+":"+revision;var cached=pieceCache.get(key);if(cached!=null)return cached;
        var computed=HistoryChunks.split(historyText(session,sequence),HISTORY_CHUNK);pieceCache.put(key,computed);
        while(pieceCache.size()>4000)pieceCache.remove(pieceCache.keySet().iterator().next());
        return computed;
    }
    public synchronized List<JsonNode> historyIndex(String id)throws SQLException{
        long last=lastSequence(id),revision=messageRevision(id);var saved=historyIndexes.get(id);if(saved!=null&&saved.last==last&&saved.revision==revision)return saved.entries;
        var entries=new ArrayList<JsonNode>();
        try(var q=connection.prepareStatement("SELECT sequence,role,provider,length(text),substr(text,1,320),length(text)-length(replace(text,char(10),'')),created_at FROM messages WHERE session_id=? ORDER BY sequence")){
            q.setString(1,id);try(var r=q.executeQuery()){while(r.next()){
                int length=r.getInt(4);var pieces=length>HISTORY_CHUNK?pieces(id,r.getLong(1),length,revision):List.of(new int[]{0,length});
                for(var piece:pieces){int offset=piece[0],count=piece[1];entries.add(Json.object("sequence",r.getLong(1),"role",r.getString(2),"provider",r.getString(3),"timestamp",r.getLong(7),"revision",revision,"offset",offset,"length",count,"lines",Math.max(1,(int)((long)r.getInt(6)*count/Math.max(1,length))),"title",offset==0&&r.getString(2).equals("user")?r.getString(5):""));}
            }}
        }
        if(historyIndexes.size()>=16)historyIndexes.remove(historyIndexes.keySet().iterator().next());historyIndexes.put(id,new HistoryIndex(last,revision,List.copyOf(entries)));return List.copyOf(entries);
    }
    public synchronized long messageRevision(String id)throws SQLException{
        try(var q=connection.prepareStatement("SELECT revision FROM message_revisions WHERE session_id=?")){q.setString(1,id);try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}
    }
    public synchronized String historyChunk(String id,long sequence,int offset,int count)throws SQLException{
        if(offset<0||count<0||count>HISTORY_CHUNK)throw new IllegalArgumentException("Некорректная часть истории");
        String text=historyText(id,sequence);
        // A whole short message needs no repair; a piece of a long one gets its code and thought blocks closed and reopened.
        return offset==0&&count>=text.codePointCount(0,text.length())?text:HistoryChunks.piece(text,offset,count);
    }
    private String historyText(String id,long sequence)throws SQLException{
        try(var q=connection.prepareStatement("SELECT text FROM messages WHERE session_id=? AND sequence=?")){q.setString(1,id);q.setLong(2,sequence);try(var r=q.executeQuery()){return r.next()?r.getString(1):"";}}
    }

    public record ProjectFile(String id,String projectId,String path,String title){}
    public synchronized List<ProjectFile> projectFiles()throws SQLException{
        var result=new ArrayList<ProjectFile>();try(var q=connection.createStatement();var r=q.executeQuery("SELECT * FROM project_files ORDER BY rowid")){while(r.next())result.add(new ProjectFile(r.getString("id"),r.getString("project_id"),r.getString("path"),r.getString("title")));}return result;
    }
    public synchronized ProjectFile projectFile(String id)throws SQLException{return projectFiles().stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Закреплённый файл не найден"));}
    public synchronized ProjectFile pinProjectFile(String project,String path,String title)throws SQLException{
        String id=UUID.randomUUID().toString();execute("INSERT INTO project_files VALUES(?,?,?,?) ON CONFLICT(project_id,path) DO UPDATE SET title=excluded.title",id,project,path,title);
        return projectFiles().stream().filter(f->f.projectId().equals(project)&&f.path().equals(path)).findFirst().orElseThrow();
    }
    public synchronized void renameProjectFile(String id,String title)throws SQLException{if(title==null||title.isBlank())throw new IllegalArgumentException("Укажите название");projectFile(id);execute("UPDATE project_files SET title=? WHERE id=?",title.strip(),id);}
    public synchronized void removeProjectFile(String id)throws SQLException{execute("DELETE FROM project_files WHERE id=?",id);}
    public synchronized String pinnedFile(String session)throws SQLException{try(var q=connection.prepareStatement("SELECT path FROM session_files WHERE session_id=?")){q.setString(1,session);try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}}
    public synchronized void pinnedFile(String session,String path)throws SQLException{get(session);if(path==null||path.isBlank())execute("DELETE FROM session_files WHERE session_id=?",session);else execute("INSERT INTO session_files VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET path=excluded.path",session,path);}

    public synchronized boolean autonomousWorkPending()throws SQLException{
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT 1 FROM session_schedules t JOIN sessions s ON s.id=t.session_id LEFT JOIN queue_state q ON q.session_id=s.id WHERE s.active=1 AND t.state='active' AND COALESCE(q.paused,0)=0 LIMIT 1")){if(r.next())return true;}
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT 1 FROM session_autonomy a JOIN sessions s ON s.id=a.session_id LEFT JOIN queue_state q ON q.session_id=s.id WHERE s.active=1 AND a.state IN ('active','waiting') AND COALESCE(q.paused,0)=0 LIMIT 1")){return r.next();}
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
                String was=previous.path("state").asText();
                if(was.equals("off"))return previous;
                // A goal that ended stays ended: Stop and a correction pause only what is going on, and only a pause can be resumed.
                if(action.equals("pause")&&!Set.of("active","waiting").contains(was)||action.equals("resume")&&!was.equals("paused"))return previous;
                if(!Set.of("pause","disable").contains(action)&&!Objects.equals(goalId,previous.path("goalId").asText()))throw new IllegalArgumentException("Цель уже изменилась, её текущий goalId: "+previous.path("goalId").asText());
                state=switch(action){case "complete"->"complete";case "blocked"->"blocked";case "pause"->"paused";case "disable"->"off";case "wait"->"waiting";case "resume"->"active";default->throw new IllegalArgumentException("Неизвестное действие автономии");};
                if(Set.of("complete","blocked","waiting").contains(state)&&(reason==null||reason.isBlank()))throw new IllegalArgumentException("Укажите результат или причину");
                if(state.equals("waiting")){try(var q=connection.prepareStatement("SELECT 1 FROM jobs WHERE source_id=? AND status IN ('QUEUED','RUNNING') LIMIT 1")){q.setString(1,id);try(var r=q.executeQuery()){if(!r.next())state="active";}}}
                execute("UPDATE session_autonomy SET state=?,reason=?,updated_at=? WHERE session_id=?",state,reason==null?"":reason,System.currentTimeMillis(),id);if(action.equals("resume"))execute("UPDATE session_autonomy SET goal_id=? WHERE session_id=?",UUID.randomUUID().toString(),id);if(!state.equals("active"))discardAutonomousQueue(id);
            }
            // Explicit activation must also release the queue pause left by a correction or Stop.
            if(action.equals("start")||action.equals("resume"))pauseQueue(id,false);
            return autonomy(id);
        });
    }
    public synchronized void discardAutonomousQueue(String id)throws SQLException{execute("UPDATE jobs SET status='CANCELLED' WHERE session_id=? AND status='QUEUED' AND id IN (SELECT job_id FROM autonomy_jobs)",id);}
    public synchronized void ensureAutonomousContinuation(String id)throws Exception{
        if(periodicSchedule(id).path("state").asText().equals("active"))return;
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
    /** A quota substitution continues the same goal; it must only undo the host's generic executor-failure block. */
    public synchronized void recoverAutonomyFromExecutorFailure(String id)throws SQLException{
        execute("UPDATE session_autonomy SET state='active',reason='',updated_at=? WHERE session_id=? AND state='blocked' AND reason LIKE 'Запуск завершился с ошибкой:%'",System.currentTimeMillis(),id);
    }

    public synchronized JsonNode periodicSchedule(String id)throws SQLException{
        try(var q=connection.prepareStatement("SELECT * FROM session_schedules WHERE session_id=?")){
            q.setString(1,id);try(var r=q.executeQuery()){return r.next()?Json.object("state",r.getString("state"),"scheduleId",r.getString("schedule_id"),"intervalSeconds",r.getLong("interval_seconds"),"prompt",r.getString("prompt"),"nextAt",r.getLong("next_at"),"lastAt",r.getLong("last_at")):Json.object("state","off","intervalSeconds",300,"prompt","","nextAt",0,"lastAt",0);}
        }
    }
    public synchronized JsonNode periodicSchedule(String id,String action,long seconds,String prompt,long now)throws Exception{
        return transaction(()->{
            get(id);var previous=periodicSchedule(id);
            switch(action){
                case "status" -> {return previous;}
                case "set" -> {
                    if(seconds<1||seconds>SessionSchedule.MAX_INTERVAL_SECONDS)throw new IllegalArgumentException("Интервал должен быть от 1 секунды до 365 дней");
                    if(prompt==null||prompt.isBlank()||prompt.length()>16000)throw new IllegalArgumentException("Укажите периодическую задачу (до 16000 символов)");
                    discardScheduledQueue(id);
                    execute("INSERT INTO session_schedules VALUES(?,?,'active',?,?,?,0) ON CONFLICT(session_id) DO UPDATE SET schedule_id=excluded.schedule_id,state='active',interval_seconds=excluded.interval_seconds,prompt=excluded.prompt,next_at=excluded.next_at,last_at=0",id,UUID.randomUUID().toString(),seconds,prompt.strip(),Math.addExact(now,seconds*1000));
                    autonomy(id,"disable","","","");pauseQueue(id,false);
                }
                case "resume" -> {
                    if(!previous.has("scheduleId"))throw new IllegalArgumentException("Сначала задайте расписание");
                    execute("UPDATE session_schedules SET state='active',next_at=? WHERE session_id=?",Math.addExact(now,previous.path("intervalSeconds").asLong()*1000),id);
                    autonomy(id,"disable","","","");pauseQueue(id,false);
                }
                case "pause", "disable" -> {
                    if(action.equals("pause")&&!previous.path("state").asText().equals("active"))return previous;
                    if(!previous.path("state").asText().equals("off"))autonomy(id,action.equals("pause")?"pause":"disable","","","Периодическая задача приостановлена");
                    execute("UPDATE session_schedules SET state=? WHERE session_id=?",action.equals("pause")?"paused":"off",id);discardScheduledQueue(id);
                }
                default -> throw new IllegalArgumentException("Неизвестное действие расписания");
            }
            return periodicSchedule(id);
        });
    }
    private void discardScheduledQueue(String id)throws SQLException{
        execute("UPDATE jobs SET status='CANCELLED' WHERE session_id=? AND status='QUEUED' AND urgent=0 AND id IN (SELECT job_id FROM schedule_jobs)",id);
    }
    public synchronized List<String> dueSchedules(long now)throws SQLException{
        var ids=new ArrayList<String>();try(var q=connection.prepareStatement("SELECT session_id FROM session_schedules WHERE state='active' AND next_at<=?")){q.setLong(1,now);try(var r=q.executeQuery()){while(r.next())ids.add(r.getString(1));}}return ids;
    }
    /** Advance the durable deadline and enqueue exactly one wake in the same transaction. */
    public synchronized boolean enqueueScheduled(String id,long now)throws Exception{
        return transaction(()->{
            var timer=periodicSchedule(id);
            if(!timer.path("state").asText().equals("active")||timer.path("nextAt").asLong()>now||!get(id).active()||trashedAt(id)>0||queuePaused(id))return false;
            try(var q=connection.prepareStatement("SELECT 1 FROM jobs WHERE session_id=? AND status IN ('QUEUED','RUNNING') UNION ALL SELECT 1 FROM runs WHERE session_id=? AND status='RUNNING' LIMIT 1")){
                q.setString(1,id);q.setString(2,id);try(var r=q.executeQuery()){if(r.next())return false;}
            }
            String job=enqueue(id,null,SessionSchedule.prompt(timer));
            execute("INSERT INTO schedule_jobs VALUES(?,?)",job,timer.path("scheduleId").asText());
            // Missed intervals coalesce into one pass; never replay a backlog after restart.
            execute("UPDATE session_schedules SET last_at=?,next_at=? WHERE session_id=?",now,Math.addExact(now,timer.path("intervalSeconds").asLong()*1000),id);
            return true;
        });
    }
    public synchronized boolean scheduledInput(String id,String prompt)throws SQLException{
        try(var q=connection.prepareStatement("SELECT 1 FROM jobs JOIN schedule_jobs ON job_id=jobs.id WHERE session_id=? AND prompt=? AND status IN ('QUEUED','RUNNING')")){
            q.setString(1,id);q.setString(2,prompt);try(var r=q.executeQuery()){return r.next();}
        }
    }

    public synchronized String project(Path directory) throws Exception {
        String root = ProjectPaths.root(directory).toString();
        try (var q = connection.prepareStatement("SELECT id FROM projects WHERE root=?")) {
            q.setString(1, root);
            try (var rows = q.executeQuery()) { if (rows.next()) return rows.getString(1); }
        }
        projectTreeOrder();
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO projects(id,root) VALUES(?,?)", id, root);
        execute("INSERT INTO project_tree_order(node_key,position) VALUES(?,?)","project:"+id,nextProjectTreePosition());
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
        try (var q = connection.prepareStatement("SELECT s.* FROM sessions s LEFT JOIN session_order o ON o.session_id=s.id WHERE s.project_id=? ORDER BY o.position IS NULL,o.position,s.rowid")) {
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
    public synchronized boolean marked(String id)throws SQLException{try(var q=connection.prepareStatement("SELECT marked FROM session_marks WHERE session_id=?")){q.setString(1,id);try(var r=q.executeQuery()){return r.next()&&r.getBoolean(1);}}}
    public synchronized void marked(String id,boolean value)throws SQLException{get(id);execute("INSERT INTO session_marks VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET marked=excluded.marked",id,value);}
    public synchronized JsonNode claimProcess(String session,long pid)throws SQLException{
        get(session);var process=ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElseThrow(()->new IllegalArgumentException("Процесс уже завершён"));long started=process.info().startInstant().orElseThrow().toEpochMilli();
        execute("INSERT INTO cpu_claims VALUES(?,?,?,?) ON CONFLICT(process_key) DO UPDATE SET session_id=excluded.session_id",pid+":"+started,session,pid,started);return Json.object("pid",pid,"started",started,"session",session);
    }
    public synchronized List<JsonNode> claimedProcesses()throws SQLException{var rows=new ArrayList<JsonNode>();try(var q=connection.createStatement();var r=q.executeQuery("SELECT c.*,s.project_id FROM cpu_claims c JOIN sessions s ON s.id=c.session_id")){while(r.next())rows.add(Json.object("pid",r.getLong("pid"),"started",r.getLong("started"),"session",r.getString("session_id"),"project",r.getString("project_id")));}return rows;}
    public synchronized JsonNode cpuControl(String scope,String target)throws Exception{
        try(var q=connection.prepareStatement("SELECT settings FROM cpu_controls WHERE scope=? AND target=?")){q.setString(1,scope);q.setString(2,target);try(var r=q.executeQuery()){return r.next()?Json.parse(r.getString(1)):dev.tethium.resources.ResourcePolicy.defaults(scope);}}
    }
    public synchronized void cpuControl(String scope,String target,JsonNode settings)throws Exception{
        if(scope.equals("global")){if(!target.isEmpty())throw new IllegalArgumentException("Global CPU target");}
        else if(scope.equals("session"))get(target);
        else if(scope.equals("project")){if(projects().stream().noneMatch(p->p.id().equals(target)))throw new IllegalArgumentException("Unknown project");}
        else throw new IllegalArgumentException("Unknown CPU scope");
        var value=dev.tethium.resources.ResourcePolicy.validate(scope,settings);execute("INSERT INTO cpu_controls VALUES(?,?,?) ON CONFLICT(scope,target) DO UPDATE SET settings=excluded.settings",scope,target,value.toString());
    }
    public synchronized void seen(String id,long version) throws SQLException {
        long bounded=Math.max(0,Math.min(version,attention(id).path("version").asLong()));
        execute("INSERT INTO session_seen(session_id,last_run) VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET last_run=MAX(last_run,excluded.last_run)",id,bounded);
    }
    public synchronized String beginRun(String sessionId, Provider provider) throws SQLException {
        if (!acceptsWork(sessionId)) throw new IllegalStateException("Session is deactivated");
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
            closeOutput(runId,sessionId);String shown=ActionLines.merge(output(runId),result.text());
            if (!shown.isEmpty()) {append(sessionId, "assistant", provider.name(), shown);linkRunMessage(runId,sessionId);}
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
    /** Finished Claude workers whose latest session run and owning job both still represent a failure. */
    public synchronized List<FailedWorkerRun> failedClaudeWorkers()throws SQLException{
        var result=new ArrayList<FailedWorkerRun>();
        String sql="SELECT w.run_id,w.session_id,w.job_id FROM worker_runs w JOIN runs r ON r.id=w.run_id JOIN jobs j ON j.id=w.job_id WHERE w.finished=1 AND r.provider='CLAUDE' AND r.status='FAILED' AND j.status='FAILED' AND r.rowid=(SELECT MAX(r2.rowid) FROM runs r2 WHERE r2.session_id=r.session_id)";
        try(var q=connection.createStatement();var r=q.executeQuery(sql)){while(r.next())result.add(new FailedWorkerRun(r.getString(1),r.getString(2),r.getString(3)));}
        return result;
    }
    public synchronized List<FailedWorkerRun> recentFailedClaudeWorkers()throws SQLException{
        var result=new ArrayList<FailedWorkerRun>();
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT w.run_id,w.session_id,w.job_id FROM worker_runs w JOIN runs r ON r.id=w.run_id WHERE w.finished=1 AND r.provider='CLAUDE' AND r.status='FAILED' ORDER BY r.rowid DESC LIMIT 32")){
            while(r.next())result.add(new FailedWorkerRun(r.getString(1),r.getString(2),r.getString(3)));
        }
        return result;
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
    private final Map<String,Narrator> narrators=new HashMap<>();
    private void closeOutput(String runId,String sessionId)throws SQLException{
        var narrator=narrators.remove(runId);if(narrator==null)return;
        String end=narrator.finish();if(!end.isEmpty())appendOutput(runId,sessionId,end);
    }
    private void appendOutput(String runId,String sessionId,String piece)throws SQLException{
        execute("INSERT INTO run_progress(run_id,session_id,output) VALUES(?,?,?) ON CONFLICT(run_id) DO UPDATE SET output=run_progress.output || excluded.output",runId,sessionId,piece);
    }
    private String output(String runId)throws SQLException{
        try(var q=connection.prepareStatement("SELECT output FROM run_progress WHERE run_id=?")){q.setString(1,runId);try(var r=q.executeQuery()){return r.next()?r.getString(1):"";}}
    }
    public synchronized void recordEvent(String runId, String sessionId, AgentAdapter.Event event) throws SQLException {
        var compaction=event.raw().path("params").path("item");
        if((event.type().equals("item/started")||event.type().equals("item/completed"))&&compaction.path("type").asText().equals("contextCompaction")&&!compaction.path("id").asText().isBlank()){
            recordCompaction(runId,sessionId,event);return;
        }
        String piece=narrators.computeIfAbsent(runId,id->new Narrator()).piece(event);if(!piece.isEmpty())appendOutput(runId,sessionId,piece);
        if (event.type().equals("conversation")) {
            execute("INSERT INTO bindings(session_id,provider,conversation_id,last_sequence) SELECT ?,provider,?,0 FROM runs WHERE id=? ON CONFLICT(session_id,provider) DO UPDATE SET conversation_id=excluded.conversation_id", sessionId, event.text(), runId);
        } else if (event.type().equals("item/started") || event.type().equals("item/completed") || event.type().equals("error") || event.type().equals("request")) {
            execute("INSERT INTO events(session_id,run_id,type,payload) VALUES(?,?,?,?)", sessionId, runId, event.type(), storedEvent(event.raw()));
            var item=event.raw().path("params").path("item");
            if(event.type().equals("item/completed")&&Set.of("imageView","imageGeneration").contains(item.path("type").asText())&&(!item.has("status")||item.path("status").asText().equals("completed"))){
                long eventId;try(var q=connection.createStatement();var r=q.executeQuery("SELECT last_insert_rowid()")){r.next();eventId=r.getLong(1);}
                execute("INSERT OR IGNORE INTO media_jobs(event_id,run_id,session_id,item_id,output_offset,input_sequence,payload) VALUES(?,?,?,?,COALESCE((SELECT length(output) FROM run_progress WHERE run_id=?),0),?,?)",eventId,runId,sessionId,item.path("id").asText(),runId,lastSequence(sessionId),item.toString());
            }
        }
    }
    private void recordCompaction(String runId,String sessionId,AgentAdapter.Event event)throws SQLException{
        var params=event.raw().path("params");String item=params.path("item").path("id").asText();
        boolean completed=event.type().equals("item/completed"),owner=connection.getAutoCommit();
        if(owner)connection.setAutoCommit(false);
        try{
            // Keep the receipt and chat message atomic, including during worker-journal replay.
            boolean alreadyCompleted=false;
            if(!completed)try(var q=connection.prepareStatement("SELECT 1 FROM compaction_notices WHERE run_id=? AND item_id=? AND phase='completed'")){
                q.setString(1,runId);q.setString(2,item);try(var r=q.executeQuery()){alreadyCompleted=r.next();}
            }
            if(!alreadyCompleted)try(var q=connection.prepareStatement("INSERT OR IGNORE INTO compaction_notices VALUES(?,?,?)")){
                q.setString(1,runId);q.setString(2,item);q.setString(3,completed?"completed":"started");
                if(q.executeUpdate()==1){
                    execute("INSERT INTO events(session_id,run_id,type,payload) VALUES(?,?,?,?)",sessionId,runId,event.type(),event.raw().toString());
                    long timestamp=params.path(completed?"completedAtMs":"startedAtMs").asLong();if(timestamp<=0)timestamp=System.currentTimeMillis();
                    if(!completed){
                        append(sessionId,"system","TETHIUM","Сжатие контекста началось.",timestamp);
                        execute("INSERT INTO compaction_messages VALUES(?,?,?,?)",runId,item,lastSequence(sessionId),timestamp);
                    }else{
                        long sequence=0,started=params.path("startedAtMs").asLong();
                        try(var lookup=connection.prepareStatement("SELECT message_sequence,started_at FROM compaction_messages WHERE run_id=? AND item_id=?")){
                            lookup.setString(1,runId);lookup.setString(2,item);try(var r=lookup.executeQuery()){if(r.next()){sequence=r.getLong(1);started=r.getLong(2);}}
                        }
                        // An older core may have written the start before this update was installed.
                        if(sequence==0)try(var lookup=connection.prepareStatement("SELECT m.sequence,m.created_at FROM events e JOIN messages m ON m.session_id=e.session_id AND m.created_at=json_extract(e.payload,'$.params.startedAtMs') WHERE e.run_id=? AND e.type='item/started' AND json_extract(e.payload,'$.params.item.type')='contextCompaction' AND json_extract(e.payload,'$.params.item.id')=? AND m.role='system' AND m.provider='TETHIUM' AND m.text='Сжатие контекста началось.' ORDER BY e.sequence DESC LIMIT 1")){
                            lookup.setString(1,runId);lookup.setString(2,item);try(var r=lookup.executeQuery()){if(r.next()){sequence=r.getLong(1);started=r.getLong(2);}}
                        }
                        String text=started>0?"Контекст сжат за "+compactionDuration(Math.max(0,timestamp-started))+".":"Контекст сжат.";
                        if(sequence>0){
                            execute("UPDATE messages SET text=? WHERE sequence=? AND session_id=?",text,sequence,sessionId);
                            execute("INSERT INTO message_revisions VALUES(?,1) ON CONFLICT(session_id) DO UPDATE SET revision=revision+1",sessionId);
                        }else append(sessionId,"system","TETHIUM",text,timestamp);
                    }
                }
            }
            if(owner)connection.commit();
        }catch(SQLException|RuntimeException failure){if(owner)connection.rollback();throw failure;}
        finally{if(owner)connection.setAutoCommit(true);}
    }
    private static String compactionDuration(long millis){
        long seconds=millis/1000;
        if(seconds==0)return "менее 1 с";
        if(seconds<60)return seconds+" с";
        if(seconds<3600)return seconds/60+" мин "+seconds%60+" с";
        return seconds/3600+" ч "+seconds/60%60+" мин "+seconds%60+" с";
    }
    public synchronized void saveInterruptedOutput(String runId, String sessionId, Provider provider) throws SQLException {
        closeOutput(runId,sessionId);
        try (var q = connection.prepareStatement("SELECT output FROM run_progress WHERE run_id=?")) {
            q.setString(1, runId);
            try (var row = q.executeQuery()) { if (row.next() && !row.getString(1).isEmpty()) {append(sessionId, "assistant", provider.name(), row.getString(1).strip());linkRunMessage(runId,sessionId);} }
        }
        execute("DELETE FROM run_progress WHERE run_id=?", runId);
    }
    public synchronized List<Project> projects() throws SQLException {
        var result = new ArrayList<Project>();
        try (var q = connection.createStatement(); var rows = q.executeQuery("SELECT p.id,p.root FROM projects p LEFT JOIN project_order o ON o.project_id=p.id ORDER BY o.position IS NULL,o.position,p.rowid")) {
            while (rows.next()) { var path = Path.of(rows.getString(2)); result.add(new Project(rows.getString(1), path.toString(), path.getFileName() == null ? path.toString() : path.getFileName().toString())); }
        }
        return List.copyOf(result);
    }
    public synchronized List<ProjectGroup> projectGroups() throws SQLException {
        var result = new ArrayList<ProjectGroup>();
        try (var q = connection.createStatement(); var rows = q.executeQuery("SELECT id,title,position,collapsed FROM project_groups ORDER BY position,rowid")) {
            while (rows.next()) result.add(new ProjectGroup(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getBoolean(4)));
        }
        return List.copyOf(result);
    }
    public synchronized Map<String,ProjectParent> projectParents() throws SQLException {
        var result = new HashMap<String,ProjectParent>();
        try (var q = connection.createStatement(); var rows = q.executeQuery("SELECT project_id,parent_kind,parent_id FROM project_hierarchy")) {
            while (rows.next()) result.put(rows.getString(1), new ProjectParent(rows.getString(2), rows.getString(3)));
        }
        return Map.copyOf(result);
    }
    public synchronized Set<String> favoriteProjects() throws SQLException {
        var result=new LinkedHashSet<String>();
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT project_id FROM project_favorites WHERE session_id IS NULL ORDER BY position,rowid")){while(rows.next())result.add(rows.getString(1));}
        return Collections.unmodifiableSet(result);
    }
    public synchronized Map<String,String> favoriteSessions() throws SQLException {
        var result=new LinkedHashMap<String,String>();
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT project_id,session_id FROM project_favorites WHERE session_id IS NOT NULL ORDER BY position,rowid")){while(rows.next())result.put(rows.getString(1),rows.getString(2));}
        return Collections.unmodifiableMap(result);
    }
    /** Project ids in the independent order of the pinned Favorites group. */
    public synchronized List<String> favoriteOrder() throws SQLException {
        var result=new ArrayList<String>();
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT project_id FROM project_favorites ORDER BY position,rowid")){while(rows.next())result.add(rows.getString(1));}
        return List.copyOf(result);
    }
    private int nextFavoritePosition() throws SQLException {
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT COALESCE(MAX(position),-1)+1 FROM project_favorites")){return rows.next()?rows.getInt(1):0;}
    }
    public synchronized void projectFavorite(String projectId,boolean favorite)throws SQLException{
        if(projects().stream().noneMatch(project->project.id().equals(projectId)))throw new IllegalArgumentException("Проект не найден");
        if(favorite)execute("INSERT INTO project_favorites(project_id,session_id,position) VALUES(?,NULL,?) ON CONFLICT(project_id) DO UPDATE SET session_id=NULL",projectId,nextFavoritePosition());else execute("DELETE FROM project_favorites WHERE project_id=? AND session_id IS NULL",projectId);
    }
    public synchronized void sessionFavorite(String sessionId,boolean favorite)throws SQLException{
        Session session=get(sessionId);
        if(favorite)execute("INSERT INTO project_favorites(project_id,session_id,position) VALUES(?,?,?) ON CONFLICT(project_id) DO UPDATE SET session_id=excluded.session_id",session.projectId(),sessionId,nextFavoritePosition());else execute("DELETE FROM project_favorites WHERE project_id=? AND session_id=?",session.projectId(),sessionId);
    }
    public synchronized void reorderFavorites(List<String> projectIds)throws Exception{
        transaction(()->{
            var current=new ArrayList<>(favoriteOrder());
            for(String id:projectIds)if(!current.contains(id))throw new IllegalArgumentException("Проект не находится в избранном: "+id);
            var merged=merge(current,projectIds);
            for(int i=0;i<merged.size();i++)execute("UPDATE project_favorites SET position=? WHERE project_id=?",i,merged.get(i));
            return null;
        });
    }
    public synchronized ProjectGroup createProjectGroup(String title) throws SQLException {
        String clean=title==null?"":title.strip();if(clean.isEmpty())throw new IllegalArgumentException("Название группы не может быть пустым");
        projectTreeOrder();
        String id=UUID.randomUUID().toString();int position=0;
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT COALESCE(MAX(position),-1)+1 FROM project_groups")){if(rows.next())position=rows.getInt(1);}
        execute("INSERT INTO project_groups(id,title,position) VALUES(?,?,?)",id,clean,position);
        execute("INSERT INTO project_tree_order(node_key,position) VALUES(?,?)","group:"+id,nextProjectTreePosition());
        return new ProjectGroup(id,clean,position,false);
    }
    public synchronized void renameProjectGroup(String id,String title)throws SQLException{
        String clean=title==null?"":title.strip();if(clean.isEmpty())throw new IllegalArgumentException("Название группы не может быть пустым");
        try(var q=connection.prepareStatement("UPDATE project_groups SET title=? WHERE id=?")){q.setString(1,clean);q.setString(2,id);if(q.executeUpdate()!=1)throw new IllegalArgumentException("Группа не найдена");}
    }
    public synchronized void projectGroupCollapsed(String id,boolean collapsed)throws SQLException{
        try(var q=connection.prepareStatement("UPDATE project_groups SET collapsed=? WHERE id=?")){q.setBoolean(1,collapsed);q.setString(2,id);if(q.executeUpdate()!=1)throw new IllegalArgumentException("Группа не найдена");}
    }
    public synchronized void removeProjectGroup(String id)throws Exception{
        transaction(()->{execute("DELETE FROM project_hierarchy WHERE parent_kind='group' AND parent_id=?",id);execute("DELETE FROM project_tree_order WHERE node_key=?","group:"+id);execute("DELETE FROM project_groups WHERE id=?",id);return null;});
    }
    /** One stable order for project and group nodes. Parentage is stored separately; filtering this list gives sibling order. */
    public synchronized List<String> projectTreeOrder() throws SQLException {
        var existing=new LinkedHashSet<String>();int next=0;
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT node_key,position FROM project_tree_order ORDER BY position,rowid")){while(rows.next()){existing.add(rows.getString(1));next=Math.max(next,rows.getInt(2)+1);}}
        for(var group:projectGroups()){String key="group:"+group.id();if(existing.add(key))execute("INSERT INTO project_tree_order(node_key,position) VALUES(?,?)",key,next++);}
        for(var project:projects()){String key="project:"+project.id();if(existing.add(key))execute("INSERT INTO project_tree_order(node_key,position) VALUES(?,?)",key,next++);}
        var result=new ArrayList<String>();
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT node_key FROM project_tree_order ORDER BY position,rowid")){while(rows.next())result.add(rows.getString(1));}
        return List.copyOf(result);
    }
    private int nextProjectTreePosition() throws SQLException {
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT COALESCE(MAX(position),-1)+1 FROM project_tree_order")){return rows.next()?rows.getInt(1):0;}
    }
    public synchronized void projectParent(String projectId,String kind,String parentId)throws Exception{
        transaction(()->{
            boolean projectExists=projects().stream().anyMatch(p->p.id().equals(projectId));if(!projectExists)throw new IllegalArgumentException("Проект не найден");
            String normalized=kind==null?"root":kind.strip().toLowerCase(Locale.ROOT);
            if(normalized.equals("root")){execute("DELETE FROM project_hierarchy WHERE project_id=?",projectId);return null;}
            if(parentId==null||parentId.isBlank())throw new IllegalArgumentException("Родитель не выбран");
            if(normalized.equals("group")){
                if(projectGroups().stream().noneMatch(g->g.id().equals(parentId)))throw new IllegalArgumentException("Группа не найдена");
            }else if(normalized.equals("project")){
                if(projectId.equals(parentId))throw new IllegalArgumentException("Проект не может быть родителем самого себя");
                if(projects().stream().noneMatch(p->p.id().equals(parentId)))throw new IllegalArgumentException("Родительский проект не найден");
                var parents=projectParents();String cursor=parentId;var seen=new HashSet<String>();
                while(seen.add(cursor)){if(cursor.equals(projectId))throw new IllegalArgumentException("Такая вложенность создаёт цикл");var parent=parents.get(cursor);if(parent==null||!parent.kind().equals("project"))break;cursor=parent.id();}
            }else throw new IllegalArgumentException("Неизвестный тип родителя");
            execute("INSERT INTO project_hierarchy(project_id,parent_kind,parent_id) VALUES(?,?,?) ON CONFLICT(project_id) DO UPDATE SET parent_kind=excluded.parent_kind,parent_id=excluded.parent_id",projectId,normalized,parentId);
            return null;
        });
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
                    if (q.executeUpdate() == 1) append(session.id(), message.role(), "IMPORTED", message.text(),message.timestamp());
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
        try (var q = connection.prepareStatement("SELECT model,access,draft,reasoning_effort,draft_images,provider FROM preferences WHERE session_id=?")) {
            q.setString(1, id);
            try (var row = q.executeQuery()) { return row.next() ? Json.object("model", preferenceValue(row.getString(1)), "access", SESSION_ACCESS, "draft", row.getString(3), "reasoningEffort", preferenceValue(row.getString(4)), "draftImages", parseStored(row.getString(5)), "provider", Provider.of(row.getString(6)).name()) : Json.object("model", null, "access", SESSION_ACCESS, "draft", "", "reasoningEffort", null, "draftImages", List.of(), "provider", Provider.CODEX.name()); }
        }
    }
    public synchronized void preferences(String id, String model, String access) throws SQLException {
        preferences(id, model, access, preferences(id).path("reasoningEffort").asText(null));
    }
    public synchronized void preferences(String id, String model, String access, String reasoningEffort) throws SQLException {
        preferences(id, model, access, reasoningEffort, preferences(id).path("provider").asText(null));
    }
    /** Model and effort belong to one executor; switching the executor without naming them resets both to its defaults. */
    public synchronized void preferences(String id, String model, String access, String reasoningEffort, String provider) throws SQLException {
        // Older windows may still send their former per-session mode. The host policy takes precedence.
        String executor = Provider.of(provider).name();
        execute("INSERT INTO preferences(session_id,model,access,reasoning_effort,provider) VALUES(?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET model=excluded.model,access=excluded.access,reasoning_effort=excluded.reasoning_effort,provider=excluded.provider", id, preferenceValue(model), SESSION_ACCESS, preferenceValue(reasoningEffort), executor);
    }
    private static String preferenceValue(String value) { return value == null || value.isBlank() || value.strip().equals("null") ? null : value.strip(); }
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
            q.setString(1, id); try (var row = q.executeQuery()) { while (row.next()) {var event=Json.parse(row.getString(1));var item=event.path("params").path("item");if(item.path("type").asText().equals("imageGeneration")&&item instanceof com.fasterxml.jackson.databind.node.ObjectNode object)object.remove("result");result.add(event);} }
        }
        Collections.reverse(result); return result;
    }
    public synchronized String enqueue(String target, String source, String prompt) throws SQLException {
        if (!acceptsWork(target)) throw new IllegalStateException("Сессия деактивирована");
        if (prompt.isBlank()) throw new IllegalArgumentException("Пустое сообщение");
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO jobs(id,session_id,source_id,prompt,prompt_parts,queue_order) VALUES(?,?,?,?,?,(SELECT COALESCE(MAX(queue_order),0)+1 FROM jobs WHERE session_id=? AND status='QUEUED'))", id, target, source, prompt, Json.MAPPER.valueToTree(List.of(prompt)).toString(), target);
        return id;
    }
    /** User input appends to the queue tail unless a separate message was requested. */
    public synchronized String submit(String target, String prompt, boolean separate) throws SQLException {
        if (!acceptsWork(target)) throw new IllegalStateException("Сессия деактивирована");
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
    /** A restart is a new turn at the front; it must never unpause a user's queue. */
    public synchronized JsonNode restartContinuation(String session,String prompt,String requestId,String source)throws Exception{
        String key=requestId+"-"+session+"-job";var previous=operationResult(session,key,"restart");if(previous!=null)return previous;
        return transaction(()->{String job=enqueue(session,source,prompt);execute("UPDATE jobs SET queue_order=(SELECT COALESCE(MIN(queue_order),0)-1 FROM jobs WHERE session_id=? AND status='QUEUED') WHERE id=?",session,job);
            var result=Json.object("jobId",job);rememberOperation(session,key,"restart",result);return result;});
    }
    public synchronized void rememberRestartNotice(String session,String requestId)throws SQLException{rememberOperation(session,requestId+"-"+session+"-notice","restart-notice",Json.object("ok",true));}
    public synchronized Job runningJob(String session)throws SQLException{
        try(var q=connection.prepareStatement("SELECT id,source_id,prompt,prompt_parts,urgent FROM jobs WHERE session_id=? AND status='RUNNING' ORDER BY rowid DESC LIMIT 1")){q.setString(1,session);try(var r=q.executeQuery()){return r.next()?new Job(r.getString(1),session,r.getString(2),r.getString(3),promptParts(r.getString(3),r.getString(4)).size(),r.getBoolean(5)):null;}}
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
    /** A queue paused because its parent was stopped: resuming that parent resumes it too, unless it was touched by hand since. */
    public synchronized void cascadePaused(String session,String origin)throws SQLException{execute("INSERT INTO cascade_pauses VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET origin=excluded.origin",session,origin);}
    public synchronized List<String> cascadedFrom(String origin)throws SQLException{var result=new ArrayList<String>();try(var q=connection.prepareStatement("SELECT session_id FROM cascade_pauses WHERE origin=?")){q.setString(1,origin);try(var r=q.executeQuery()){while(r.next())result.add(r.getString(1));}}return result;}
    public synchronized void clearCascade(String session)throws SQLException{execute("DELETE FROM cascade_pauses WHERE session_id=?",session);}
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
            for(String id:affected) execute("UPDATE jobs SET urgent=0 WHERE session_id=? AND status='QUEUED'",id);
            String job=enqueue(target,null,prompt); execute("UPDATE jobs SET urgent=1 WHERE id=?",job);
            execute("INSERT INTO queue_interruptions(job_id,affected) VALUES(?,?)",job,Json.MAPPER.valueToTree(affected).toString());
            if(updateDesktopDraft){draft(target,""); draftImages(target,Json.MAPPER.createArrayNode());}
            var result=Json.object("jobId",job,"affected",affected); rememberOperation(target,requestId,"interrupt",result); connection.commit(); return result;
        } catch(SQLException|RuntimeException e) { connection.rollback(); throw e; } finally { connection.setAutoCommit(true); }
    }
    /** Promote the existing head atomically: keep grouped parts, attachments and delegation recipients. */
    public synchronized JsonNode interruptQueueHead(String target,List<String> affected,String requestId)throws SQLException{
        var previous=operationResult(target,requestId,"interrupt");if(previous!=null)return previous;
        connection.setAutoCommit(false);try{
            var queue=queued(target);if(queue.isEmpty())throw new IllegalStateException("Очередь уже пуста");String job=queue.getFirst().id();
            for(String id:affected)execute("UPDATE jobs SET urgent=0 WHERE session_id=? AND status='QUEUED'",id);execute("UPDATE jobs SET urgent=1 WHERE id=?",job);
            execute("INSERT INTO queue_interruptions VALUES(?,?) ON CONFLICT(job_id) DO UPDATE SET affected=excluded.affected",job,Json.MAPPER.valueToTree(affected).toString());
            var result=Json.object("jobId",job,"affected",affected);rememberOperation(target,requestId,"interrupt",result);connection.commit();return result;
        }catch(SQLException|RuntimeException error){connection.rollback();throw error;}finally{connection.setAutoCommit(true);}
    }
    /** Transient ordering barrier, independent of the user's durable queue pause. */
    public synchronized boolean interruptionBlocks(String session,String candidateJob)throws SQLException{return interruptionBlocker(session,candidateJob)!=null;}
    /** Session of the urgent correction whose scope still holds this session's queue, or null when nothing holds it. */
    public synchronized String interruptionBlocker(String session,String candidateJob)throws SQLException{
        try(var q=connection.prepareStatement("SELECT i.affected,j.session_id FROM queue_interruptions i JOIN jobs j ON j.id=i.job_id WHERE j.urgent=1 AND j.status IN ('QUEUED','RUNNING') AND j.id<>?")){
            q.setString(1,candidateJob);try(var rows=q.executeQuery()){while(rows.next())for(var affected:parseStored(rows.getString(1)))if(session.equals(affected.asText()))return rows.getString(2);}
        }return null;
    }
    public synchronized List<String> interruptionScope(String jobId) throws SQLException {
        try(var q=connection.prepareStatement("SELECT affected FROM queue_interruptions WHERE job_id=?")) {
            q.setString(1,jobId); try(var r=q.executeQuery()) { var result=new ArrayList<String>(); if(r.next()) parseStored(r.getString(1)).forEach(n->result.add(n.asText())); return result; }
        }
    }
    private void linkRunMessage(String run,String session)throws SQLException{execute("INSERT INTO run_messages(run_id,session_id,result_sequence) VALUES(?,?,?) ON CONFLICT(run_id) DO UPDATE SET result_sequence=excluded.result_sequence",run,session,lastSequence(session));}
    /** An event as stored, whole; only a generated image is left out: it is already a file of its own (session_media). */
    static String storedEvent(JsonNode raw){
        var copy=raw.deepCopy();
        if(copy.path("params").path("item") instanceof com.fasterxml.jackson.databind.node.ObjectNode item&&item.path("type").asText().equals("imageGeneration"))item.remove("result");
        return copy.toString();
    }
    /**
     * Removes image data that older cores kept inside events, then gives the space back. Runs at the core's start, before other
     * connections open; waits for the media migration, which still reads images from old events.
     */
    public synchronized void compactEvents()throws SQLException{
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT 1 FROM media_migrations WHERE name='events-v1'")){if(!r.next())return;}
        execute("UPDATE events SET payload=json_remove(payload,'$.params.item.result') WHERE type='item/completed' AND instr(payload,'imageGeneration')>0 AND json_valid(payload) AND json_extract(payload,'$.params.item.type')='imageGeneration' AND json_type(payload,'$.params.item.result') IS NOT NULL");
        long pages,free;
        try(var q=connection.createStatement()){try(var r=q.executeQuery("PRAGMA page_count")){r.next();pages=r.getLong(1);}try(var r=q.executeQuery("PRAGMA freelist_count")){r.next();free=r.getLong(1);}}
        if(free*4>pages)try(var q=connection.createStatement()){q.execute("VACUUM");}
    }
    public synchronized void seedMediaJobs()throws SQLException{
        try(var q=connection.createStatement();var r=q.executeQuery("SELECT 1 FROM media_migrations WHERE name='events-v1'")){if(r.next())return;}
        execute("INSERT OR IGNORE INTO media_jobs(event_id,run_id,session_id,item_id,output_offset,input_sequence,payload) SELECT e.sequence,e.run_id,e.session_id,json_extract(e.payload,'$.params.item.id'),0,COALESCE(w.delivered_through,0),json_extract(e.payload,'$.params.item') FROM events e LEFT JOIN worker_runs w ON w.run_id=e.run_id WHERE e.type='item/completed' AND json_extract(e.payload,'$.params.item.type') IN ('imageView','imageGeneration') AND COALESCE(json_extract(e.payload,'$.params.item.status'),'completed')='completed'");
        execute("INSERT OR IGNORE INTO media_migrations VALUES('events-v1')");
    }
    public synchronized List<JsonNode> mediaJobs()throws Exception{var rows=new ArrayList<JsonNode>();try(var q=connection.createStatement();var r=q.executeQuery("SELECT * FROM media_jobs WHERE done=0 ORDER BY event_id LIMIT 4")){while(r.next())rows.add(Json.object("event",r.getLong("event_id"),"session",r.getString("session_id"),"run",r.getString("run_id"),"itemId",r.getString("item_id"),"offset",r.getInt("output_offset"),"input",r.getLong("input_sequence"),"item",Json.parse(r.getString("payload"))));}return rows;}
    public synchronized void finishMediaJob(long event)throws SQLException{execute("UPDATE media_jobs SET done=1,payload='' WHERE event_id=?",event);}
    public synchronized void saveMedia(JsonNode job,Path file,Path thumb,int width,int height,String title)throws Exception{transaction(()->{
        execute("INSERT OR IGNORE INTO session_media(session_id,run_id,item_id,output_offset,input_sequence,path,thumbnail,width,height,title) VALUES(?,?,?,?,?,?,?,?,?,?)",job.path("session").asText(),job.path("run").asText(),job.path("itemId").asText(),job.path("offset").asInt(),job.path("input").asLong(),file.toString(),thumb.toString(),width,height,title);
        try(var q=connection.prepareStatement("SELECT 1 FROM artifacts WHERE session_id=? AND path=?")){q.setString(1,job.path("session").asText());q.setString(2,file.toString());try(var r=q.executeQuery()){if(!r.next())artifact(job.path("session").asText(),file,title);}}
        finishMediaJob(job.path("event").asLong());return null;
    });}
    public synchronized long mediaRevision(String session)throws SQLException{try(var q=connection.prepareStatement("SELECT COALESCE(MAX(id),0) FROM session_media WHERE session_id=?")){q.setString(1,session);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}}
    public synchronized List<JsonNode> mediaHistory(String session)throws SQLException{
        var rows=new ArrayList<JsonNode>();String sql="SELECT m.*,r.status,COALESCE(l.result_sequence,(SELECT MIN(sequence) FROM messages WHERE session_id=m.session_id AND role='assistant' AND sequence>m.input_sequence AND m.input_sequence>0)) result FROM session_media m LEFT JOIN runs r ON r.id=m.run_id LEFT JOIN run_messages l ON l.run_id=m.run_id WHERE m.session_id=? ORDER BY m.id";
        try(var q=connection.prepareStatement(sql)){q.setString(1,session);try(var r=q.executeQuery()){while(r.next()){long anchor=r.getLong("result");if(anchor==0)anchor="RUNNING".equals(r.getString("status"))?-1:r.getLong("input_sequence");rows.add(Json.object("kind","image","sequence",-100-r.getLong("id"),"mediaId",r.getLong("id"),"anchor",anchor,"outputOffset",r.getInt("output_offset"),"role","assistant","provider","CODEX","offset",0,"length",0,"lines",8,"file",r.getString("path"),"thumbnailFile",r.getString("thumbnail"),"width",r.getInt("width"),"height",r.getInt("height"),"title",r.getString("title")));}}}return rows;
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
        append(sessionId,role,provider,text,System.currentTimeMillis());
    }
    /** One source run owns one growing card in every recipient session. Status notes and its final result stay together. */
    public synchronized void appendSessionUpdate(String target,String updateKey,String firstText,String continuation)throws Exception{
        transaction(()->{
            long sequence=0;String current=null;
            try(var q=connection.prepareStatement("SELECT u.message_sequence,m.text FROM session_updates u JOIN messages m ON m.sequence=u.message_sequence AND m.session_id=u.target_session_id WHERE u.target_session_id=? AND u.update_key=?")){
                q.setString(1,target);q.setString(2,updateKey);try(var r=q.executeQuery()){if(r.next()){sequence=r.getLong(1);current=r.getString(2);}}
            }
            if(sequence==0){append(target,"session","TETHIUM",firstText);execute("INSERT INTO session_updates VALUES(?,?,?)",target,updateKey,lastSequence(target));}
            else{execute("UPDATE messages SET text=? WHERE sequence=? AND session_id=?",current+"\n\n"+continuation,sequence,target);messagesChanged(target);}
            return null;
        });
    }
    private void append(String sessionId,String role,String provider,String text,long timestamp)throws SQLException{
        execute("INSERT INTO messages(session_id,role,provider,text,created_at) VALUES(?,?,?,?,?)",sessionId,role,provider,text,Math.max(0,timestamp));
    }
    public synchronized long appendInput(String sessionId, String text) throws SQLException {
        boolean automated=autonomousInput(sessionId,text)||scheduledInput(sessionId,text);append(sessionId, automated?"system":"user", automated?"TETHIUM":"USER", text); return lastSequence(sessionId);
    }
    public synchronized List<Message> messages(String sessionId, long after, int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("History limit must be 1..1000");
        var result = new ArrayList<Message>();
        try (var q = connection.prepareStatement("SELECT sequence,role,provider,text,created_at FROM messages WHERE session_id=? AND sequence>? ORDER BY sequence LIMIT ?")) {
            q.setString(1, sessionId); q.setLong(2, after); q.setInt(3, limit);
            try (var rows = q.executeQuery()) { while (rows.next()) result.add(new Message(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4),rows.getLong(5))); }
        }
        return List.copyOf(result);
    }
    /** Newest messages strictly before a sequence, oldest first; feeds backward paging on the phone. */
    public synchronized List<Message> messagesBefore(String sessionId, long before, int limit) throws SQLException {
        if (limit < 1 || limit > 1001) throw new IllegalArgumentException("History limit must be 1..1000");
        var result = new ArrayList<Message>();
        try (var q = connection.prepareStatement("SELECT sequence,role,provider,text,created_at FROM messages WHERE session_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?")) {
            q.setString(1, sessionId); q.setLong(2, before); q.setInt(3, limit);
            try (var rows = q.executeQuery()) { while (rows.next()) result.add(new Message(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4),rows.getLong(5))); }
        }
        Collections.reverse(result); return List.copyOf(result);
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
    /** Forget the executor conversations of a session: the next run starts a fresh one while the Tethium chat stays. */
    public synchronized int unbind(String id) throws SQLException {
        get(id); try (var q = connection.prepareStatement("DELETE FROM bindings WHERE session_id=?")) { q.setString(1, id); return q.executeUpdate(); }
    }
    /** Remove chosen chat messages; the executor keeps its own memory of them until the context is reset. */
    public synchronized int deleteMessages(String id, java.util.Collection<Long> sequences) throws Exception {
        return transaction(() -> { get(id); int removed = 0; for (long sequence : sequences) removed += deleteMessage(id, sequence); if (removed > 0) messagesChanged(id); return removed; });
    }
    public synchronized int deleteMessagesAfter(String id, long sequence) throws Exception {
        return transaction(() -> {
            get(id); var doomed = new ArrayList<Long>();
            try (var q = connection.prepareStatement("SELECT sequence FROM messages WHERE session_id=? AND sequence>?")) { q.setString(1, id); q.setLong(2, sequence); try (var r = q.executeQuery()) { while (r.next()) doomed.add(r.getLong(1)); } }
            int removed = 0; for (long s : doomed) removed += deleteMessage(id, s); if (removed > 0) messagesChanged(id); return removed;
        });
    }
    private int deleteMessage(String id, long sequence) throws SQLException {
        execute("DELETE FROM compaction_messages WHERE message_sequence=?", sequence);
        try (var q = connection.prepareStatement("DELETE FROM messages WHERE session_id=? AND sequence=?")) { q.setString(1, id); q.setLong(2, sequence); return q.executeUpdate(); }
    }
    private void messagesChanged(String id) throws SQLException {
        execute("INSERT INTO message_revisions VALUES(?,1) ON CONFLICT(session_id) DO UPDATE SET revision=revision+1", id); historyIndexes.remove(id);
    }
    /** One action for everything that keeps a session busy on its own: autonomy, schedule, queue pause, attention mark and draft. */
    public synchronized void resetState(String id) throws Exception {
        transaction(() -> {
            get(id); autonomy(id, "disable", "", "", ""); periodicSchedule(id, "disable", 0, "", System.currentTimeMillis());
            pauseQueue(id, false); marked(id, false); draft(id, ""); draftImages(id, Json.MAPPER.createArrayNode()); return null;
        });
    }
    /** Projects are reordered by drag and drop in the sidebar. The given ids are the visible subset in their new relative
     *  order; projects not mentioned (e.g. in the other tree) keep their places. */
    public synchronized void reorderProjects(List<String> ids) throws Exception {
        transaction(() -> {
            var current = new ArrayList<String>(); for (var p : projects()) current.add(p.id());
            var merged = merge(current, ids);
            for (int i = 0; i < merged.size(); i++) execute("INSERT INTO project_order VALUES(?,?) ON CONFLICT(project_id) DO UPDATE SET position=excluded.position", merged.get(i), i);
            reorderProjectTree(ids.stream().map(id->"project:"+id).toList());
            return null;
        });
    }
    /** Reorders a visible sibling subset in the mixed project/group catalog without changing its parentage. */
    public synchronized void reorderProjectTree(List<String> keys) throws Exception {
        transaction(()->{
            var current=new ArrayList<>(projectTreeOrder());var known=new HashSet<>(current);
            for(String key:keys)if(!known.contains(key))throw new IllegalArgumentException("Неизвестный узел дерева: "+key);
            var merged=merge(current,keys);
            for(int i=0;i<merged.size();i++)execute("INSERT INTO project_tree_order(node_key,position) VALUES(?,?) ON CONFLICT(node_key) DO UPDATE SET position=excluded.position",merged.get(i),i);
            int projectsAt=0,groupsAt=0;
            for(String key:merged){
                if(key.startsWith("project:"))execute("INSERT INTO project_order(project_id,position) VALUES(?,?) ON CONFLICT(project_id) DO UPDATE SET position=excluded.position",key.substring(8),projectsAt++);
                else if(key.startsWith("group:"))execute("UPDATE project_groups SET position=? WHERE id=?",groupsAt++,key.substring(6));
            }
            return null;
        });
    }
    /** Same for the sessions of one project: nested sessions stay under their parents, only the relative order changes. */
    public synchronized void reorderSessions(String projectId, List<String> ids) throws Exception {
        transaction(() -> {
            var current = new ArrayList<String>(); for (var s : list(projectId)) current.add(s.id());
            for (String id : ids) if (!current.contains(id)) throw new IllegalArgumentException("Сессия " + id + " не из этого проекта");
            var merged = merge(current, ids);
            for (int i = 0; i < merged.size(); i++) execute("INSERT INTO session_order VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET position=excluded.position", merged.get(i), i);
            return null;
        });
    }
    /** Keeps unmentioned ids in place and fills the mentioned slots with the subset in its new order. */
    static List<String> merge(List<String> current, List<String> subset) {
        var wanted = new LinkedHashSet<>(subset); wanted.retainAll(current);
        var queue = new ArrayDeque<String>(wanted); var result = new ArrayList<String>();
        for (String id : current) result.add(wanted.contains(id) ? queue.poll() : id);
        return result;
    }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
