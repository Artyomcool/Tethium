package dev.tethium.host;

import dev.tethium.agent.*;
import dev.tethium.core.*;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class AutonomyTest {
    @TempDir Path data;
    static final class Adapter implements AgentAdapter {
        final BlockingQueue<Request> requests=new LinkedBlockingQueue<>();final Semaphore finish=new Semaphore(0);
        public Provider provider(){return Provider.CODEX;}
        public Result run(Request request,Consumer<Event> events,Cancellation cancel)throws Exception{
            requests.add(request);events.accept(new Event("conversation","autonomy-thread",Json.object()));
            while(!finish.tryAcquire(20,TimeUnit.MILLISECONDS))if(cancel.isCancelled())return new Result("autonomy-thread",Status.CANCELLED,"");
            return new Result("autonomy-thread",Status.COMPLETED,"Шаг выполнен");
        }
        Request next()throws Exception{var r=requests.poll(5,TimeUnit.SECONDS);assertNotNull(r);return r;}
    }
    private String session(HostClient client)throws Exception{String p=client.post("/projects",Json.object("path",data.toString())).path("id").asText();return client.post("/sessions",Json.object("projectId",p,"title","Autonomy")).path("id").asText();}
    private JsonNode tool(AgentAdapter.Request request,JsonNode args)throws Exception{
        try(var http=HttpClient.newHttpClient()){
            var response=http.send(HttpRequest.newBuilder(request.toolsUrl()).header("Authorization","Bearer "+request.toolsToken()).POST(HttpRequest.BodyPublishers.ofString(Json.object("jsonrpc","2.0","id",1,"method","tools/call","params",Json.object("name","session_autonomy","arguments",args)).toString())).build(),HttpResponse.BodyHandlers.ofString());
            var result=Json.parse(response.body()).path("result");assertFalse(result.path("isError").asBoolean(),result.toString());return Json.parse(result.path("content").get(0).path("text").asText());
        }
    }
    private JsonNode idle(HostClient c,String id)throws Exception{for(int i=0;i<200;i++){var s=c.get("/state?session="+id);if(!s.path("sessions").get(0).path("running").asBoolean())return s;Thread.sleep(20);}throw new AssertionError("Still running");}
    @Test void explicitGoalContinuesWithoutFakeUserMessagesAndCompleteStopsIt()throws Exception{
        var a=new Adapter();try(var host=new HostService(data,a);var c=new HostClient(host.descriptor())){
            String id=session(c);c.post("/sessions/"+id+"/run",Json.object("prompt","Выполни задачу автономно"));var first=a.next();
            var goal=tool(first,Json.object("action","start","goal","Проверить конкретную задачу"));a.finish.release();var second=a.next();
            assertTrue(second.prompt().contains("Tethium autonomous continuation:"));assertTrue(second.prompt().contains(goal.path("goalId").asText()));
            var answer=second.requests().handle("item/tool/requestUserInput",Json.object("questions",List.of(Json.object("id","next","question","Продолжать?"))),new Cancellation());
            assertTrue(answer.path("answers").path("next").path("answers").get(0).asText().contains("не ответ пользователя"));
            tool(second,Json.object("action","complete","goalId",goal.path("goalId").asText(),"reason","Результат проверен"));a.finish.release();
            var state=idle(c,id);assertEquals("complete",state.path("sessions").get(0).path("autonomy").path("state").asText());assertNull(a.requests.poll(200,TimeUnit.MILLISECONDS));
            long user=0,system=0;for(var m:state.path("conversation").path("messages")){if(m.path("role").asText().equals("user"))user++;if(m.path("text").asText().startsWith(Autonomy.PREFIX)&&m.path("role").asText().equals("system"))system++;}assertEquals(1,user);assertEquals(1,system);
        }
    }
    @Test void stopPreservesUserQueueAndResumeRestartsWhileDisableStopsContinuations()throws Exception{
        var a=new Adapter();try(var host=new HostService(data,a);var c=new HostClient(host.descriptor())){
            String id=session(c);c.post("/sessions/"+id+"/autonomy",Json.object("action","start","goal","Цель"));a.next();
            c.post("/sessions/"+id+"/run",Json.object("prompt","Правка","separate",true));c.post("/sessions/"+id+"/stop",Json.object());var paused=idle(c,id);assertEquals(1,paused.path("conversation").path("queued").size());assertEquals("paused",paused.path("sessions").get(0).path("autonomy").path("state").asText());assertNull(a.requests.poll(200,TimeUnit.MILLISECONDS));
            c.post("/sessions/"+id+"/queue-resume",Json.object());assertTrue(a.next().prompt().endsWith("Правка"));a.finish.release();a.next();
            c.post("/sessions/"+id+"/autonomy",Json.object("action","disable"));a.finish.release();idle(c,id);assertNull(a.requests.poll(200,TimeUnit.MILLISECONDS));
        }
    }
    @Test void durableReceiptsAndChildResultsPreventDuplicatesAndMissedWakeups()throws Exception{
        String id,goal;try(var s=new SessionStore(data.resolve("db"))){id=s.create(s.project(data),null,"A",data).id();goal=s.autonomy(id,"start","","Goal","").path("goalId").asText();s.ensureAutonomousContinuation(id);s.ensureAutonomousContinuation(id);assertEquals(1,s.queued(id).size());s.jobStatus(s.queued(id).get(0).id(),"COMPLETED");}
        try(var s=new SessionStore(data.resolve("db"))){s.ensureAutonomousContinuation(id);s.ensureAutonomousContinuation(id);assertEquals(1,s.queued(id).size());s.autonomy(id,"wait",goal,"","Results already arrived");assertEquals("active",s.autonomy(id).path("state").asText());
            String child=s.create(s.get(id).projectId(),id,"B",data).id();String job=s.enqueue(child,id,"Delegate");s.autonomy(id,"wait",goal,"","Waiting for child");assertEquals("waiting",s.autonomy(id).path("state").asText());s.jobStatus(job,"COMPLETED");s.wakeAutonomy(id);assertEquals("active",s.autonomy(id).path("state").asText());
            s.autonomy(id,"start","","New goal","");assertThrows(IllegalArgumentException.class,()->s.autonomy(id,"complete",goal,"","Stale result"));s.autonomy(id,"blocked",s.autonomy(id).path("goalId").asText(),"","Credentials unavailable");s.ensureAutonomousContinuation(id);assertTrue(s.queued(id).isEmpty());
        }
    }
    @Test void autonomyDoesNotAnswerPermissionsOrQuestionsWhilePaused(){
        var active=Json.object("state","active","goal","Goal","goalId","id");assertNull(Autonomy.answer("item/permissions/requestApproval",Json.object(),active));assertNull(Autonomy.answer("mcpServer/elicitation/request",Json.object(),active));assertNull(Autonomy.answer("item/tool/requestUserInput",Json.object(),Json.object("state","paused")));
    }
}
