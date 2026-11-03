package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import dev.tethium.worker.WorkerJournal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(75)
class WorkerHandoffTest {
    @TempDir Path root;
    private Process process;
    private HostClient client;
    private Path data,fixture;
    @BeforeEach void setup()throws Exception{data=Files.createDirectories(root.resolve("data"));fixture=Files.createDirectories(root.resolve("fixture"));start(false);}
    private void start(boolean standby)throws Exception{
        var args=new ArrayList<>(List.of(AppPaths.javaExecutable(true),"--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("tethium.test.classpath"),DetachedHostFixture.class.getName(),data.toString(),fixture.toString()));
        if(standby)args.add("standby");
        var builder=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("hosts.log").toFile()));
        builder.environment().put("CODEX_HOME",Files.createDirectories(root.resolve("empty-codex")).toString());
        process=builder.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){
            if(!process.isAlive())throw new AssertionError(Files.readString(root.resolve("hosts.log")));
            try{var d=Json.MAPPER.readTree(data.resolve("host.json").toFile());if(d.path("pid").asLong()==process.pid()){client=new HostClient(d);client.get("/health");return;}}catch(Exception ignored){}
            Thread.sleep(50);
        }throw new AssertionError("Test host did not start");
    }
    private String session(String name)throws Exception{
        String project=client.post("/projects",Json.object("path",fixture.toString())).path("id").asText();
        return client.post("/sessions",Json.object("projectId",project,"title",name)).path("id").asText();
    }
    private JsonNode until(String id,Predicate<JsonNode> condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){var s=client.get("/state?session="+id);if(condition.test(s))return s;Thread.sleep(50);}
        throw new AssertionError("State did not converge: "+client.get("/state?session="+id)+"\n"+Files.readString(root.resolve("hosts.log")));
    }
    private void detach()throws Exception{
        client.post("/updates/handoff",Json.object());assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue());client.close();client=null;
    }
    private Path worker()throws Exception{try(var files=Files.list(data.resolve("workers"))){return files.filter(Files::isDirectory).findFirst().orElseThrow();}}
    private void signal(String name)throws Exception{Files.writeString(fixture.resolve(name),"yes");}
    private long workerPid()throws Exception{try(var journal=new WorkerJournal(worker())){return Long.parseLong(journal.state("pid"));}}
    @AfterEach void cleanup()throws Exception{
        if(client!=null){try{client.post("/shutdown",Json.object());}catch(Exception ignored){}client.close();}
        if(process!=null&&process.isAlive()&&!process.waitFor(10,TimeUnit.SECONDS))process.destroyForcibly().waitFor();
        Path workers=data.resolve("workers");if(Files.isDirectory(workers))try(var paths=Files.list(workers)){
            for(Path p:paths.toList())try(var journal=new WorkerJournal(p)){journal.state("cancel","true");String pid=journal.state("pid");if(pid!=null){var handle=ProcessHandle.of(Long.parseLong(pid));if(handle.isPresent())try{handle.get().onExit().get(10,TimeUnit.SECONDS);}catch(Exception ignored){handle.get().descendants().forEach(ProcessHandle::destroyForcibly);handle.get().destroyForcibly();}}}
        }
    }
    @Test void activeTurnToolsQuestionsAndPausedQueueSurviveTwoHostReplacements()throws Exception{
        String id=session("working"),target=session("recipient");Files.writeString(fixture.resolve("target"),target);signal("ask");
        client.post("/sessions/"+target+"/stop",Json.object());
        client.post("/sessions/"+target+"/run",Json.object("prompt","paused"));
        client.post("/sessions/"+id+"/draft",Json.object("draft","unsent"));
        client.post("/sessions/"+id+"/run",Json.object("prompt","first","preserveDesktopDraft",true));
        until(id,s->s.path("conversation").path("live").asText().equals("before "));long pid=workerPid();
        detach();signal("gap");
        Thread.sleep(300);assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
        start(true);
        var question=until(id,s->!s.path("conversation").path("requests").isEmpty()).path("conversation").path("requests").get(0);
        assertEquals(pid,workerPid());assertTrue(Files.readString(fixture.resolve("tool-result")).contains("false"));
        detach();start(true);
        var state=until(id,s->!s.path("conversation").path("requests").isEmpty());
        assertEquals(question.path("id"),state.path("conversation").path("requests").get(0).path("id"));
        assertEquals("before during ",state.path("conversation").path("live").asText());
        client.post("/answer",Json.object("id",question.path("id").asText(),"answer",Json.object("answers",Json.object("q","yes"))));
        signal("finish");
        var finished=until(id,s->s.path("conversation").path("messages").size()==2);
        assertEquals("before during answered after",finished.path("conversation").path("messages").get(1).path("text").asText());
        assertEquals(1,Files.readAllLines(fixture.resolve("submissions")).size());
        assertEquals(1,client.get("/state?session="+target).path("conversation").path("messages").size());
        for(var s:finished.path("sessions"))if(s.path("id").asText().equals(id))assertEquals("unsent",s.path("preferences").path("draft").asText());
        var recipient=client.get("/state?session="+target);assertEquals(1,recipient.path("conversation").path("queued").size());
        client.post("/updates/resume",Json.object());
        for(var s:client.get("/state").path("sessions"))if(s.path("id").asText().equals(target)){assertTrue(s.path("queuePaused").asBoolean());assertFalse(s.path("running").asBoolean());}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void queuedWorkContinuesAfterRestartWithoutResumingUserPause(boolean crash)throws Exception{
        String id=session("queued restart"),paused=session("user paused");
        client.post("/sessions/"+paused+"/stop",Json.object());
        client.post("/sessions/"+paused+"/run",Json.object("prompt","must stay queued"));
        client.post("/sessions/"+id+"/run",Json.object("prompt","first"));
        until(id,s->s.path("conversation").path("live").asText().equals("before "));
        client.post("/sessions/"+id+"/run",Json.object("prompt","next","separate",true));
        if(crash){process.destroyForcibly().waitFor();client.close();client=null;}else detach();
        start(true);
        var held=client.get("/state?session="+id);
        assertEquals(1,held.path("conversation").path("queued").size());
        for(var s:held.path("sessions"))assertEquals(s.path("id").asText().equals(paused),s.path("queuePaused").asBoolean());
        client.post("/updates/resume",Json.object());signal("gap");signal("finish");
        var done=until(id,s->s.path("conversation").path("messages").size()==4&&s.path("conversation").path("queued").isEmpty()
                &&java.util.stream.StreamSupport.stream(s.path("sessions").spliterator(),false).noneMatch(v->v.path("running").asBoolean()));
        assertEquals(2,Files.readAllLines(fixture.resolve("submissions")).size());
        for(var s:done.path("sessions"))assertEquals(s.path("id").asText().equals(paused),s.path("queuePaused").asBoolean());
        assertEquals(1,client.get("/state?session="+paused).path("conversation").path("queued").size());
    }
    @Test void abruptHostDeathKeepsWorkerAndStopWorksAfterReconnect()throws Exception{
        String id=session("crash");client.post("/sessions/"+id+"/run",Json.object("prompt","first"));
        until(id,s->s.path("conversation").path("live").asText().equals("before "));long pid=workerPid();
        process.destroyForcibly().waitFor();client.close();client=null;
        signal("gap");Thread.sleep(200);assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
        start(false);until(id,s->s.path("conversation").path("live").asText().equals("before during "));
        client.post("/sessions/"+id+"/stop",Json.object());
        var stopped=until(id,s->!s.path("sessions").get(0).path("running").asBoolean());
        assertEquals("CANCELLED",stopped.path("sessions").get(0).path("status").asText());
        assertEquals(1,Files.readAllLines(fixture.resolve("submissions")).size());
        assertEquals("before during ",stopped.path("conversation").path("messages").get(1).path("text").asText());
    }
    @Test void resultProducedWithoutAHostIsImportedOnce()throws Exception{
        String id=session("offline completion");client.post("/sessions/"+id+"/run",Json.object("prompt","first"));
        until(id,s->s.path("conversation").path("live").asText().equals("before "));long pid=workerPid();
        detach();signal("gap");signal("finish");
        var worker=ProcessHandle.of(pid);if(worker.isPresent())worker.get().onExit().get(10,TimeUnit.SECONDS);
        start(false);until(id,s->s.path("conversation").path("messages").size()==2&&!s.path("sessions").get(0).path("running").asBoolean());
        detach();start(false);
        var messages=client.get("/state?session="+id).path("conversation").path("messages");
        assertEquals(2,messages.size());assertEquals("before during after",messages.get(1).path("text").asText());
        assertEquals(1,Files.readAllLines(fixture.resolve("submissions")).size());
    }
    @Test void aDeadWorkerDoesNotResubmitAnUncertainTurn()throws Exception{
        String id=session("dead worker");client.post("/sessions/"+id+"/run",Json.object("prompt","first"));
        until(id,s->s.path("conversation").path("live").asText().equals("before "));
        var worker=ProcessHandle.of(workerPid()).orElseThrow();var children=worker.descendants().toList();
        worker.destroyForcibly();worker.onExit().get(5,TimeUnit.SECONDS);
        var state=until(id,s->!s.path("sessions").get(0).path("running").asBoolean());
        assertEquals("FAILED",state.path("sessions").get(0).path("status").asText());
        assertTrue(state.path("conversation").path("messages").get(1).path("text").asText().startsWith("before "));
        detach();start(false);Thread.sleep(200);
        assertEquals(1,Files.readAllLines(fixture.resolve("submissions")).size());
        for(var child:children)if(child.isAlive())child.destroyForcibly();
    }
}
