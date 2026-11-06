package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** A technical session lights nothing, never waits for a person, and keeps working from the inactive list; ordinary sessions do not change. */
class TechnicalSessionTest {
    @TempDir Path root;
    private final Map<String, JsonNode> answers = new ConcurrentHashMap<>();
    private final AgentAdapter adapter = new AgentAdapter() {
        public Provider provider() { return Provider.CODEX; }
        public Result run(Request r, Consumer<Event> events, Cancellation cancel) throws Exception {
            if (r.prompt().endsWith("ask")) answers.put("question", r.requests().handle("item/tool/requestUserInput", Json.object("questions", List.of(Json.object("id", "q0", "question", "Что делать?"))), cancel));
            if (r.prompt().endsWith("ask")) answers.put("approval", r.requests().handle("item/commandExecution/requestApproval", Json.object("command", "rm"), cancel));
            answers.put("ran:" + r.prompt().substring(Math.max(0, r.prompt().length() - 3)), Json.object());
            return new Result("c", Status.COMPLETED, "готово");
        }
    };
    private static JsonNode session(HostClient c, String id) throws Exception { for (var s : c.get("/state").path("sessions")) if (s.path("id").asText().equals(id)) return s; throw new AssertionError(id); }
    private void until(java.util.concurrent.Callable<Boolean> test, String what) throws Exception { for (int i = 0; i < 300; i++) { if (test.call()) return; Thread.sleep(50); } fail("Timed out: " + what); }
    @Test void staysQuietWorksWhenInactiveAndNeverWaits() throws Exception {
        try (var h = new HostService(root, adapter); var c = new HostClient(h.descriptor())) {
            String p = c.post("/projects", Json.object("path", root.toString())).path("id").asText();
            String tech = c.post("/sessions", Json.object("projectId", p, "title", "Распознавание")).path("id").asText();
            String plain = c.post("/sessions", Json.object("projectId", p, "title", "Обычная")).path("id").asText();
            assertFalse(session(c, tech).path("technical").asBoolean(), "off by default");

            c.post("/sessions/" + plain + "/active", Json.object("active", false));
            assertThrows(Exception.class, () -> c.post("/sessions/" + plain + "/run", Json.object("prompt", "one", "requestId", "p1")), "an ordinary inactive session still refuses work");

            c.post("/sessions/" + tech + "/technical", Json.object("enabled", true));
            c.post("/sessions/" + tech + "/active", Json.object("active", false));
            assertTrue(session(c, tech).path("technical").asBoolean()); assertFalse(session(c, tech).path("active").asBoolean());
            c.post("/sessions/" + tech + "/run", Json.object("prompt", "ask", "requestId", "t1"));
            until(() -> answers.containsKey("question") && answers.containsKey("approval") && !session(c, tech).path("running").asBoolean(), "inactive technical session ran and did not wait");
            assertTrue(answers.get("question").path("answers").isObject(), answers.toString());
            assertEquals("decline", answers.get("approval").path("decision").asText(), "nobody approves on its behalf");
            assertEquals(0, c.get("/state?session=" + tech).path("conversation").path("requests").size(), "no question left hanging");

            c.post("/sessions/" + tech + "/attention", Json.object("marked", true));
            var attention = session(c, tech).path("attention");
            assertEquals("", attention.path("kind").asText(), "no indicator: " + attention);
            assertFalse(attention.path("unread").asBoolean()); assertFalse(attention.path("manual").asBoolean());

            c.post("/sessions/" + tech + "/technical", Json.object("enabled", false));
            assertEquals("manual", session(c, tech).path("attention").path("kind").asText(), "turning it off brings indicators back");
            assertThrows(Exception.class, () -> c.post("/sessions/" + tech + "/run", Json.object("prompt", "two", "requestId", "t2")), "and the inactive state counts again");
        }
    }
}
