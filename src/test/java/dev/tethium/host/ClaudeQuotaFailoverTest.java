package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.*;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import dev.tethium.worker.WorkerJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeQuotaFailoverTest {
    @TempDir Path root;

    private static void until(java.util.concurrent.Callable<Boolean> condition)throws Exception{
        long end=System.nanoTime()+8_000_000_000L;
        while(!condition.call()){if(System.nanoTime()>end)fail("Timed out");Thread.sleep(20);}
    }
    private static JsonNode session(HostClient client,String id)throws Exception{
        for(var row:client.get("/state").path("sessions"))if(row.path("id").asText().equals(id))return row;
        throw new AssertionError(id);
    }
    private static AgentAdapter claude(List<String> calls){return new AgentAdapter(){
        public Provider provider(){return Provider.CLAUDE;}
        public Result run(Request request,Consumer<Event> events,Cancellation cancellation){
            calls.add("CLAUDE:"+request.model()+":"+request.prompt());
            events.accept(new Event("finished","",Json.object("type","result","api_error","usage_limit_reached","result","You've hit your weekly limit")));
            return new Result("claude-thread",Status.FAILED,"partial work before the account limit");
        }
    };}
    private static AgentAdapter codex(List<String> calls){return new AgentAdapter(){
        public Provider provider(){return Provider.CODEX;}
        public Result run(Request request,Consumer<Event> events,Cancellation cancellation){calls.add("CODEX:"+request.model()+":"+request.prompt());return new Result("codex-thread",Status.COMPLETED,"done");}
    };}

    @Test void claudeQuotaSwitchesTheSessionAndRetriesTheSameJobOnCodex()throws Exception{
        var calls=new CopyOnWriteArrayList<String>();
        try(var host=new HostService(root,List.of(codex(calls),claude(calls)),new CodexQuotaService(null),false,null);var client=new HostClient(host.descriptor())){
            String project=client.post("/projects",Json.object("path",root.toString())).path("id").asText();
            String id=client.post("/sessions",Json.object("projectId",project)).path("id").asText();
            client.post("/sessions/"+id+"/settings",Json.object("provider","CLAUDE","model","opus","reasoningEffort","xhigh"));
            client.post("/sessions/"+id+"/run",Json.object("prompt","keep this exact task"));
            until(()->calls.size()==2&&!session(client,id).path("running").asBoolean());
            assertTrue(calls.get(0).startsWith("CLAUDE:opus:")&&calls.get(0).contains("keep this exact task"),calls.toString());
            assertTrue(calls.get(1).startsWith("CODEX:gpt-6.1-sol:")&&calls.get(1).contains("keep this exact task"),calls.toString());
            var prefs=session(client,id).path("preferences");
            assertEquals("CODEX",prefs.path("provider").asText());assertEquals("gpt-6.1-sol",prefs.path("model").asText());assertEquals("xhigh",prefs.path("reasoningEffort").asText());
            assertEquals(0,session(client,id).path("queued").asInt());
        }
    }

    @Test void deferredTaskOptOutKeepsClaudeAndDoesNotRetry()throws Exception{
        var calls=new CopyOnWriteArrayList<String>();
        try(var host=new HostService(root,List.of(codex(calls),claude(calls)),new CodexQuotaService(null),false,null);var client=new HostClient(host.descriptor())){
            String project=client.post("/projects",Json.object("path",root.toString())).path("id").asText();
            String id=client.post("/sessions",Json.object("projectId",project)).path("id").asText();
            client.post("/sessions/"+id+"/settings",Json.object("provider","CLAUDE","model","opus"));
            String task=client.post("/backlog",Json.object("projectId",project,"sessionId",id,"target","session","title","pinned","prompt","stay on Claude","minQuota",0,"minFreeCpu",0,"autoSwitch",false)).path("id").asText();
            client.post("/backlog/"+task+"/start",Json.object());
            until(()->calls.size()==1&&!session(client,id).path("running").asBoolean());Thread.sleep(100);
            assertEquals(1,calls.size());assertTrue(calls.getFirst().startsWith("CLAUDE:opus:"));
            assertEquals("CLAUDE",session(client,id).path("preferences").path("provider").asText());
        }
    }

    @Test void restartRecoversAQuotaFailureFromTheDurableWorkerJournal()throws Exception{
        String id;
        try(var store=new SessionStore(root.resolve("tethium.db"))){
            String project=store.project(root);id=store.create(project,null,"lane",root).id();
            store.preferences(id,"opus",SessionStore.SESSION_ACCESS,"high","CLAUDE");
            String job=store.enqueue(id,null,"resume after restart");
            String run=store.beginRun(id,AgentAdapter.Provider.CLAUDE);
            store.jobStatus(job,"RUNNING");store.registerWorker(run,id,job,0,Json.object("provider","CLAUDE"));
            store.finishRun(run,"FAILED");store.jobStatus(job,"FAILED");store.workerFinished(run);
            try(var journal=new WorkerJournal(root.resolve("workers").resolve(run))){
                journal.append("event",Json.object("type","finished","raw",Json.object("api_error","usage_limit_reached","result","You've hit your weekly limit")));
            }
        }
        var calls=new CopyOnWriteArrayList<String>();
        try(var host=new HostService(root,codex(calls));var client=new HostClient(host.descriptor())){
            until(()->calls.size()==1&&!session(client,id).path("running").asBoolean());
            assertTrue(calls.getFirst().startsWith("CODEX:gpt-6.1-sol:")&&calls.getFirst().contains("resume after restart"),calls.toString());
            assertEquals("CODEX",session(client,id).path("preferences").path("provider").asText());
        }
    }

    @Test void recoveryOnlyUnblocksTheHostsGenericExecutorFailure()throws Exception{
        try(var store=new SessionStore(root.resolve("tethium.db"))){
            String project=store.project(root),id=store.create(project,null,"lane",root).id();
            var goal=store.autonomy(id,"start","","keep going","");
            store.autonomy(id,"blocked",goal.path("goalId").asText(),"","Запуск завершился с ошибкой: quota");
            store.recoverAutonomyFromExecutorFailure(id);assertEquals("active",store.autonomy(id).path("state").asText());
            store.autonomy(id,"blocked",goal.path("goalId").asText(),"","Нужен ответ пользователя");
            store.recoverAutonomyFromExecutorFailure(id);assertEquals("blocked",store.autonomy(id).path("state").asText());
        }
    }
}
