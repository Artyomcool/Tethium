package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.*;
import dev.tethium.core.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SessionScheduleTest {
    @TempDir Path data;
    static class Adapter implements AgentAdapter {
        final BlockingQueue<Request> requests=new LinkedBlockingQueue<>();
        final Semaphore finish=new Semaphore(0);final AtomicInteger count=new AtomicInteger();
        final CountDownLatch cancelled=new CountDownLatch(1),allowCancelledExit=new CountDownLatch(1);
        boolean failFirst,holdCancellation;
        public Provider provider(){return Provider.CODEX;}
        public Result run(Request request,Consumer<Event> events,Cancellation cancel)throws Exception{
            int turn=count.incrementAndGet();requests.add(request);
            if(failFirst&&turn==1)return new Result("timer-thread",Status.FAILED,"Selected model is at capacity. Please try a different model.");
            while(!finish.tryAcquire(10,TimeUnit.MILLISECONDS))if(cancel.isCancelled()){
                cancelled.countDown();if(holdCancellation)assertTrue(allowCancelledExit.await(5,TimeUnit.SECONDS));
                return new Result("timer-thread",Status.CANCELLED,"");
            }
            return new Result("timer-thread",Status.COMPLETED,"One pass completed");
        }
        Request next()throws Exception{var request=requests.poll(5,TimeUnit.SECONDS);assertNotNull(request,"Timer did not launch the session");return request;}
    }
    private String session(HostClient c)throws Exception{
        String project=c.post("/projects",Json.object("path",data.toString())).path("id").asText();
        return c.post("/sessions",Json.object("projectId",project,"title","Timer")).path("id").asText();
    }
    private JsonNode row(HostClient c,String id)throws Exception{
        for(var s:c.get("/state").path("sessions"))if(s.path("id").asText().equals(id))return s;throw new AssertionError("Missing session");
    }
    private void idle(HostClient c,String id)throws Exception{
        for(int i=0;i<200;i++){if(!row(c,id).path("running").asBoolean())return;Thread.sleep(10);}fail("Session did not finish");
    }
    private JsonNode set(HostClient c,String id,long seconds)throws Exception{
        return c.post("/sessions/"+id+"/schedule",Json.object("action","set","intervalSeconds",seconds,"prompt","Check unfinished work once"));
    }
    private JsonNode tool(AgentAdapter.Request r,JsonNode args)throws Exception{
        try(var http=HttpClient.newHttpClient()){
            var body=Json.object("jsonrpc","2.0","id",1,"method","tools/call","params",Json.object("name","session_schedule","arguments",args));
            var response=http.send(HttpRequest.newBuilder(r.toolsUrl()).header("Authorization","Bearer "+r.toolsToken()).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
            return Json.parse(response.body()).path("result");
        }
    }
    @Test void deadlineAndWakeAreAtomicDurableAndMissedIntervalsCoalesce()throws Exception{
        String id;Path db=data.resolve("db");
        try(var s=new SessionStore(db)){
            id=s.create(s.project(data),null,"Timer",data).id();s.periodicSchedule(id,"set",5,"One pass",1000);
            assertFalse(s.enqueueScheduled(id,5999));
            assertThrows(IllegalStateException.class,()->s.transaction(()->{assertTrue(s.enqueueScheduled(id,6000));throw new IllegalStateException("rollback");}));
            assertTrue(s.queued(id).isEmpty());assertEquals(6000,s.periodicSchedule(id).path("nextAt").asLong());
        }
        try(var s=new SessionStore(db)){
            assertTrue(s.enqueueScheduled(id,100_000));assertFalse(s.enqueueScheduled(id,100_000));
            assertEquals(1,s.queued(id).size());assertEquals(105_000,s.periodicSchedule(id).path("nextAt").asLong());
            String prompt=s.queued(id).getFirst().prompt();s.appendInput(id,prompt);
            assertEquals("system",s.messages(id,0,10).getFirst().role());
            assertFalse(s.enqueueScheduled(id,999_999),"A pending wake must not accumulate repeats");
        }
        try(var s=new SessionStore(db)){
            assertEquals(1,s.queued(id).size());assertFalse(s.enqueueScheduled(id,999_999));
            s.jobStatus(s.queued(id).getFirst().id(),"COMPLETED");assertTrue(s.enqueueScheduled(id,999_999));
            assertEquals(1,s.queued(id).size());assertEquals(1_004_999,s.periodicSchedule(id).path("nextAt").asLong());
        }
    }
    @Test void pauseCancelsOnlyTimerWakeAndInvalidSettingsNeverResumeIt()throws Exception{
        try(var s=new SessionStore(data.resolve("db"))){
            String id=s.create(s.project(data),null,"Timer",data).id();s.periodicSchedule(id,"set",1,"One pass",1000);
            assertTrue(s.enqueueScheduled(id,2000));String userJob=s.enqueue(id,null,"User queue");
            s.pauseQueue(id,true);s.periodicSchedule(id,"pause",0,"",2001);
            assertEquals(1,s.queued(id).size());assertEquals(userJob,s.queued(id).getFirst().id());
            assertThrows(IllegalArgumentException.class,()->s.periodicSchedule(id,"set",0,"Bad interval",3000));
            assertTrue(s.queuePaused(id));assertEquals("paused",s.periodicSchedule(id).path("state").asText());assertFalse(s.enqueueScheduled(id,999_999));
            s.periodicSchedule(id,"disable",0,"",3000);s.periodicSchedule(id,"pause",0,"",3000);
            assertEquals("off",s.periodicSchedule(id).path("state").asText(),"Stop must not turn a disabled timer into a resumable timer");
            s.removeQueued(id,userJob);s.periodicSchedule(id,"set",1,"One pass",3000);s.trash(id,true);
            assertFalse(s.enqueueScheduled(id,999_999));s.purge(id);assertTrue(s.dueSchedules(999_999).isEmpty());
        }
    }
    @Test void actualHostTimerRetriesModelFailureAndStopSurvivesRestart()throws Exception{
        var a=new Adapter();a.failFirst=true;String id;
        try(var host=new HostService(data,a);var c=new HostClient(host.descriptor())){
            id=session(c);set(c,id,1);
            assertTrue(a.next().prompt().contains("Tethium scheduled task:"));
            assertTrue(a.next().prompt().contains("Tethium scheduled task:"));
            assertEquals("off",row(c,id).path("autonomy").path("state").asText());
            host.observeSchedules(System.currentTimeMillis()+60_000);assertEquals(0,row(c,id).path("queued").asInt());
            c.post("/sessions/"+id+"/stop",Json.object());idle(c,id);
            assertEquals("paused",row(c,id).path("schedule").path("state").asText());assertTrue(row(c,id).path("queuePaused").asBoolean());
        }
        try(var host=new HostService(data,a);var c=new HostClient(host.descriptor())){
            host.observeSchedules(System.currentTimeMillis()+60_000);assertNull(a.requests.poll(150,TimeUnit.MILLISECONDS));
            assertEquals("paused",row(c,id).path("schedule").path("state").asText());
            c.post("/sessions/"+id+"/queue-resume",Json.object());
            host.observeSchedules(System.currentTimeMillis()+60_000);a.next();
            assertEquals(3,a.count.get());c.post("/sessions/"+id+"/stop",Json.object());idle(c,id);
        }
    }
    @Test void restoredDueScheduleWaitsForPromotionAndThenRunsOnlyOnce()throws Exception{
        var a=new Adapter();String id;long due;
        try(var host=new HostService(data,a);var c=new HostClient(host.descriptor())){
            id=session(c);due=set(c,id,3600).path("nextAt").asLong();
        }
        try(var host=new HostService(data,a,new CodexQuotaService(null),true);var c=new HostClient(host.descriptor())){
            assertEquals(due,row(c,id).path("schedule").path("nextAt").asLong());
            host.observeSchedules(due+10_000);assertNull(a.requests.poll(100,TimeUnit.MILLISECONDS));
            c.post("/updates/resume",Json.object());host.observeSchedules(due+10_000);a.next();
            host.observeSchedules(due+20_000);assertEquals(1,a.count.get());assertEquals(0,row(c,id).path("queued").asInt());
            c.post("/sessions/"+id+"/stop",Json.object());idle(c,id);
        }
    }
    @Test void enablingTimerAlsoReleasesExistingUserQueue()throws Exception{
        var a=new Adapter();try(var host=new HostService(data,a);var c=new HostClient(host.descriptor())){
            String id=session(c);c.post("/sessions/"+id+"/stop",Json.object());
            c.post("/sessions/"+id+"/run",Json.object("prompt","Existing user work","separate",true));
            assertEquals(1,row(c,id).path("queued").asInt());set(c,id,300);
            assertTrue(a.next().prompt().endsWith("Existing user work"));
            a.finish.release();idle(c,id);assertNull(a.requests.poll(150,TimeUnit.MILLISECONDS));
            assertThrows(Exception.class,()->c.post("/sessions/"+id+"/schedule",Json.object("action","set","intervalSeconds",1.5,"prompt","Invalid")));
            assertEquals(300,row(c,id).path("schedule").path("intervalSeconds").asLong());
        }
    }
    @Test void mcpTimerReplacesImmediateAutonomyAndLateToolCannotUndoStop()throws Exception{
        var a=new Adapter();a.holdCancellation=true;
        try(var host=new HostService(data,a);var c=new HostClient(host.descriptor())){
            String id=session(c);c.post("/sessions/"+id+"/autonomy",Json.object("action","start","goal","Watch work"));var request=a.next();
            var command=Json.object("action","set","intervalSeconds",300,"prompt","One periodic pass");
            var result=tool(request,command);assertFalse(result.path("isError").asBoolean(),result.toString());
            assertEquals("active",row(c,id).path("schedule").path("state").asText());assertEquals("off",row(c,id).path("autonomy").path("state").asText());
            c.post("/sessions/"+id+"/stop",Json.object());assertTrue(a.cancelled.await(5,TimeUnit.SECONDS));
            assertTrue(tool(request,command).path("isError").asBoolean());
            assertEquals("paused",row(c,id).path("schedule").path("state").asText());assertTrue(row(c,id).path("queuePaused").asBoolean());
            a.allowCancelledExit.countDown();idle(c,id);assertNull(a.requests.poll(100,TimeUnit.MILLISECONDS));
        }finally{a.allowCancelledExit.countDown();}
    }
}
