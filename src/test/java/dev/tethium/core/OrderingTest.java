package dev.tethium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrderingTest {
    @TempDir Path root;
    @Test void mergeKeepsUnmentionedEntriesInPlace() {
        assertEquals(List.of("c", "b", "x", "a"), SessionStore.merge(List.of("a", "b", "x", "c"), List.of("c", "b", "a")));
        assertEquals(List.of("a", "b"), SessionStore.merge(List.of("a", "b"), List.of("zzz")), "unknown ids are ignored");
    }
    @Test void projectsAndSessionsKeepTheirDraggedOrderAcrossRestarts() throws Exception {
        Path a = Files.createDirectories(root.resolve("A")), b = Files.createDirectories(root.resolve("B")), c = Files.createDirectories(root.resolve("C"));
        String pa, pb, pc, s1, s2, s3;
        try (var store = new SessionStore(root.resolve("s.db"))) {
            pa = store.project(a); pb = store.project(b); pc = store.project(c);
            assertEquals(List.of(pa, pb, pc), store.projects().stream().map(SessionStore.Project::id).toList());
            store.reorderProjects(List.of(pc, pa));
            assertEquals(List.of(pc, pb, pa), store.projects().stream().map(SessionStore.Project::id).toList(), "B was not mentioned and stays second");
            s1 = store.create(pa, null, "one", a).id(); s2 = store.create(pa, null, "two", a).id(); s3 = store.create(pa, s2, "child of two", a).id();
            store.reorderSessions(pa, List.of(s2, s3, s1));
            assertEquals(List.of(s2, s3, s1), store.list(pa).stream().map(SessionStore.Session::id).toList());
            assertThrows(IllegalArgumentException.class, () -> store.reorderSessions(pb, List.of(s1)));
        }
        try (var store = new SessionStore(root.resolve("s.db"))) {
            assertEquals(List.of(pc, pb, pa), store.projects().stream().map(SessionStore.Project::id).toList());
            assertEquals(List.of(s2, s3, s1), store.list(pa).stream().map(SessionStore.Session::id).toList());
            String s4 = store.create(pa, null, "four", a).id();
            assertEquals(List.of(s2, s3, s1, s4), store.list(pa).stream().map(SessionStore.Session::id).toList(), "new sessions go last");
        }
    }
}
