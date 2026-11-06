package dev.tethium.agent;

import dev.tethium.json.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real child-process transport with deterministic protocol transcripts; no model requests. */
public final class ProtocolFixture {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (mode.equals("malformed")) { System.out.println("not json"); return; }
        if (mode.startsWith("claude")) { claude(mode, List.of(args), input); return; }
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
    /** Claude Code print mode: one user message in, streaming JSON out, control requests both ways. */
    private static void claude(String mode, List<String> args, BufferedReader input) throws Exception {
        var first = Json.parse(input.readLine());
        if (!first.path("type").asText().equals("user") || !first.path("message").path("content").get(0).path("text").asText().equals("hello")) throw new AssertionError("User message must carry the prompt as a content block");
        int resume = args.indexOf("--resume"), fresh = args.indexOf("--session-id");
        String session = resume >= 0 ? args.get(resume + 1) : fresh >= 0 ? args.get(fresh + 1) : "claude-thread";
        if (mode.equals("claude-flags")) {
            require(args, "--model", "chosen-model"); require(args, "--effort", "max"); require(args, "--permission-mode", "bypassPermissions");
            require(args, "--resume", "claude-thread"); require(args, "--permission-prompt-tool", "stdio");
            int prompt = args.indexOf("--append-system-prompt");
            if (prompt < 0 || !args.get(prompt + 1).contains("Never end a turn to wait for a background job")) throw new AssertionError("Claude must be told that background waits do not survive the turn");
            if (args.contains("--session-id")) throw new AssertionError("A resumed conversation must not get a new session id");
            if (!first.path("message").path("content").get(1).path("source").path("media_type").asText().equals("image/png")) throw new AssertionError("Images must be sent as base64 content blocks");
        }
        if (mode.equals("claude-new") && (fresh < 0 || resume >= 0)) throw new AssertionError("A new conversation must announce its own session id");
        if (!mode.equals("claude-flags") && !args.contains("--model")) throw new AssertionError("Runs without an explicit model must still name the default model instead of the account default");
        if (mode.equals("claude-stale")) {
            // A resumed session with an interrupted background task closes that stale turn first: notification, init, empty result.
            output(Json.object("type", "system", "subtype", "task_notification", "task_id", "b1", "status", "stopped", "summary", "Background shell command didn't finish before the previous session ended", "session_id", session));
            output(Json.object("type", "system", "subtype", "init", "session_id", session, "model", "fixture-model"));
            output(Json.object("type", "result", "subtype", "success", "is_error", false, "num_turns", 0, "duration_api_ms", 0, "result", "", "session_id", session));
            if (args.contains("--exit-after-stale")) return;
        }
        output(Json.object("type", "system", "subtype", "init", "session_id", session, "model", "fixture-model"));
        if (mode.equals("claude-error")) {
            output(Json.object("type", "result", "subtype", "success", "is_error", true, "result", "Weekly limit reached", "session_id", session)); return;
        }
        output(Json.object("type", "stream_event", "event", Json.object("type", "content_block_start", "index", 0, "content_block", Json.object("type", "text", "text", ""))));
        output(Json.object("type", "stream_event", "event", Json.object("type", "content_block_delta", "delta", Json.object("type", "text_delta", "text", "Hello world"))));
        if (mode.equals("claude-cancel")) {
            // The adapter must interrupt through the control channel instead of killing the process.
            String line; while ((line = input.readLine()) != null) {
                var control = Json.parse(line);
                if (control.path("type").asText().equals("control_request") && control.path("request").path("subtype").asText().equals("interrupt")) {
                    output(Json.object("type", "control_response", "response", Json.object("subtype", "success", "request_id", control.path("request_id").asText())));
                    output(Json.object("type", "result", "subtype", "success", "is_error", false, "result", "Hello world", "session_id", session, "terminal_reason", "aborted_streaming")); return;
                }
            }
            return;
        }
        output(Json.object("type", "assistant", "message", Json.object("content", List.of(Json.object("type", "text", "text", "Hello world"), Json.object("type", "tool_use", "id", "toolu_1", "name", "Bash", "input", Json.object("command", "echo ok"))),
                "usage", Json.object("input_tokens", 10, "cache_creation_input_tokens", 5, "cache_read_input_tokens", 100, "output_tokens", 7)), "session_id", session));
        String answer = "";
        if (mode.equals("claude-question")) {
            output(Json.object("type", "control_request", "request_id", "ask-1", "request", Json.object("subtype", "can_use_tool", "tool_name", "AskUserQuestion",
                    "input", Json.object("questions", List.of(Json.object("question", "Which color?", "header", "Color", "options", List.of(Json.object("label", "Red", "description", "r"), Json.object("label", "Blue", "description", "b")), "multiSelect", false))))));
            var reply = Json.parse(input.readLine());
            if (!reply.path("type").asText().equals("control_response") || !reply.path("response").path("request_id").asText().equals("ask-1")) throw new AssertionError("Control response expected");
            var response = reply.path("response").path("response");
            if (!response.path("behavior").asText().equals("allow")) throw new AssertionError("Answered questions must be allowed");
            answer = response.path("updatedInput").path("answers").path("Which color?").asText();
        }
        output(Json.object("type", "user", "message", Json.object("role", "user", "content", List.of(Json.object("type", "tool_result", "tool_use_id", "toolu_1", "content", "ok", "is_error", false))), "session_id", session));
        output(Json.object("type", "stream_event", "event", Json.object("type", "content_block_start", "index", 0, "content_block", Json.object("type", "text", "text", ""))));
        output(Json.object("type", "stream_event", "event", Json.object("type", "content_block_delta", "delta", Json.object("type", "text_delta", "text", "Done" + (answer.isEmpty() ? "" : " " + answer)))));
        output(Json.object("type", "assistant", "message", Json.object("content", List.of(Json.object("type", "text", "text", "Done"))), "session_id", session));
        output(Json.object("type", "result", "subtype", "success", "is_error", false, "result", "Hello world\n\nDone", "session_id", session));
    }
    private static void require(List<String> args, String flag, String value) {
        int index = args.indexOf(flag);
        if (index < 0 || !args.get(index + 1).equals(value)) throw new AssertionError("Expected " + flag + " " + value + " in " + args);
    }
    private static void completed(String status) {
        output(Json.object("method", "turn/completed", "params", Json.object("threadId", "codex-thread", "turn", Json.object("id", "turn-1", "status", status))));
    }
    private static void output(Object value) { System.out.println(value); System.out.flush(); }
}
