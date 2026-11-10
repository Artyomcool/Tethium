package dev.tethium.agent;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class CodexSearchRecoveryTest {
    @TempDir Path root;
    private AgentAdapter.Result run(String scenario, List<AgentAdapter.Event> events, Cancellation cancellation) throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("tethium.test.classpath"), Fixture.class.getName(), scenario);
        var request = new AgentAdapter.Request(root, "original prompt", "thread", null, null, null,
                Duration.ofSeconds(5), "danger-full-access", null, null, List.of(root.resolve("screen.png")));
        return new CodexAdapter(command).run(request, events::add, cancellation);
    }
    private long calls(String method) throws Exception {
        return Files.readAllLines(root.resolve("calls.jsonl")).stream().filter(s -> s.contains("\"method\":\"" + method + "\"")).count();
    }
    @Test void retriesOrdinaryPreGenerationRejectionWithoutDuplicatingUserInput() throws Exception {
        var events = new ArrayList<AgentAdapter.Event>();
        assertEquals("Recovered", run("recover", events, new Cancellation()).text());
        assertEquals(2, calls("turn/start"));
        assertEquals(1, calls("thread/revert"));
        assertEquals(0, calls("thread/compact/start"));
        assertEquals(1, events.stream().filter(e -> e.type().equals("finished")).count());
    }
    @Test void neverRetriesAfterAgentTextReasoningToolsOrQuestions() throws Exception {
        for (String scenario : List.of("text", "reasoning", "reasoning-delta", "tool", "question")) {
            Files.deleteIfExists(root.resolve("calls.jsonl"));
            assertEquals(AgentAdapter.Status.FAILED, run(scenario, new ArrayList<>(), new Cancellation()).status(), scenario);
            assertEquals(1, calls("turn/start"), scenario);
            assertEquals(0, calls("thread/revert"), scenario);
        }
    }
    @Test void rejectsOtherErrorsAndBoundsRetryToOnce() throws Exception {
        assertEquals(AgentAdapter.Status.FAILED, run("other-error", new ArrayList<>(), new Cancellation()).status());
        assertEquals(1, calls("turn/start"));
        Files.delete(root.resolve("calls.jsonl"));
        assertEquals(AgentAdapter.Status.FAILED, run("repeat", new ArrayList<>(), new Cancellation()).status());
        assertEquals(2, calls("turn/start"));
        assertEquals(1, calls("thread/revert"));
    }
    @Test void doesNotEraseLaterTurnsOrUnexpectedItemsAndDoesNotRepeatOnRevertFailure() throws Exception {
        for (String scenario : List.of("changed", "unexpected-item", "revert-fails")) {
            Files.deleteIfExists(root.resolve("calls.jsonl"));
            assertThrows(IOException.class, () -> run(scenario, new ArrayList<>(), new Cancellation()), scenario);
            assertEquals(1, calls("turn/start"), scenario);
        }
    }
    @Test void stopBeforeRetryDoesNotResubmit() throws Exception {
        var cancellation = new Cancellation();
        var events = new ArrayList<AgentAdapter.Event>() {
            @Override public boolean add(AgentAdapter.Event e) {
                if (e.type().equals("error")) cancellation.cancel();
                return super.add(e);
            }
        };
        assertEquals(AgentAdapter.Status.CANCELLED, run("recover", events, cancellation).status());
        assertEquals(1, calls("turn/start"));
        assertEquals(0, calls("thread/revert"));
    }

    public static class Fixture {
        static void out(Object o) { System.out.println(o); System.out.flush(); }
        static void event(String method, Object... pairs) { out(Json.object("method", method, "params", Json.object(pairs))); }
        static void item(String type) { event("item/started", "threadId", "thread", "item", Json.object("id", type, "type", type)); }
        public static void main(String[] args) throws Exception {
            String scenario = args[0]; boolean retry = false, reverted = false;
            String error = Json.object("type", "error", "error", Json.object("message", "The 'web_search' tool is not available for this request.",
                    "type", "invalid_request_error", "param", "tools", "code", "unsupported_parameter"), "status", 400).toString();
            var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                var r = Json.parse(line); var p = r.path("params"); var id = r.get("id"); String method = r.path("method").asText();
                if (method.isEmpty()) continue;
                Files.writeString(Path.of("calls.jsonl"), Json.object("method", method, "search", p.path("config").path("web_search").asText()).toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                switch (method) {
                    case "initialize" -> out(Json.object("id", id, "result", Json.object()));
                    case "thread/resume" -> {
                        retry = p.path("config").path("web_search").asText().equals("disabled");
                        if (retry && (!p.path("model").asText().equals("effective-model") || !p.path("config").path("model_reasoning_effort").asText().equals("xhigh"))) throw new AssertionError("Changed effective model/effort");
                        if (!p.path("sandbox").asText().equals("danger-full-access") || !p.path("approvalPolicy").asText().equals("never")) throw new AssertionError("Changed permissions");
                        out(Json.object("id", id, "result", Json.object("model", "effective-model", "reasoningEffort", "xhigh", "thread", Json.object("id", "thread"))));
                    }
                    case "thread/turns/list" -> out(Json.object("id", id, "result", Json.object("data", List.of(Json.object("id", scenario.equals("changed") ? "another-turn" : "failed-turn", "status", "failed", "items", List.of(Json.object("type", scenario.equals("unexpected-item") ? "commandExecution" : "userMessage")))))));
                    case "thread/revert" -> {
                        if (!p.path("beforeTurnId").asText().equals("failed-turn")) throw new AssertionError("Wrong revert target");
                        if (scenario.equals("revert-fails")) out(Json.object("id", id, "error", Json.object("code", -1, "message", "revert failed")));
                        else { reverted = true; out(Json.object("id", id, "result", Json.object())); }
                    }
                    case "turn/start" -> {
                        if (!p.path("input").path(0).path("text").asText().equals("original prompt") || !p.path("input").path(1).path("path").asText().endsWith("screen.png")) throw new AssertionError("Lost prompt/image");
                        if (retry && !reverted) throw new AssertionError("Duplicated input");
                        out(Json.object("id", id, "result", Json.object("turn", Json.object("id", "failed-turn"))));
                        if (retry && !scenario.equals("repeat")) {
                            event("item/agentMessage/delta", "threadId", "thread", "delta", "Recovered");
                            event("turn/completed", "threadId", "thread", "turn", Json.object("id", "retry-turn", "status", "completed"));
                        } else {
                            item("userMessage");
                            if (scenario.equals("text")) event("item/agentMessage/delta", "threadId", "thread", "delta", "Working");
                            if (scenario.equals("reasoning")) item("reasoning");
                            if (scenario.equals("reasoning-delta")) event("item/reasoning/summaryTextDelta", "threadId", "thread", "delta", "Working");
                            if (scenario.equals("tool")) item("commandExecution");
                            if (scenario.equals("question")) out(Json.object("id", "question", "method", "item/tool/requestUserInput", "params", Json.object("threadId", "thread")));
                            String reason = scenario.equals("other-error") ? error.replace("unsupported_parameter", "rate_limit") : error;
                            event("error", "threadId", "thread", "error", Json.object("message", reason));
                            event("turn/completed", "threadId", "thread", "turn", Json.object("id", "failed-turn", "status", "failed", "error", Json.object("message", reason)));
                        }
                    }
                }
            }
        }
    }
}
