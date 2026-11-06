package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.util.*;

/** Claude Code has no catalog RPC; the picker offers the CLI's documented aliases and effort levels in the
 * same shape as the Codex catalog. The configured default comes from TETHIUM_CLAUDE_MODEL or the user's settings. */
public final class ClaudeModelCatalog {
    private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    private ClaudeModelCatalog() {}
    public static JsonNode get(boolean available) {
        if (!available) return Json.object("available", false, "data", List.of(), "error", "Исполнитель Claude недоступен в этой сборке");
        var efforts = Json.MAPPER.createArrayNode();
        for (String effort : EFFORTS) efforts.add(Json.object("reasoningEffort", effort, "description", "Усилие Claude: " + effort));
        String configured = dev.tethium.agent.ClaudeAdapter.defaultModel();
        var data = Json.MAPPER.createArrayNode();
        for (var alias : List.of(new String[]{"opus", "Opus", "Псевдоним последней версии Opus; модель по умолчанию"},
                new String[]{"fable", "Fable", "Самая способная модель; тратит недельную квоту в несколько раз быстрее Opus"},
                new String[]{"sonnet", "Sonnet", "Псевдоним последней версии Sonnet"},
                new String[]{"haiku", "Haiku", "Быстрая и экономная модель"})) {
            data.add(Json.object("model", alias[0], "displayName", alias[1], "description", alias[2], "supportedReasoningEfforts", efforts, "isDefault", alias[0].equals(configured)));
        }
        return Json.object("available", true, "data", data, "configuredModel", configured, "configuredEffort", null);
    }
}
