package dev.tethium.agent;

import dev.tethium.json.Json;
import java.time.Duration;
import java.util.*;

public final class CodexAdapter implements AgentAdapter {
    private final List<String> executable;
    public CodexAdapter() { this(System.getenv().getOrDefault("TETHIUM_CODEX", "codex")); }
    public CodexAdapter(String executable) { this(List.of(executable)); }
    public CodexAdapter(List<String> executable) { this.executable = List.copyOf(executable); }
    @Override public List<String> executable() { return executable; }
    @Override public Provider provider() { return Provider.CODEX; }

    @Override public Result run(Request request, java.util.function.Consumer<Event> events, Cancellation cancellation) throws Exception {
        events.accept(new Event("connecting", "", Json.object()));
        var command = new ArrayList<>(executable);
        command.addAll(List.of("app-server", "--listen", "stdio://"));
        var environment = new HashMap<String, String>();
        if (request.toolsUrl() != null) {
            command.addAll(List.of("-c", "mcp_servers.tethium.url=" + request.toolsUrl(),
                    "-c", "mcp_servers.tethium.bearer_token_env_var=TETHIUM_MCP_TOKEN",
                    "-c", "mcp_servers.tethium.enabled=true", "-c", "mcp_servers.tethium.required=true"));
            environment.put("TETHIUM_MCP_TOKEN", request.toolsToken());
        }
        try (var process = new JsonProcess(command, request.directory(), environment)) {
            cancellation.onCancel(process::stop);
            process.call("initialize", Json.object("clientInfo", Json.object("name", "tethium", "title", "Tethium", "version", "0.1.0")), Duration.ofSeconds(30));
            process.send(Json.object("method", "initialized"));
            boolean fullAccess = request.access().equals("danger-full-access");
            var params = Json.object("cwd", request.directory().toString(), "approvalPolicy", fullAccess || request.requests() == null ? "never" : "on-request", "sandbox", request.access());
            params.put("developerInstructions", DEVELOPER_INSTRUCTIONS);
            if (request.model() != null) params.put("model", request.model());
            if (request.reasoningEffort() != null) params.set("config", Json.object("model_reasoning_effort", request.reasoningEffort()));
            String method = "thread/start";
            if (request.conversationId() != null) {
                method = "thread/resume";
                params.put("threadId", request.conversationId());
            }
            var thread = process.call(method, params, Duration.ofSeconds(60));
            String threadId = thread.path("thread").path("id").asText();
            if (threadId.isBlank()) throw new IllegalStateException("Codex did not return a thread id");
            events.accept(new Event("conversation", threadId, thread));
            if (cancellation.isCancelled()) return new Result(threadId, Status.CANCELLED, "");
            var inputs = Json.MAPPER.createArrayNode().add(Json.object("type", "text", "text", request.prompt(), "text_elements", List.of()));
            for(var image:request.images()) inputs.add(Json.object("type","localImage","path",image.toAbsolutePath().toString()));
            var turnParams = Json.object("threadId", threadId, "input", inputs);
            if (request.model() != null) turnParams.put("model", request.model());
            String effort = request.reasoningEffort() != null ? request.reasoningEffort() : thread.path("reasoningEffort").asText(null);
            if (effort != null) turnParams.put("effort", effort);
            if (fullAccess) {
                turnParams.put("approvalPolicy", "never");
                turnParams.set("sandboxPolicy", Json.object("type", "dangerFullAccess"));
            }
            var turn = process.call("turn/start", turnParams, Duration.ofSeconds(30));
            String turnId = turn.path("turn").path("id").asText();
            cancellation.onCancel(() -> {
                try { process.call("turn/interrupt", Json.object("threadId", threadId, "turnId", turnId), Duration.ofSeconds(5)); }
                catch (Exception ignored) { process.stop(); }
            });
            long deadline = System.nanoTime() + request.timeout().toNanos();
            long cancelDeadline = Long.MAX_VALUE;
            var text = new StringBuilder();
            String lastMessageItem = null;
            while (System.nanoTime() < deadline) {
                if (cancellation.isCancelled() && cancelDeadline == Long.MAX_VALUE) cancelDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                if (System.nanoTime() > cancelDeadline) return new Result(threadId, Status.CANCELLED, text.toString());
                var message = process.next(Duration.ofMillis(250));
                if (message == null) continue;
                String event = Json.text(message, "method");
                var payload = message.path("params");
                if (message.has("id")) {
                    events.accept(new Event("request", event, message));
                    if (request.requests() != null) {
                        var answer = request.requests().handle(event, payload, cancellation);
                        if (answer != null) process.send(Json.object("id", message.get("id"), "result", answer));
                        else process.send(Json.object("id", message.get("id"), "error", Json.object("code", -32601, "message", "Unsupported interactive request: " + event)));
                    } else process.send(Json.object("id", message.get("id"), "error", Json.object("code", -32601, "message", "Interactive requests are not enabled")));
                    continue;
                }
                if (payload.has("threadId") && !threadId.equals(payload.path("threadId").asText())) continue;
                if (event.equals("item/agentMessage/delta")) {
                    String item = payload.path("itemId").asText();
                    if (lastMessageItem != null && !lastMessageItem.equals(item) && !text.isEmpty()) {
                        text.append("\n\n"); events.accept(new Event("text", "\n\n", message));
                    }
                    lastMessageItem = item;
                    String delta = Json.text(payload, "delta"); text.append(delta);
                    events.accept(new Event("text", delta, message));
                } else if (event.equals("turn/completed")) {
                    var terminal = payload.path("turn");
                    String status = Json.text(terminal, "status");
                    if (status.equals("failed")) {
                        String reason = terminal.path("error").path("message").asText("Codex failed to complete the turn");
                        text.append("\n\n").append(reason);
                    }
                    events.accept(new Event("finished", status, message));
                    return new Result(threadId, switch (status) {
                        case "completed" -> Status.COMPLETED;
                        case "interrupted" -> Status.CANCELLED;
                        default -> Status.FAILED;
                    }, text.toString());
                } else events.accept(new Event(event, "", message));
            }
            throw new java.util.concurrent.TimeoutException("Codex turn timed out");
        } catch (Exception failure) {
            if (cancellation.isCancelled()) return new Result(request.conversationId(), Status.CANCELLED, "");
            throw failure;
        }
    }
}

