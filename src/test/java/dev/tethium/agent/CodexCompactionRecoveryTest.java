package dev.tethium.agent;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class CodexCompactionRecoveryTest {
    @TempDir Path root;
    private List<String> command(String scenario) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), "-cp",
                System.getProperty("tethium.test.classpath"), Fixture.class.getName(), scenario);
    }
    private AgentAdapter.Result run(String scenario, List<AgentAdapter.Event> events, Cancellation cancellation) throws Exception {
        var request = new AgentAdapter.Request(root, "hello", "codex-thread", null, null, null,
                Duration.ofSeconds(5), "danger-full-access", null, null, List.of(root.resolve("screen.png")));
        return new CodexAdapter(command(scenario)).run(request, events::add, cancellation);
    }
    private long calls(String method) throws Exception {
        return Files.readAllLines(root.resolve("calls.jsonl")).stream().filter(s -> s.contains("\"method\":\"" + method + "\"")).count();
    }
    @Test void repairsPreTurnCompactionThenRestoresSearchAndOriginalInput() throws Exception {
        var events = new ArrayList<AgentAdapter.Event>();
        var result = run("recover", events, new Cancellation());
        assertEquals(AgentAdapter.Status.COMPLETED, result.status());
        assertEquals("Recovered", result.text());
        assertEquals("codex-thread", result.conversationId());
        assertEquals(2, calls("turn/start"));
        assertEquals(1, calls("thread/compact/start"));
        assertEquals(1, events.stream().filter(e -> e.type().equals("finished")).count());
        var notices = events.stream().filter(e -> e.raw().path("params").path("item").path("type").asText().equals("contextCompaction")).toList();
        assertEquals(List.of("item/started", "item/completed"), notices.stream().map(AgentAdapter.Event::type).toList());
        assertTrue(notices.stream().allMatch(e -> e.raw().path("params").path("item").path("id").asText().equals("original-compact")));
    }
    @Test void neverReplaysAfterAgentTextToolItemsOrInteractiveRequests() throws Exception {
        for (String scenario : List.of("after-text", "after-tool", "after-request")) {
            Files.deleteIfExists(root.resolve("calls.jsonl"));
            var result = run(scenario, new ArrayList<>(), new Cancellation());
            assertEquals(AgentAdapter.Status.FAILED, result.status(), scenario);
            assertEquals(1, calls("turn/start"), scenario);
            assertEquals(0, calls("thread/compact/start"), scenario);
        }
    }
    @Test void doesNotTreatOtherErrorsOrMissingCompactionAsRecoverable() throws Exception {
        for (String scenario : List.of("other-error", "no-compact")) {
            Files.deleteIfExists(root.resolve("calls.jsonl"));
            assertEquals(AgentAdapter.Status.FAILED, run(scenario, new ArrayList<>(), new Cancellation()).status());
            assertEquals(0, calls("thread/compact/start"));
        }
    }
    @Test void recoveryIsBoundedToOneAttempt() throws Exception {
        assertEquals(AgentAdapter.Status.FAILED, run("repeat", new ArrayList<>(), new Cancellation()).status());
        assertEquals(2, calls("turn/start"));
        assertEquals(1, calls("thread/compact/start"));
    }
    @Test void failedRecoveryDoesNotStartTheUserTurnAgain() throws Exception {
        assertThrows(IOException.class, () -> run("recovery-fails", new ArrayList<>(), new Cancellation()));
        assertEquals(1, calls("turn/start"));
        assertEquals(1, calls("thread/compact/start"));
    }
    @Test void stopDuringRecoveryPreventsResubmittingTheMessage() throws Exception {
        var cancellation = new Cancellation();
        var events = new ArrayList<AgentAdapter.Event>() {
            @Override public boolean add(AgentAdapter.Event event) {
                if (event.type().equals("item/completed") && event.raw().path("params").path("item").path("type").asText().equals("contextCompaction")) cancellation.cancel();
                return super.add(event);
            }
        };
        assertEquals(AgentAdapter.Status.CANCELLED, run("recover", events, cancellation).status());
        assertEquals(1, calls("turn/start"));
    }

    public static class Fixture {
        static final String ERROR = "Error running remote compact task: " + Json.object("type", "error", "error", Json.object("message", "The 'web_search' tool is not available for this request.", "type", "invalid_request_error", "param", "tools", "code", "unsupported_parameter"), "status", 400);
        static void out(Object value) { System.out.println(value); System.out.flush(); }
        static void event(String method, Object... pairs) { out(Json.object("method", method, "params", Json.object(pairs))); }
        static void item(String method, String id) { event(method, "threadId", "codex-thread", "item", Json.object("id", id, "type", "contextCompaction")); }
        static void completed(String status, String error) {
            event("turn/completed", "threadId", "codex-thread", "turn", Json.object("id", "turn-1", "status", status, "error", error == null ? null : Json.object("message", error)));
        }
        public static void main(String[] args) throws Exception {
            String scenario = args[0]; boolean recovery = false;
            var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                var r = Json.parse(line); String method = r.path("method").asText(); var p = r.path("params"); var id = r.get("id");
                if (method.isEmpty()) continue;
                Files.writeString(Path.of("calls.jsonl"), Json.object("method", method, "search", p.path("config").path("web_search").asText()).toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                switch (method) {
                    case "initialize" -> out(Json.object("id", id, "result", Json.object()));
                    case "thread/resume" -> {
                        recovery = p.path("config").path("web_search").asText().equals("disabled");
                        if (recovery && (!p.path("model").asText().equals("restored-model") || !p.path("config").path("model_reasoning_effort").asText().equals("high"))) throw new AssertionError("Recovery changed the effective model or effort");
                        if (!p.path("approvalPolicy").asText().equals("never") || !p.path("sandbox").asText().equals("danger-full-access")) throw new AssertionError("Changed access");
                        out(Json.object("id", id, "result", Json.object("thread", Json.object("id", "codex-thread"), "model", "restored-model", "reasoningEffort", "high")));
                    }
                    case "turn/start" -> {
                        if (recovery) throw new AssertionError("Search must be restored before running the user turn");
                        var inputs = p.path("input");
                        if (inputs.size() != 2 || !inputs.get(0).path("text").asText().equals("hello") || !inputs.get(1).path("path").asText().endsWith("screen.png")) throw new AssertionError("Original prompt and attachment must be preserved");
                        out(Json.object("id", id, "result", Json.object("turn", Json.object("id", "turn-1"))));
                        if (Files.exists(Path.of("compact.done")) && !scenario.equals("repeat")) {
                            event("item/agentMessage/delta", "threadId", "codex-thread", "delta", "Recovered"); completed("completed", null); continue;
                        }
                        if (!scenario.equals("no-compact")) item("item/started", "original-compact");
                        if (scenario.equals("after-text")) event("item/agentMessage/delta", "threadId", "codex-thread", "delta", "Already working");
                        if (scenario.equals("after-tool")) event("item/started", "threadId", "codex-thread", "item", Json.object("type", "commandExecution", "id", "command"));
                        if (scenario.equals("after-request")) out(Json.object("id", "ask", "method", "item/tool/requestUserInput", "params", Json.object("threadId", "codex-thread")));
                        completed("failed", scenario.equals("other-error") ? ERROR.replace("unsupported_parameter", "rate_limit") : ERROR);
                    }
                    case "thread/compact/start" -> {
                        if (!recovery) throw new AssertionError("Compaction recovery must disable only built-in search");
                        out(Json.object("id", id, "result", Json.object()));
                        item("item/started", "recovery-compact");
                        if (scenario.equals("recovery-fails")) { completed("failed", ERROR); continue; }
                        Files.writeString(Path.of("compact.done"), "done");
                        item("item/completed", "recovery-compact"); completed("completed", null);
                    }
                }
            }
        }
    }
}
