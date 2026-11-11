package dev.tethium.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Append-only observed counters; differences are computed before filtering a quota period. */
public final class UsageLedger implements AutoCloseable {
    private final Connection db;
    public UsageLedger(Path database)throws Exception{
        db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath());
        try(var s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=15000");
            s.execute("CREATE TABLE IF NOT EXISTS token_executors(thread TEXT PRIMARY KEY,executor TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS usage_windows(id TEXT PRIMARY KEY,bucket TEXT NOT NULL,kind TEXT NOT NULL,label TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,observed INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS quota_wait(session_id TEXT PRIMARY KEY,auto_resume INTEGER NOT NULL DEFAULT 0,waiting INTEGER NOT NULL DEFAULT 0,blocked_at INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS quota_redemptions(id TEXT PRIMARY KEY,result TEXT,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS quota_reset_boundaries(id TEXT PRIMARY KEY,at INTEGER NOT NULL)");
        }
        openCpuTotals();
        openTokenTotals();
        publishSidebar();
    }
    private void exec(String sql,Object...args)throws SQLException{try(var q=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    public synchronized void sample(String session,String project,String thread,long at,JsonNode total,JsonNode last)throws SQLException{sample(session,project,thread,at,total,last,null);}
    /** Threads without a recorded executor count as Codex: the only executor before the mapping existed. */
    public synchronized void sample(String session,String project,String thread,long at,JsonNode total,JsonNode last,String executor)throws SQLException{
        if(thread==null||thread.isBlank()||!total.path("totalTokens").isIntegralNumber())return;
        long counter=total.path("totalTokens").asLong();if(counter<0)return;
        long input=total.path("inputTokens").asLong(),cached=total.path("cachedInputTokens").asLong(),output=total.path("outputTokens").asLong(),reasoning=total.path("reasoningOutputTokens").asLong();
        exec("BEGIN IMMEDIATE");
        try{
            if(executor!=null&&!executor.isBlank())exec("INSERT INTO token_executors VALUES(?,?) ON CONFLICT(thread) DO UPDATE SET executor=excluded.executor",thread,executor.toLowerCase(Locale.ROOT));
            long[] was=null;
            try(var q=db.prepareStatement("SELECT counter,input,cached,output,reasoning FROM token_threads WHERE thread=?")){q.setString(1,thread);try(var r=q.executeQuery()){if(r.next())was=new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5)};}}
            // At the first sight of a thread only its last turn is known to be ours.
            if(was==null)was=last.path("totalTokens").isIntegralNumber()&&counter>last.path("totalTokens").asLong()?new long[]{counter-last.path("totalTokens").asLong(),difference(total,last,"inputTokens"),difference(total,last,"cachedInputTokens"),difference(total,last,"outputTokens"),difference(total,last,"reasoningOutputTokens")}:new long[5];
            // Only growth counts: a repeated or late sample is already in the sum.
            if(counter>was[0]){
                exec("INSERT INTO token_threads VALUES(?,?,?,?,?,?) ON CONFLICT(thread) DO UPDATE SET counter=excluded.counter,input=excluded.input,cached=excluded.cached,output=excluded.output,reasoning=excluded.reasoning",thread,counter,input,cached,output,reasoning);
                String named="codex";try(var q=db.prepareStatement("SELECT executor FROM token_executors WHERE thread=?")){q.setString(1,thread);try(var r=q.executeQuery()){if(r.next())named=r.getString(1);}}
                long start=resetStart(db);
                exec("INSERT INTO token_totals VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(since,session_id,project_id,executor) DO UPDATE SET tokens=tokens+excluded.tokens,input=input+excluded.input,cached=cached+excluded.cached,output=output+excluded.output,reasoning=reasoning+excluded.reasoning",at>=start?start:0,session,project,named,counter-was[0],Math.max(0,input-was[1]),Math.max(0,cached-was[2]),Math.max(0,output-was[3]),Math.max(0,reasoning-was[4]));
            }
            exec("COMMIT");
        }catch(SQLException e){exec("ROLLBACK");throw e;}
        publishSidebar();
    }
    private static long difference(JsonNode a,JsonNode b,String key){return Math.max(0,a.path(key).asLong()-b.path(key).asLong());}
    public synchronized void snapshot(JsonNode quota)throws SQLException{
        long at=quota.path("updatedAt").asLong();if(at<=0)return;
        for(var bucket:quota.path("buckets"))for(var w:bucket.path("windows")){
            long end=w.path("resetsAt").asLong()*1000,duration=w.path("durationMinutes").asLong()*60_000;if(end<=at||duration<=0)continue;
            long start=end-duration;try(var q=db.prepareStatement("SELECT MAX(at) FROM quota_reset_boundaries WHERE at<=?")){q.setLong(1,at);try(var r=q.executeQuery()){if(r.next())start=Math.max(start,r.getLong(1));}}
            String id=bucket.path("id").asText()+":"+w.path("kind").asText()+":"+end+":"+start;
            exec("INSERT INTO usage_windows VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET observed=MAX(observed,excluded.observed)",id,bucket.path("id").asText(),w.path("kind").asText(),bucket.path("label").asText()+" · "+(duration==604_800_000?"Неделя":duration/3_600_000+" ч"),start,end,at);
        }
        if(resetStart(db)!=sidebarStart)publishSidebar();
    }
    public synchronized JsonNode report(String period,String project,String session)throws SQLException{
        long resetStart=resetStart(db),resetEnd=System.currentTimeMillis()+1,now=resetEnd;
        try(var q=db.createStatement();var r=q.executeQuery("SELECT MIN(end) FROM usage_windows WHERE bucket='codex' AND observed=(SELECT MAX(observed) FROM usage_windows WHERE bucket='codex')")){if(r.next())resetEnd=Math.max(resetEnd,r.getLong(1));}
        var periods=Json.MAPPER.createArrayNode();periods.add(Json.object("id","since-reset","label","С последнего сброса","start",resetStart,"end",resetEnd));
        boolean sinceReset=period==null||period.isBlank()||period.equals("since-reset");long start=sinceReset?resetStart:0;
        var byProject=new LinkedHashMap<String,Long>();var bySession=new LinkedHashMap<String,Long>();var byExecutor=Json.object();
        long total=0,own=0,input=0,cached=0,output=0,reasoning=0;
        try(var q=db.prepareStatement("SELECT project_id,session_id,executor,SUM(tokens),SUM(input),SUM(cached),SUM(output),SUM(reasoning) FROM token_totals WHERE since>=? GROUP BY 1,2,3")){q.setLong(1,start);try(var r=q.executeQuery()){while(r.next()){
            String pid=r.getString(1),sid=r.getString(2);long n=r.getLong(4);total+=n;byProject.merge(pid,n,Long::sum);bySession.merge(sid,n,Long::sum);
            var executor=child(byExecutor,r.getString(3));add(executor,"total",n);add(child(executor,"projects"),pid,n);add(child(executor,"sessions"),sid,n);
            boolean mine=(project==null||project.isBlank()||project.equals(pid))&&(session==null||session.isBlank()||session.equals(sid));
            add(executor,"tokens",mine?n:0);
            if(mine){own+=n;input+=r.getLong(5);cached+=r.getLong(6);output+=r.getLong(7);reasoning+=r.getLong(8);}
        }}}
        var cpuProjects=new LinkedHashMap<String,Long>();var cpuSessions=new LinkedHashMap<String,Long>();long cpuTotal=0,cpuOwn=0,cpuSince=0;
        try(var q=db.prepareStatement("SELECT project_id,session_id,SUM(nanos) FROM cpu_totals WHERE since>=? GROUP BY 1,2")){q.setLong(1,start);try(var r=q.executeQuery()){while(r.next()){String pid=r.getString(1),sid=r.getString(2);long n=r.getLong(3);cpuTotal+=n;cpuProjects.merge(pid,n,Long::sum);cpuSessions.merge(sid,n,Long::sum);if((project==null||project.isBlank()||project.equals(pid))&&(session==null||session.isBlank()||session.equals(sid)))cpuOwn+=n;}}}
        try(var q=db.createStatement();var r=q.executeQuery("SELECT COALESCE(MIN(since),0) FROM cpu_totals WHERE since>0")){if(r.next())cpuSince=r.getLong(1);}
        var cpu=Json.object("totalNanos",cpuTotal,"nanos",cpuOwn,"share",cpuTotal==0?0:100.0*cpuOwn/cpuTotal,"projects",cpuProjects,"sessions",cpuSessions,"observedSince",cpuSince,"sampled",true);
        return Json.object("cpu",cpu,"periods",periods,"period",sinceReset?"since-reset":"all","start",start,"end",sinceReset?resetEnd:now,"total",total,"tokens",own,"share",total==0?0:100.0*own/total,"input",input,"cached",cached,"output",output,"reasoning",reasoning,"projects",byProject,"sessions",bySession,"executors",byExecutor,"scope","Токены разговоров Tethium; доля среди учтённых токенов, не процент квоты аккаунта");
    }
    /** The state of every session asks for this, so it is prepared on each write from a few rows of running sums. */
    private volatile JsonNode sidebarCache;
    private long sidebarStart;
    public JsonNode sidebar(){return sidebarCache;}
    public synchronized void publishSidebar()throws SQLException{
        var r=report("since-reset","","");sidebarStart=r.path("start").asLong();
        // The bars compare work, not cache: reads from the cache are part of the input and would drown everything else.
        long total=0;var projects=new LinkedHashMap<String,Long>();var sessions=new LinkedHashMap<String,Long>();var executors=Json.object();
        try(var q=db.prepareStatement("SELECT project_id,session_id,executor,MAX(0,SUM(input-cached+output)) FROM token_totals WHERE since>=? GROUP BY 1,2,3")){q.setLong(1,sidebarStart);try(var c=q.executeQuery()){while(c.next()){
            String pid=c.getString(1),sid=c.getString(2);long n=c.getLong(4);total+=n;projects.merge(pid,n,Long::sum);sessions.merge(sid,n,Long::sum);
            var executor=child(executors,c.getString(3));add(executor,"total",n);add(executor,"tokens",n);add(child(executor,"projects"),pid,n);add(child(executor,"sessions"),sid,n);
        }}}
        sidebarCache=Json.object("period",r.path("period"),"start",r.path("start"),"total",total,"projects",projects,"sessions",sessions,"executors",executors,"cpu",r.path("cpu"));
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode child(com.fasterxml.jackson.databind.node.ObjectNode parent,String key){
        if(parent.get(key) instanceof com.fasterxml.jackson.databind.node.ObjectNode found)return found;
        var made=Json.object();parent.set(key,made);return made;
    }
    private static void add(com.fasterxml.jackson.databind.node.ObjectNode node,String key,long delta){node.put(key,node.path(key).asLong()+delta);}
    /** Sums up the old per-update rows once; token_samples is not used after that. */
    private void openTokenTotals()throws SQLException{
        exec("BEGIN IMMEDIATE");
        try{
            boolean exists,legacy;
            try(var q=db.createStatement();var r=q.executeQuery("SELECT COALESCE(SUM(name='token_totals'),0),COALESCE(SUM(name='token_samples'),0) FROM sqlite_master WHERE type='table'")){r.next();exists=r.getInt(1)>0;legacy=r.getInt(2)>0;}
            if(!exists){
                exec("CREATE TABLE token_totals(since INTEGER NOT NULL,session_id TEXT NOT NULL,project_id TEXT NOT NULL,executor TEXT NOT NULL,tokens INTEGER NOT NULL,input INTEGER NOT NULL,cached INTEGER NOT NULL,output INTEGER NOT NULL,reasoning INTEGER NOT NULL,PRIMARY KEY(since,session_id,project_id,executor))");
                exec("CREATE TABLE token_threads(thread TEXT PRIMARY KEY,counter INTEGER NOT NULL,input INTEGER NOT NULL,cached INTEGER NOT NULL,output INTEGER NOT NULL,reasoning INTEGER NOT NULL)");
                if(legacy){
                    long reset=resetStart(db);
                    exec("INSERT INTO token_totals SELECT CASE WHEN at>=? THEN ? ELSE 0 END,session_id,project_id,executor,SUM(MAX(0,dt)),SUM(MAX(0,di)),SUM(MAX(0,dc)),SUM(MAX(0,dout)),SUM(MAX(0,dr)) FROM (SELECT t.at,t.session_id,t.project_id,t.baseline,COALESCE(e.executor,'codex') AS executor,t.counter-COALESCE(LAG(t.counter) OVER w,0) AS dt,t.input-COALESCE(LAG(t.input) OVER w,0) AS di,t.cached-COALESCE(LAG(t.cached) OVER w,0) AS dc,t.output-COALESCE(LAG(t.output) OVER w,0) AS dout,t.reasoning-COALESCE(LAG(t.reasoning) OVER w,0) AS dr FROM token_samples t LEFT JOIN token_executors e ON e.thread=t.thread WINDOW w AS (PARTITION BY t.thread ORDER BY t.counter)) WHERE baseline=0 GROUP BY 1,2,3,4",reset,reset);
                    exec("INSERT INTO token_threads SELECT thread,MAX(counter),input,cached,output,reasoning FROM token_samples GROUP BY thread");
                }
            }
            exec("COMMIT");
        }catch(SQLException e){exec("ROLLBACK");throw e;}
    }
    /** The start of the current quota period. */
    private static long resetStart(Connection c)throws SQLException{
        long start=0;
        try(var q=c.createStatement();var r=q.executeQuery("SELECT MAX(start) FROM usage_windows WHERE bucket='codex' AND observed=(SELECT MAX(observed) FROM usage_windows WHERE bucket='codex')")){if(r.next())start=r.getLong(1);}
        try(var q=c.createStatement();var r=q.executeQuery("SELECT MAX(at) FROM quota_reset_boundaries")){if(r.next())start=Math.max(start,r.getLong(1));}
        return start;
    }
    /** CPU is one running sum per session, started anew at each quota reset. */
    public synchronized void cpuAdd(String session,String project,long nanos)throws SQLException{
        long since=resetStart(db);
        exec("INSERT INTO cpu_totals VALUES(?,?,?,?) ON CONFLICT(since,session_id,project_id) DO UPDATE SET nanos=nanos+excluded.nanos",since,session,project,nanos);
        publishSidebar();
    }
    private void openCpuTotals()throws SQLException{
        exec("CREATE TABLE IF NOT EXISTS cpu_totals(since INTEGER NOT NULL,session_id TEXT NOT NULL,project_id TEXT NOT NULL,nanos INTEGER NOT NULL,PRIMARY KEY(since,session_id,project_id))");
    }
    public synchronized JsonNode waitState(String session)throws SQLException{
        try(var q=db.prepareStatement("SELECT * FROM quota_wait WHERE session_id=?")){q.setString(1,session);try(var r=q.executeQuery()){return r.next()?Json.object("autoResume",r.getBoolean("auto_resume"),"waiting",r.getBoolean("waiting"),"blockedAt",r.getLong("blocked_at")):Json.object("autoResume",false,"waiting",false,"blockedAt",0);}}
    }
    public synchronized void autoResume(String session,boolean value)throws SQLException{exec("INSERT INTO quota_wait(session_id,auto_resume) VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET auto_resume=excluded.auto_resume",session,value);}
    public synchronized void waiting(String session,boolean value)throws SQLException{exec("INSERT INTO quota_wait(session_id,waiting,blocked_at) VALUES(?,?,?) ON CONFLICT(session_id) DO UPDATE SET waiting=excluded.waiting,blocked_at=excluded.blocked_at",session,value,System.currentTimeMillis());}
    public synchronized JsonNode redemption(String id)throws Exception{try(var q=db.prepareStatement("SELECT result FROM quota_redemptions WHERE id=?")){q.setString(1,id);try(var r=q.executeQuery()){return !r.next()?null:r.getString(1)==null?Json.object("pending",true):Json.parse(r.getString(1));}}}
    public synchronized void beginRedemption(String id)throws SQLException{UUID.fromString(id);exec("INSERT OR IGNORE INTO quota_redemptions VALUES(?,NULL,?)",id,System.currentTimeMillis());}
    public synchronized void finishRedemption(String id,JsonNode result)throws SQLException{exec("UPDATE quota_redemptions SET result=? WHERE id=?",result.toString(),id);}
    public synchronized void resetBoundary(String id)throws SQLException{
        long at=System.currentTimeMillis();try(var q=db.prepareStatement("SELECT at FROM quota_reset_boundaries WHERE id=?")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next())return;}}
        exec("INSERT INTO quota_reset_boundaries VALUES(?,?)",id,at);exec("UPDATE usage_windows SET end=? WHERE start<? AND end>?",at,at,at);publishSidebar();
    }
    @Override public synchronized void close()throws SQLException{db.close();}
}
