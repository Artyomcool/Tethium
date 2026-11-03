package dev.tethium.host;

import dev.tethium.agent.*;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class HistoryAndTrashTest {
    @TempDir Path root;
    static final AgentAdapter NO_AGENT=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> e,Cancellation c){throw new AssertionError("No model calls");}};
    @Test void largeHistoryPagesAreBoundedAndReconstructUnicodeWithoutChangingAgentHistory()throws Exception{
        String id;String longText="абв🙂\n".repeat(6000);
        try(var store=new SessionStore(root.resolve("tethium.db"))){String p=store.project(root);id=store.create(p,null,"history",root).id();for(int i=0;i<300;i++)store.append(id,i%2==0?"user":"assistant","CODEX","message "+i+" text ".repeat(200));store.append(id,"assistant","CODEX",longText);}
        try(var h=new HostService(root,NO_AGENT);var c=new HostClient(h.descriptor())){
            var state=c.get("/state?session="+id+"&history=window");assertTrue(state.path("conversation").path("messages").isEmpty());assertTrue(state.toString().length()<20_000);
            var index=c.get("/history/index?session="+id).path("entries");assertTrue(index.size()>300);StringBuilder joined=new StringBuilder();
            for(int first=300;first<index.size();first+=12){var page=c.get("/history/page?session="+id+"&first="+first+"&count=12");assertTrue(page.toString().length()<150_000);for(var row:page.path("entries"))joined.append(row.path("text").asText());}
            assertEquals(longText,joined.toString());assertThrows(Exception.class,()->c.get("/history/page?session="+id+"&first=0&count=100"));assertEquals(301,c.get("/state?session="+id).path("conversation").path("messages").size());
        }
    }
    @Test void projectArchiveAndTrashPersistAndPurgeDetachesChildrenWithoutDeletingFiles()throws Exception{
        String id,child,p;Path file=Files.writeString(root.resolve("keep.md"),"Keep project file");
        try(var store=new SessionStore(root.resolve("tethium.db"))){p=store.project(root);id=store.create(p,null,"parent",root).id();child=store.create(p,id,"child",root).id();store.append(id,"user","USER","history");store.setActive(child,false);store.projectActive(p,false);}
        try(var h=new HostService(root,NO_AGENT);var c=new HostClient(h.descriptor())){
            assertFalse(c.get("/state").path("projects").get(0).path("active").asBoolean());
            assertThrows(Exception.class,()->c.post("/sessions/"+id+"/trash",Json.object()));
            c.post("/sessions/"+id+"/trash",Json.object("confirmed",true));assertEquals(1,c.get("/state").path("sessions").size());assertEquals(id,c.get("/trash").get(0).path("id").asText());
        }
        try(var h=new HostService(root,NO_AGENT);var c=new HostClient(h.descriptor())){
            c.post("/sessions/"+id+"/restore",Json.object());assertEquals("history",c.get("/state?session="+id).path("conversation").path("messages").get(0).path("text").asText());
            c.post("/projects/"+p+"/active",Json.object("active",true));var all=c.get("/state").path("sessions");assertTrue(all.get(0).path("active").asBoolean());assertFalse(all.get(1).path("active").asBoolean());
            c.post("/sessions/"+id+"/trash",Json.object("confirmed",true));assertThrows(Exception.class,()->c.post("/sessions/"+id+"/purge",Json.object()));
            c.post("/sessions/"+id+"/purge",Json.object("confirmed",true));assertTrue(c.get("/trash").isEmpty());assertTrue(c.get("/state").path("sessions").get(0).path("parentId").isNull());assertTrue(Files.exists(file));
        }
    }
    @Test void hidingRunningSessionDoesNotCancelAndPermanentDeleteRemainsBlocked()throws Exception{
        String streamed="x".repeat(7999)+"🙂"+"я".repeat(8000);var release=new java.util.concurrent.Semaphore(0);var started=new java.util.concurrent.CountDownLatch(1);
        AgentAdapter agent=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request request,Consumer<Event> events,Cancellation cancel)throws Exception{events.accept(new Event("text",streamed,Json.object()));started.countDown();while(!release.tryAcquire(20,java.util.concurrent.TimeUnit.MILLISECONDS))if(cancel.isCancelled())throw new AssertionError("Hiding cancelled execution");return new Result("fixture",Status.COMPLETED,"saved result");}};
        try(var h=new HostService(root,agent);var c=new HostClient(h.descriptor())){
            var project=c.post("/projects",Json.object("path",root.toString()));String id=c.post("/sessions",Json.object("projectId",project.path("id").asText(),"title","running")).path("id").asText();
            c.post("/sessions/"+id+"/run",Json.object("prompt","work"));assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var page=c.get("/history/page?session="+id+"&first=0&count=12");StringBuilder joined=new StringBuilder();for(var row:page.path("entries"))if(row.path("live").asBoolean())joined.append(row.path("text").asText());assertEquals(streamed,joined.toString());
            c.post("/sessions/"+id+"/trash",Json.object("confirmed",true));assertTrue(c.get("/state").path("sessions").isEmpty());assertTrue(c.get("/trash").get(0).path("running").asBoolean());
            assertThrows(Exception.class,()->c.post("/sessions/"+id+"/purge",Json.object("confirmed",true)));release.release();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(c.get("/trash").get(0).path("running").asBoolean()&&System.nanoTime()<deadline)Thread.sleep(20);
            c.post("/sessions/"+id+"/restore",Json.object());assertTrue(c.get("/state?session="+id).path("conversation").path("messages").toString().contains("saved result"));
            c.post("/sessions/"+id+"/trash",Json.object("confirmed",true));c.post("/sessions/"+id+"/purge",Json.object("confirmed",true));assertTrue(c.get("/trash").isEmpty());
        }finally{release.release();}
    }

}
