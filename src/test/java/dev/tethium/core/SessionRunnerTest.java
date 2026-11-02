package dev.tethium.core;

import dev.tethium.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SessionRunnerTest {
    @TempDir Path root;
    private static final class RecordingAdapter implements AgentAdapter {
        final Provider provider;
        final List<Request> requests = new ArrayList<>();
        boolean fail;
        RecordingAdapter(Provider provider) { this.provider = provider; }
        public Provider provider() { return provider; }
        public Result run(Request request, Consumer<Event> events, Cancellation cancellation) {
            requests.add(request);
            if (fail) throw new IllegalStateException("Executor failed");
            return new Result(provider.name() + "-thread", Status.COMPLETED, provider.name() + " result " + requests.size());
        }
    }
    @Test void switchesProvidersAndDeliversOnlyMissedHistoryWhenReturning() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            var session = store.create(store.project(root), null, "Switching", root);
            var runner = new SessionRunner(store);
            var codex = new RecordingAdapter(AgentAdapter.Provider.CODEX);
            var claude = new RecordingAdapter(AgentAdapter.Provider.CLAUDE);
            runner.run(session.id(), codex, "Original requirements", null, null, e -> {}, new Cancellation());
            runner.run(session.id(), claude, "Review the result", null, null, e -> {}, new Cancellation());
            assertNull(claude.requests.getFirst().conversationId());
            assertTrue(claude.requests.getFirst().prompt().contains("Original requirements"));
            assertTrue(claude.requests.getFirst().prompt().contains("CODEX result 1"));
            runner.run(session.id(), codex, "Apply review feedback", null, null, e -> {}, new Cancellation());
            var returning = codex.requests.getLast();
            assertEquals("CODEX-thread", returning.conversationId());
            assertTrue(returning.prompt().contains("CLAUDE result 1"));
            assertFalse(returning.prompt().contains("CODEX result 1"));
            assertEquals(6, store.messages(session.id(), 0, 100).size());
            assertTrue(store.get(session.id()).active());
        }
    }
    @Test void executorFailureReleasesSessionAndDoesNotAdvanceProviderHistory() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            var session = store.create(store.project(root), null, "Failure", root);
            var runner = new SessionRunner(store);
            var adapter = new RecordingAdapter(AgentAdapter.Provider.CLAUDE); adapter.fail = true;
            assertThrows(IllegalStateException.class, () -> runner.run(session.id(), adapter, "Hello", null, null, e -> {}, new Cancellation()));
            assertNull(store.binding(session.id(), adapter.provider()).conversationId());
            adapter.fail = false;
            assertEquals(AgentAdapter.Status.COMPLETED, runner.run(session.id(), adapter, "Try again", null, null, e -> {}, new Cancellation()).status());
            assertTrue(store.get(session.id()).active());
        }
    }
    @Test void messagesDeliveredDuringRunReachNextTurn() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            String id = store.create(store.project(root), null, "Parent", root).id();
            var runner = new SessionRunner(store);
            AgentAdapter first = new AgentAdapter() {
                public Provider provider() { return Provider.CODEX; }
                public Result run(Request r, Consumer<Event> events, Cancellation cancellation) throws Exception {
                    store.append(id, "session", "TETHIUM", "Child completed its review while parent was working");
                    return new Result("CODEX-thread", Status.COMPLETED, "Parent result");
                }
            };
            runner.run(id, first, "Start", null, null, e -> {}, new Cancellation());
            var next = new RecordingAdapter(AgentAdapter.Provider.CODEX);
            runner.run(id, next, "Continue", null, null, e -> {}, new Cancellation());
            assertTrue(next.requests.getFirst().prompt().contains("Child completed its review"));
            assertFalse(next.requests.getFirst().prompt().contains("Parent result"));
        }
    }

    @Test void anotherSessionsMessageBeforeNewInputDoesNotStallHistory() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            String project = store.project(root);
            String id = store.create(project, null, "First", root).id();
            String other = store.create(project, null, "Other", root).id();
            var runner = new SessionRunner(store);
            var adapter = new RecordingAdapter(AgentAdapter.Provider.CODEX);
            runner.run(id, adapter, "First request", null, null, e -> {}, new Cancellation());
            store.append(id, "session", "TETHIUM", "Relevant history");
            store.append(other, "system", "TETHIUM", "Other session only");
            runner.run(id, adapter, "Second request", null, null, e -> {}, new Cancellation());
            String prompt = adapter.requests.getLast().prompt();
            assertTrue(prompt.contains("Relevant history"));
            assertFalse(prompt.contains("Other session only"));
            assertEquals(1, prompt.split("Second request", -1).length - 1);
            assertEquals("COMPLETED", store.lastStatus(id));
        }
    }

    @Test void firstTurnAndMultiPageHistoryHandleGapsAtTheBoundary() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            String project = store.project(root);
            String id = store.create(project, null, "First", root).id();
            String other = store.create(project, null, "Other", root).id();
            var runner = new SessionRunner(store);
            var adapter = new RecordingAdapter(AgentAdapter.Provider.CODEX);
            store.append(other, "user", "USER", "Earlier foreign message");
            runner.run(id, adapter, "First request", null, null, e -> {}, new Cancellation());
            assertFalse(adapter.requests.getFirst().prompt().contains("Earlier foreign message"));
            for (int i = 0; i < 205; i++) {
                store.append(id, "session", "TETHIUM", "History entry " + i);
                store.append(other, "user", "USER", "Foreign entry " + i);
            }
            long through = store.lastSequence(other);
            store.append(id, "session", "TETHIUM", "Beyond snapshot boundary");
            String context = runner.context(id, 0, through);
            assertTrue(context.contains("History entry 0"));
            assertTrue(context.contains("History entry 204"));
            assertFalse(context.contains("Foreign entry"));
            assertFalse(context.contains("Beyond snapshot boundary"));
        }
    }

    @Test void cancellationBeforeContextPreparationDoesNotStartAdapter() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            String id = store.create(store.project(root), null, "Cancelled", root).id();
            var adapter = new RecordingAdapter(AgentAdapter.Provider.CODEX);
            var cancellation = new Cancellation(); cancellation.cancel();
            assertThrows(java.util.concurrent.CancellationException.class, () ->
                    new SessionRunner(store).run(id, adapter, "Stop", null, null, e -> {}, cancellation));
            assertTrue(adapter.requests.isEmpty());
            assertEquals("CANCELLED", store.lastStatus(id));
        }
    }
}
