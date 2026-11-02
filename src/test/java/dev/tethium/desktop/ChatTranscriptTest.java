package dev.tethium.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatTranscriptTest {
    @Test void codeAndUnbrokenPathsWrapWithoutClippingOrChangingCopiedText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var text = new ChatTranscript(); var pane = new JScrollPane(text);
                pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                String path = "C:/project/" + "longdirectory/".repeat(35) + "File.java";
                String code = "    String value = \"" + "x".repeat(220) + "\";";
                text.setText(ChatTheme.page("<div class='message'><p>Обычный текст</p><p>" + path + "</p><pre>" + code + "\n\n    next();</pre></div>"));
                String copied = text.getDocument().getText(0, text.getDocument().getLength());
                assertTrue(copied.contains(path)); assertTrue(copied.contains(code)); assertTrue(copied.contains("\n\n    next();"));
                for (int width : new int[]{680, 340, 970, 380}) {
                    pane.setSize(width, 500); pane.doLayout(); pane.getViewport().doLayout();
                    text.setSize(pane.getViewport().getWidth(), text.getPreferredSize().height);
                    assertEquals(pane.getViewport().getWidth(), text.getWidth());
                    for (int i = 1; i < text.getDocument().getLength(); i++) {
                        var bounds = text.modelToView2D(i);
                        assertNotNull(bounds);
                        assertTrue(bounds.getX() >= 0 && bounds.getMaxX() <= text.getWidth(), "Clipped character " + i + " at " + bounds + ", width " + width);
                    }
                    assertFalse(pane.getHorizontalScrollBar().isVisible());
                }
                assertEquals(copied, text.getDocument().getText(0, text.getDocument().getLength()));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
