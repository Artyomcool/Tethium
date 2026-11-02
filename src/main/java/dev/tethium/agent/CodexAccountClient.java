package dev.tethium.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Account RPCs never create a thread or start a turn. Reset consumption is explicit. */
public final class CodexAccountClient {
    private final List<String> executable;
    private final Path directory;
    public CodexAccountClient(Path directory) { this(List.of(System.getenv().getOrDefault("TETHIUM_CODEX", "codex")), directory); }
    public CodexAccountClient(List<String> executable, Path directory) { this.executable = List.copyOf(executable); this.directory = directory; }
    public JsonNode readRateLimits() throws Exception {
        var command = new ArrayList<>(executable); command.addAll(List.of("app-server", "--listen", "stdio://"));
        try (var process = new JsonProcess(command, directory, Map.of())) {
            process.call("initialize", Json.object("clientInfo", Json.object("name", "tethium-quota", "title", "Tethium", "version", "0.1.0")), Duration.ofSeconds(20));
            process.send(Json.object("method", "initialized"));
            return process.call("account/rateLimits/read", Json.object(), Duration.ofSeconds(20));
        }
    }
    public JsonNode consumeReset(String idempotencyKey) throws Exception {
        UUID.fromString(idempotencyKey);
        var command=new ArrayList<>(executable);command.addAll(List.of("app-server","--listen","stdio://"));
        try(var process=new JsonProcess(command,directory,Map.of())){
            process.call("initialize",Json.object("clientInfo",Json.object("name","tethium-quota","title","Tethium","version","0.1.0")),Duration.ofSeconds(20));
            process.send(Json.object("method","initialized"));
            return process.call("account/rateLimitResetCredit/consume",Json.object("idempotencyKey",idempotencyKey),Duration.ofSeconds(30));
        }
    }
    /** Picker capabilities and effective local configuration; never opens a conversation. */
    public JsonNode readModels() throws Exception {
        var command = new ArrayList<>(executable); command.addAll(List.of("app-server", "--listen", "stdio://"));
        try (var process = new JsonProcess(command, directory, Map.of())) {
            process.call("initialize", Json.object("clientInfo", Json.object("name", "tethium-models", "title", "Tethium", "version", "0.1.0")), Duration.ofSeconds(20));
            process.send(Json.object("method", "initialized"));
            var models = Json.MAPPER.createArrayNode(); String cursor = null;
            var seen = new HashSet<String>();
            do {
                var page = process.call("model/list", Json.object("limit", 100, "includeHidden", false, "cursor", cursor), Duration.ofSeconds(20));
                for (var model : page.path("data")) if (!model.path("hidden").asBoolean()) models.add(model);
                cursor = page.path("nextCursor").asText(null);
                if (cursor != null && !seen.add(cursor)) throw new IllegalStateException("Codex repeated the model catalog cursor");
            } while (cursor != null);
            var config = process.call("config/read", Json.object("includeLayers", false, "cwd", directory.toString()), Duration.ofSeconds(20)).path("config");
            String configuredModel = System.getenv("TETHIUM_CODEX_MODEL");
            if (configuredModel == null || configuredModel.isBlank()) configuredModel = config.path("model").asText(null);
            return Json.object("available", true, "data", models, "configuredModel", configuredModel,
                    "configuredEffort", config.path("model_reasoning_effort").asText(null));
        }
    }
}
