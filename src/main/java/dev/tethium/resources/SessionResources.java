package dev.tethium.resources;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** Called before a worker creates Codex, so the entire process tree inherits its session job. */
public final class SessionResources implements AutoCloseable {
    private final WindowsJobs jobs;
    private SessionResources(WindowsJobs jobs){this.jobs=jobs;}
    public static SessionResources enter(JsonNode spec){
        if(spec.isMissingNode()||!WindowsJobs.supported())return new SessionResources(null);
        var jobs=new WindowsJobs(spec.path("namespace").asText());String project="p-"+spec.path("project").asText(),session="s-"+spec.path("session").asText();
        try{jobs.configure("all",spec.path("globalRate").asInt(),-1,true);jobs.configure(project,spec.path("projectRate").asInt(),-1,true);jobs.configure(session,spec.path("sessionRate").asInt(),spec.path("nativePriority").asInt(0x20),true);
            System.setProperty("tethium.worker.resources","true");jobs.attach(ProcessHandle.current().pid(),"all",project,session);return new SessionResources(jobs);
        }catch(RuntimeException e){jobs.close();throw e;}
    }
    public static Map<String,String> environment(Path data,String session)throws Exception{
        UUID.fromString(session);Path temp=Files.createDirectories(data.toAbsolutePath().resolve("session-resources").resolve(session).resolve("tmp"));
        var env=new HashMap<String,String>();String path=temp.toString();env.put("TEMP",path);env.put("TMP",path);env.put("TMPDIR",path);
        env.put("JAVA_TOOL_OPTIONS",System.getenv().getOrDefault("JAVA_TOOL_OPTIONS","")+" \"-Djava.io.tmpdir="+path+"\"");
        env.put("GRADLE_OPTS",System.getenv().getOrDefault("GRADLE_OPTS","")+" -Dorg.gradle.daemon.idletimeout=900000");
        env.put("TETHIUM_SESSION_ID",session);return env;
    }
    @Override public void close(){if(jobs!=null)jobs.close();}
}
