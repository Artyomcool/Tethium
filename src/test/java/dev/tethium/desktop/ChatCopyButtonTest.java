package dev.tethium.desktop;

import dev.tethium.core.ActionLines;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import static org.junit.jupiter.api.Assertions.*;

/** Command lines and code blocks offer their text for copying; ordinary text does not. */
class ChatCopyButtonTest {
    @Test void commandLinesAndCodeAreCopyableWithoutTheirLabel() throws Exception {
        var transcript = new ChatTranscript[1];
        SwingUtilities.invokeAndWait(() -> {
            var t = new ChatTranscript();
            String md = "Смотрю тесты.\n\n" + ActionLines.MARK + "Команда: gradlew test --tests Foo  \n" + ActionLines.MARK + "Файлы: A.java\n\n```\ngit status\n```\n";
            t.setText("<html><body>" + new ChatMarkdown().render(md) + "</body></html>");
            transcript[0] = t;
        });
        var doc = (HTMLDocument) transcript[0].getDocument();
        String all = doc.getText(0, doc.getLength());
        int words = all.indexOf("Смотрю"), command = all.indexOf("gradlew"), files = all.indexOf("A.java"), code = all.indexOf("git status");
        assertNull(ChatTranscript.copyable(doc.getParagraphElement(words)), "ordinary text has no button");
        var line = ChatTranscript.copyable(doc.getParagraphElement(command));
        assertNotNull(line, "a command line has one");
        assertEquals("gradlew test --tests Foo", transcript[0].copyText(line));
        assertEquals("A.java", transcript[0].copyText(ChatTranscript.copyable(doc.getParagraphElement(files))));
        var block = ChatTranscript.copyable(doc.getParagraphElement(code));
        assertNotNull(block, "a code block has one");
        assertEquals("git status", transcript[0].copyText(block));
    }
}
