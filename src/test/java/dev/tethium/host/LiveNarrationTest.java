package dev.tethium.host;

import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** The live answer narrates tool calls and shows a capped start of each thought; the stored message keeps only the text. */
class LiveNarrationTest {
    @TempDir Path root;
    @Test void liveTextCarriesActionsAndCappedThoughtsAndTheMessageKeepsThem() throws Exception {
        var adapter = new AgentAdapter() {
            public Provider provider() { return Provider.CODEX; }
            public Result run(Request r, Consumer<Event> events, Cancellation cancel) throws Exception {
                var thought = Json.object("id", "t1", "type", "reasoning");
                events.accept(new Event("item/started", "", Json.object("method", "item/started", "params", Json.object("item", thought))));
                events.accept(new Event("thinking", "Сначала посмотрю тесты. ", Json.object()));
                events.accept(new Event("thinking", "x".repeat(2000), Json.object()));
                events.accept(new Event("item/completed", "", Json.object("method", "item/completed", "params", Json.object("item", thought))));
                var tool = Json.object("id", "c1", "type", "commandExecution", "command", "Запускает тесты шлюза", "detail", "gradlew test");
                events.accept(new Event("item/started", "", Json.object("method", "item/started", "params", Json.object("item", tool))));
                events.accept(new Event("item/completed", "", Json.object("method", "item/completed", "params", Json.object("item", tool.deepCopy().put("status", "completed")))));
                var second = Json.object("id", "c2", "type", "fileChange", "changes", Json.MAPPER.createArrayNode().add(Json.object("path", "C:/x/A.java", "kind", "update")));
                events.accept(new Event("item/started", "", Json.object("method", "item/started", "params", Json.object("item", second))));
                var empty = Json.object("id", "t2", "type", "reasoning");
                events.accept(new Event("item/started", "", Json.object("method", "item/started", "params", Json.object("item", empty))));
                events.accept(new Event("item/completed", "", Json.object("method", "item/completed", "params", Json.object("item", empty))));
                events.accept(new Event("text", "Тесты прошли.", Json.object()));
                while (!cancel.isCancelled()) Thread.sleep(20);
                return new Result("c", Status.CANCELLED, "Тесты прошли.");
            }
        };
        try (var host = new HostService(root, adapter); var client = new HostClient(host.descriptor())) {
            String p = client.post("/projects", Json.object("path", root.toString())).path("id").asText();
            String s = client.post("/sessions", Json.object("projectId", p)).path("id").asText();
            client.post("/sessions/" + s + "/run", Json.object("prompt", "go", "requestId", "n1"));
            String live = "";
            for (int i = 0; i < 300 && !live.contains("Тесты прошли."); i++) { Thread.sleep(50); live = client.get("/state?session=" + s).path("conversation").path("live").asText(); }
            assertTrue(live.startsWith("\n\n```thought\nСначала посмотрю тесты. "), live);
            assertTrue(live.contains("x".repeat(100) + "…\n```\n\n"), "thought is capped: " + live.length());
            assertFalse(live.contains("x".repeat(1600)), "thought is capped");
            assertTrue(live.contains("\n\n" + dev.tethium.core.ActionLines.MARK + "Команда: Запускает тесты шлюза  \n" + dev.tethium.core.ActionLines.MARK + "Файлы: C:/x/A.java\n\nТесты прошли."), "actions share one paragraph: " + live);
            assertEquals(1, live.split("```thought").length - 1, "an empty thought leaves no block: " + live);
            assertTrue(live.endsWith("Тесты прошли."), live);
            client.post("/sessions/" + s + "/stop", Json.object());
            for (int i = 0; i < 100 && client.get("/state?session=" + s).path("sessions").get(0).path("running").asBoolean(); i++) Thread.sleep(50);
            var messages = client.get("/state?session=" + s).path("conversation").path("messages");
            String last = messages.get(messages.size() - 1).path("text").asText();
            assertEquals(live.strip(), last, "the message keeps what was seen live: thoughts and reports");
        }
    }
}
