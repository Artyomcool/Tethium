package dev.tethium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectHierarchyTest {
    @TempDir Path root;

    @Test void groupsAndProjectParentsPersistAndCyclesAreRejected() throws Exception {
        Path a=Files.createDirectories(root.resolve("A"));
        Path b=Files.createDirectories(root.resolve("B"));
        Path c=Files.createDirectories(root.resolve("C"));
        String pa,pb,pc,group;
        try(var store=new SessionStore(root.resolve("state.db"))){
            pa=store.project(a);pb=store.project(b);pc=store.project(c);
            group=store.createProjectGroup("Heroes tools").id();
            store.projectParent(pa,"group",group);
            store.projectParent(pb,"project",pa);
            assertEquals(new SessionStore.ProjectParent("group",group),store.projectParents().get(pa));
            assertEquals(new SessionStore.ProjectParent("project",pa),store.projectParents().get(pb));
            assertThrows(IllegalArgumentException.class,()->store.projectParent(pa,"project",pb));
            assertThrows(IllegalArgumentException.class,()->store.projectParent(pc,"group","missing"));
            store.projectGroupCollapsed(group,true);
        }
        try(var store=new SessionStore(root.resolve("state.db"))){
            assertTrue(store.projectGroups().getFirst().collapsed());
            assertEquals("Heroes tools",store.projectGroups().getFirst().title());
            assertEquals(new SessionStore.ProjectParent("project",pa),store.projectParents().get(pb));
            store.removeProjectGroup(group);
            assertFalse(store.projectParents().containsKey(pa));
            assertEquals(new SessionStore.ProjectParent("project",pa),store.projectParents().get(pb));
        }
    }
}
