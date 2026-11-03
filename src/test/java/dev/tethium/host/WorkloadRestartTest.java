package dev.tethium.host;

import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class WorkloadRestartTest {
    static class Adapter implements AgentAdapter {
        final BlockingQueue<Request> starts=new LinkedBlockingQueue<>();
        public Provider provider(){return Provider.CODEX;}
        public Result run(Request request,Consumer<Event> events,Cancellation cancel)throws Exception{
            starts.add(request);events.accept(new Event("text","Сохранённый результат",Json.object()));
            while(!cancel.isCancelled())Thread.sleep(15);
            return new Result("thread",Status.CANCELLED,"Сохранённый результат");
        }
    }
    @Test void coldRestartRestoresOnlyInterruptedWorkWithoutUnpausingOrDuplicating(@TempDir Path data)throws Exception{
        var adapter=new Adapter();String active,paused,id=UUID.randomUUID().toString();
        try(var host=new HostService(data,adapter);var client=new HostClient(host.descriptor())){
            String project=client.post("/projects",Json.object("path",data.toString())).path("id").asText();
            active=client.post("/sessions",Json.object("projectId",project)).path("id").asText();
            paused=client.post("/sessions",Json.object("projectId",project)).path("id").asText();
            client.post("/sessions/"+paused+"/stop",Json.object());client.post("/sessions/"+paused+"/run",Json.object("prompt","Не запускать"));
            client.post("/sessions/"+active+"/run",Json.object("prompt","Продолжительная задача"));assertNotNull(adapter.starts.poll(5,TimeUnit.SECONDS));
            client.post("/sessions/"+active+"/run",Json.object("prompt","Следующая задача","separate",true));
            String another=client.post("/sessions",Json.object("projectId",project)).path("id").asText();client.post("/sessions/"+another+"/run",Json.object("prompt","Вторая работа"));assertNotNull(adapter.starts.poll(5,TimeUnit.SECONDS));
            client.post("/sessions/"+active+"/draft",Json.object("draft","Черновик"));
            assertThrows(HostClient.ApiException.class,()->client.post("/updates/restart/prepare",Json.object("id",id)));
            assertFalse(client.get("/health").path("draining").asBoolean());
            var plan=client.post("/updates/restart/prepare",Json.object("id",id,"confirmed",true));assertEquals(2,plan.path("sessions").size());assertTrue(plan.path("sessions").get(0).path("resume").asBoolean());
            assertEquals(plan,client.post("/updates/restart/prepare",Json.object("id",id,"confirmed",true)));
            client.post("/updates/restart/stop",Json.object("id",id,"confirmed",true));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(client.get("/health").path("running").asInt()>0&&System.nanoTime()<end)Thread.sleep(20);assertEquals(0,client.get("/health").path("running").asInt());
        }
        try(var host=new HostService(data,adapter,new CodexQuotaService(null),true);var client=new HostClient(host.descriptor())){
            client.post("/updates/restart/restore",Json.object("id",id));client.post("/updates/restart/restore",Json.object("id",id));
            var state=client.get("/state?session="+active);var queue=state.path("conversation").path("queued");assertEquals(2,queue.size());assertTrue(queue.get(0).path("prompt").asText().contains("Снимок твоего контекста"));assertTrue(java.nio.file.Files.readString(data.resolve("restarts").resolve(id).resolve(active+".json")).contains("Продолжительная задача"));assertEquals("Следующая задача",queue.get(1).path("prompt").asText());
            long notices=0;for(var message:state.path("conversation").path("messages"))if(message.path("text").asText().startsWith("Сорян, перезапустились"))notices++;assertEquals(1,notices);
            for(var session:state.path("sessions")){if(session.path("id").asText().equals(paused)){assertTrue(session.path("queuePaused").asBoolean());assertEquals(1,session.path("queued").asInt());}if(session.path("id").asText().equals(active))assertEquals("Черновик",session.path("preferences").path("draft").asText());}
            client.post("/updates/resume",Json.object());var resumed=adapter.starts.poll(5,TimeUnit.SECONDS);assertNotNull(resumed);assertTrue(resumed.prompt().contains("Сорян, перезапустились"));
        }
    }
    @Test void staleProcessIdentityCannotBeStopped(){
        var self=ProcessHandle.current();assertTrue(WorkloadRestart.alive(self.pid(),self.info().startInstant().orElseThrow().toEpochMilli()));assertFalse(WorkloadRestart.alive(self.pid(),0));
        var rows=new HashMap<Long,com.fasterxml.jackson.databind.JsonNode>();WorkloadRestart.process(rows,self.pid(),self.info().startInstant().orElseThrow().toEpochMilli(),"x","host");assertTrue(rows.isEmpty());
    }
}
