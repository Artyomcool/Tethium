package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import dev.tethium.resources.*;
import java.nio.file.Path;
import java.util.*;

/** Controls named OS groups; the host is never a member of its own workload budget. */
final class ResourceController implements AutoCloseable {
    private final SessionStore store;
    private final String namespace;
    private final Path data;
    private final WindowsJobs jobs;
    private JsonNode global=ResourcePolicy.defaults("global");
    private Map<String,JsonNode> projects=Map.of(),sessions=Map.of();
    private Map<String,String> sessionProjects=Map.of();
    private final Map<String,Long> counters=new HashMap<>();
    private final Map<String,Double> loads=new HashMap<>();
    private final Map<String,Integer> counts=new HashMap<>();
    private final Map<Long,String> errors=new HashMap<>();
    private Map<Long,CpuSampler.Owner> owned=Map.of();
    private Set<Long> globalMembers=Set.of();
    private long observed;
    private int shared;
    private boolean legacyRetired;
    ResourceController(SessionStore store,Path data,boolean nativeEnabled)throws Exception{this.store=store;this.data=data;namespace=ResourcePolicy.namespace(data);jobs=nativeEnabled&&WindowsJobs.supported()?new WindowsJobs(namespace):null;refresh();}
    // Read the database before taking this monitor: MCP calls can already own the store monitor.
    void refresh()throws Exception{
        var g=store.cpuControl("global","");var p=new LinkedHashMap<String,JsonNode>();var s=new LinkedHashMap<String,JsonNode>();var parents=new HashMap<String,String>();
        for(var project:store.projects()){p.put(project.id(),store.cpuControl("project",project.id()));for(var session:store.list(project.id())){s.put(session.id(),store.cpuControl("session",session.id()));parents.put(session.id(),project.id());}}
        if(jobs!=null){var names=new ArrayList<String>();names.add("all");p.keySet().forEach(id->names.add("p-"+id));s.keySet().forEach(id->names.add("s-"+id));JobKeeper.ensure(data,names);}
        synchronized(this){global=g;projects=p;sessions=s;sessionProjects=parents;if(jobs!=null){retireLegacy();jobs.configure("all",enabled()?globalCap()*100:0,-1,false);for(String id:projects.keySet())jobs.configure("p-"+id,enabled()?ResourcePolicy.relativeRate(projectCap(id),globalCap()):0,-1,false);for(String id:sessions.keySet())configureSession(id);}}
    }
    private void retireLegacy(){
        if(legacyRetired)return;
        // The first CPU-control build could anchor its root below an unrelated legacy daemon.
        // Release its obsolete caps without terminating a worker or daemon.
        String old=UUID.nameUUIDFromBytes(data.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        try(var previous=new WindowsJobs(old)){
            previous.configure("all",0,-1,false);
            for(String id:projects.keySet())previous.configure("p-"+id,0,-1,false);
            for(String id:sessions.keySet())previous.configure("s-"+id,0,enabled()?ResourcePolicy.nativePriority(sessionPriority(id)):0x20,false);
        }
        legacyRetired=true;
    }
    private boolean enabled(){return global.path("enabled").asBoolean(true);}
    private int globalCap(){return global.path("cap").asInt(85);}
    private int projectCap(String id){return ResourcePolicy.cap(projects.getOrDefault(id,ResourcePolicy.defaults("project")),globalCap());}
    private String projectPriority(String id){return ResourcePolicy.priority(projects.getOrDefault(id,ResourcePolicy.defaults("project")),global.path("priority").asText("normal"));}
    private int sessionCap(String id){return ResourcePolicy.cap(sessions.get(id),projectCap(sessionProjects.get(id)));}
    private String sessionPriority(String id){return ResourcePolicy.priority(sessions.get(id),projectPriority(sessionProjects.get(id)));}
    private void configureSession(String id){jobs.configure("s-"+id,enabled()?ResourcePolicy.relativeRate(sessionCap(id),projectCap(sessionProjects.get(id))):0,enabled()?ResourcePolicy.nativePriority(sessionPriority(id)):0x20,false);}
    synchronized JsonNode spec(String session){
        String project=sessionProjects.get(session);if(project==null)throw new IllegalArgumentException("Missing CPU session");
        return Json.object("namespace",namespace,"session",session,"project",project,"globalRate",enabled()?globalCap()*100:0,"projectRate",enabled()?ResourcePolicy.relativeRate(projectCap(project),globalCap()):0,"sessionRate",enabled()?ResourcePolicy.relativeRate(sessionCap(session),projectCap(project)):0,"nativePriority",enabled()?ResourcePolicy.nativePriority(sessionPriority(session)):0x20);
    }
    synchronized List<CpuSampler.Root> roots(List<CpuSampler.Sample> samples){
        if(jobs==null)return List.of();var owners=new HashMap<Long,CpuSampler.Owner>();var result=new ArrayList<CpuSampler.Root>();
        for(var entry:sessionProjects.entrySet())for(long pid:jobs.members("s-"+entry.getKey()))owners.put(pid,new CpuSampler.Owner(entry.getKey(),entry.getValue()));
        owned=Map.copyOf(owners);globalMembers=jobs.members("all");for(var sample:samples){var owner=owners.get(sample.pid());if(owner!=null)result.add(new CpuSampler.Root(sample.pid(),sample.started(),owner));}return result;
    }
    synchronized void checkOwner(long pid,String session){var owner=owned.get(pid);if(owner!=null&&!owner.session().equals(session))throw new IllegalArgumentException("Процесс уже закреплён за другой сессией");}
    synchronized void observe(List<CpuSampler.Sample> samples,Map<Long,CpuSampler.Assignment> assignments){
        if(jobs==null)return;var live=new HashSet<Long>();shared=0;
        for(var sample:samples){live.add(sample.pid());var assignment=assignments.get(sample.pid());if(assignment==null)continue;
            boolean communal=assignment.shared()&&!owned.containsKey(sample.pid());if(communal&&sample.daemon())shared++;
            if(owned.containsKey(sample.pid())||communal&&globalMembers.contains(sample.pid()))continue;
            try{if(communal)jobs.attach(sample.pid(),"all");else{var owner=assignment.owner();if(owner==null||!sessions.containsKey(owner.session()))continue;jobs.attach(sample.pid(),"all","p-"+owner.project(),"s-"+owner.session());}errors.remove(sample.pid());}
            catch(Exception error){if(ProcessHandle.of(sample.pid()).filter(ProcessHandle::isAlive).isPresent())errors.put(sample.pid(),error.getMessage());}
        }
        errors.keySet().retainAll(live);long now=System.nanoTime();double denominator=(now-observed)*(double)Runtime.getRuntime().availableProcessors();
        sampleGroup("all",now,denominator);for(String p:projects.keySet())sampleGroup("p-"+p,now,denominator);for(String s:sessions.keySet())sampleGroup("s-"+s,now,denominator);observed=now;
    }
    private void sampleGroup(String id,long now,double denominator){long value=jobs.cpuNanos(id);Long before=counters.put(id,value);loads.put(id,before==null||observed==0?0:Math.clamp(100.0*Math.max(0,value-before)/denominator,0,100));counts.put(id,jobs.members(id).size());}
    private JsonNode row(String job,JsonNode settings,int cap,String priority){return Json.object("settings",settings,"effectiveCap",enabled()?cap:100,"effectivePriority",enabled()?priority:"important","cpuPercent",loads.getOrDefault(job,0.0),"processes",counts.getOrDefault(job,0));}
    synchronized JsonNode snapshot(){
        var p=Json.object();projects.forEach((id,value)->p.set(id,row("p-"+id,value,projectCap(id),projectPriority(id))));var s=Json.object();sessions.forEach((id,value)->s.set(id,row("s-"+id,value,sessionCap(id),sessionPriority(id))));
        return Json.object("supported",jobs!=null,"enabled",enabled(),"global",row("all",global,globalCap(),global.path("priority").asText()),"projects",p,"sessions",s,"sharedDaemons",shared,"uncontrolledCount",errors.size(),"uncontrolled",errors.entrySet().stream().limit(12).map(e->Json.object("pid",e.getKey(),"reason",e.getValue())).toList());
    }
    @Override public synchronized void close(){if(jobs!=null)jobs.close();}
}
