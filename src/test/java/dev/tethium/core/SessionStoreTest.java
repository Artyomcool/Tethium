package dev.tethium.core;

import dev.tethium.agent.AgentAdapter.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path root;
    @Test void mobileLiteralNullNeverBecomesAnEffortOrModel() throws Exception {
        try(var store=new SessionStore(root.resolve("settings.db"))){
            String id=store.create(store.project(root),null,"Settings",root).id();
            store.preferences(id,"null",SessionStore.SESSION_ACCESS,"null");
            assertTrue(store.preferences(id).path("model").isNull());assertTrue(store.preferences(id).path("reasoningEffort").isNull());
            store.preferences(id,"gpt-6-astra",SessionStore.SESSION_ACCESS,"xhigh");store.draft(id,"Keep settings");
            assertEquals("xhigh",store.preferences(id).path("reasoningEffort").asText());
        }
    }
    @Test void oldDatabaseMigratesAndLegacySettingsAndDraftsKeepReasoningEffort() throws Exception {
        Path database = root.resolve("legacy.db"); String id;
        try (var store = new SessionStore(database)) {
            id = store.create(store.project(root), null, "Existing", root).id();
            store.preferences(id, "model-a", "danger-full-access"); store.draft(id, "Draft");
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE preferences DROP COLUMN reasoning_effort");
        }
        try (var store = new SessionStore(database)) {
            assertEquals("Draft", store.preferences(id).path("draft").asText());
            assertTrue(store.preferences(id).path("reasoningEffort").isNull());
            store.preferences(id, "model-b", "danger-full-access", "max");
            store.preferences(id, "model-b", "danger-full-access"); store.draft(id, "Updated draft");
        }
        try (var store = new SessionStore(database)) {
            assertEquals("max", store.preferences(id).path("reasoningEffort").asText());
            assertEquals("model-b", store.preferences(id).path("model").asText());
            assertEquals("Updated draft", store.preferences(id).path("draft").asText());
            store.preferences(id, null, "danger-full-access", null);
            assertTrue(store.preferences(id).path("reasoningEffort").isNull());
        }
    }
    @Test void finishedRunsKeepSessionsActiveAndConcurrentRunsAreRejected() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db"))) {
            var project = store.project(root);
            assertEquals(project, store.project(root.resolve(".")));
            var session = store.create(project, null, "Main", root);
            String run = store.beginRun(session.id(), Provider.CODEX);
            assertThrows(SQLException.class, () -> store.beginRun(session.id(), Provider.CLAUDE));
            store.finishRun(run, "COMPLETED");
            assertTrue(store.get(session.id()).active());
            store.setActive(session.id(), false);
            assertThrows(IllegalStateException.class, () -> store.beginRun(session.id(), Provider.CLAUDE));
            store.setActive(session.id(), true);
            assertNotNull(store.beginRun(session.id(), Provider.CLAUDE));
        }
    }
    @Test void historyBindingsAndChildrenSurviveHostRestart() throws Exception {
        String id, childId, project;
        try (var store = new SessionStore(root.resolve("test.db"))) {
            project = store.project(root);
            id = store.create(project, null, "Parent", root).id();
            childId = store.create(project, id, "Child", root).id();
            store.append(id, "user", "USER", "Keep the compact layout");
            store.append(id, "assistant", "CODEX", "Layout implemented");
            store.bind(id, Provider.CODEX, "codex-thread", store.lastSequence(id));
            store.append(id, "assistant", "CLAUDE", "Review complete");
            store.bind(id, Provider.CLAUDE, "claude-thread", store.lastSequence(id));
            store.beginRun(id, Provider.CLAUDE);
        }
        try (var store = new SessionStore(root.resolve("test.db"))) {
            assertEquals(id, store.get(childId).parentId());
            assertEquals(2, store.list(project).size());
            assertEquals("codex-thread", store.binding(id, Provider.CODEX).conversationId());
            var context = new SessionRunner(store).context(id, store.binding(id, Provider.CODEX).lastSequence(), store.lastSequence(id));
            assertTrue(context.contains("Review complete"));
            assertFalse(context.contains("Layout implemented"));
            store.recoverInterruptedRuns();
            assertNotNull(store.beginRun(id, Provider.CODEX));
        }
    }
    @Test void fullAccessAppliesToExistingNewAndChildSessionsWithoutLosingDraftsOrModels() throws Exception {
        Path database = root.resolve("test.db"); String id, project;
        try (var store = new SessionStore(database)) {
            project = store.project(root); id = store.create(project, null, "Existing", root).id();
            store.preferences(id, "chosen-model", "workspace-write"); store.draft(id, "Keep this draft");
            assertEquals(SessionStore.SESSION_ACCESS, store.preferences(id).path("access").asText());
        }
        // Emulate a preferences row written by a previous release.
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE preferences SET access='workspace-write'");
        }
        try (var store = new SessionStore(database)) {
            var preferences = store.preferences(id);
            assertEquals(SessionStore.SESSION_ACCESS, preferences.path("access").asText());
            assertEquals("chosen-model", preferences.path("model").asText());
            assertEquals("Keep this draft", preferences.path("draft").asText());
            String child = store.create(project, id, "Child", root).id();
            assertEquals(SessionStore.SESSION_ACCESS, store.preferences(child).path("access").asText());
            store.draft(child, "Child draft");
            assertEquals(SessionStore.SESSION_ACCESS, store.preferences(child).path("access").asText());
        }
    }
}

