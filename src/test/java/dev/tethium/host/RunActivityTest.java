package dev.tethium.host;

import dev.tethium.agent.AgentAdapter.Event;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunActivityTest {
    @Test void preparationAndConnectionReplaceWaitingForWorkerDetail() {
        var activity = new RunActivity();
        assertEquals("Ожидает свободного исполнителя", activity.snapshot().path("detail").asText());
        activity.preparingContext();
        assertEquals("Читает историю разговора", activity.snapshot().path("detail").asText());
        assertEquals("Подключение Codex", activity.accept(new Event("connecting", "", Json.object())));
        assertEquals("Запускает Codex и подключает разговор", activity.snapshot().path("detail").asText());
    }
    @Test void progressTracksConcurrentActionsWithoutExposingReasoning() {
        var activity = new RunActivity();
        activity.accept(new Event("item/started", "", Json.object("params", Json.object("item", Json.object("id", "r", "type", "reasoning", "text", "PRIVATE")))));
        activity.accept(event("item/started", "one", "echo hello", null));
        activity.accept(event("item/started", "two", "echo other", null));
        activity.accept(event("item/completed", "one", "echo hello", 0));
        activity.accept(event("item/completed", "one", "echo hello", 0));
        var state = activity.snapshot();
        assertEquals(1, state.path("completedActions").asInt());
        assertEquals(1, state.path("pendingActions").asInt());
        assertTrue(state.path("detail").asText().contains("echo other"));
        assertFalse(state.toString().contains("PRIVATE"));
        activity.accept(event("item/completed", "two", "echo other", 1));
        assertTrue(activity.snapshot().path("detail").asText().startsWith("Ошибка"));
    }
    private static Event event(String type, String id, String command, Integer code) {
        return new Event(type, "", Json.object("params", Json.object("item", Json.object("type", "commandExecution", "id", id, "command", command, "exitCode", code))));
    }
}
