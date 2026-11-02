package dev.tethium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import static dev.tethium.core.SessionStore.QUEUE_SEPARATOR;
import static org.junit.jupiter.api.Assertions.*;

class QueueEditingTest {
    @TempDir Path root;
    @Test void priorityPersistsAndDefaultAppendUsesTheReorderedTail() throws Exception {
        Path db = root.resolve("queue.db"); String session, first, second;
        try (var store = new SessionStore(db)) {
            session = store.create(store.project(root), null, "Queue", root).id();
            first = store.enqueue(session, null, "A"); second = store.enqueue(session, null, "B");
            store.moveQueued(session, second, "first");
            assertEquals(first, store.submit(session, "A2", false));
            assertEquals(List.of(second, first), store.queued(session).stream().map(SessionStore.Job::id).toList());
        }
        try (var store = new SessionStore(db)) {
            assertEquals(List.of("B", "A" + QUEUE_SEPARATOR + "A2"), store.queued(session).stream().map(SessionStore.Job::prompt).toList());
            store.moveQueued(session, second, "down"); assertEquals(first, store.queued(session).getFirst().id());
            store.moveQueued(session, first, "last"); assertEquals(second, store.queued(session).getFirst().id());
            store.jobStatus(second, "RUNNING");
            assertThrows(IllegalStateException.class, () -> store.moveQueued(session, second, "up"));
        }
    }
    @Test void returnPreservesParagraphsDraftAndPauseAndCanBeRetriedAfterRestart() throws Exception {
        Path db = root.resolve("queue.db"); String session, job;
        String base = "First\n\nparagraph\n\n---\n\nliteral divider";
        String addition = "Last submission\n\nsecond paragraph\n\n---\n\nkeep this too";
        String original = base + QUEUE_SEPARATOR + addition, draft = "Already typing\n\nkeep it";
        try (var store = new SessionStore(db)) {
            session = store.create(store.project(root), null, "Queue", root).id();
            store.pauseQueue(session, true); job = store.submit(session, base, false); store.submit(session, addition, false);
            assertEquals(2, store.queued(session).getFirst().partsCount());
            var result = store.takeQueuedPart(session, job, original, draft, "return-1");
            assertEquals(addition, result.path("text").asText());
            assertEquals(draft + QUEUE_SEPARATOR + addition, result.path("draft").asText());
            assertEquals(base, store.queued(session).getFirst().prompt()); assertTrue(store.queuePaused(session));
        }
        try (var store = new SessionStore(db)) {
            assertEquals(addition, store.takeQueuedPart(session, job, original, draft, "return-1").path("text").asText());
            assertEquals(base, store.queued(session).getFirst().prompt());
            assertEquals(draft + QUEUE_SEPARATOR + addition, store.preferences(session).path("draft").asText());
            assertThrows(IllegalStateException.class, () -> store.takeQueuedPart(session, job, original, draft, "stale"));
            String other = store.create(store.project(root), null, "Other", root).id();
            assertThrows(IllegalStateException.class, () -> store.takeQueuedPart(other, job, base, "", "other"));
            store.takeQueuedPart(session, job, base, "", "return-2");
            assertTrue(store.queued(session).isEmpty()); assertEquals(base, store.preferences(session).path("draft").asText());
        }
    }
    @Test void legacyQueueTextIsNotSplitAtUserParagraphsAndDraftFailureRollsBackReturn() throws Exception {
        Path db = root.resolve("queue.db");
        try (var store = new SessionStore(db)) {
            String session = store.create(store.project(root), null, "Legacy", root).id();
            String text = "Old message\n\nwith paragraphs\n\n---\n\nand literal divider";
            String job = store.enqueue(session, null, text); store.draft(session, "Draft");
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var q = c.createStatement()) {
                q.execute("UPDATE jobs SET prompt_parts=NULL");
                q.execute("CREATE TRIGGER fail_draft BEFORE UPDATE ON preferences BEGIN SELECT RAISE(ABORT,'test failure'); END");
            }
            store.submit(session, "New addition", false);
            assertThrows(java.sql.SQLException.class, () -> store.takeQueuedPart(session, job, text + QUEUE_SEPARATOR + "New addition", "Draft", "retry"));
            assertEquals(2, store.queued(session).getFirst().partsCount()); assertEquals("Draft", store.preferences(session).path("draft").asText());
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var q = c.createStatement()) { q.execute("DROP TRIGGER fail_draft"); }
            store.takeQueuedPart(session, job, text + QUEUE_SEPARATOR + "New addition", "Draft", "retry");
            assertEquals(text, store.queued(session).getFirst().prompt());
            store.jobStatus(job, "RUNNING");
            assertThrows(IllegalStateException.class, () -> store.takeQueuedPart(session, job, text, "Draft", "running"));
        }
    }
}
