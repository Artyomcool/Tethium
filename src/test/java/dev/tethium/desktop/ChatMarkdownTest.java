package dev.tethium.desktop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatMarkdownTest {
    @Test void preservesWindowsFileAndRelativeLinksWithoutAllowingExecutableSchemes() {
        var markdown = new ChatMarkdown();
        assertTrue(markdown.render("[PNG](C:/git/Tethium/design/image-demo/tethium-workspace-v1.png)").contains("href=\"C:/git/Tethium/design/image-demo/tethium-workspace-v1.png\""));
        assertTrue(markdown.render("[PNG](file:///C:/images/a%20b.png)").contains("href=\"file:///C:/images/a%20b.png\""));
        assertTrue(markdown.render("[PNG](images/a+b.png)").contains("href=\"images/a+b.png\""));
        assertTrue(markdown.render("[Web](https://example.com)").contains("href=\"https://example.com\""));
        assertFalse(markdown.render("[Blocked](javascript:alert%281%29)").contains("href=\"javascript:"));
        assertFalse(markdown.render("<script>alert(1)</script>").contains("<script>"));
    }
}
