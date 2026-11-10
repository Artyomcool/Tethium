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
        try {
            try {
                return runTurn(request, request.conversationId(), events, cancellation, true, null);
            } catch (CompactionRejected failure) {
                if (cancellation.isCancelled()) return new Result(failure.threadId, Status.CANCELLED, "");
                try {
                    compactWithoutSearch(request, failure, events, cancellation);
                    if (cancellation.isCancelled()) return new Result(failure.threadId, Status.CANCELLED, "");
                    return runTurn(request, failure.threadId, events, cancellation, false, null);
                } catch (Exception recoveryFailure) {
                    if (cancellation.isCancelled()) return new Result(failure.threadId, Status.CANCELLED, "");
                    throw recoveryFailure;
                }
            }
        } catch (SearchRejected failure) {
            if (cancellation.isCancelled()) return new Result(failure.threadId, Status.CANCELLED, "");
            // Only an exact pre-generation rejection is safe to replay. The override lives
            // in this process; the next user turn uses the normal search configuration.
            return runTurn(request, failure.threadId, events, cancellation, false, failure);
        }
    }

    private JsonProcess openProcess(Request request) throws Exception {
        var command = new ArrayList<>(executable);
        command.addAll(List.of("app-server", "--listen", "stdio://"));
        var environment = new HashMap<String, String>();
        if (request.toolsUrl() != null) {
            command.addAll(List.of("-c", "mcp_servers.tethium.url=" + request.toolsUrl(),
                    "-c", "mcp_servers.tethium.bearer_token_env_var=TETHIUM_MCP_TOKEN",
                    "-c", "mcp_servers.tethium.enabled=true", "-c", "mcp_servers.tethium.required=true"));
            environment.put("TETHIUM_MCP_TOKEN", request.toolsToken());
        }
        return new JsonProcess(command, request.directory(), environment);
    }

    private static void initialize(JsonProcess process) throws Exception {
        process.call("initialize", Json.object("clientInfo", Json.object("name", "tethium", "title", "Tethium", "version", "0.1.0")), Duration.ofSeconds(30));
        process.send(Json.object("method", "initialized"));
    }

    private Result runTurn(Request request, String conversationId, java.util.function.Consumer<Event> events, Cancellation cancellation, boolean canRecover, SearchRejected retry) throws Exception {
        events.accept(new Event("connecting", "", Json.object()));
        try (var process = openProcess(request)) {
            cancellation.onCancel(process::stop);
            initialize(process);
            boolean fullAccess = request.access().equals("danger-full-access");
            var params = Json.object("cwd", request.directory().toString(), "approvalPolicy", fullAccess || request.requests() == null ? "never" : "on-request", "sandbox", request.access());
            params.put("developerInstructions", DEVELOPER_INSTRUCTIONS);
            String model = request.model() != null ? request.model() : retry != null ? retry.model : null;
            String requestedEffort = request.reasoningEffort() != null ? request.reasoningEffort() : retry != null ? retry.effort : null;
            if (model != null) params.put("model", model);
            var config = Json.object();
            if (requestedEffort != null) config.put("model_reasoning_effort", requestedEffort);
            if (retry != null) config.put("web_search", "disabled");
            if (!config.isEmpty()) params.set("config", config);
            String method = "thread/start";
            if (conversationId != null) {
                method = "thread/resume";
                params.put("threadId", conversationId);
            }
            var thread = process.call(method, params, Duration.ofSeconds(60));
            String threadId = thread.path("thread").path("id").asText();
            if (threadId.isBlank()) throw new IllegalStateException("Codex did not return a thread id");
            if (retry != null && !retry.threadId.equals(threadId)) throw new java.io.IOException("Search recovery resumed a different Codex thread");
            if (retry != null && retry.inputRecorded) {
                if (cancellation.isCancelled()) return new Result(threadId, Status.CANCELLED, "");
                revertRejectedTurn(process, retry);
            }
            events.accept(new Event("conversation", threadId, thread));
            if (cancellation.isCancelled()) return new Result(threadId, Status.CANCELLED, "");
            var inputs = Json.MAPPER.createArrayNode().add(Json.object("type", "text", "text", request.prompt(), "text_elements", List.of()));
            for(var image:request.images()) inputs.add(Json.object("type","localImage","path",image.toAbsolutePath().toString()));
            var turnParams = Json.object("threadId", threadId, "input", inputs);
            if (model != null) turnParams.put("model", model);
            String effort = requestedEffort != null ? requestedEffort : thread.path("reasoningEffort").asText(null);
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
            String compactionId = null;
            boolean workStarted = false;
            boolean inputRecorded = false;
            while (System.nanoTime() < deadline) {
                if (cancellation.isCancelled() && cancelDeadline == Long.MAX_VALUE) cancelDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                if (System.nanoTime() > cancelDeadline) return new Result(threadId, Status.CANCELLED, text.toString());
                var message = process.next(Duration.ofMillis(250));
                if (message == null) continue;
                String event = Json.text(message, "method");
                var payload = message.path("params");
                if (message.has("id")) {
                    workStarted = true;
                    events.accept(new Event("request", event, message));
                    if (request.requests() != null) {
                        var answer = request.requests().handle(event, payload, cancellation);
                        if (answer != null) process.send(Json.object("id", message.get("id"), "result", answer));
                        else process.send(Json.object("id", message.get("id"), "error", Json.object("code", -32601, "message", "Unsupported interactive request: " + event)));
                    } else process.send(Json.object("id", message.get("id"), "error", Json.object("code", -32601, "message", "Interactive requests are not enabled")));
                    continue;
                }
                if (payload.has("threadId") && !threadId.equals(payload.path("threadId").asText())) continue;
                if (event.equals("item/started") || event.equals("item/completed")) {
                    if (payload.path("item").path("type").asText().equals("contextCompaction"))
                        compactionId = payload.path("item").path("id").asText();
                    else if (payload.path("item").path("type").asText().equals("userMessage")) inputRecorded = true;
                    else workStarted = true;
                }
                if (event.startsWith("item/") && !event.equals("item/started") && !event.equals("item/completed")) workStarted = true;
                if (event.equals("turn/diff/updated")) workStarted = true;
                if (event.equals("item/agentMessage/delta")) {
                    workStarted = true;
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
                        if (canRecover && !workStarted && !inputRecorded && text.isEmpty() && compactionId != null && !compactionId.isBlank() && unsupportedCompactionSearch(reason))
                            throw new CompactionRejected(threadId, compactionId, thread.path("model").asText(request.model()), effort);
                        if (retry == null && !workStarted && text.isEmpty() && unsupportedSearch(reason))
                            throw new SearchRejected(threadId, turnId, thread.path("model").asText(model), effort, inputRecorded);
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
            if (cancellation.isCancelled()) return new Result(conversationId, Status.CANCELLED, "");
            throw failure;
        }
    }

    private static boolean unsupportedCompactionSearch(String reason) {
        String prefix = "Error running remote compact task: ";
        return reason.startsWith(prefix) && unsupportedSearch(reason.substring(prefix.length()));
    }

    private static boolean unsupportedSearch(String reason) {
        try {
            var response = Json.parse(reason);
            var error = response.path("error");
            return response.path("status").asInt() == 400
                    && error.path("type").asText().equals("invalid_request_error")
                    && error.path("code").asText().equals("unsupported_parameter")
                    && error.path("param").asText().equals("tools")
                    && error.path("message").asText().equals("The 'web_search' tool is not available for this request.");
        } catch (java.io.IOException ignored) { return false; }
    }

    private static final class SearchRejected extends Exception {
        final String threadId, turnId, model, effort;
        final boolean inputRecorded;
        SearchRejected(String threadId, String turnId, String model, String effort, boolean inputRecorded) {
            this.threadId = threadId; this.turnId = turnId; this.model = model; this.effort = effort; this.inputRecorded = inputRecorded;
        }
    }

    private static void revertRejectedTurn(JsonProcess process, SearchRejected failure) throws Exception {
        // Do not append the user's input twice, or erase another client's later work.
        var page = process.call("thread/turns/list", Json.object("threadId", failure.threadId,
                "limit", 1, "sortDirection", "desc", "itemsView", "full"), Duration.ofSeconds(30));
        var latest = page.path("data").path(0);
        if (!latest.path("id").asText().equals(failure.turnId) || !latest.path("status").asText().equals("failed"))
            throw new java.io.IOException("Cannot retry rejected search: Codex thread has changed");
        for (var item : latest.path("items"))
            if (!item.path("type").asText().equals("userMessage"))
                throw new java.io.IOException("Cannot retry rejected search after agent activity");
        process.call("thread/revert", Json.object("threadId", failure.threadId, "beforeTurnId", failure.turnId), Duration.ofSeconds(30));
    }

    private static final class CompactionRejected extends Exception {
        final String threadId, itemId, model, effort;
        CompactionRejected(String threadId, String itemId, String model, String effort) {
            this.threadId = threadId; this.itemId = itemId; this.model = model; this.effort = effort;
        }
    }

    private void compactWithoutSearch(Request request, CompactionRejected failure, java.util.function.Consumer<Event> events, Cancellation cancellation) throws Exception {
        try (var process = openProcess(request)) {
            cancellation.onCancel(process::stop);
            if (cancellation.isCancelled()) return;
            initialize(process);
            var config = Json.object("web_search", "disabled");
            if (failure.effort != null) config.put("model_reasoning_effort", failure.effort);
            var params = Json.object("threadId", failure.threadId, "cwd", request.directory().toString(), "config", config,
                    "developerInstructions", DEVELOPER_INSTRUCTIONS,
                    "approvalPolicy", request.access().equals("danger-full-access") || request.requests() == null ? "never" : "on-request", "sandbox", request.access());
            if (failure.model != null) params.put("model", failure.model);
            var thread = process.call("thread/resume", params, Duration.ofSeconds(60));
            if (!failure.threadId.equals(thread.path("thread").path("id").asText())) throw new java.io.IOException("Compaction resumed a different Codex thread");
            if (cancellation.isCancelled()) return;
            process.call("thread/compact/start", Json.object("threadId", failure.threadId), Duration.ofSeconds(30));
            long deadline = System.nanoTime() + Math.min(request.timeout().toNanos(), Duration.ofMinutes(10).toNanos());
            boolean compacted = false;
            while (System.nanoTime() < deadline) {
                if (cancellation.isCancelled()) return;
                var message = process.next(Duration.ofMillis(250));
                if (message == null) continue;
                String event = Json.text(message, "method");
                var payload = message.path("params");
                if (message.has("id")) {
                    // Compaction must never execute tools or answer questions on the user's behalf.
                    process.send(Json.object("id", message.get("id"), "error", Json.object("code", -32601, "message", "Interactive requests are not supported during compaction recovery")));
                    throw new java.io.IOException("Codex requested an interactive action during compaction recovery");
                }
                if (payload.has("threadId") && !failure.threadId.equals(payload.path("threadId").asText())) continue;
                if (event.equals("item/completed") && payload.path("item").path("type").asText().equals("contextCompaction")) {
                    compacted = true;
                    // Complete the existing chat notice only after real, successful compaction.
                    var completed = message.deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) completed.path("params").path("item")).put("id", failure.itemId);
                    events.accept(new Event(event, "", completed));
                } else if (event.equals("turn/completed")) {
                    var turn = payload.path("turn");
                    if (!turn.path("status").asText().equals("completed") || !compacted)
                        throw new java.io.IOException("Codex compaction recovery failed: " + turn.path("error").path("message").asText(turn.path("status").asText()));
                    return;
                } else if (event.equals("thread/tokenUsage/updated")) events.accept(new Event(event, "", message));
            }
            throw new java.util.concurrent.TimeoutException("Codex compaction recovery timed out");
        }
    }
}
