package dev.tethium.host;

import dev.tethium.agent.*;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class HostServiceTest {
    @TempDir Path data;
    private static final class Adapter implements AgentAdapter {
        final BlockingQueue<Request> started = new LinkedBlockingQueue<>();
        final Semaphore release = new Semaphore(0);
        final List<String> prompts = new CopyOnWriteArrayList<>();
        public Provider provider() { return Provider.CODEX; }
        public Result run(Request r, Consumer<Event> events, Cancellation cancel) throws Exception {
            prompts.add(r.prompt()); started.add(r);
            events.accept(new Event("conversation", "persistent-thread", Json.object()));
            events.accept(new Event("text", "Сохранённый фрагмент", Json.object()));
            if (r.prompt().endsWith("approval")) {
                var answer = r.requests().handle("item/commandExecution/requestApproval", Json.object("command", "echo test"), cancel);
                assertEquals("decline", answer.path("decision").asText());
            } else while (!release.tryAcquire(30, TimeUnit.MILLISECONDS)) if (cancel.isCancelled()) return new Result("persistent-thread", Status.CANCELLED, "");
            return new Result("persistent-thread", Status.COMPLETED, "Готово");
        }
    }
    private String session(HostClient c) throws Exception {
        String p = c.post("/projects", Json.object("path", data.toString())).path("id").asText();
        return c.post("/sessions", Json.object("projectId", p, "title", "Работа")).path("id").asText();
    }
    private JsonNode until(HostClient c, String id, java.util.function.Predicate<JsonNode> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { var s = c.get("/state?session=" + id); if (condition.test(s)) return s; Thread.sleep(30); }
        throw new AssertionError("Host state did not converge");
    }
    @Test void sixteenSessionsRunAndTheSeventeenthWaitsForAFreeSlot() throws Exception {
        var adapter = new Adapter();
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            for (int i = 0; i < 16; i++) {
                String id = session(c);
                c.post("/sessions/" + id + "/run", Json.object("prompt", "parallel-" + i));
                assertNotNull(adapter.started.poll(5, TimeUnit.SECONDS), "Session " + i + " should start");
            }
            String waiting = session(c);
            c.post("/sessions/" + waiting + "/run", Json.object("prompt", "waiting"));
            assertNull(adapter.started.poll(300, TimeUnit.MILLISECONDS), "The seventeenth session must wait");
            assertEquals(1, c.get("/state?session=" + waiting).path("conversation").path("queued").size());
            adapter.release.release();
            var next = adapter.started.poll(5, TimeUnit.SECONDS);
            assertNotNull(next);
            assertTrue(next.prompt().endsWith("waiting"));
            adapter.release.release(16);
            until(c, waiting, s -> {
                for (var session : s.path("sessions")) if (session.path("running").asBoolean()) return false;
                return true;
            });
        }
    }
    @Test void queuedTurnUsesNewModelAndEffortWhileCurrentTurnKeepsItsSettings() throws Exception {
        var adapter = new Adapter();
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            String id = session(c);
            c.post("/sessions/" + id + "/settings", Json.object("model", "model-a", "reasoningEffort", "high"));
            c.post("/sessions/" + id + "/run", Json.object("prompt", "first"));
            var first = adapter.started.poll(5, TimeUnit.SECONDS); assertNotNull(first); assertEquals("high", first.reasoningEffort());
            c.post("/sessions/" + id + "/settings", Json.object("model", "model-b", "reasoningEffort", "max"));
            c.post("/sessions/" + id + "/settings", Json.object("model", "model-b")); // an older client must not erase effort
            c.post("/sessions/" + id + "/run", Json.object("prompt", "next")); adapter.release.release();
            var next = adapter.started.poll(5, TimeUnit.SECONDS); assertNotNull(next);
            assertEquals("model-b", next.model()); assertEquals("max", next.reasoningEffort());
            assertEquals("model-a", first.model()); assertEquals("high", first.reasoningEffort());
            adapter.release.release(); until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
        }
        try (var restarted = new HostService(data, adapter); var c = new HostClient(restarted.descriptor())) {
            assertEquals("max", c.get("/state").path("sessions").get(0).path("preferences").path("reasoningEffort").asText());
        }
    }
    @Test void closingClientKeepsWorkRunningAndQueueResumesSameConversation() throws Exception {
        var adapter = new Adapter();
        try (var host = new HostService(data, adapter)) {
            String id;
            try (var first = new HostClient(host.descriptor())) {
                id = session(first); first.post("/sessions/" + id + "/run", Json.object("prompt", "first"));
                assertNotNull(adapter.started.poll(5, TimeUnit.SECONDS));
                first.post("/sessions/" + id + "/run", Json.object("prompt", "second"));
            }
            try (var reopened = new HostClient(host.descriptor())) {
                assertTrue(reopened.get("/state").path("sessions").get(0).path("running").asBoolean());
                adapter.release.release();
                assertEquals("persistent-thread", adapter.started.poll(5, TimeUnit.SECONDS).conversationId());
                adapter.release.release();
                var finalState = until(reopened, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
                assertEquals(4, finalState.path("conversation").path("messages").size());
                assertTrue(finalState.path("sessions").get(0).path("active").asBoolean());
                reopened.post("/sessions/" + id + "/draft", Json.object("draft", "Черновик"));
            }
        }
        try (var restarted = new HostService(data, adapter); var client = new HostClient(restarted.descriptor())) {
            var s = client.get("/state").path("sessions").get(0);
            assertEquals("Черновик", s.path("preferences").path("draft").asText());
            assertEquals("COMPLETED", s.path("status").asText());
        }
    }
    @Test void approvalsCanBeAnsweredAndStopKeepsPartialOutput() throws Exception {
        var adapter = new Adapter();
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            String id = session(c);
            c.post("/sessions/" + id + "/run", Json.object("prompt", "approval"));
            var pending = until(c, id, s -> !s.path("conversation").path("requests").isEmpty()).path("conversation").path("requests").get(0);
            c.post("/answer", Json.object("id", pending.path("id").asText(), "answer", Json.object("decision", "decline")));
            until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
            c.post("/sessions/" + id + "/run", Json.object("prompt", "interrupt"));
            until(c, id, s -> !s.path("conversation").path("live").asText().isEmpty());
            c.post("/sessions/" + id + "/stop", Json.object());
            var state = until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
            assertEquals("CANCELLED", state.path("sessions").get(0).path("status").asText());
            assertEquals("Сохранённый фрагмент", state.path("conversation").path("messages").get(3).path("text").asText());
            c.post("/sessions/" + id + "/active", Json.object("active", false));
            assertThrows(Exception.class, () -> c.post("/sessions/" + id + "/run", Json.object("prompt", "cannot run")));
        }
    }
    @Test void hostApiRejectsMissingCredentialsAndBrowserOrigins() throws Exception {
        try (var host = new HostService(data, new Adapter()); var http = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + host.descriptor().path("port").asInt() + "/state");
            assertEquals(401, http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + host.descriptor().path("token").asText()).header("Origin", "http://example.com").build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
    @Test void stopPreservesQueueAndPauseAcrossRestartUntilExplicitResume() throws Exception {
        var adapter = new Adapter(); String id;
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            id = session(c);
            c.post("/sessions/" + id + "/run", Json.object("prompt", "first"));
            assertNotNull(adapter.started.poll(5, TimeUnit.SECONDS));
            c.post("/sessions/" + id + "/run", Json.object("prompt", "second"));
            c.post("/sessions/" + id + "/run", Json.object("prompt", "third", "separate", true));
            c.post("/sessions/" + id + "/stop", Json.object());
            var stopped = until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
            assertEquals("CANCELLED", stopped.path("sessions").get(0).path("status").asText());
            assertTrue(stopped.path("sessions").get(0).path("queuePaused").asBoolean());
            assertEquals(List.of("second", "third"), prompts(stopped));
            assertEquals(1, adapter.prompts.size());
        }
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            var restored = c.get("/state?session=" + id);
            assertTrue(restored.path("sessions").get(0).path("queuePaused").asBoolean());
            assertFalse(restored.path("sessions").get(0).path("running").asBoolean());
            assertEquals(List.of("second", "third"), prompts(restored));
            c.post("/sessions/" + id + "/run", Json.object("prompt", "fourth", "separate", true));
            assertEquals(1, adapter.prompts.size());
            c.post("/sessions/" + id + "/queue-resume", Json.object());
            assertTrue(adapter.started.poll(5, TimeUnit.SECONDS).prompt().endsWith("second"));
            adapter.release.release();
            assertTrue(adapter.started.poll(5, TimeUnit.SECONDS).prompt().endsWith("third"));
            adapter.release.release();
            assertTrue(adapter.started.poll(5, TimeUnit.SECONDS).prompt().endsWith("fourth"));
            adapter.release.release();
            var completed = until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
            assertTrue(prompts(completed).isEmpty());
            assertEquals(4, adapter.prompts.size());
        }
    }
    @Test void removingQueuedMessageCannotRemoveRunningOrAnotherSessionsWork() throws Exception {
        var adapter = new Adapter();
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            String id = session(c), other = session(c);
            c.post("/sessions/" + id + "/stop", Json.object());
            String first = c.post("/sessions/" + id + "/run", Json.object("prompt", "keep")).path("jobId").asText();
            String second = c.post("/sessions/" + id + "/run", Json.object("prompt", "remove", "separate", true)).path("jobId").asText();
            c.post("/sessions/" + other + "/queue-remove", Json.object("jobId", first));
            assertEquals(List.of("keep", "remove"), prompts(c.get("/state?session=" + id)));
            c.post("/sessions/" + id + "/queue-remove", Json.object("jobId", second));
            assertEquals(List.of("keep"), prompts(c.get("/state?session=" + id)));
            c.post("/sessions/" + id + "/queue-resume", Json.object());
            assertNotNull(adapter.started.poll(5, TimeUnit.SECONDS));
            c.post("/sessions/" + id + "/queue-remove", Json.object("jobId", first));
            adapter.release.release();
            var done = until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
            assertEquals("COMPLETED", done.path("sessions").get(0).path("status").asText());
        }
    }
    @Test void editedPriorityControlsExecutionAndReturningPartKeepsDraft() throws Exception {
        var adapter = new Adapter();
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            String id = session(c); c.post("/sessions/" + id + "/stop", Json.object());
            c.post("/sessions/" + id + "/run", Json.object("prompt", "A"));
            String urgent = c.post("/sessions/" + id + "/run", Json.object("prompt", "B", "separate", true)).path("jobId").asText();
            c.post("/sessions/" + id + "/run", Json.object("prompt", "B addition\n\nwith paragraphs"));
            c.post("/sessions/" + id + "/queue-move", Json.object("jobId", urgent, "direction", "first"));
            var prompt = prompts(c.get("/state?session=" + id)).getFirst();
            var returned = c.post("/sessions/" + id + "/queue-take", Json.object("jobId", urgent, "expectedPrompt", prompt, "draft", "Existing draft", "requestId", "return"));
            assertEquals("B addition\n\nwith paragraphs", returned.path("text").asText());
            assertEquals("Existing draft\n\n---\n\nB addition\n\nwith paragraphs", c.get("/state").path("sessions").get(0).path("preferences").path("draft").asText());
            assertEquals(List.of("B", "A"), prompts(c.get("/state?session=" + id)));
            c.post("/sessions/" + id + "/queue-resume", Json.object());
            assertTrue(adapter.started.poll(5, TimeUnit.SECONDS).prompt().endsWith("B"));
            assertThrows(Exception.class, () -> c.post("/sessions/" + id + "/queue-take", Json.object("jobId", urgent, "expectedPrompt", "B", "draft", "", "requestId", "late")));
            adapter.release.release(); assertTrue(adapter.started.poll(5, TimeUnit.SECONDS).prompt().endsWith("A"));
            adapter.release.release(); until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
        }
    }
    @Test void defaultSubmissionAppendsOnlyToQueueTailAndSurvivesRestart() throws Exception {
        var adapter = new Adapter(); String id;
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            id = session(c);
            String running = c.post("/sessions/" + id + "/run", Json.object("prompt", "running")).path("jobId").asText();
            assertNotNull(adapter.started.poll(5, TimeUnit.SECONDS));
            String first = c.post("/sessions/" + id + "/run", Json.object("prompt", "first")).path("jobId").asText();
            assertNotEquals(running, first);
            assertEquals(first, c.post("/sessions/" + id + "/run", Json.object("prompt", "addition")).path("jobId").asText());
            String tail = c.post("/sessions/" + id + "/run", Json.object("prompt", "second", "separate", true)).path("jobId").asText();
            assertNotEquals(first, tail);
            assertEquals(tail, c.post("/sessions/" + id + "/run", Json.object("prompt", "tail addition")).path("jobId").asText());
            assertEquals(List.of("first\n\n---\n\naddition", "second\n\n---\n\ntail addition"), prompts(c.get("/state?session=" + id)));
            assertTrue(adapter.prompts.getFirst().endsWith("running"));
            c.post("/sessions/" + id + "/stop", Json.object());
            until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
            c.post("/sessions/" + id + "/run", Json.object("prompt", "while paused"));
            assertThrows(Exception.class, () -> c.post("/sessions/" + id + "/run", Json.object("prompt", " \n ")));
        }
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            var state = c.get("/state?session=" + id);
            assertEquals(List.of("first\n\n---\n\naddition", "second\n\n---\n\ntail addition\n\n---\n\nwhile paused"), prompts(state));
            assertTrue(state.path("sessions").get(0).path("queuePaused").asBoolean());
            assertEquals(2, state.path("conversation").path("messages").size()); // Pending input stays outside chat.
            c.post("/sessions/" + id + "/queue-resume", Json.object());
            assertTrue(adapter.started.poll(5, TimeUnit.SECONDS).prompt().endsWith("first\n\n---\n\naddition"));
            adapter.release.release();
            assertTrue(adapter.started.poll(5, TimeUnit.SECONDS).prompt().endsWith("second\n\n---\n\ntail addition\n\n---\n\nwhile paused"));
            adapter.release.release();
            until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
        }
    }
    @Test void concurrentSubmissionsKeepEveryParagraphInOneQueuedMessage() throws Exception {
        try (var host = new HostService(data, new Adapter()); var c = new HostClient(host.descriptor());
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            String id = session(c);
            c.post("/sessions/" + id + "/stop", Json.object());
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) {
                String text = "paragraph-" + i;
                futures.add(workers.submit(() -> c.post("/sessions/" + id + "/run", Json.object("prompt", text)).path("jobId").asText()));
            }
            var ids = new HashSet<String>(); for (var future : futures) ids.add(future.get(5, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            var queued = prompts(c.get("/state?session=" + id)); assertEquals(1, queued.size());
            var paragraphs = Arrays.asList(queued.getFirst().split("\n\n---\n\n")); assertEquals(12, paragraphs.size());
            for (int i = 0; i < 12; i++) assertTrue(paragraphs.contains("paragraph-" + i));
        }
    }
    @Test void drainFinishesCurrentRunAndStandbyRetainsQueueUntilPromotion() throws Exception {
        var adapter = new Adapter(); String id;
        try (var host = new HostService(data, adapter); var c = new HostClient(host.descriptor())) {
            id = session(c); c.post("/sessions/" + id + "/run", Json.object("prompt", "active"));
            assertNotNull(adapter.started.poll(5, TimeUnit.SECONDS));
            c.post("/sessions/" + id + "/run", Json.object("prompt", "next"));
            assertTrue(c.post("/updates/drain", Json.object()).path("draining").asBoolean());
            assertThrows(Exception.class, () -> c.post("/updates/shutdown", Json.object()));
            c.post("/sessions/" + id + "/run", Json.object("prompt", "during drain"));
            adapter.release.release();
            var done = until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
            assertEquals("COMPLETED", done.path("sessions").get(0).path("status").asText());
            assertFalse(done.path("sessions").get(0).path("queuePaused").asBoolean());
            assertEquals(List.of("next\n\n---\n\nduring drain"), prompts(done)); assertEquals(1, adapter.prompts.size());
        }
        try (var host = new HostService(data, adapter, new CodexQuotaService(null), true); var c = new HostClient(host.descriptor())) {
            assertTrue(c.get("/health").path("draining").asBoolean());
            assertEquals(1, adapter.prompts.size()); assertEquals(1, prompts(c.get("/state?session=" + id)).size());
            c.post("/updates/resume", Json.object());
            assertTrue(adapter.started.poll(5, TimeUnit.SECONDS).prompt().endsWith("next\n\n---\n\nduring drain"));
            adapter.release.release(); until(c, id, s -> !s.path("sessions").get(0).path("running").asBoolean());
            assertEquals(2, adapter.prompts.size());
        }
    }
    private static List<String> prompts(JsonNode state) {
        var result = new ArrayList<String>(); for (var job : state.path("conversation").path("queued")) result.add(job.path("prompt").asText()); return result;
    }
}
