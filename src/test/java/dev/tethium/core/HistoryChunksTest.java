package dev.tethium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryChunksTest {
    private static String sample() {
        var text = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            text.append("Абзац номер ").append(i).append(" с обычным текстом, который переносится по словам.\n\n");
            if (i % 7 == 0) text.append("```thought\nДумаю над шагом ").append(i).append(" и сверяю его с дизассемблером.\n```\n\n");
            if (i % 11 == 0) text.append("—⁣ Команда: grep -n \"inflateBlocks\" core/src/main/java/ZlibNative.java\n");
        }
        return text.toString();
    }
    private static int fences(String piece) { int count = 0; for (String line : piece.split("\n")) if (line.strip().startsWith("```")) count++; return count; }

    @Test void piecesCoverTheTextAndEndOnParagraphs() {
        String text = sample(); var pieces = HistoryChunks.split(text, 700);
        assertTrue(pieces.size() > 3);
        int expected = 0; var joined = new StringBuilder();
        for (var piece : pieces) {
            assertEquals(expected, piece[0]); expected += piece[1];
            String raw = text.substring(text.offsetByCodePoints(0, piece[0]), text.offsetByCodePoints(0, piece[0] + piece[1])); joined.append(raw);
            assertTrue(piece[1] <= 700);
            if (piece[0] + piece[1] < text.codePointCount(0, text.length())) assertTrue(raw.endsWith("\n\n"), "piece ends mid-paragraph: " + raw.substring(Math.max(0, raw.length() - 40)));
            assertEquals(0, fences(HistoryChunks.piece(text, piece[0], piece[1])) % 2, "unbalanced fences");
        }
        assertEquals(text, joined.toString());
    }

    @Test void aBlockLongerThanAPieceIsClosedAndReopened() {
        var text = new StringBuilder("Начало\n\n```java\n");
        for (int i = 0; i < 200; i++) text.append("int value").append(i).append(" = ").append(i).append(";\n");
        text.append("```\n\nКонец\n");
        var pieces = HistoryChunks.split(text.toString(), 500);
        assertTrue(pieces.size() > 2);
        for (var piece : pieces) {
            String shown = HistoryChunks.piece(text.toString(), piece[0], piece[1]);
            assertEquals(0, fences(shown) % 2, "unbalanced fences in: " + shown);
            assertFalse(shown.contains("valu\n"), "cut inside a word");
        }
        assertTrue(HistoryChunks.piece(text.toString(), pieces.get(1)[0], pieces.get(1)[1]).startsWith("```java\n"));
    }

    @Test void shortTextIsOnePiece() {
        assertEquals(1, HistoryChunks.split("коротко", 8000).size());
        assertEquals("коротко", HistoryChunks.piece("коротко", 0, 7));
    }
}
