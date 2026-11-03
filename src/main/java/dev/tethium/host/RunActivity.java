package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.AgentAdapter;
import dev.tethium.json.Json;
import java.util.*;

/** Public execution activity only; reasoning content and tool payloads are never included. */
final class RunActivity {
    private final long startedAt;
    private long updatedAt;
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Set<String> completed = new HashSet<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private String detail = "Ожидает свободного исполнителя";
    RunActivity() { this(System.currentTimeMillis()); }
    RunActivity(long startedAt) { this.startedAt=startedAt;this.updatedAt=startedAt; }

    synchronized void preparingContext() {
        updatedAt = System.currentTimeMillis();
        detail = "Читает историю разговора";
    }

    synchronized String accept(AgentAdapter.Event event) {
        updatedAt = System.currentTimeMillis();
        if (event.type().equals("connecting")) { String executor = event.text() == null || event.text().isBlank() ? "Codex" : event.text(); detail = "Запускает " + executor + " и подключает разговор"; return "Подключение " + executor; }
        if (event.type().equals("conversation")) { detail = "Контекст подключён"; return "Работает"; }
        if (event.type().equals("text")) return "Отвечает";
        if (!event.type().equals("item/started") && !event.type().equals("item/completed")) return null;
        var item = event.raw().path("params").path("item");
        String type = item.path("type").asText(), id = item.path("id").asText();
        String label = switch (type) {
            case "commandExecution" -> "Команда: " + item.path("command").asText("выполнение");
            case "fileChange" -> "Файлы: " + paths(item.path("changes"));
            case "mcpToolCall", "dynamicToolCall" -> "Инструмент: " + item.path("tool").asText(item.path("name").asText("вызов"));
            case "webSearch" -> "Поиск: " + item.path("query").asText("в интернете");
            case "imageGeneration", "imageGenerationCall" -> "Генерация изображения";
            default -> "";
        };
        if (label.isEmpty()) return type.equals("reasoning") && pending.isEmpty() ? "Обдумывает" : null;
        label = label.replaceAll("\\s+", " ").strip();
        if (label.length() > 200) label = label.substring(0, 197) + "…";
        if (event.type().equals("item/started")) {
            pending.put(id, label); detail = label;
            return type.equals("commandExecution") ? "Выполняет команду" : type.equals("fileChange") ? "Изменяет файлы" : "Работает";
        }
        pending.remove(id);
        String status = item.path("status").asText();
        boolean failed = status.equals("failed") || (item.hasNonNull("exitCode") && item.path("exitCode").asInt() != 0);
        if (completed.add(id)) {
            recent.addLast((failed ? "Ошибка · " : "Готово · ") + label);
            while (recent.size() > 4) recent.removeFirst();
        }
        detail = pending.isEmpty() ? recent.getLast() : pending.values().iterator().next();
        return "Работает";
    }
    synchronized JsonNode snapshot() {
        return Json.object("startedAt", startedAt, "updatedAt", updatedAt, "detail", detail,
                "completedActions", completed.size(), "pendingActions", pending.size(), "recent", List.copyOf(recent));
    }
    private static String paths(JsonNode changes) {
        var paths = new ArrayList<String>(); for (var change : changes) paths.add(change.path("path").asText());
        return paths.isEmpty() ? "изменение" : String.join(", ", paths);
    }
}
