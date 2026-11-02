package dev.tethium.host;

import dev.tethium.agent.*;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class QuotaWorkflowTest {
    @TempDir Path root;
    private static JsonNode quota(int used){return Json.object("rateLimits",Json.object("limitId","codex","primary",Json.object("usedPercent",used,"windowDurationMins",10080,"resetsAt",System.currentTimeMillis()/1000+600)),"rateLimitResetCredits",Json.object("availableCount",2));}
    interface Check{boolean get()throws Exception;}
    static void await(Check check)throws Exception{long end=System.nanoTime()+8_000_000_000L;while(!check.get()){if(System.nanoTime()>end)fail("Timed out");Thread.sleep(15);}}
    @Test void quotaFailureRetainsJobAndResumesOnlyWithOptInAndFreshAvailability()throws Exception{
        var used=new AtomicInteger(100);var calls=new AtomicInteger();
        var adapter=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> e,Cancellation c){return calls.incrementAndGet()==1?new Result("thread",Status.FAILED,"usageLimitExceeded"):new Result("thread",Status.COMPLETED,"done");}};
        var service=new CodexQuotaService(()->quota(used.get()),0);
        try(var host=new HostService(root,adapter,service);var client=new HostClient(host.descriptor())){
            String p=client.post("/projects",Json.object("path",root.toString())).path("id").asText(),s=client.post("/sessions",Json.object("projectId",p)).path("id").asText();
            client.post("/sessions/"+s+"/run",Json.object("prompt","work"));
            await(()->!client.get("/state?session="+s).path("sessions").get(0).path("running").asBoolean());
            var state=client.get("/state?session="+s);assertTrue(state.path("sessions").get(0).path("quotaWait").path("waiting").asBoolean());assertEquals(1,state.path("conversation").path("queued").size());host.observeQuota();assertEquals(1,calls.get());
            await(()->!service.snapshot().path("refreshing").asBoolean());long previous=service.snapshot().path("updatedAt").asLong();used.set(0);Thread.sleep(5);service.refresh();await(()->service.snapshot().path("updatedAt").asLong()>previous);host.observeQuota();assertEquals(1,calls.get(),"Auto resume must be opt in");client.post("/sessions/"+s+"/quota-auto",Json.object("enabled",true));host.observeQuota();await(()->calls.get()==2&&!client.get("/state?session="+s).path("sessions").get(0).path("running").asBoolean());assertTrue(client.get("/state?session="+s).path("conversation").path("queued").isEmpty());
        }
    }
    @Test void resetRequiresConfirmationAndLowQuotaAndDeduplicatesRetries()throws Exception{
        var calls=new AtomicInteger();var service=new CodexQuotaService(()->quota(97),key->{calls.incrementAndGet();return Json.object("outcome","reset");});
        var adapter=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> e,Cancellation c){throw new AssertionError("No model calls");}};
        try(var host=new HostService(root,adapter,service);var client=new HostClient(host.descriptor())){
            await(()->service.snapshot().path("state").asText().equals("ready"));String id=UUID.randomUUID().toString();assertThrows(HostClient.ApiException.class,()->client.post("/quota/reset",Json.object("requestId",id)));assertEquals(0,calls.get());
            var request=Json.object("requestId",id,"confirmed",true);var first=client.post("/quota/reset",request);assertEquals(first,client.post("/quota/reset",request));assertEquals(1,calls.get());assertTrue(service.snapshot().path("resetCredits").path("availableCount").isNumber());
        }
    }
}
