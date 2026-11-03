package dev.tethium.host;

import dev.tethium.agent.*;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class InterruptTest {
    @TempDir Path root;
    @Test void correctionWaitsForChildrenThenResumesPreviouslyRunningQueues() throws Exception {
        String parent,child,other;
        try(var store=new SessionStore(root.resolve("tethium.db"))) {
            String p=store.project(root); parent=store.create(p,null,"parent",root).id();
            child=store.create(p,parent,"child",root).id(); other=store.create(p,null,"other",root).id();
        }
        var starts=new LinkedBlockingQueue<String>(); var childCancelled=new CountDownLatch(1);var allowChildExit=new CountDownLatch(1);var correctionDone=new CountDownLatch(1);
        var adapter=new AgentAdapter(){
            public Provider provider(){return Provider.CODEX;}
            public Result run(Request request,Consumer<Event> events,Cancellation cancel) throws Exception {
                String label=request.prompt().endsWith("child-work")?"child":request.prompt().endsWith("parent-work")?"parent":request.prompt().endsWith("fix-now")?"fix":request.prompt().endsWith("old-parent")?"queued-parent":request.prompt().endsWith("old-child")?"queued-child":"other";
                starts.add(label);
                if(label.equals("fix")){assertTrue(correctionDone.await(10,TimeUnit.SECONDS));return new Result("thread",Status.COMPLETED,"fixed");}
                while(!cancel.isCancelled())Thread.sleep(10);
                if(label.equals("child")){childCancelled.countDown();assertTrue(allowChildExit.await(10,TimeUnit.SECONDS));}
                return new Result("thread",Status.CANCELLED,"");
            }
        };
        try(var host=new HostService(root,adapter);var c=new HostClient(host.descriptor())) {
            c.post("/sessions/"+parent+"/run",Json.object("prompt","parent-work"));assertEquals("parent",starts.poll(5,TimeUnit.SECONDS));
            c.post("/sessions/"+child+"/run",Json.object("prompt","child-work"));assertEquals("child",starts.poll(5,TimeUnit.SECONDS));
            c.post("/sessions/"+other+"/run",Json.object("prompt","other-work"));assertEquals("other",starts.poll(5,TimeUnit.SECONDS));
            c.post("/sessions/"+parent+"/run",Json.object("prompt","old-parent"));c.post("/sessions/"+child+"/run",Json.object("prompt","old-child"));
            var command=Json.object("requestId","priority","prompt","fix-now");
            var receipt=c.post("/sessions/"+parent+"/interrupt",command);
            assertTrue(childCancelled.await(5,TimeUnit.SECONDS));assertNull(starts.poll(150,TimeUnit.MILLISECONDS),"Must wait for child process to stop");
            allowChildExit.countDown();assertEquals("fix",starts.poll(5,TimeUnit.SECONDS));
            assertEquals(receipt,c.post("/sessions/"+parent+"/interrupt",command));
            assertTrue(session(c,other).path("running").asBoolean());assertFalse(session(c,other).path("queuePaused").asBoolean());
            assertFalse(session(c,parent).path("queuePaused").asBoolean());assertFalse(session(c,child).path("queuePaused").asBoolean());
            assertNull(starts.poll(150,TimeUnit.MILLISECONDS),"Subtree queue must wait for priority correction");
            correctionDone.countDown();
            assertEquals(java.util.Set.of("queued-parent","queued-child"),java.util.Set.of(starts.poll(5,TimeUnit.SECONDS),starts.poll(5,TimeUnit.SECONDS)));
            c.post("/sessions/"+parent+"/stop",Json.object());await(()->!session(c,parent).path("running").asBoolean()&&!session(c,child).path("running").asBoolean());
            c.post("/sessions/"+other+"/stop",Json.object());await(()->!session(c,other).path("running").asBoolean());
        }
        try(var host=new HostService(root,adapter);var c=new HostClient(host.descriptor())){
            assertTrue(session(c,parent).path("queuePaused").asBoolean());assertFalse(session(c,parent).path("running").asBoolean());
        }
    }
    @Test void pendingQuestionRemainsMarkedAfterViewingAndClearsAfterAnswer() throws Exception {
        var adapter=new AgentAdapter(){
            public Provider provider(){return Provider.CODEX;}
            public Result run(Request r,Consumer<Event> events,Cancellation cancel)throws Exception{
                r.requests().handle("item/tool/requestUserInput",Json.object("questions",Json.MAPPER.createArrayNode()),cancel);
                return new Result("thread",Status.COMPLETED,"done");
            }
        };
        try(var host=new HostService(root,adapter);var c=new HostClient(host.descriptor())){
            String project=c.post("/projects",Json.object("path",root.toString())).path("id").asText();
            String id=c.post("/sessions",Json.object("projectId",project)).path("id").asText();c.post("/sessions/"+id+"/run",Json.object("prompt","ask"));
            await(()->session(c,id).path("attention").path("kind").asText().equals("question"));
            c.post("/sessions/"+id+"/seen",Json.object("version",0));assertEquals("question",session(c,id).path("attention").path("kind").asText());
            var request=c.get("/state?session="+id).path("conversation").path("requests").get(0);
            c.post("/answer",Json.object("id",request.path("id"),"answer",Json.object()));await(()->!session(c,id).path("running").asBoolean());
            assertEquals("finished",session(c,id).path("attention").path("kind").asText());
        }
    }
    private static JsonNode session(HostClient c,String id)throws Exception {for(var s:c.get("/state").path("sessions"))if(s.path("id").asText().equals(id))return s;throw new AssertionError();}
    private interface Check{boolean get()throws Exception;}
    private static void await(Check check)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<end){if(check.get())return;Thread.sleep(20);}throw new AssertionError("Timed out");}
}
