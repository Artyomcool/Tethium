package dev.tethium.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

public interface AgentAdapter {
    enum Provider { CODEX, CLAUDE }
    enum Status { COMPLETED, FAILED, CANCELLED }
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
    Result run(Request request, Consumer<Event> events, Cancellation cancellation) throws Exception;
}

