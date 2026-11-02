package dev.tethium.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactServerTest {
    @TempDir Path root;
    @Test void servesBundleAssetsButRejectsTraversalAndWrites() throws Exception {
        var bundle = Files.createDirectory(root.resolve("bundle"));
        Files.writeString(bundle.resolve("index.html"), "<b>hello</b>");
        Files.writeString(root.resolve("secret.txt"), "not-an-artifact");
        try (var server = new ArtifactServer(bundle); var client = HttpClient.newHttpClient()) {
            var good = client.send(HttpRequest.newBuilder(server.uri("index.html")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, good.statusCode());
            assertEquals("<b>hello</b>", good.body());
            assertTrue(good.headers().firstValue("Content-Security-Policy").orElseThrow().contains("connect-src 'self'"));
            var escaped = client.send(HttpRequest.newBuilder(URI.create(server.baseUri() + "%2e%2e/secret.txt")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, escaped.statusCode());
            assertFalse(escaped.body().contains("not-an-artifact"));
            var post = client.send(HttpRequest.newBuilder(server.uri("index.html")).POST(HttpRequest.BodyPublishers.ofString("overwrite")).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(405, post.statusCode());
            assertEquals("<b>hello</b>", Files.readString(bundle.resolve("index.html")));
        }
    }
}
