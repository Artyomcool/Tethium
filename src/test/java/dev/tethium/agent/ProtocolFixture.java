package dev.tethium.agent;

import dev.tethium.json.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Real child-process transport with deterministic protocol transcripts; no model requests. */
public final class ProtocolFixture {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (mode.equals("malformed")) { System.out.println("not json"); return; }
        if (mode.startsWith("claude")) {
            Json.parse(input.readLine());
            output(Json.object("type", "system", "subtype", "init", "session_id", "claude-thread"));
            if (mode.equals("claude-error")) {
                output(Json.object("type", "result", "subtype", "success", "is_error", true, "result", "Weekly limit reached", "session_id", "claude-thread")); return;
            }
            output(Json.object("type", "stream_event", "event", Json.object("type", "content_block_delta", "delta", Json.object("type", "text_delta", "text", "Hello world"))));
            if (mode.equals("claude-cancel")) { Thread.sleep(30000); return; }
            output(Json.object("type", "assistant", "message", Json.object("content", java.util.List.of(Json.object("type", "text", "text", "Hello world")))));
            output(Json.object("type", "result", "subtype", "success", "is_error", false, "result", "Hello world", "session_id", "claude-thread")); return;
        }
        String line;
        while ((line = input.readLine()) != null) {
            var request = Json.parse(line);
            var id = request.get("id");
            switch (request.path("method").asText()) {
                case "initialize" -> {
                    output(Json.object("method", "test/notification", "params", Json.object("value", 42)));
                    output(Json.object("id", id, "result", Json.object("userAgent", "fixture")));
                }
                case "account/rateLimits/read" -> output(Json.object("id", id, "result", Json.object("rateLimits", Json.object("limitId", "codex", "primary", Json.object("usedPercent", 82, "windowDurationMins", 10080, "resetsAt", 1794207924)))));
                case "model/list" -> {
                    boolean second = request.path("params").path("cursor").asText().equals("page-two");
                    output(Json.object("id", id, "result", Json.object("data", java.util.List.of(Json.object("model", second ? "second-model" : "first-model", "supportedReasoningEfforts", java.util.List.of(Json.object("reasoningEffort", "high")))), "nextCursor", second ? null : "page-two")));
                }
                case "config/read" -> output(Json.object("id", id, "result", Json.object("config", Json.object("model", "first-model", "model_reasoning_effort", "high"))));
                case "thread/start", "thread/resume" -> {
                    if (mode.equals("quota") || mode.equals("models")) throw new AssertionError("Discovery must not create or resume a thread");
                    if (mode.equals("codex-effort") && (!request.path("params").path("model").asText().equals("chosen-model") || !request.path("params").path("config").path("model_reasoning_effort").asText().equals("max"))) throw new AssertionError("Model/effort missing from thread start/resume");
                    if (mode.equals("codex-full-access") && (!request.path("params").path("approvalPolicy").asText().equals("never") || !request.path("params").path("sandbox").asText().equals("danger-full-access")))
                        throw new AssertionError("Full access was not applied when opening the thread");
                    output(Json.object("id", id, "result", Json.object("thread", Json.object("id", "codex-thread"), "reasoningEffort", "high")));
                }
                case "turn/start" -> {
                    if(mode.equals("codex-image")) {
                        var inputs=request.path("params").path("input");
                        if(inputs.size()!=2 || !inputs.get(1).path("type").asText().equals("localImage") || !inputs.get(1).path("path").asText().endsWith("screen.png")) throw new AssertionError("Missing native image input");
                    }
                    if (mode.equals("quota") || mode.equals("models")) throw new AssertionError("Discovery must not start a model turn");
                    if (mode.equals("codex-effort") && (!request.path("params").path("model").asText().equals("chosen-model") || !request.path("params").path("effort").asText().equals("max"))) throw new AssertionError("Model/effort missing from turn start");
                    if (mode.equals("codex-default-effort") && !request.path("params").path("effort").asText().equals("high")) throw new AssertionError("Default effort must use the fresh thread configuration");
                    if (mode.equals("codex-full-access") && (!request.path("params").path("approvalPolicy").asText().equals("never") || !request.path("params").path("sandboxPolicy").path("type").asText().equals("dangerFullAccess")))
                        throw new AssertionError("Full access was not applied when starting the turn");
                    output(Json.object("id", id, "result", Json.object("turn", Json.object("id", "turn-1"))));
                    output(Json.object("method", "item/agentMessage/delta", "params", Json.object("threadId", "other-thread", "delta", "must not leak")));
                    output(Json.object("method", "item/agentMessage/delta", "params", Json.object("threadId", "codex-thread", "delta", "Hello world")));
                    if (!mode.equals("codex-cancel")) completed("completed");
                }
                case "turn/interrupt" -> { output(Json.object("id", id, "result", Json.object())); completed("interrupted"); }
            }
        }
    }
    private static void completed(String status) {
        output(Json.object("method", "turn/completed", "params", Json.object("threadId", "codex-thread", "turn", Json.object("id", "turn-1", "status", status))));
    }
    private static void output(Object value) { System.out.println(value); System.out.flush(); }
}

