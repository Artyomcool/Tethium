package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** The per-project switch opens cross-project delegation for that project's sessions only, in one direction, and can be closed again. */
class CrossProjectDelegationTest {
    @TempDir Path root;
    private final Map<String, JsonNode> seen = new ConcurrentHashMap<>();
    private final Map<String, String> calls = new ConcurrentHashMap<>();
    private static JsonNode mcp(AgentAdapter.Request r, String method, JsonNode params) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var body = Json.object("jsonrpc", "2.0", "id", 1, "method", method, "params", params);
            return Json.parse(http.send(HttpRequest.newBuilder(r.toolsUrl()).header("Authorization", "Bearer " + r.toolsToken()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString()).body()).path("result");
        }
    }
    /** "probe:<label>:<tool>:<json args>" reports the tool list and the tool result; anything else is ordinary work. */
    private final AgentAdapter adapter = new AgentAdapter() {
        public Provider provider() { return Provider.CODEX; }
        public Result run(Request r, Consumer<Event> events, Cancellation cancel) throws Exception {
            String prompt = r.prompt(); int at = prompt.lastIndexOf("probe:");
            if (at >= 0) {
                var parts = prompt.substring(at).split(":", 4);
                seen.put(parts[1] + ".tools", mcp(r, "tools/list", Json.object()).path("tools"));
                if (!parts[2].equals("none")) { var result = mcp(r, "tools/call", Json.object("name", parts[2], "arguments", Json.parse(parts[3]))); seen.put(parts[1] + ".call", result); }
            } else calls.put(r.directory().getFileName().toString(), prompt);
            return new Result("c", Status.COMPLETED, "готово");
        }
    };
    private static List<String> names(JsonNode tools) { var out = new ArrayList<String>(); for (var t : tools) out.add(t.path("name").asText()); return out; }
    private void until(java.util.function.BooleanSupplier test, String what) throws Exception { for (int i = 0; i < 200; i++) { if (test.getAsBoolean()) return; Thread.sleep(50); } fail("Timed out: " + what); }
    private void probe(HostClient c, String session, String label, String tool, JsonNode args) throws Exception {
        c.post("/sessions/" + session + "/run", Json.object("prompt", "probe:" + label + ":" + tool + ":" + args, "requestId", label));
        until(() -> seen.containsKey(label + ".tools") && (tool.equals("none") || seen.containsKey(label + ".call")), label);
        until(() -> { try { return !session(c, session).path("running").asBoolean(); } catch (Exception e) { return false; } }, label + " finished");
    }
    private static JsonNode session(HostClient c, String id) throws Exception { for (var s : c.get("/state").path("sessions")) if (s.path("id").asText().equals(id)) return s; throw new AssertionError(id); }
    private static String text(JsonNode call) { return call.path("content").get(0).path("text").asText(); }
    @Test void switchOpensDelegationFromOneProjectOnly() throws Exception {
        Path pa = Files.createDirectories(root.resolve("alpha")), pb = Files.createDirectories(root.resolve("beta"));
        try (var h = new HostService(root.resolve("data"), adapter); var c = new HostClient(h.descriptor())) {
            String a = c.post("/projects", Json.object("path", pa.toString())).path("id").asText(), b = c.post("/projects", Json.object("path", pb.toString())).path("id").asText();
            String sa = c.post("/sessions", Json.object("projectId", a, "title", "Отправитель")).path("id").asText(), sa2 = c.post("/sessions", Json.object("projectId", a, "title", "Сосед")).path("id").asText();
            String sb = c.post("/sessions", Json.object("projectId", b, "title", "Получатель")).path("id").asText();
            var send = Json.object("session_id", sb, "prompt", "сделай работу");

            probe(c, sa, "off", "cross_project_delegate", send);
            assertFalse(names(seen.get("off.tools")).contains("cross_project_delegate"), "the tool is hidden by default");
            assertFalse(names(seen.get("off.tools")).contains("cross_project_sessions"));
            assertTrue(seen.get("off.call").path("isError").asBoolean(), seen.get("off.call").toString());
            assertTrue(c.get("/state").path("projects").get(0).has("crossProject"));
            for (var p : c.get("/state").path("projects")) assertFalse(p.path("crossProject").asBoolean(), "off by default");

            c.post("/projects/" + a + "/cross-project", Json.object("enabled", true));
            for (var p : c.get("/state").path("projects")) assertEquals(p.path("id").asText().equals(a), p.path("crossProject").asBoolean(), "only the chosen project");

            probe(c, sa, "list", "cross_project_sessions", Json.object());
            assertTrue(names(seen.get("list.tools")).containsAll(List.of("cross_project_sessions", "cross_project_delegate")));
            var listed = Json.parse(text(seen.get("list.call")));
            assertEquals(1, listed.size(), "other projects only: " + listed); assertEquals("beta", listed.get(0).path("title").asText());
            assertEquals(sb, listed.get(0).path("sessions").get(0).path("id").asText());

            probe(c, sa, "own", "cross_project_delegate", Json.object("session_id", sa2, "prompt", "x"));
            assertTrue(seen.get("own.call").path("isError").asBoolean(), "the same project stays on session_delegate");
            probe(c, sa, "plain", "session_delegate", send);
            assertTrue(seen.get("plain.call").path("isError").asBoolean(), "the ordinary tool stays inside the project");

            probe(c, sa, "send", "cross_project_delegate", send);
            assertFalse(seen.get("send.call").path("isError").asBoolean(), seen.get("send.call").toString());
            until(() -> calls.containsKey("beta"), "delegated work ran");
            assertTrue(calls.get("beta").contains("alpha") && calls.get("beta").contains("Отправитель") && calls.get("beta").contains("сделай работу"), "the recipient sees who asks: " + calls.get("beta"));
            until(() -> { try { return c.get("/state?session=" + sa).path("conversation").path("messages").toString().contains("Результат сессии"); } catch (Exception e) { return false; } }, "the result came back to the sender");

            probe(c, sb, "reverse", "cross_project_delegate", Json.object("session_id", sa, "prompt", "назад"));
            assertFalse(names(seen.get("reverse.tools")).contains("cross_project_delegate"), "the recipient project has no switch");
            assertTrue(seen.get("reverse.call").path("isError").asBoolean());

            c.post("/projects/" + a + "/cross-project", Json.object("enabled", false));
            probe(c, sa, "closed", "cross_project_delegate", send);
            assertFalse(names(seen.get("closed.tools")).contains("cross_project_delegate"));
            assertTrue(seen.get("closed.call").path("isError").asBoolean(), "switching off takes effect at once");
        }
    }
}
