package dev.tethium.host;

import dev.tethium.agent.AgentAdapter;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class HistoryStatePayloadTest {
    @Test void stateDoesNotFetchUnusedToolEvents(@TempDir Path root)throws Exception{
        String id;
        try(var store=new SessionStore(root.resolve("tethium.db"))){
            id=store.create(store.project(root),null,"History",root).id();store.append(id,"assistant","CODEX","Visible answer");
            String run=store.beginRun(id,AgentAdapter.Provider.CODEX);
            store.recordEvent(run,id,new AgentAdapter.Event("item/completed","",Json.object("params",Json.object("item",Json.object("type","commandExecution","command","x".repeat(200_000))))));
            store.finishRun(run,"COMPLETED");assertFalse(store.events(id).isEmpty());
        }
        try(var host=new HostService(root,HistoryAndTrashTest.NO_AGENT);var client=new HostClient(host.descriptor())){
            var state=client.get("/state?session="+id+"&history=window");
            assertTrue(state.path("conversation").path("windowed").asBoolean());
            assertFalse(state.path("conversation").has("events"));assertTrue(state.toString().length()<20_000);
            var page=client.get("/history/page?session="+id+"&first=0&count=12");
            assertEquals("Visible answer",page.path("entries").get(0).path("text").asText());
        }
    }
}
