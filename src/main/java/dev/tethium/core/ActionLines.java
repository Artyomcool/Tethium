package dev.tethium.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Reports of what the agent did and thought, kept in the dialog among its words. They are for the person: a client shows them muted,
 * and they never go back to an agent. An invisible mark tells action lines from text of the agent's own that starts with a dash.
 */
public final class ActionLines {
    public static final String MARK = "—⁣ ";
    private static final String THOUGHT_OPEN = "```thought";
    private static final Pattern LINE = Pattern.compile("(?m)^" + Pattern.quote(MARK) + ".*(\\R|\\z)");
    private static final Pattern THOUGHT = Pattern.compile("(?ms)^" + THOUGHT_OPEN + "\\R.*?(^```[ \\t]*(\\R|\\z)|\\z)");
    private ActionLines() {}

    /** One human line per tool call; empty for reasoning and unknown items. */
    public static String label(JsonNode item) {
        String label = switch (item.path("type").asText()) {
            case "commandExecution" -> "Команда: " + item.path("command").asText("выполнение");
            case "fileChange" -> "Файлы: " + paths(item.path("changes"));
            case "mcpToolCall", "dynamicToolCall" -> "Инструмент: " + item.path("tool").asText(item.path("name").asText("вызов"));
            case "webSearch" -> "Поиск: " + item.path("query").asText("в интернете");
            case "imageGeneration", "imageGenerationCall" -> "Генерация изображения";
            default -> "";
        };
        label = label.replaceAll("\\s+", " ").strip();
        return label.length() > 200 ? label.substring(0, 197) + "…" : label;
    }
    private static String paths(JsonNode changes) {
        var paths = new ArrayList<String>(); for (var change : changes) paths.add(change.path("path").asText());
        return paths.isEmpty() ? "изменение" : String.join(", ", paths);
    }
    /** Hard line breaks keep a run of actions inside one paragraph instead of a gap per action. */
    public static String piece(boolean afterAction, String label) { return (afterAction ? "  \n" : "\n\n") + MARK + label; }
    /** The text without the reports, for anything an agent reads. */
    public static String strip(String text) {
        if (!narrated(text)) return text;
        return LINE.matcher(THOUGHT.matcher(text).replaceAll("")).replaceAll("").replaceAll("\n{3,}", "\n\n").strip();
    }
    /**
     * What stays in the dialog: the output as it appeared, reports included. The result is added only when the output lacks it, so a
     * run without reports stores exactly what the executor returned.
     */
    public static String merge(String output, String result) {
        if (!narrated(output)) return result;
        String shown = output.strip();
        if (result.isBlank() || squeeze(strip(shown)).contains(squeeze(result))) return shown;
        return shown + "\n\n" + result;
    }
    private static boolean narrated(String text) { return text.contains(MARK) || text.contains(THOUGHT_OPEN); }
    private static String squeeze(String text) { return text.replaceAll("\\s+", ""); }
}
