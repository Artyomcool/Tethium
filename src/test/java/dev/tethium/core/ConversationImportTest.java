package dev.tethium.core;

import dev.tethium.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ConversationImportTest {
    @TempDir Path root;
    @Test void importIsIdempotentAndOriginalAssistantContextReachesNewCodexThread() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            String project = store.project(root);
            var messages = List.of(new SessionStore.ImportedMessage("1", "user", "Нужны проекты и сессии"), new SessionStore.ImportedMessage("2", "assistant", "Выбрали Swing и Java 25"));
            var session = store.importConversation(project, root, "Разработка", "nimbalyst:example", messages);
            assertEquals(session.id(), store.importConversation(project, root, "Разработка", "nimbalyst:example", messages).id());
            assertEquals(2, store.messages(session.id(), 0, 100).size());
            assertTrue(session.active());
            AgentAdapter adapter = new AgentAdapter() {
                public Provider provider() { return Provider.CODEX; }
                public Result run(Request request, Consumer<Event> events, Cancellation cancel) {
                    assertNull(request.conversationId());
                    assertTrue(request.prompt().contains("Нужны проекты и сессии"));
                    assertTrue(request.prompt().contains("Выбрали Swing и Java 25"));
                    return new Result("new-independent-thread", Status.COMPLETED, "Продолжаем");
                }
            };
            new SessionRunner(store).run(session.id(), adapter, "Дальше", null, null, e -> {}, new Cancellation());
            assertEquals("new-independent-thread", store.binding(session.id(), AgentAdapter.Provider.CODEX).conversationId());
        }
    }
    @Test void invalidImportDoesNotLeaveHalfImportedSession() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            String project = store.project(root);
            assertThrows(IllegalArgumentException.class, () -> store.importConversation(project, root, "Bad", "example", List.of(new SessionStore.ImportedMessage("1", "tool", "Not a conversation message"))));
            assertTrue(store.list(project).isEmpty());
        }
    }
}
