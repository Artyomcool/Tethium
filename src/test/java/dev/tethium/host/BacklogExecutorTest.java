package dev.tethium.host;

import dev.tethium.agent.AgentAdapter.Provider;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BacklogExecutorTest {
    @TempDir Path root;
    private static JsonNode quota(int codex,int claude,int fable){return Json.object("buckets",List.of(
        Json.object("id","codex","windows",List.of(Json.object("remainingPercent",codex))),
        Json.object("id","claude","windows",List.of(Json.object("remainingPercent",claude))),
        Json.object("id","claude-fable","windows",List.of(Json.object("remainingPercent",fable)))));}
    @Test void switchesBothDirectionsOnlyForRequestedPairsAndKeepsSessionPreferences()throws Exception{
        for(var pair:List.of(new String[]{"CLAUDE","fable","CODEX","gpt-6-astra"},new String[]{"CLAUDE","opus","CODEX","gpt-6.1-sol"},new String[]{"CODEX","gpt-6-astra","CLAUDE","fable"},new String[]{"CODEX","gpt-6.1-sol","CLAUDE","opus"})){
            try(var store=new SessionStore(root.resolve(pair[1]+".db"))){
                String project=store.project(root),session=store.create(project,null,"work",root).id();
                store.preferences(session,pair[1],SessionStore.SESSION_ACCESS,"high",pair[0]);var before=store.preferences(session);
                var b=new Backlog(store,()->quota(pair[0].equals("CODEX")?0:100,pair[0].equals("CLAUDE")?0:100,100),()->Json.object("cpuTotal",1),id->storeBusy(store,id),()->{});
                String id=b.add(Json.object("projectId",project,"title","work","target","session","sessionId",session,"minFreeCpu",0),session).path("id").asText();
                assertTrue(b.state(null).path("tasks").get(0).path("executorSwitched").asBoolean());
                assertEquals(List.of(session),b.tick());String job=store.queued(session).getFirst().id();var prefs=store.executionPreferences(session,job);
                assertEquals(pair[2],prefs.path("provider").asText());assertEquals(pair[3],prefs.path("model").asText());assertEquals("high",prefs.path("reasoningEffort").asText());assertEquals(before,store.preferences(session));
                store.preferences(session,"sonnet",SessionStore.SESSION_ACCESS,null,"CLAUDE");assertEquals(pair[3],store.executionPreferences(session,job).path("model").asText(),"queued job retains its choice");
            }
        }
    }
    private static boolean storeBusy(SessionStore store,String id){try{return store.queuePaused(id)||!store.queued(id).isEmpty();}catch(Exception e){throw new RuntimeException(e);}}
    @Test void optedOutPinnedAndManualPauseDoNotSwitchAndChoiceSurvivesRestart()throws Exception{
        Path db=root.resolve("state.db");String session,job;
        try(var store=new SessionStore(db)){
            String project=store.project(root);session=store.create(project,null,"work",root).id();store.preferences(session,"opus",SessionStore.SESSION_ACCESS,null,"CLAUDE");
            var b=new Backlog(store,()->quota(100,0,0),()->Json.object("cpuTotal",1),id->storeBusy(store,id),()->{});
            var body=Json.object("projectId",project,"title","work","target","session","sessionId",session,"minFreeCpu",0,"autoSwitch",false);
            String id=b.add(body,session).path("id").asText();assertTrue(b.tick().isEmpty());
            body.put("autoSwitch",true);body.put("pinnedProvider","CLAUDE");body.put("pinnedModel","opus");b.update(id,body);assertTrue(b.tick().isEmpty());
            body.put("pinnedProvider","CODEX");body.put("pinnedModel","gpt-6-astra");b.update(id,body);
            store.pauseQueue(session,true);assertTrue(b.tick().isEmpty());assertTrue(store.queuePaused(session));
            store.pauseQueue(session,false);assertEquals(List.of(session),b.tick());job=store.queued(session).getFirst().id();
        }
        try(var store=new SessionStore(db)){assertEquals("gpt-6-astra",store.executionPreferences(session,job).path("model").asText());assertEquals("CLAUDE",store.preferences(session).path("provider").asText());assertEquals("CODEX",store.backlog().getFirst().path("pinnedProvider").asText());}
    }
    @Test void respectsPerModelQuotaFreshnessUnknownModelsAndAvailableAdapters()throws Exception{
        var fable=new BacklogExecutor.Choice(Provider.CLAUDE,"fable",null);
        assertEquals(0,BacklogExecutor.remaining(quota(100,100,0),fable));
        assertEquals(100,BacklogExecutor.remaining(quota(100,100,0),new BacklogExecutor.Choice(Provider.CLAUDE,"opus",null)));
        var error=(com.fasterxml.jackson.databind.node.ObjectNode)quota(100,100,100);error.set("sources",Json.object("codex","error"));assertNull(BacklogExecutor.remaining(error,new BacklogExecutor.Choice(Provider.CODEX,"gpt-6-astra",null)));
        assertNull(new BacklogExecutor.Choice(Provider.CLAUDE,"sonnet",null).counterpart());
        try(var store=new SessionStore(root.resolve("defaults.db"))){
            String p=store.project(root),s=store.create(p,null,"work",root).id();
            var b=new Backlog(store,()->quota(0,100,100),()->Json.object("cpuTotal",0),id->false,()->{},EnumSet.allOf(Provider.class),id->"gpt-6-astra");
            b.add(Json.object("projectId",p,"title","work","target","session","sessionId",s,"minFreeCpu",0),s);
            assertEquals("fable",b.state(null).path("tasks").get(0).path("effectiveModel").asText());
            var unavailable=new Backlog(store,()->quota(0,100,100),()->Json.object("cpuTotal",0),id->false,()->{},EnumSet.of(Provider.CODEX),id->"gpt-6-astra");assertTrue(unavailable.tick().isEmpty());
        }
    }
}
