package dev.tethium.tools;

import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionToolsServerTest {
    @TempDir Path root;
    @Test void requiresTokenAndRestrictsHistoryToCallersProject() throws Exception {
        try (var store = new SessionStore(root.resolve("test.db")); var client = HttpClient.newHttpClient()) {
            var first = store.create(store.project(root), null, "Caller", root);
            var otherRoot = Files.createDirectory(root.resolve("other"));
            var other = store.create(store.project(otherRoot), null, "Other project", otherRoot);
            store.append(other.id(), "user", "USER", "private-other-project");
            try (var tools = new SessionToolsServer(store, first.id())) {
                var body = Json.object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Json.object("name", "session_history", "arguments", Json.object("session_id", other.id())));
                var denied = client.send(HttpRequest.newBuilder(tools.uri()).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(401, denied.statusCode());
                var reply = client.send(HttpRequest.newBuilder(tools.uri()).header("Authorization", "Bearer " + tools.token()).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(Json.parse(reply.body()).path("result").path("isError").asBoolean());
                assertFalse(reply.body().contains("private-other-project"));
                body.withObject("/params").put("name", "session_list");
                var sessions = client.send(HttpRequest.newBuilder(tools.uri()).header("Authorization", "Bearer " + tools.token()).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(sessions.body().contains(first.id()));
                assertFalse(sessions.body().contains(other.id()));
            }
        }
    }
}
