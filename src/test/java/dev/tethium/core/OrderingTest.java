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
    @Test void groupsAndProjectsShareOnePersistentOrder() throws Exception {
        Path a=Files.createDirectories(root.resolve("MixedA")),b=Files.createDirectories(root.resolve("MixedB"));String pa,pb,group;
        try(var store=new SessionStore(root.resolve("mixed.db"))){
            pa=store.project(a);pb=store.project(b);group=store.createProjectGroup("Tools").id();
            assertEquals(List.of("project:"+pa,"project:"+pb,"group:"+group),store.projectTreeOrder());
            store.reorderProjectTree(List.of("project:"+pa,"group:"+group,"project:"+pb));
            assertEquals(List.of("project:"+pa,"group:"+group,"project:"+pb),store.projectTreeOrder());
        }
        try(var store=new SessionStore(root.resolve("mixed.db"))){
            assertEquals(List.of("project:"+pa,"group:"+group,"project:"+pb),store.projectTreeOrder());
            assertThrows(IllegalArgumentException.class,()->store.reorderProjectTree(List.of("group:missing")));
            store.removeProjectGroup(group);assertEquals(List.of("project:"+pa,"project:"+pb),store.projectTreeOrder());
        }
    }
    @Test void favoritesDuplicateProjectsWithoutChangingTheirHierarchy() throws Exception {
        Path a=Files.createDirectories(root.resolve("FavoriteA")),b=Files.createDirectories(root.resolve("FavoriteB"));String pa,pb,group,s1,s2;
        try(var store=new SessionStore(root.resolve("favorites.db"))){
            pa=store.project(a);pb=store.project(b);group=store.createProjectGroup("Games").id();store.projectParent(pa,"group",group);
            store.projectFavorite(pa,true);assertEquals(java.util.Set.of(pa),store.favoriteProjects());
            assertEquals(new SessionStore.ProjectParent("group",group),store.projectParents().get(pa));
            s1=store.create(pa,null,"One",a).id();s2=store.create(pa,null,"Two",a).id();
            store.sessionFavorite(s1,true);assertTrue(store.favoriteProjects().isEmpty());assertEquals(java.util.Map.of(pa,s1),store.favoriteSessions());
            store.sessionFavorite(s2,true);store.sessionFavorite(s1,false);assertEquals(java.util.Map.of(pa,s2),store.favoriteSessions(),"another session replaces the project's one slot");
            store.projectFavorite(pb,true);assertEquals(java.util.Set.of(pb),store.favoriteProjects());
            assertEquals(List.of(pa,pb),store.favoriteOrder(),"changing the favorite kind keeps its slot");
            store.reorderFavorites(List.of(pb,pa));assertEquals(List.of(pb,pa),store.favoriteOrder());
        }
        try(var store=new SessionStore(root.resolve("favorites.db"))){
            assertEquals(java.util.Set.of(pb),store.favoriteProjects());assertEquals(java.util.Map.of(pa,s2),store.favoriteSessions());assertEquals(new SessionStore.ProjectParent("group",group),store.projectParents().get(pa));
            assertEquals(List.of(pb,pa),store.favoriteOrder(),"favorites keep their independent order across restarts");
            assertThrows(IllegalArgumentException.class,()->store.projectFavorite("missing",true));
            assertThrows(IllegalArgumentException.class,()->store.reorderFavorites(List.of("missing")));
        }
    }
}
