package dev.tethium.mobile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ApkPublisherTest {
    @Test void metadataComesFromApkAndReservedOrSplitPackagesAreRejected(){
        var info=ApkPublisher.parseBadging("package: name='my.game' versionCode='42' versionName='1.2'\napplication-label:'Game'","");
        assertEquals(42,info.path("versionCode").asInt());assertEquals("Game",info.path("title").asText());
        assertThrows(IllegalArgumentException.class,()->ApkPublisher.parseBadging("package: name='my.game' versionCode='42' versionName='1.2' split='arm64'",""));
        assertThrows(IllegalArgumentException.class,()->ApkPublisher.parseBadging("package: name='dev.tethium.mobile' versionCode='42' versionName='1.2'",""));
    }
}
