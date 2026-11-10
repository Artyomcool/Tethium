package dev.tethium.mobile;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class FileNavigationTest {
    private final Map<String,String> roots=Map.of("drive-c","C:\\","home","C:\\Users\\Raider","project-p","C:\\git\\Example");
    @Test public void climbsInsideFolderAndThenLeavesProjectShortcutForTheDrive(){
        assertEquals(new FileNavigation.Place("project-p","src"),FileNavigation.parent("project-p","src/main",roots));
        assertEquals(new FileNavigation.Place("project-p",""),FileNavigation.parent("project-p","src",roots));
        assertEquals(new FileNavigation.Place("drive-c","git"),FileNavigation.parent("project-p","",roots));
    }
    @Test public void homeShortcutIsNotTheFilesystemRoot(){assertEquals(new FileNavigation.Place("drive-c","Users"),FileNavigation.parent("home","",roots));}
    @Test public void onlyRealFilesystemRootHasNoParent(){
        assertNull(FileNavigation.parent("drive-c","",roots));assertEquals(new FileNavigation.Place("drive-c",""),FileNavigation.parent("drive-c","Users",roots));
        assertNull(FileNavigation.parent("drive-unix","",Map.of("drive-unix","/")));
    }
}
