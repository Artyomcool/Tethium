package dev.tethium.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.tethium.mobile.AppReleaseState.Action.*;
public class AppReleaseStateTest {
    @Test public void freshAppsUpdatesAndSameVersionDevelopmentBuildsAreDistinct(){
        assertEquals(INSTALL,AppReleaseState.action(false,0,1,"a","",false));
        assertEquals(UPDATE,AppReleaseState.action(true,1,2,"a","b",true));
        assertEquals(REINSTALL,AppReleaseState.action(true,1,1,"a","b",true));
        assertEquals(REINSTALL,AppReleaseState.action(true,1,1,"a","",false));
        assertEquals(CURRENT,AppReleaseState.action(true,1,1,"a","a",true));
        assertEquals(REINSTALL,AppReleaseState.action(true,1,1,"a","a",false));
        assertEquals(NEWER_INSTALLED,AppReleaseState.action(true,2,1,"a","b",true));
    }
}
