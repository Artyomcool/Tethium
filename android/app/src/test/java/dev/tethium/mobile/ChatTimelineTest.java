package dev.tethium.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatTimelineTest {
    @Test public void visualizeMarkerBecomesAFileLink() {
        String marker="\uE200visualize\uE202{\"path\":\"C:\\\\git\\\\Tethium\\\\preview image.html\",\"title\":\"16 вариантов\"}\uE201";
        String shown=ChatTimeline.visualizeLinks(marker);
        assertFalse(shown.contains("visualize"));
        assertTrue(shown,shown.contains("[16 вариантов](C:/git/Tethium/preview%20image.html)"));
    }
}
