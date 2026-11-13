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
    @Test void liveNarrationRendersMutedThoughtsAndActionsAndCanHideThoughts(){
        var markdown=new ChatMarkdown();String live="""


            ```thought
            Сначала тесты.
            Потом сборка…
            ```

            —⁣ Команда: Запускает тесты\s\s
            —⁣ Файлы: A.java

            Готово.""";
        ChatMarkdown.showThoughts=true;String shown=markdown.render(live);
        assertTrue(shown.contains("<div class='thought'>Сначала тесты.<br>Потом сборка…</div>"),shown);assertTrue(shown.contains("<p class='action'>Команда: Запускает тесты</p>\n<p class='action'>Файлы: A.java</p>"),shown);assertFalse(shown.contains("—"),shown);assertFalse(shown.contains("language-thought"),shown);
        ChatMarkdown.showThoughts=false;String hidden=markdown.render(live);
        assertFalse(hidden.contains("Сначала тесты"),hidden);assertTrue(hidden.contains("class='action'"),hidden);ChatMarkdown.showThoughts=true;
    }
    @Test void aDashOfTheAgentsOwnIsPlainText() {
        var html = new ChatMarkdown().render("— Обычная реплика агента");
        assertFalse(html.contains("action"), html);
        assertTrue(html.contains("— Обычная реплика агента"), html);
    }
    @Test void codexVisualizeMarkerBecomesAFileLink() {
        String marker="\uE200visualize\uE202{\"path\":\"C:\\\\git\\\\Tethium\\\\preview image.html\",\"title\":\"16 вариантов\"}\uE201";
        String expanded=ChatMarkdown.visualizeLinks(marker);assertFalse(expanded.contains("visualize"),expanded);assertTrue(expanded.contains("[16 вариантов](C:/git/Tethium/preview%20image.html)"),expanded);
        String html=new ChatMarkdown().render(marker);assertTrue(html.contains("href=\"C:/git/Tethium/preview%20image.html\""),html);
    }
}
