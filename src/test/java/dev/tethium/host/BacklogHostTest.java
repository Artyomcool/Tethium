package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** The whole way through the real core: a task is put off, shows on the project, starts, runs and leaves the list. */
class BacklogHostTest {
    @TempDir Path root;
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final AgentAdapter adapter = new AgentAdapter() {
        public Provider provider() { return Provider.CODEX; }
        public Result run(Request r, Consumer<Event> events, Cancellation cancel) { prompts.add(r.prompt()); return new Result("c", Status.COMPLETED, "готово"); }
    };
    private void until(java.util.concurrent.Callable<Boolean> test, String what) throws Exception { for (int i = 0; i < 200; i++) { if (test.call()) return; Thread.sleep(50); } fail("Timed out: " + what); }
    private static JsonNode project(HostClient c) throws Exception { return c.get("/state").path("projects").get(0); }
    @Test void aTaskStartsRunsAndLeavesTheList() throws Exception {
        try (var h = new HostService(root, adapter); var c = new HostClient(h.descriptor())) {
            String p = c.post("/projects", Json.object("path", root.toString())).path("id").asText();
            String s = c.post("/sessions", Json.object("projectId", p, "title", "Логика")).path("id").asText();
            assertEquals(0, project(c).path("backlog").asInt());
            String waiting = c.post("/backlog", Json.object("projectId", p, "title", "Ждёт", "prompt", "Позже", "target", "session", "sessionId", s, "minQuota", 80, "minFreeCpu", 0)).path("id").asText();
            String free = c.post("/backlog", Json.object("projectId", p, "title", "Свободная", "prompt", "Сделай это", "target", "named", "sessionTitle", "Мелкие задачи", "minQuota", 0, "minFreeCpu", 0)).path("id").asText();
            assertEquals(2, project(c).path("backlog").asInt(), "the counter on the project");
            h.observeBacklog();
            until(() -> prompts.stream().anyMatch(t -> t.contains("Сделай это")), "the free task ran");
            assertTrue(prompts.stream().noneMatch(t -> t.contains("Позже")), "no quota data: this one waits");
            until(() -> { h.observeBacklog(); for (var t : c.get("/backlog").path("tasks")) if (t.path("id").asText().equals(free)) return false; return true; }, "a finished task leaves the list");
            assertEquals(1, project(c).path("backlog").asInt());
            var left = c.get("/backlog").path("tasks").get(0);
            assertEquals("Нет данных о квоте", left.path("why").asText());
            boolean shared = false; for (var x : c.get("/state").path("sessions")) shared |= x.path("title").asText().equals("Мелкие задачи") && x.path("projectId").asText().equals(p);
            assertTrue(shared, "the shared session was created");
            c.post("/backlog/" + waiting + "/start", Json.object());
            until(() -> prompts.stream().anyMatch(t -> t.contains("Позже")), "started by hand");
            until(() -> { h.observeBacklog(); return c.get("/backlog").path("tasks").isEmpty(); }, "the list is empty");
        }
    }
}
