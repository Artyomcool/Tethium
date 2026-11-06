package dev.tethium.host;

import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** A parent's urgent correction holds its child's ordinary queue and says so; the child's own "now" is not held. */
class InterruptionHoldTest {
    @TempDir Path root;
    private static boolean running(HostClient c, String s) throws Exception { return c.get("/state?session=" + s).path("sessions").get(0).path("running").asBoolean(); }
    private static com.fasterxml.jackson.databind.JsonNode session(HostClient c, String s) throws Exception { for (var x : c.get("/state").path("sessions")) if (x.path("id").asText().equals(s)) return x; return Json.object(); }
    @Test void childQueueWaitsWithAReasonButItsOwnUrgentMessageStarts() throws Exception {
        var adapter = new AgentAdapter() {
            public Provider provider() { return Provider.CODEX; }
            public Result run(Request r, Consumer<Event> events, Cancellation cancel) throws Exception {
                events.accept(new Event("text", "работаю", Json.object())); while (!cancel.isCancelled()) Thread.sleep(20); return new Result("c", Status.CANCELLED, "стоп");
            }
        };
        try (var host = new HostService(root, adapter); var client = new HostClient(host.descriptor())) {
            String p = client.post("/projects", Json.object("path", root.toString())).path("id").asText();
            String parent = client.post("/sessions", Json.object("projectId", p, "title", "Логика")).path("id").asText();
            String child = client.post("/sessions", Json.object("projectId", p, "title", "Интерпретатор", "parentId", parent)).path("id").asText();
            // The parent's urgent correction covers every session, including this one.
            client.post("/sessions/" + parent + "/interrupt", Json.object("prompt", "срочно", "requestId", "i1", "scope", "all"));
            for (int i = 0; i < 100 && !running(client, parent); i++) Thread.sleep(50);
            assertTrue(running(client, parent));
            client.post("/sessions/" + child + "/run", Json.object("prompt", "обычное", "requestId", "r1"));
            Thread.sleep(400);
            var held = session(client, child);
            assertFalse(held.path("running").asBoolean(), "ordinary queue stays held");
            assertEquals(parent, held.path("hold").path("sessionId").asText(), held.toString());
            assertEquals("Логика", held.path("hold").path("title").asText());
            client.post("/sessions/" + child + "/interrupt", Json.object("prompt", "сейчас", "requestId", "i2"));
            for (int i = 0; i < 100 && !running(client, child); i++) Thread.sleep(50);
            assertTrue(running(client, child), "the child's own urgent message starts despite the hold");
            assertTrue(session(client, child).path("hold").isNull());
            client.post("/sessions/" + child + "/stop", Json.object()); client.post("/sessions/" + parent + "/stop", Json.object());
        }
    }
}
