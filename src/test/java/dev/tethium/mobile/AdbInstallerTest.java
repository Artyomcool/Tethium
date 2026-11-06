package dev.tethium.mobile;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdbInstallerTest {
    @Test void parsesVersionCodeAndDiscoversThePhoneAmongMdnsServices() {
        assertEquals(17, AdbInstaller.parseVersionCode("Packages:\n  Package [dev.tethium.mobile] (abc):\n    versionCode=17 minSdk=26 targetSdk=36\n    versionName=0.5.4"));
        assertEquals(-1, AdbInstaller.parseVersionCode("Unable to find package: dev.tethium.mobile"));
        String mdns = "List of discovered mdns services\nadb-R5CT-aaaa\t_adb-tls-pairing._tcp\t192.168.178.50:37123\nadb-R5CT-aaaa\t_adb-tls-connect._tcp\t192.168.178.50:41234\nadb-other\t_adb-tls-connect._tcp\t192.168.178.60:41111\n";
        assertEquals("192.168.178.50:41234", AdbInstaller.discover(mdns, "192.168.178.50"));
        assertNull(AdbInstaller.discover(mdns, "192.168.178.70"));
    }
}
