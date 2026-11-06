package dev.tethium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** A goal that ended stays ended: Stop and a correction pause only what is going on, and only a pause can be resumed. */
class AutonomyLifecycleTest {
    @TempDir Path dir;
    private interface Check { void run(SessionStore store, String session, String goalId) throws Exception; }
    private void withGoal(Check check) throws Exception {
        try (var store = new SessionStore(dir.resolve("t.db"))) {
            Path root = Files.createDirectories(dir.resolve("r")); String s = store.create(store.project(root), null, "s", root).id();
            check.run(store, s, store.autonomy(s, "start", "", "Цель", "").path("goalId").asText());
        }
    }
    @Test void aCompletedGoalSurvivesStopAndContinue() throws Exception {
        withGoal((store, s, goal) -> {
            store.autonomy(s, "complete", goal, "", "Сделано");
            store.autonomy(s, "pause", "", "", "Остановлено пользователем");
            var state = store.autonomy(s);
            assertEquals("complete", state.path("state").asText(), "Stop must not turn a finished goal into a paused one");
            assertEquals("Сделано", state.path("reason").asText(), "the result stays");
            assertEquals(goal, state.path("goalId").asText());
            store.autonomy(s, "resume", goal, "", "");
            assertEquals("complete", store.autonomy(s).path("state").asText(), "and nothing can resume it");
            store.ensureAutonomousContinuation(s);
            assertTrue(store.queued(s).isEmpty(), "no more continuations");
        });
    }
    @Test void aBlockedGoalStaysBlockedWithItsReason() throws Exception {
        withGoal((store, s, goal) -> {
            store.autonomy(s, "blocked", goal, "", "Нужен доступ");
            store.autonomy(s, "pause", "", "", "Приоритетная правка пользователя");
            assertEquals("blocked", store.autonomy(s).path("state").asText());
            assertEquals("Нужен доступ", store.autonomy(s).path("reason").asText());
        });
    }
    @Test void aRunningGoalStillPausesAndResumes() throws Exception {
        withGoal((store, s, goal) -> {
            store.autonomy(s, "pause", "", "", "Остановлено пользователем");
            assertEquals("paused", store.autonomy(s).path("state").asText());
            store.autonomy(s, "pause", "", "", "Ещё раз");
            assertEquals("Остановлено пользователем", store.autonomy(s).path("reason").asText(), "a second pause changes nothing");
            store.autonomy(s, "resume", goal, "", "");
            assertEquals("active", store.autonomy(s).path("state").asText());
        });
    }
    @Test void aWaitingGoalPauses() throws Exception {
        withGoal((store, s, goal) -> {
            String child = store.create(store.get(s).projectId(), s, "c", store.get(s).directory()).id();
            store.enqueue(child, s, "дело");
            assertEquals("waiting", store.autonomy(s, "wait", goal, "", "Жду").path("state").asText());
            assertEquals("paused", store.autonomy(s, "pause", "", "", "Стоп").path("state").asText());
        });
    }
    @Test void aStaleGoalIdSaysWhichOneIsCurrent() throws Exception {
        withGoal((store, s, goal) -> {
            var failure = assertThrows(Exception.class, () -> store.autonomy(s, "complete", "old-id", "", "Сделано"));
            assertTrue(failure.getMessage().contains(goal), failure.getMessage());
            assertEquals("active", store.autonomy(s).path("state").asText());
        });
    }
}
