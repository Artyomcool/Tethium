package dev.tethium.agent;

import dev.tethium.json.Json;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;

public final class ClaudeAdapter implements AgentAdapter {
    private final List<String> executable;
    public ClaudeAdapter() { this(System.getenv().getOrDefault("TETHIUM_CLAUDE", "claude")); }
    public ClaudeAdapter(String executable) { this(List.of(executable)); }
    public ClaudeAdapter(List<String> executable) { this.executable = List.copyOf(executable); }
    @Override public Provider provider() { return Provider.CLAUDE; }

    @Override public Result run(Request request, java.util.function.Consumer<Event> events, Cancellation cancellation) throws Exception {
        var command = new ArrayList<>(executable);
        command.addAll(List.of("-p", "--input-format", "stream-json", "--output-format", "stream-json",
                "--verbose", "--include-partial-messages", "--permission-mode", "dontAsk", "--tools", "", "--strict-mcp-config", "--no-chrome"));
        if (request.conversationId() != null) command.addAll(List.of("--resume", request.conversationId()));
        if (request.model() != null) command.addAll(List.of("--model", request.model()));
        Path configFile = null;
        if (request.toolsUrl() != null) {
            // Secret is expanded from the child environment, never placed on its command line.
            var config = Json.object("mcpServers", Json.object("tethium", Json.object("type", "http", "url", request.toolsUrl().toString(),
                    "headers", Json.object("Authorization", "Bearer ${TETHIUM_MCP_TOKEN}"))));
            configFile = Files.createTempFile("tethium-mcp-", ".json");
            Json.MAPPER.writeValue(configFile.toFile(), config);
            command.addAll(List.of("--mcp-config", configFile.toString(), "--allowedTools", "mcp__tethium__*"));
        }
        var environment = new HashMap<String, String>();
        if (request.toolsToken() != null) environment.put("TETHIUM_MCP_TOKEN", request.toolsToken());
        String conversation = request.conversationId();
        var text = new StringBuilder();
        try (var process = new JsonProcess(command, request.directory(), environment)) {
            cancellation.onCancel(process::stop);
            if (cancellation.isCancelled()) return new Result(conversation, Status.CANCELLED, "");
            process.send(Json.object("type", "user", "message", Json.object("role", "user", "content", request.prompt())));
            process.closeInput();
            long deadline = System.nanoTime() + request.timeout().toNanos();
            while (System.nanoTime() < deadline) {
                if (cancellation.isCancelled()) return new Result(conversation, Status.CANCELLED, text.toString());
                var message = process.next(Duration.ofMillis(250));
                if (message == null) continue;
                if (message.hasNonNull("session_id")) {
                    String id = message.get("session_id").asText();
                    if (!id.equals(conversation)) {
                        conversation = id;
                        events.accept(new Event("conversation", id, Json.object("session_id", id)));
                    }
                }
                String type = Json.text(message, "type");
                if (type.equals("stream_event")) {
                    var delta = message.path("event").path("delta");
                    if (delta.path("type").asText().equals("text_delta")) {
                        String chunk = Json.text(delta, "text"); text.append(chunk);
                        events.accept(new Event("text", chunk, message));
                    } else events.accept(new Event(type, "", message));
                } else if (type.equals("result")) {
                    String result = Json.text(message, "result");
                    if (text.isEmpty()) text.append(result);
                    boolean success = !message.path("is_error").asBoolean() && message.path("subtype").asText().equals("success");
                    if (!success && message.has("errors")) text.append("\n").append(message.get("errors"));
                    events.accept(new Event("finished", success ? "completed" : "failed", message));
                    return new Result(conversation, success ? Status.COMPLETED : Status.FAILED, text.toString());
                } else events.accept(new Event(type, "", message));
            }
            throw new java.util.concurrent.TimeoutException("Claude turn timed out");
        } catch (Exception failure) {
            if (cancellation.isCancelled()) return new Result(conversation, Status.CANCELLED, text.toString());
            throw failure;
        } finally {
            if (configFile != null) Files.deleteIfExists(configFile);
        }
    }
}

