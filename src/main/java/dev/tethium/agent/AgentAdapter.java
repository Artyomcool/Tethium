package dev.tethium.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public interface AgentAdapter {
    enum Provider {
        CODEX("Codex"), CLAUDE("Claude");
        public final String title;
        Provider(String title) { this.title = title; }
        /** Stored preferences may be empty or come from an older build; both mean the default executor. */
        public static Provider of(String value) {
            if (value == null || value.isBlank() || value.strip().equals("null")) return CODEX;
            for (var provider : values()) if (provider.name().equalsIgnoreCase(value.strip())) return provider;
            throw new IllegalArgumentException("Неизвестный исполнитель: " + value);
        }
    }
    enum Status { COMPLETED, FAILED, CANCELLED }
    /** Shared guidance for every executor; the CLI-specific delivery channel is chosen by the adapter. */
    String DEVELOPER_INSTRUCTIONS = "You are running inside Tethium. Use its session_list/session_history tools to coordinate with other sessions in the same project. Use artifact_publish to show generated HTML or images to the user. Use app_publish to publish completed signed APK builds to the local Android app store when delivering an Android app or update. Keep the signing key and increase versionCode for automatic updates. Do not create git worktrees unless the user asks. Keep user-facing communication in the user's language. Register detached or reused background helper processes with process_track so their CPU is accounted to this session.";
    record Request(Path directory, String prompt, String conversationId, String model,
                   URI toolsUrl, String toolsToken, Duration timeout, String access, RequestHandler requests, String reasoningEffort, java.util.List<Path> images) {
        public Request { images=java.util.List.copyOf(images); }
        public Request(Path directory, String prompt, String conversationId, String model, URI toolsUrl, String toolsToken, Duration timeout, String access, RequestHandler requests, String reasoningEffort) {
            this(directory,prompt,conversationId,model,toolsUrl,toolsToken,timeout,access,requests,reasoningEffort,java.util.List.of());
        }
        public Request(Path directory, String prompt, String conversationId, String model, URI toolsUrl, String toolsToken, Duration timeout, String access, RequestHandler requests) {
            this(directory, prompt, conversationId, model, toolsUrl, toolsToken, timeout, access, requests, null);
        }
        public Request(Path directory, String prompt, String conversationId, String model, URI toolsUrl, String toolsToken, Duration timeout) {
            this(directory, prompt, conversationId, model, toolsUrl, toolsToken, timeout, "read-only", null);
        }
    }
    @FunctionalInterface interface RequestHandler {
        JsonNode handle(String method, JsonNode params, Cancellation cancellation) throws Exception;
    }
    record Event(String type, String text, JsonNode raw) {}
    record Result(String conversationId, Status status, String text) {}

    Provider provider();
    /** Command prefix a detached worker uses to start the same CLI; empty for in-process test doubles. */
    default List<String> executable() { return List.of(); }
    Result run(Request request, Consumer<Event> events, Cancellation cancellation) throws Exception;
}
