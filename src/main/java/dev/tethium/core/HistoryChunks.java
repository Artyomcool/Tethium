package dev.tethium.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Long messages are shown in pieces. A piece ends where Markdown can be cut: at a blank line outside code and thought blocks,
 * else at a line end, never inside a word. A block longer than a piece is closed at the end of one piece and reopened in the
 * next, so each piece renders on its own. Offsets and lengths are in code points, like SQLite's substr.
 */
public final class HistoryChunks {
    private HistoryChunks() {}

    /** Pairs of {offset, length} covering the text. */
    public static List<int[]> split(String text, int limit) {
        var result = new ArrayList<int[]>();
        int total = text.codePointCount(0, text.length());
        if (total <= limit) { result.add(new int[]{0, total}); return result; }
        boolean[] fenced = fencedLines(text); int[] starts = lineStarts(text);
        int startChar = 0;
        while (true) {
            int remaining = text.codePointCount(startChar, text.length());
            if (remaining <= limit) { result.add(new int[]{text.codePointCount(0, startChar), remaining}); break; }
            int hardEnd = text.offsetByCodePoints(startChar, limit), floor = text.offsetByCodePoints(startChar, limit / 2);
            int end = boundary(text, fenced, starts, floor, hardEnd);
            result.add(new int[]{text.codePointCount(0, startChar), text.codePointCount(startChar, end)});
            startChar = end;
        }
        return result;
    }

    /** The piece as it should be rendered: a block cut by the piece boundaries is reopened before it and closed after it. */
    public static String piece(String text, int offset, int length) {
        int total = text.codePointCount(0, text.length());
        int from = text.offsetByCodePoints(0, Math.min(offset, total)), to = text.offsetByCodePoints(0, Math.min(offset + length, total));
        String body = text.substring(from, to), openAtStart = openFence(text, from), openAtEnd = to >= text.length() ? null : openFence(text, to);
        if (openAtStart != null) body = openAtStart + "\n" + body;
        if (openAtEnd != null) body = body + (body.endsWith("\n") ? "" : "\n") + "```";
        return body;
    }

    /** A paragraph break outside blocks if there is one late enough, else a line end outside blocks, else any line end. */
    private static int boundary(String text, boolean[] fenced, int[] lineStarts, int floor, int hardEnd) {
        int line = -1, anyLine = -1;
        for (int i = hardEnd - 1; i >= floor; i--) {
            if (text.charAt(i) != '\n') continue;
            int next = i + 1; boolean outside = !fenced[lineAt(lineStarts, next)];
            if (outside && i > 0 && text.charAt(i - 1) == '\n') return next;
            if (outside && line < 0) line = next;
            if (anyLine < 0) anyLine = next;
        }
        if (line > 0) return line;
        if (anyLine > 0) return anyLine;
        int cut = hardEnd; if (cut < text.length() && Character.isLowSurrogate(text.charAt(cut))) cut--;
        return cut;
    }

    private static int[] lineStarts(String text) {
        var starts = new ArrayList<Integer>(); starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineAt(int[] starts, int charIndex) {
        int found = Arrays.binarySearch(starts, charIndex); return found >= 0 ? found : -found - 2;
    }

    /** For each line: whether it is inside a fenced block (fence lines count as inside). */
    private static boolean[] fencedLines(String text) {
        String[] lines = text.split("\n", -1); boolean[] result = new boolean[lines.length + 1]; boolean open = false;
        for (int i = 0; i < lines.length; i++) { boolean fence = isFence(lines[i]); result[i] = open || fence; if (fence) open = !open; }
        result[lines.length] = open;
        return result;
    }

    /** The opening fence line of a block still open at a position (counting whole lines before it), or null. */
    private static String openFence(String text, int charIndex) {
        String open = null; int start = 0;
        while (start < charIndex) {
            int end = text.indexOf('\n', start); if (end < 0 || end >= charIndex) break;
            String line = text.substring(start, end); if (isFence(line)) open = open == null ? line.strip() : null;
            start = end + 1;
        }
        return open;
    }

    private static boolean isFence(String line) {
        int spaces = 0; while (spaces < line.length() && spaces < 4 && line.charAt(spaces) == ' ') spaces++;
        return spaces < 4 && line.startsWith("```", spaces);
    }
}
