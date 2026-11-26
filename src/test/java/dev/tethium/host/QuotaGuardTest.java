package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** A quota guard stops every start on its executor once the quota is at the threshold, until the user removes it. */
class QuotaGuardTest {
    @TempDir Path root;
    private static void until(java.util.concurrent.Callable<Boolean> condition)throws Exception{
        long end=System.nanoTime()+8_000_000_000L;
        while(!condition.call()){if(System.nanoTime()>end)fail("Timed out");Thread.sleep(20);}
    }
    private static JsonNode session(HostClient client,String id)throws Exception{
        for(var row:client.get("/state").path("sessions"))if(row.path("id").asText().equals(id))return row;
        throw new AssertionError(id);
    }
    private static AgentAdapter adapter(AgentAdapter.Provider provider,List<String> calls){return new AgentAdapter(){
        public Provider provider(){return provider;}
        public Result run(Request request,Consumer<Event> events,Cancellation cancellation){calls.add(provider+":"+request.prompt());return new Result("thread",Status.COMPLETED,"done");}
    };}

    @Test void aTrippedGuardHoldsTheQueueUntilTheUserAllowsIt()throws Exception{
        var calls=new CopyOnWriteArrayList<String>();
        var quota=new CodexQuotaService(null,null,()->Json.object("seven_day",Json.object("utilization",95.0,"resets_at","2030-01-01T00:00:00Z")),0);
        try(var host=new HostService(root,List.of(adapter(AgentAdapter.Provider.CODEX,calls),adapter(AgentAdapter.Provider.CLAUDE,calls)),quota,false,null);var client=new HostClient(host.descriptor())){
            until(()->CodexQuotaService.claudeAvailable(quota.snapshot()));
            String project=client.post("/projects",Json.object("path",root.toString())).path("id").asText();
            String id=client.post("/sessions",Json.object("projectId",project)).path("id").asText();
            client.post("/sessions/"+id+"/settings",Json.object("provider","CLAUDE","model","opus"));
            client.post("/quota/guards",Json.object("provider","CLAUDE","threshold",10,"hours",12));
            client.post("/sessions/"+id+"/run",Json.object("prompt","guarded work"));
            Thread.sleep(500);
            assertTrue(calls.isEmpty(),"Claude must not start below the guard: "+calls);
            assertEquals(1,session(client,id).path("queued").asInt());
            var guards=client.get("/state").path("quota").path("guards");
            assertEquals(1,guards.size());assertTrue(guards.get(0).path("tripped").asBoolean(),guards.toString());
            client.post("/quota/guards/remove",Json.object("provider","CLAUDE"));
            until(()->calls.size()==1);
            assertTrue(calls.get(0).startsWith("CLAUDE:")&&calls.get(0).contains("guarded work"),calls.toString());
            assertEquals(0,client.get("/state").path("quota").path("guards").size());
        }
    }

    @Test void aGuardAboveTheQuotaDoesNotStopWork()throws Exception{
        var calls=new CopyOnWriteArrayList<String>();
        var quota=new CodexQuotaService(null,null,()->Json.object("seven_day",Json.object("utilization",50.0,"resets_at","2030-01-01T00:00:00Z")),0);
        try(var host=new HostService(root,List.of(adapter(AgentAdapter.Provider.CODEX,calls),adapter(AgentAdapter.Provider.CLAUDE,calls)),quota,false,null);var client=new HostClient(host.descriptor())){
            until(()->CodexQuotaService.claudeAvailable(quota.snapshot()));
            String project=client.post("/projects",Json.object("path",root.toString())).path("id").asText();
            String id=client.post("/sessions",Json.object("projectId",project)).path("id").asText();
            client.post("/sessions/"+id+"/settings",Json.object("provider","CLAUDE","model","opus"));
            client.post("/quota/guards",Json.object("provider","CLAUDE","threshold",10,"hours",12));
            client.post("/sessions/"+id+"/run",Json.object("prompt","normal work"));
            until(()->calls.size()==1);
            assertFalse(client.get("/state").path("quota").path("guards").get(0).path("tripped").asBoolean());
        }
    }
}
