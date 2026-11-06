package dev.tethium.tools;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A stateless local MCP endpoint bound to one calling session; never trusts a supplied project id. */
public final class SessionToolsServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final SessionStore store;
    private final String sessionId;
    private final String projectId;
    private final String token = UUID.randomUUID() + "-" + UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger();
    public interface Extension {
        List<JsonNode> tools();
        Object call(String name, JsonNode arguments) throws Exception;
    }
    private final Extension extension;
    @FunctionalInterface public interface ToolCall { Object call(JsonNode params) throws Exception; }
    private List<Object> proxyCatalog;
    private ToolCall proxy;

    public SessionToolsServer(List<Object> catalog, ToolCall proxy) throws Exception {
        this.store=null; this.sessionId=null; this.projectId=null; this.extension=null;
        this.proxyCatalog=List.copyOf(catalog); this.proxy=proxy;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",this::handle); server.setExecutor(executor); server.start();
    }

    public SessionToolsServer(SessionStore store, String sessionId) throws Exception {
        this(store, sessionId, null);
    }
    public SessionToolsServer(SessionStore store, String sessionId, Extension extension) throws Exception {
        this.extension = extension;
        this.store = store; this.sessionId = sessionId; this.projectId = store.get(sessionId).projectId();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.setExecutor(executor); server.start();
    }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"); }
    public String token() { return token; }
    public int calls() { return calls.get(); }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/mcp")) { send(exchange, 404, null); return; }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 401, null); return;
            }
            if (exchange.getRequestHeaders().containsKey("Origin")) { send(exchange, 403, null); return; }
            if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, null); return; }
            byte[] input = exchange.getRequestBody().readNBytes(65537);
            if (input.length > 65536) { send(exchange, 413, null); return; }
            JsonNode request;
            try { request = Json.MAPPER.readTree(input); }
            catch (IOException e) { send(exchange, 400, Json.object("jsonrpc", "2.0", "id", null, "error", Json.object("code", -32700, "message", "Invalid JSON"))); return; }
            if (request == null || !request.isObject()) { send(exchange, 400, null); return; }
            if (!request.has("id")) { send(exchange, 202, null); return; }
            String method = Json.text(request, "method");
            Object result;
            try {
                result = switch (method) {
                    case "initialize" -> Json.object("protocolVersion", "2025-03-26", "capabilities", Json.object("tools", Json.object()),
                            "serverInfo", Json.object("name", "tethium", "version", "0.1.0"));
                    case "ping" -> Json.object();
                    case "tools/list" -> Json.object("tools", toolList());
                    case "tools/call" -> call(request.path("params"));
                    default -> null;
                };
                if (result == null) send(exchange, 200, Json.object("jsonrpc", "2.0", "id", request.get("id"), "error", Json.object("code", -32601, "message", "Unknown method")));
                else send(exchange, 200, Json.object("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            } catch (Exception failure) {
                send(exchange, 200, Json.object("jsonrpc", "2.0", "id", request.get("id"), "result", content(failure.getMessage() == null ? "Tool service failed" : failure.getMessage(), true)));
            }
        }
    }
    private List<Object> toolList() {
        return proxy != null ? proxyCatalog : catalog(extension);
    }
    public static List<Object> catalog(Extension extension) {
        var tools = new ArrayList<>(List.of(
                            tool("session_list", "List all sessions in this project, including inactive sessions.", Json.object()),
                            tool("session_history", "Read another session's history in this project, or your own. Paginated by after_sequence.", Json.object(
                                    "session_id", Json.object("type", "string"), "after_sequence", Json.object("type", "integer", "minimum", 0))),
                            tool("probe_echo", "Integration probe: return the exact value and the current session id.", Json.object("value", Json.object("type", "string")))));
        if (extension != null) tools.addAll(extension.tools());
        return tools;
    }
    private static Object tool(String name, String description, JsonNode properties) {
        return Json.object("name", name, "description", description, "inputSchema", Json.object("type", "object", "properties", properties, "additionalProperties", false),
                "annotations", Json.object("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }
    private Object call(JsonNode params) throws Exception {
        Object result = proxy != null ? proxy.call(params) : dispatch(store,sessionId,extension,params);
        calls.incrementAndGet(); return result;
    }
    public static Object dispatch(SessionStore store, String sessionId, Extension extension, JsonNode params) throws Exception {
        String projectId=store.get(sessionId).projectId();
        String name = Json.text(params, "name");
        var args = params.path("arguments");
        Object result;
        switch (name) {
            case "session_list" -> {
                var sessions = new ArrayList<JsonNode>();
                for (var s : store.list(projectId)) sessions.add(Json.object("id", s.id(), "self", s.id().equals(sessionId), "title", s.title(), "active", s.active(), "parent_id", s.parentId(),
                        "directory", s.directory().toString(), "run_status", store.lastStatus(s.id()), "queued", store.queued(s.id()).size(), "last_sequence", store.lastSequence(s.id())));
                result = sessions;
            }
            case "session_history" -> {
                String target = args.path("session_id").asText(sessionId);
                try {
                    if (!store.get(target).projectId().equals(projectId)) return content("Session not found in this project", true);
                } catch (IllegalArgumentException e) { return content("Session not found in this project", true); }
                result = store.messages(target, Math.max(0, args.path("after_sequence").asLong()), 100).stream().map(m -> new SessionStore.Message(m.sequence(), m.role(), m.provider(), dev.tethium.core.ActionLines.strip(m.text()), m.timestamp())).toList();
            }
            case "probe_echo" -> result = Json.object("value", args.path("value").asText(), "session_id", sessionId);
            default -> {
                if (extension == null) return content("Unknown tool", true);
                result = extension.call(name, args);
            }
        }
        return content(Json.MAPPER.writeValueAsString(result), false);
    }
    private static Object content(String text, boolean error) { return Json.object("content", List.of(Json.object("type", "text", "text", text)), "isError", error); }
    private void send(HttpExchange exchange, int code, Object value) throws IOException {
        if (value == null) { exchange.sendResponseHeaders(code, -1); return; }
        byte[] bytes = Json.MAPPER.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); executor.close(); }
}

