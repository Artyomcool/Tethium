package dev.tethium.host;

import dev.tethium.agent.AgentAdapter;
import dev.tethium.agent.Cancellation;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class CompactionHistoryTest {
    @Test void apiRevisionChangesWhileMessageSequenceAndCountStayTheSame(@TempDir Path root)throws Exception{
        var started=new CountDownLatch(1);var finish=new CountDownLatch(1);var updated=new CountDownLatch(1);var release=new CountDownLatch(1);
        AgentAdapter agent=new AgentAdapter(){
            public Provider provider(){return Provider.CODEX;}
            public Result run(Request request,Consumer<Event> events,Cancellation cancel)throws Exception{
                events.accept(event("item/started",1000));started.countDown();
                try{if(!finish.await(10,TimeUnit.SECONDS))throw new AssertionError("No completion signal");events.accept(event("item/completed",8000));updated.countDown();release.await(10,TimeUnit.SECONDS);return new Result("fixture",Status.COMPLETED,"");}
                finally{updated.countDown();}
            }
            private Event event(String type,long time){return new Event(type,"",Json.object("params",Json.object("item",Json.object("id","compact","type","contextCompaction"),type.equals("item/started")?"startedAtMs":"completedAtMs",time)));}
        };
        try(var host=new HostService(root,agent);var client=new HostClient(host.descriptor())){
            String project=client.post("/projects",Json.object("path",root.toString())).path("id").asText();
            String id=client.post("/sessions",Json.object("projectId",project,"title","Compaction")).path("id").asText();
            client.post("/sessions/"+id+"/run",Json.object("prompt","Work"));assertTrue(started.await(10,TimeUnit.SECONDS));
            var before=client.get("/history/page?session="+id+"&first=0&count=12");
            finish.countDown();assertTrue(updated.await(10,TimeUnit.SECONDS));
            var after=client.get("/history/page?session="+id+"&first=0&count=12");
            assertNotEquals(before.path("version"),after.path("version"));assertEquals(before.path("entries").size(),after.path("entries").size());
            assertEquals(before.path("entries").get(1).path("sequence"),after.path("entries").get(1).path("sequence"));
            assertEquals("Контекст сжат за 7 с.",after.path("entries").get(1).path("text").asText());
            assertEquals(after.path("version"),client.get("/state?session="+id+"&history=window").path("conversation").path("historyVersion"));
            release.countDown();
        }finally{finish.countDown();release.countDown();}
    }
}
