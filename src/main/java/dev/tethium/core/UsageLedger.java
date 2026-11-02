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
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS token_samples(thread TEXT NOT NULL,counter INTEGER NOT NULL,session_id TEXT NOT NULL,project_id TEXT NOT NULL,at INTEGER NOT NULL,input INTEGER NOT NULL,cached INTEGER NOT NULL,output INTEGER NOT NULL,reasoning INTEGER NOT NULL,baseline INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(thread,counter))");
            s.execute("CREATE INDEX IF NOT EXISTS token_time ON token_samples(at)");
            s.execute("CREATE TABLE IF NOT EXISTS usage_windows(id TEXT PRIMARY KEY,bucket TEXT NOT NULL,kind TEXT NOT NULL,label TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,observed INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS quota_wait(session_id TEXT PRIMARY KEY,auto_resume INTEGER NOT NULL DEFAULT 0,waiting INTEGER NOT NULL DEFAULT 0,blocked_at INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS quota_redemptions(id TEXT PRIMARY KEY,result TEXT,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS quota_reset_boundaries(id TEXT PRIMARY KEY,at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS cpu_processes(process_key TEXT PRIMARY KEY,pid INTEGER NOT NULL,started INTEGER NOT NULL,session_id TEXT,project_id TEXT,counter INTEGER NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS cpu_samples(process_key TEXT NOT NULL,counter INTEGER NOT NULL,session_id TEXT NOT NULL,project_id TEXT NOT NULL,at INTEGER NOT NULL,nanos INTEGER NOT NULL,PRIMARY KEY(process_key,counter))");
            s.execute("CREATE INDEX IF NOT EXISTS cpu_time ON cpu_samples(at)");
        }
    }
    private void exec(String sql,Object...args)throws SQLException{try(var q=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    public synchronized void sample(String session,String project,String thread,long at,JsonNode total,JsonNode last)throws SQLException{
        if(thread==null||thread.isBlank()||!total.path("totalTokens").isIntegralNumber())return;
        long counter=total.path("totalTokens").asLong();if(counter<0)return;
        boolean exists;try(var q=db.prepareStatement("SELECT 1 FROM token_samples WHERE thread=? LIMIT 1")){q.setString(1,thread);try(var r=q.executeQuery()){exists=r.next();}}
        if(!exists&&counter>last.path("totalTokens").asLong()&&last.path("totalTokens").isIntegralNumber())
            insert(session,project,thread,at-1,counter-last.path("totalTokens").asLong(),difference(total,last,"inputTokens"),difference(total,last,"cachedInputTokens"),difference(total,last,"outputTokens"),difference(total,last,"reasoningOutputTokens"),true);
        insert(session,project,thread,at,counter,total.path("inputTokens").asLong(),total.path("cachedInputTokens").asLong(),total.path("outputTokens").asLong(),total.path("reasoningOutputTokens").asLong(),false);
    }
    private static long difference(JsonNode a,JsonNode b,String key){return Math.max(0,a.path(key).asLong()-b.path(key).asLong());}
    private void insert(String session,String project,String thread,long at,long total,long input,long cached,long output,long reasoning,boolean baseline)throws SQLException{
        exec("INSERT INTO token_samples VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(thread,counter) DO UPDATE SET baseline=MIN(baseline,excluded.baseline),at=MIN(at,excluded.at) WHERE excluded.baseline<token_samples.baseline OR excluded.at<token_samples.at",thread,total,session,project,at,input,cached,output,reasoning,baseline);
    }
    public synchronized void snapshot(JsonNode quota)throws SQLException{
        long at=quota.path("updatedAt").asLong();if(at<=0)return;
        for(var bucket:quota.path("buckets"))for(var w:bucket.path("windows")){
            long end=w.path("resetsAt").asLong()*1000,duration=w.path("durationMinutes").asLong()*60_000;if(end<=at||duration<=0)continue;
            long start=end-duration;try(var q=db.prepareStatement("SELECT MAX(at) FROM quota_reset_boundaries WHERE at<=?")){q.setLong(1,at);try(var r=q.executeQuery()){if(r.next())start=Math.max(start,r.getLong(1));}}
            String id=bucket.path("id").asText()+":"+w.path("kind").asText()+":"+end+":"+start;
            exec("INSERT INTO usage_windows VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET observed=MAX(observed,excluded.observed)",id,bucket.path("id").asText(),w.path("kind").asText(),bucket.path("label").asText()+" · "+(duration==604_800_000?"Неделя":duration/3_600_000+" ч"),start,end,at);
        }
    }
    public synchronized JsonNode report(String period,String project,String session)throws Exception{
        var periods=Json.MAPPER.createArrayNode();long resetStart=0,resetEnd=System.currentTimeMillis()+1;
        try(var q=db.createStatement();var r=q.executeQuery("SELECT MAX(start),MIN(end) FROM usage_windows WHERE bucket='codex' AND observed=(SELECT MAX(observed) FROM usage_windows WHERE bucket='codex')")){if(r.next()){resetStart=r.getLong(1);resetEnd=Math.max(resetEnd,r.getLong(2));}}
        try(var q=db.createStatement();var r=q.executeQuery("SELECT MAX(at) FROM quota_reset_boundaries")){if(r.next())resetStart=Math.max(resetStart,r.getLong(1));}
        periods.add(Json.object("id","since-reset","label","С последнего сброса","start",resetStart,"end",resetEnd));
        try(var q=db.createStatement();var r=q.executeQuery("SELECT * FROM usage_windows ORDER BY end DESC,kind DESC")){while(r.next())periods.add(Json.object("id",r.getString("id"),"label",r.getString("label"),"start",r.getLong("start"),"end",r.getLong("end")));}
        long start=0,end=System.currentTimeMillis()+1;String selected="all";
        for(var p:periods)if((period==null||period.isBlank())&&selected.equals("all")||p.path("id").asText().equals(period)){selected=p.path("id").asText();start=p.path("start").asLong();end=p.path("end").asLong();if(period==null||period.isBlank())break;}
        var byProject=new LinkedHashMap<String,Long>();var bySession=new LinkedHashMap<String,Long>();var points=new TreeMap<Long,Long>();
        long total=0,own=0,input=0,cached=0,output=0,reasoning=0,first=0;boolean partial=false;
        String sql="SELECT *,counter-COALESCE(LAG(counter) OVER(PARTITION BY thread ORDER BY counter),0) AS dt,input-COALESCE(LAG(input) OVER(PARTITION BY thread ORDER BY counter),0) AS di,cached-COALESCE(LAG(cached) OVER(PARTITION BY thread ORDER BY counter),0) AS dc,output-COALESCE(LAG(output) OVER(PARTITION BY thread ORDER BY counter),0) AS doutput,reasoning-COALESCE(LAG(reasoning) OVER(PARTITION BY thread ORDER BY counter),0) AS dr FROM token_samples ORDER BY at";
        try(var q=db.createStatement();var r=q.executeQuery(sql)){while(r.next()){
            long at=r.getLong("at");if(first==0||at<first)first=at;if(r.getBoolean("baseline")){partial=true;continue;}if(at<start||at>=end)continue;
            long delta=Math.max(0,r.getLong("dt"));total+=delta;String pid=r.getString("project_id"),sid=r.getString("session_id");byProject.merge(pid,delta,Long::sum);bySession.merge(sid,delta,Long::sum);
            if((project==null||project.isBlank()||project.equals(pid))&&(session==null||session.isBlank()||session.equals(sid))){own+=delta;input+=Math.max(0,r.getLong("di"));cached+=Math.max(0,r.getLong("dc"));output+=Math.max(0,r.getLong("doutput"));reasoning+=Math.max(0,r.getLong("dr"));points.merge(at/300_000*300_000,delta,Long::sum);}
        }}
        var series=Json.MAPPER.createArrayNode();long cumulative=0;for(var p:points.entrySet()){cumulative+=p.getValue();series.add(Json.object("at",p.getKey(),"tokens",cumulative));}
        var cpuProjects=new LinkedHashMap<String,Long>();var cpuSessions=new LinkedHashMap<String,Long>();var cpuPoints=new TreeMap<Long,Long>();long cpuTotal=0,cpuOwn=0,cpuSince=0;
        try(var q=db.prepareStatement("SELECT * FROM cpu_samples WHERE at>=? AND at<? ORDER BY at")){q.setLong(1,start);q.setLong(2,end);try(var r=q.executeQuery()){while(r.next()){long n=r.getLong("nanos");cpuTotal+=n;String pid=r.getString("project_id"),sid=r.getString("session_id");cpuProjects.merge(pid,n,Long::sum);cpuSessions.merge(sid,n,Long::sum);if((project==null||project.isBlank()||project.equals(pid))&&(session==null||session.isBlank()||session.equals(sid))){cpuOwn+=n;cpuPoints.merge(r.getLong("at")/300_000*300_000,n,Long::sum);}}}}
        try(var q=db.createStatement();var r=q.executeQuery("SELECT MIN(at) FROM cpu_samples")){if(r.next())cpuSince=r.getLong(1);}
        var cpuSeries=Json.MAPPER.createArrayNode();long cpuCumulative=0;for(var point:cpuPoints.entrySet()){cpuCumulative+=point.getValue();cpuSeries.add(Json.object("at",point.getKey(),"nanos",cpuCumulative));}
        var cpu=Json.object("totalNanos",cpuTotal,"nanos",cpuOwn,"share",cpuTotal==0?0:100.0*cpuOwn/cpuTotal,"projects",cpuProjects,"sessions",cpuSessions,"series",cpuSeries,"observedSince",cpuSince,"sampled",true);
        return Json.object("cpu",cpu,"periods",periods,"period",selected,"start",start,"end",end,"total",total,"tokens",own,"share",total==0?0:100.0*own/total,"input",input,"cached",cached,"output",output,"reasoning",reasoning,"projects",byProject,"sessions",bySession,"series",series,"observedSince",first,"partial",partial,"scope","Токены разговоров Tethium; доля среди учтённых токенов, не процент квоты аккаунта");
    }
    private JsonNode sidebarCache;private long sidebarAt;
    public synchronized JsonNode sidebar()throws Exception{
        long now=System.currentTimeMillis();if(sidebarCache==null||now-sidebarAt>=3000){var r=report("since-reset","","");sidebarCache=Json.object("period",r.path("period"),"start",r.path("start"),"total",r.path("total"),"projects",r.path("projects"),"sessions",r.path("sessions"),"cpu",r.path("cpu"));sidebarAt=now;}return sidebarCache;
    }
    public synchronized java.util.List<JsonNode> cpuProcesses()throws SQLException{
        var values=new ArrayList<JsonNode>();try(var q=db.createStatement();var r=q.executeQuery("SELECT * FROM cpu_processes")){while(r.next())values.add(Json.object("key",r.getString("process_key"),"pid",r.getLong("pid"),"started",r.getLong("started"),"session",r.getString("session_id"),"project",r.getString("project_id"),"counter",r.getLong("counter"),"at",r.getLong("at"),"kind",r.getString("kind")));}return values;
    }
    /** Persist a cumulative baseline and its measured delta atomically, so a new host cannot count it twice. */
    public synchronized void cpuSample(String key,long pid,long started,String session,String project,long counter,long at,String kind,boolean fresh)throws Exception{
        db.setAutoCommit(false);try{
            Long previous=null;String owner=null;try(var q=db.prepareStatement("SELECT counter,session_id FROM cpu_processes WHERE process_key=?")){q.setString(1,key);try(var r=q.executeQuery()){if(r.next()){previous=r.getLong(1);owner=r.getString(2);}}}
            long delta=previous==null?(fresh?counter:0):Math.max(0,counter-previous);
            if(previous!=null&&!Objects.equals(owner,session))delta=0; // A shared daemon changed owners between observations; do not guess the boundary.
            if(session!=null&&project!=null&&delta>0)exec("INSERT OR IGNORE INTO cpu_samples VALUES(?,?,?,?,?,?)",key,counter,session,project,at,delta);
            exec("INSERT INTO cpu_processes VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(process_key) DO UPDATE SET session_id=excluded.session_id,project_id=excluded.project_id,counter=excluded.counter,at=excluded.at,kind=excluded.kind",key,pid,started,session,project,counter,at,kind);db.commit();
        }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
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
        sidebarAt=0;long at=System.currentTimeMillis();try(var q=db.prepareStatement("SELECT at FROM quota_reset_boundaries WHERE id=?")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next())return;}}
        exec("INSERT INTO quota_reset_boundaries VALUES(?,?)",id,at);exec("UPDATE usage_windows SET end=? WHERE start<? AND end>?",at,at,at);
    }
    @Override public synchronized void close()throws SQLException{db.close();}
}
