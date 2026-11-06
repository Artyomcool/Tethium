package dev.tethium.core;

import dev.tethium.agent.AgentAdapter.Provider;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionResetTest {
    @TempDir Path root;
    @Test void unbindForgetsExecutorConversationsButKeepsTheChat() throws Exception {
        try (var store = new SessionStore(root.resolve("s.db"))) {
            String id = store.create(store.project(root), null, "Reset", root).id();
            store.append(id, "user", "USER", "hello"); store.bind(id, Provider.CODEX, "thread-1", 1); store.bind(id, Provider.CLAUDE, "conv-1", 1);
            assertEquals(2, store.unbind(id));
            assertNull(store.binding(id, Provider.CODEX).conversationId()); assertNull(store.binding(id, Provider.CLAUDE).conversationId());
            assertEquals(1, store.messages(id, 0, 10).size(), "chat history survives a context reset");
        }
    }
    @Test void deletingMessagesBumpsTheHistoryRevisionAndTrimsAfterAPoint() throws Exception {
        try (var store = new SessionStore(root.resolve("s.db"))) {
            String id = store.create(store.project(root), null, "Trim", root).id();
            for (int i = 1; i <= 5; i++) store.append(id, i % 2 == 1 ? "user" : "assistant", "USER", "m" + i);
            var all = store.messages(id, 0, 10); assertEquals(5, all.size()); long revision = store.messageRevision(id);
            assertEquals(1, store.deleteMessages(id, List.of(all.get(1).sequence())));
            assertEquals(List.of("m1", "m3", "m4", "m5"), store.messages(id, 0, 10).stream().map(SessionStore.Message::text).toList());
            assertTrue(store.messageRevision(id) > revision, "readers must notice the edited history");
            assertEquals(2, store.deleteMessagesAfter(id, all.get(2).sequence()));
            assertEquals(List.of("m1", "m3"), store.messages(id, 0, 10).stream().map(SessionStore.Message::text).toList());
            assertEquals(2, store.historyIndex(id).size());
            assertEquals(0, store.deleteMessages(id, List.of(999_999L)), "unknown sequences are ignored");
        }
    }
    @Test void resetStateClearsAutonomyScheduleQueuePauseMarkAndDraft() throws Exception {
        try (var store = new SessionStore(root.resolve("s.db"))) {
            String id = store.create(store.project(root), null, "State", root).id();
            store.autonomy(id, "start", "", "finish the thing", ""); store.periodicSchedule(id, "set", 300, "check", System.currentTimeMillis());
            store.pauseQueue(id, true); store.marked(id, true); store.draft(id, "draft text"); store.draftImages(id, Json.MAPPER.createArrayNode().add("x.png"));
            store.resetState(id);
            assertEquals("off", store.autonomy(id).path("state").asText()); assertEquals("off", store.periodicSchedule(id).path("state").asText());
            assertFalse(store.queuePaused(id)); assertFalse(store.marked(id));
            assertEquals("", store.preferences(id).path("draft").asText()); assertTrue(store.preferences(id).path("draftImages").isEmpty());
        }
    }
}
