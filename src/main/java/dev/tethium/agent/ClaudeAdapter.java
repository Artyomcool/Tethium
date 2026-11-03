package dev.tethium.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Claude Code in print mode with streaming JSON on both pipes. Tool activity is normalised to the
 * same item events Codex produces, so the host, history and GUI need no provider-specific branches. */
public final class ClaudeAdapter implements AgentAdapter {
    private final List<String> executable;
    public ClaudeAdapter() { this(System.getenv().getOrDefault("TETHIUM_CLAUDE", "claude")); }
    public ClaudeAdapter(String executable) { this(List.of(executable)); }
    public ClaudeAdapter(List<String> executable) { this.executable = List.copyOf(executable); }
    @Override public Provider provider() { return Provider.CLAUDE; }
    @Override public List<String> executable() { return executable; }

    @Override public Result run(Request request, Consumer<Event> events, Cancellation cancellation) throws Exception {
        events.accept(new Event("connecting", "Claude", Json.object()));
        boolean fullAccess = "danger-full-access".equals(request.access());
        var command = new ArrayList<>(executable);
        command.addAll(List.of("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--permission-prompt-tool", "stdio", "--permission-mode", fullAccess ? "bypassPermissions" : "default", "--no-chrome",
                "--append-system-prompt", DEVELOPER_INSTRUCTIONS));
        String conversation = request.conversationId();
        if (conversation != null) command.addAll(List.of("--resume", conversation));
        else { conversation = UUID.randomUUID().toString(); command.addAll(List.of("--session-id", conversation)); }
        if (request.model() != null) command.addAll(List.of("--model", request.model()));
        if (request.reasoningEffort() != null) command.addAll(List.of("--effort", request.reasoningEffort()));
        var environment = new HashMap<String, String>();
        environment.put("CLAUDECODE", null); // Tethium itself may be driven from a Claude Code session; the child is not nested.
        Path configFile = null;
        if (request.toolsUrl() != null) {
            // Windows command lines cannot carry the JSON intact, so it goes through a private file.
            // The secret is expanded from the child environment, never placed in the file or on the command line.
            var config = Json.object("mcpServers", Json.object("tethium", Json.object("type", "http", "url", request.toolsUrl().toString(),
                    "headers", Json.object("Authorization", "Bearer ${TETHIUM_MCP_TOKEN}"))));
            configFile = Files.createTempFile("tethium-mcp-", ".json");
            Json.MAPPER.writeValue(configFile.toFile(), config);
            command.addAll(List.of("--mcp-config", configFile.toString(), "--allowedTools", "mcp__tethium__*"));
            environment.put("TETHIUM_MCP_TOKEN", request.toolsToken());
        }
        var text = new StringBuilder();
        String turn = UUID.randomUUID().toString();
        try (var process = new JsonProcess(command, request.directory(), environment)) {
            cancellation.onCancel(process::stop);
            if (cancellation.isCancelled()) return new Result(request.conversationId(), Status.CANCELLED, "");
            process.send(Json.object("type", "user", "message", Json.object("role", "user", "content", content(request))));
            cancellation.onCancel(() -> {
                try { process.send(Json.object("type", "control_request", "request_id", UUID.randomUUID().toString(), "request", Json.object("subtype", "interrupt"))); }
                catch (Exception ignored) { process.stop(); }
            });
            boolean announced = false;
            var started = new HashMap<String, ObjectNode>();
            var thinking = new HashMap<Integer, String>();
            var usage = new long[4];
            long deadline = System.nanoTime() + request.timeout().toNanos(), cancelDeadline = Long.MAX_VALUE;
            while (System.nanoTime() < deadline) {
                if (cancellation.isCancelled() && cancelDeadline == Long.MAX_VALUE) cancelDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                if (System.nanoTime() > cancelDeadline) return new Result(conversation, Status.CANCELLED, text.toString());
                var message = process.next(Duration.ofMillis(250));
                if (message == null) continue;
                String type = Json.text(message, "type");
                switch (type) {
                    case "system" -> {
                        String subtype = Json.text(message, "subtype");
                        if (subtype.equals("init")) {
                            String id = message.path("session_id").asText(conversation);
                            if (!announced || !id.equals(conversation)) { conversation = id; announced = true; events.accept(new Event("conversation", id, Json.object("session_id", id, "model", message.path("model").asText(null)))); }
                        } else if (subtype.equals("compact_boundary")) {
                            long now = System.currentTimeMillis(); var item = Json.object("id", "compaction-" + UUID.randomUUID(), "type", "contextCompaction");
                            events.accept(new Event("item/started", "", Json.object("method", "item/started", "params", Json.object("item", item, "startedAtMs", now))));
                            events.accept(new Event("item/completed", "", Json.object("method", "item/completed", "params", Json.object("item", item, "startedAtMs", now, "completedAtMs", now))));
                        } else if (!subtype.equals("status")) events.accept(new Event(type, subtype, message));
                    }
                    case "stream_event" -> {
                        var event = message.path("event"); String kind = Json.text(event, "type");
                        if (kind.equals("content_block_delta") && event.path("delta").path("type").asText().equals("text_delta")) {
                            String chunk = Json.text(event.path("delta"), "text"); text.append(chunk); events.accept(new Event("text", chunk, message));
                        } else if (kind.equals("content_block_start")) {
                            String block = event.path("content_block").path("type").asText();
                            if (block.equals("text") && !text.isEmpty() && !text.toString().endsWith("\n\n")) { text.append("\n\n"); events.accept(new Event("text", "\n\n", message)); }
                            if (block.equals("thinking")) { String id = "thinking-" + UUID.randomUUID(); thinking.put(event.path("index").asInt(), id); events.accept(item("item/started", Json.object("id", id, "type", "reasoning"))); }
                        } else if (kind.equals("content_block_stop")) {
                            String id = thinking.remove(event.path("index").asInt());
                            if (id != null) events.accept(item("item/completed", Json.object("id", id, "type", "reasoning", "status", "completed")));
                        }
                    }
                    case "assistant" -> {
                        var payload = message.path("message");
                        for (var block : payload.path("content")) if (block.path("type").asText().equals("tool_use") && !block.path("name").asText().equals("AskUserQuestion")) {
                            var item = toolItem(block); started.put(item.path("id").asText(), item); events.accept(item("item/started", item));
                        }
                        var last = usage(payload.path("usage"));
                        if (last != null) {
                            for (int i = 0; i < 4; i++) usage[i] += last[i];
                            events.accept(new Event("thread/tokenUsage/updated", "", Json.object("params", Json.object("threadId", conversation + "#" + turn,
                                    "tokenUsage", Json.object("total", breakdown(usage), "last", breakdown(last))))));
                        }
                    }
                    case "user" -> {
                        for (var block : message.path("message").path("content")) if (block.path("type").asText().equals("tool_result")) {
                            var item = started.remove(block.path("tool_use_id").asText());
                            if (item != null) { item = item.deepCopy(); item.put("status", block.path("is_error").asBoolean() ? "failed" : "completed"); events.accept(item("item/completed", item)); }
                        }
                    }
                    case "control_request" -> process.send(control(message, request, cancellation, events));
                    case "control_response" -> { }
                    case "result" -> {
                        boolean success = !message.path("is_error").asBoolean() && message.path("subtype").asText().equals("success");
                        if (text.isEmpty()) text.append(Json.text(message, "result"));
                        if (!success) {
                            for (var error : message.path("errors")) text.append(text.isEmpty() ? "" : "\n").append(error.asText());
                            if (text.isEmpty()) { String diagnostic = process.diagnostic().strip(); text.append(diagnostic.isEmpty() ? "Claude завершил ход с ошибкой (" + message.path("subtype").asText() + ")." : diagnostic); }
                        }
                        if (cancellation.isCancelled()) { events.accept(new Event("finished", "interrupted", message)); return new Result(conversation, Status.CANCELLED, text.toString()); }
                        events.accept(new Event("finished", success ? "completed" : "failed", message));
                        return new Result(conversation, success ? Status.COMPLETED : Status.FAILED, text.toString());
                    }
                    default -> events.accept(new Event(type, "", message));
                }
            }
            throw new java.util.concurrent.TimeoutException("Claude turn timed out");
        } catch (Exception failure) {
            if (cancellation.isCancelled()) return new Result(conversation, Status.CANCELLED, text.toString());
            throw failure;
        } finally {
            if (configFile != null) Files.deleteIfExists(configFile);
        }
    }

    private static JsonNode content(Request request) throws Exception {
        var blocks = Json.MAPPER.createArrayNode();
        blocks.add(Json.object("type", "text", "text", request.prompt()));
        for (var image : request.images()) {
            String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
            String media = name.endsWith(".png") ? "image/png" : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : name.endsWith(".gif") ? "image/gif" : name.endsWith(".webp") ? "image/webp" : null;
            if (media == null || !Files.isRegularFile(image)) { blocks.add(Json.object("type", "text", "text", "Attached file: " + image.toAbsolutePath())); continue; }
            blocks.add(Json.object("type", "image", "source", Json.object("type", "base64", "media_type", media, "data", Base64.getEncoder().encodeToString(Files.readAllBytes(image)))));
        }
        return blocks;
    }
    private static Event item(String method, ObjectNode item) { return new Event(method, "", Json.object("method", method, "params", Json.object("item", item))); }
    /** Codex item vocabulary, so RunActivity and the activity log describe Claude tools the same way. */
    static ObjectNode toolItem(JsonNode block) {
        String name = block.path("name").asText(); var input = block.path("input");
        var item = Json.object("id", block.path("id").asText(), "name", name);
        switch (name) {
            case "Bash", "PowerShell" -> { item.put("type", "commandExecution"); item.put("command", input.path("command").asText(input.path("description").asText(name))); }
            case "Edit", "Write", "MultiEdit", "NotebookEdit" -> {
                item.put("type", "fileChange");
                item.set("changes", Json.MAPPER.createArrayNode().add(Json.object("path", input.path("file_path").asText(input.path("notebook_path").asText("")), "kind", name.equals("Write") ? "add" : "update")));
            }
            case "WebSearch", "WebFetch" -> { item.put("type", "webSearch"); item.put("query", input.path("query").asText(input.path("url").asText(""))); }
            default -> {
                if (name.startsWith("mcp__")) { item.put("type", "mcpToolCall"); var parts = name.split("__", 3); item.put("server", parts.length > 1 ? parts[1] : ""); item.put("tool", parts.length > 2 ? parts[2] : name); }
                else { item.put("type", "dynamicToolCall"); item.put("tool", name); }
            }
        }
        return item;
    }
    private static long[] usage(JsonNode usage) {
        if (!usage.isObject()) return null;
        long input = usage.path("input_tokens").asLong(), creation = usage.path("cache_creation_input_tokens").asLong(), cached = usage.path("cache_read_input_tokens").asLong(), output = usage.path("output_tokens").asLong();
        return new long[]{input + creation + cached + output, input + creation + cached, cached, output};
    }
    private static JsonNode breakdown(long[] values) {
        return Json.object("totalTokens", values[0], "inputTokens", values[1], "cachedInputTokens", values[2], "outputTokens", values[3], "reasoningOutputTokens", 0);
    }
    /** Permission prompts and AskUserQuestion arrive as control requests; both map onto the Codex request shapes the GUI already renders. */
    private static JsonNode control(JsonNode message, Request request, Cancellation cancellation, Consumer<Event> events) throws Exception {
        String id = message.path("request_id").asText(); var control = message.path("request");
        if (!control.path("subtype").asText().equals("can_use_tool")) return response(Json.object("subtype", "error", "request_id", id, "error", "Unsupported control request: " + control.path("subtype").asText()));
        String tool = control.path("tool_name").asText(); var input = control.path("input");
        JsonNode decision;
        if (tool.equals("AskUserQuestion")) {
            var questions = Json.MAPPER.createArrayNode(); int index = 0;
            for (var question : input.path("questions")) {
                var options = Json.MAPPER.createArrayNode(); for (var option : question.path("options")) options.add(Json.object("label", option.path("label").asText(), "description", option.path("description").asText("")));
                questions.add(Json.object("id", "q" + index++, "header", question.path("header").asText(""), "question", question.path("question").asText(), "options", options));
            }
            var params = Json.object("questions", questions);
            events.accept(new Event("request", "item/tool/requestUserInput", Json.object("id", id, "method", "item/tool/requestUserInput", "params", params)));
            var answer = request.requests() == null ? null : request.requests().handle("item/tool/requestUserInput", params, cancellation);
            if (answer == null) decision = Json.object("behavior", "deny", "message", "No user is available to answer; continue with your best judgement and state your assumptions.");
            else {
                var answers = Json.object(); index = 0;
                for (var question : input.path("questions")) {
                    var given = answer.path("answers").path("q" + index++).path("answers"); var joined = new StringJoiner(", ");
                    for (var value : given) if (!value.asText().isBlank()) joined.add(value.asText());
                    if (joined.length() > 0) answers.put(question.path("question").asText(), joined.toString());
                }
                var updated = (ObjectNode) input.deepCopy(); updated.set("answers", answers);
                decision = Json.object("behavior", "allow", "updatedInput", updated);
            }
        } else {
            var params = Json.object("command", tool + " " + input.toString(), "tool", tool, "input", input);
            events.accept(new Event("request", "item/commandExecution/requestApproval", Json.object("id", id, "method", "item/commandExecution/requestApproval", "params", params)));
            var answer = request.requests() == null ? null : request.requests().handle("item/commandExecution/requestApproval", params, cancellation);
            boolean allowed = answer != null && answer.path("decision").asText().startsWith("accept");
            decision = allowed ? Json.object("behavior", "allow", "updatedInput", input) : Json.object("behavior", "deny", "message", "The user declined this action in Tethium.");
        }
        return response(Json.object("subtype", "success", "request_id", id, "response", decision));
    }
    private static JsonNode response(JsonNode body) { return Json.object("type", "control_response", "response", body); }
}
