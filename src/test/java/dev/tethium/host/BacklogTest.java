package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BacklogTest {
    @TempDir Path dir;
    private SessionStore store; private Backlog backlog; private String project, session;
    private int quotaLeft = 90; private Double cpuBusy = 10.0; private final Set<String> busy = new HashSet<>();
    private JsonNode quota() { return Json.object("buckets", List.of(Json.object("id", "codex", "windows", List.of(Json.object("kind", "primary", "remainingPercent", quotaLeft), Json.object("kind", "secondary", "remainingPercent", 100))))); }
    @BeforeEach void open() throws Exception {
        store = new SessionStore(dir.resolve("t.db"));
        Path root = java.nio.file.Files.createDirectories(dir.resolve("repo"));
        project = store.project(root); session = store.create(project, null, "Логика", root).id();
        backlog = new Backlog(store, this::quota, () -> Json.object("cpuTotal", cpuBusy), id -> { try { return busy.contains(id) || !store.queued(id).isEmpty(); } catch (Exception e) { throw new RuntimeException(e); } }, () -> {});
    }
    @AfterEach void close() throws Exception { store.close(); }
    private String add(Object... extra) throws Exception {
        var body = Json.object("projectId", project, "title", "Задача", "prompt", "Сделай", "target", "session", "sessionId", session);
        for (int i = 0; i < extra.length; i += 2) body.set((String) extra[i], Json.MAPPER.valueToTree(extra[i + 1]));
        return backlog.add(body, session).path("id").asText();
    }
    private JsonNode row(String id) throws Exception { for (var t : backlog.state(null).path("tasks")) if (t.path("id").asText().equals(id)) return t; return null; }
    private void settings(int max, int minQuota, int minCpu) throws Exception { backlog.settings(Json.object("maxRunning", max, "minQuota", minQuota, "minFreeCpu", minCpu)); }

    @Test void defaultsAreThreeTasksEightyQuotaFortyCpu() throws Exception {
        var s = backlog.state(null).path("settings");
        assertEquals(3, s.path("maxRunning").asInt()); assertEquals(80, s.path("minQuota").asInt()); assertEquals(40, s.path("minFreeCpu").asInt()); assertFalse(s.path("paused").asBoolean());
    }
    @Test void startsOnlyWhenQuotaAndCpuAllowAndSaysWhy() throws Exception {
        String id = add();
        quotaLeft = 62; backlog.tick();
        assertEquals("Квота 62% из нужных 80%", row(id).path("why").asText());
        assertEquals(1, store.backlogWaiting().get(project));
        quotaLeft = 90; cpuBusy = 70.0; backlog.tick();
        assertEquals("Свободно CPU 30% из нужных 40%", row(id).path("why").asText());
        cpuBusy = 10.0;
        for (int i = 0; i < 3; i++) { assertTrue(backlog.tick().isEmpty(), "a quiet moment is not free CPU"); }
        assertEquals(List.of(session), backlog.tick(), "free for a whole minute of checks");
        assertEquals("running", row(id).path("state").asText());
        assertTrue(store.queued(session).getFirst().prompt().contains("Сделай"));
        assertNull(store.backlogWaiting().get(project), "a running task is no longer put off");
        quotaLeft = 1; backlog.tick();
        assertEquals("running", row(id).path("state").asText(), "conditions are checked only at the start");
    }
    @Test void ownConditionsOverrideTheDefaultsAndZeroMeansNone() throws Exception {
        String free = add("minQuota", 0, "minFreeCpu", 0), strict = add("minQuota", 95, "minFreeCpu", 0);
        quotaLeft = 50; cpuBusy = 99.0;
        assertEquals(List.of(session), backlog.tick());
        assertEquals("running", row(free).path("state").asText());
        assertEquals("waiting", row(strict).path("state").asText());
    }
    @Test void aBusySessionWaitsAndAFailedRunStaysInTheList() throws Exception {
        String id = add("minQuota", 0, "minFreeCpu", 0);
        busy.add(session); backlog.tick();
        assertEquals("Сессия занята", row(id).path("why").asText());
        busy.clear(); backlog.tick(); assertEquals("running", row(id).path("state").asText());
        String job = row(id).path("jobId").asText();
        store.jobStatus(job, "FAILED"); backlog.tick();
        assertEquals("failed", row(id).path("state").asText()); assertEquals(1, store.backlogWaiting().get(project));
        assertTrue(backlog.tick().isEmpty(), "a failed task does not restart by itself");
        backlog.start(id); assertEquals("running", row(id).path("state").asText());
        store.jobStatus(row(id).path("jobId").asText(), "COMPLETED"); backlog.tick();
        assertNull(row(id), "a finished task leaves the list");
    }
    @Test void placesPriorityAndOrder() throws Exception {
        settings(2, 0, 0);
        Path root = dir.resolve("repo");
        String a = store.create(project, null, "a", root).id(), b = store.create(project, null, "b", root).id(), c = store.create(project, null, "c", root).id();
        String low = add("priority", "low", "sessionId", a), normal = add("sessionId", b), high = add("priority", "high", "sessionId", c);
        assertEquals(List.of(c, b), backlog.tick());
        assertEquals("waiting", row(low).path("state").asText());
        assertEquals("Заняты все места: 2", row(low).path("why").asText());
        store.jobStatus(row(high).path("jobId").asText(), "COMPLETED");
        assertEquals(List.of(a), backlog.tick(), "a freed place goes to the next task");
        assertEquals("running", row(normal).path("state").asText());
    }
    @Test void aPauseStopsStartingButNotAPersonsStart() throws Exception {
        String id = add("minQuota", 0, "minFreeCpu", 0);
        backlog.settings(Json.object("paused", true)); assertTrue(backlog.tick().isEmpty());
        assertEquals("Автозапуск на паузе", row(id).path("why").asText());
        assertEquals(3, backlog.state(null).path("settings").path("maxRunning").asInt(), "changing one setting keeps the others");
        quotaLeft = 0; assertEquals(session, backlog.start(id));
        assertEquals("running", row(id).path("state").asText());
    }
    @Test void aSharedSessionIsFoundByNameOrCreatedOnceAndANewOneIsAnException() throws Exception {
        settings(5, 0, 0);
        store.preferences(session, "gpt-probe", "danger-full-access", null, "codex");
        String shared1 = add("target", "named", "sessionTitle", "Мелкие задачи"), shared2 = add("target", "named", "sessionTitle", "Мелкие задачи"), own = add("target", "new", "title", "Своя");
        assertEquals("Мелкие задачи", row(shared1).path("sessionName").asText());
        assertEquals("Новая сессия", row(own).path("sessionName").asText());
        var started = backlog.tick();
        assertEquals(2, started.size(), "the shared session takes one task at a time: " + started);
        var titles = new ArrayList<String>(); for (var s : store.list(project)) titles.add(s.title());
        assertEquals(1, titles.stream().filter("Мелкие задачи"::equals).count()); assertTrue(titles.contains("Своя"));
        String made = row(shared1).path("sessionId").asText();
        assertEquals("gpt-probe", store.preferences(made).path("model").asText(), "made like the session that put it off");
        store.jobStatus(row(shared1).path("jobId").asText(), "COMPLETED"); store.backlogRemove(shared1);
        store.queued(made).forEach(j -> { try { store.jobStatus(j.id(), "COMPLETED"); } catch (Exception e) { throw new RuntimeException(e); } });
        assertEquals(List.of(made), backlog.tick(), "the second one finds the same session");
        assertEquals(made, row(shared2).path("sessionId").asText());
    }
    @Test void anAgentPutsOffInItsOwnSessionByDefault() throws Exception {
        var added = backlog.addFromAgent(session, project, Json.object("title", "Превью как виджет", "prompt", "Сделать", "priority", "high", "minQuotaPercent", 70));
        var row = row(added.path("id").asText());
        assertEquals(session, row.path("sessionId").asText()); assertEquals("high", row.path("priority").asText()); assertEquals(70, row.path("effectiveMinQuota").asInt()); assertEquals(40, row.path("effectiveMinFreeCpu").asInt());
        assertEquals(session, row.path("sourceId").asText());
        String named = backlog.addFromAgent(session, project, Json.object("title", "x", "prompt", "y", "session", "Мелкие задачи")).path("id").asText();
        assertEquals("named", row(named).path("target").asText());
        assertEquals("new", row(backlog.addFromAgent(session, project, Json.object("title", "x", "prompt", "y", "session", "new")).path("id").asText()).path("target").asText());
        assertThrows(Exception.class, () -> backlog.removeInProject("other", named));
        backlog.removeInProject(project, named); assertNull(row(named));
    }
    @Test void deletingTheSessionKeepsTheTaskAndGivesItANewOne() throws Exception {
        Path root = dir.resolve("repo");
        String doomed = store.create(project, null, "Удаляемая", root).id();
        String id = add("sessionId", doomed);
        store.trash(doomed, true); store.purge(doomed);
        assertEquals("new", row(id).path("target").asText());
    }
    @Test void aBadTaskIsRefused() {
        assertThrows(Exception.class, () -> add("title", " "));
        assertThrows(Exception.class, () -> add("sessionId", "missing"));
        assertThrows(Exception.class, () -> add("target", "named", "sessionTitle", ""));
    }
}
