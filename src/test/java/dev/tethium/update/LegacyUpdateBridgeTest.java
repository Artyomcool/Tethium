package dev.tethium.update;
import dev.tethium.core.SessionStore;
import dev.tethium.agent.AgentAdapter.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LegacyUpdateBridgeTest {
    @TempDir Path root;
    @Test void bridgeKeepsOriginalPausesAndUserStopAndCatchesNewSessions() throws Exception {
        Path db=root.resolve("source.db"), ledger=root.resolve("ledger.json");
        try (var store=new SessionStore(db)) {
            String p=store.project(root), active=store.create(p,null,"active",root).id(), paused=store.create(p,null,"paused",root).id(), stopped=store.create(p,null,"stopped",root).id();
            store.pauseQueue(paused,true); String run=store.beginRun(stopped,Provider.CODEX);
            LegacyUpdateBridge.main(new String[]{"pause",db.toString(),ledger.toString()});
            assertTrue(store.queuePaused(active));
            String child=store.create(p,null,"new",root).id(); store.enqueue(child,null,"after drain");
            assertTrue(store.queuePaused(child));
            LegacyUpdateBridge.main(new String[]{"pause",db.toString(),ledger.toString()});
            store.finishRun(run,"CANCELLED");
            LegacyUpdateBridge.main(new String[]{"backup",db.toString(),root.resolve("backup.db").toString()});
            LegacyUpdateBridge.main(new String[]{"restore",db.toString(),ledger.toString()});
            assertFalse(store.queuePaused(active)); assertFalse(store.queuePaused(child)); assertTrue(store.queuePaused(paused)); assertTrue(store.queuePaused(stopped));
            store.pauseQueue(active,true); LegacyUpdateBridge.main(new String[]{"restore",db.toString(),ledger.toString()}); assertTrue(store.queuePaused(active));
            String fresh=store.create(p,null,"fresh",root).id(); store.enqueue(fresh,null,"normal"); assertFalse(store.queuePaused(fresh));
        }
    }
}
