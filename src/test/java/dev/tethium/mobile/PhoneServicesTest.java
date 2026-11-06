package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.tethium.agent.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class PhoneServicesTest {
    @TempDir Path root;
    private static final String SIGNER = "ab".repeat(32);
    private static final AgentAdapter ADAPTER = new AgentAdapter() { public Provider provider() { return Provider.CODEX; } public Result run(Request r, Consumer<Event> e, Cancellation c) { throw new AssertionError("No model calls"); } };
    private static HttpResponse<String> send(HttpClient c, MobileGateway m, String method, String path, String body, String token) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + m.httpPort() + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        return c.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode mintBody(String service, String pkg, String signer) { return Json.object("service", service, "package", pkg, "signers", List.of(signer)); }
    @Test void forwardsOnlyWithAScopedTokenAndKeepsTethiumTokensFromTheService() throws Exception {
        var seenAuth = new AtomicReference<String>(); var seenQuery = new AtomicReference<String>(); var seenLength = new AtomicReference<String>();
        var service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/", e -> { try (e) {
            seenAuth.set(e.getRequestHeaders().getFirst("Authorization")); seenQuery.set(e.getRequestURI().getRawQuery()); seenLength.set(e.getRequestHeaders().getFirst("Content-Length"));
            byte[] in = e.getRequestBody().readAllBytes(); String path = e.getRequestURI().getRawPath();
            if (path.equals("/empty")) { e.sendResponseHeaders(204, -1); return; }
            byte[] out = (e.getRequestMethod() + " " + path + " " + new String(in, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("X-Service", "yes"); e.sendResponseHeaders(path.equals("/missing") ? 404 : 200, out.length); e.getResponseBody().write(out);
        } });
        service.start();
        Path project = Files.createDirectories(root.resolve("shop")); int port = service.getAddress().getPort();
        try (var h = new HostService(root.resolve("data"), ADAPTER); var local = new HostClient(h.descriptor()); var m = new MobileGateway(root.resolve("data"), h.feed(), 0, 0); var c = HttpClient.newHttpClient()) {
            local.post("/projects", Json.object("path", project.toString()));
            String device = Json.parse(send(c, m, "POST", "/pair", Json.object("code", URI.create(m.pairInfo().path("pairing").asText()).getQuery().split("code=")[1]).toString(), null).body()).path("token").asText();
            var published = MobileGateway.publishService(project, "shop", port, "our.shop");
            assertTrue(published.path("guide").asText().contains("dev.tethium.mobile.link")); assertTrue(published.path("client").asText().contains("final class TethiumLink"));
            assertEquals(401, send(c, m, "POST", "/service-token", mintBody("shop", "our.shop", SIGNER).toString(), null).statusCode(), "minting needs a paired device");
            assertEquals(401, send(c, m, "POST", "/service-token", mintBody("shop", "our.shop", SIGNER).toString(), device).statusCode(), "the APK is not published yet");
            Json.MAPPER.writeValue(project.resolve(".tethium-apps.json").toFile(), Json.object("apps", List.of(Json.object("packageName", "our.shop", "signerSha256", SIGNER))));
            assertEquals(401, send(c, m, "POST", "/service-token", mintBody("shop", "evil.app", SIGNER).toString(), device).statusCode(), "another package");
            var forged = send(c, m, "POST", "/service-token", mintBody("shop", "our.shop", "cd".repeat(32)).toString(), device);
            assertEquals(401, forged.statusCode(), "another signing key"); assertTrue(forged.body().contains("cd".repeat(32)));
            assertEquals(404, send(c, m, "POST", "/service-token", mintBody("absent", "our.shop", SIGNER).toString(), device).statusCode());
            var minted = send(c, m, "POST", "/service-token", mintBody("shop", "our.shop", SIGNER).toString(), device); assertEquals(200, minted.statusCode(), minted.body());
            String token = Json.parse(minted.body()).path("token").asText();

            assertEquals(401, send(c, m, "GET", "/svc/shop/api", null, null).statusCode());
            assertEquals(401, send(c, m, "GET", "/svc/shop/api", null, device).statusCode(), "the device token does not open services");
            assertEquals(401, send(c, m, "GET", "/state", null, token).statusCode(), "the service token does not open Tethium");
            var get = send(c, m, "GET", "/svc/shop/api/lists?a=1&b=%20x", null, token);
            assertEquals(200, get.statusCode()); assertEquals("GET /api/lists ", get.body()); assertEquals("yes", get.headers().firstValue("X-Service").orElse(""));
            assertEquals("a=1&b=%20x", seenQuery.get()); assertNull(seenAuth.get(), "the Tethium token stays in the gateway");
            String text = "тело ".repeat(50000);
            var post = send(c, m, "POST", "/svc/shop/upload", text, token);
            assertEquals("POST /upload " + text, post.body()); assertEquals(Integer.toString(text.getBytes(StandardCharsets.UTF_8).length), seenLength.get());
            assertEquals(404, send(c, m, "GET", "/svc/shop/missing", null, token).statusCode(), "the service status passes through");
            assertEquals(204, send(c, m, "DELETE", "/svc/shop/empty", null, token).statusCode());

            MobileGateway.publishService(project, "other", port, "our.other"); Thread.sleep(600);
            assertEquals(401, send(c, m, "GET", "/svc/other/api", null, token).statusCode(), "a token opens one service only");
            service.stop(0);
            var down = send(c, m, "GET", "/svc/shop/api", null, token);
            assertEquals(503, down.statusCode()); assertTrue(down.body().contains("shop"), down.body());
            m.revoke();
            assertEquals(401, send(c, m, "GET", "/svc/shop/api", null, token).statusCode(), "revoking phones revokes their service tokens");
        } finally { service.stop(0); }
    }
    @Test void publishKeepsOtherServicesOfTheProjectAndRejectsBadInput() throws Exception {
        MobileGateway.publishService(root, "one", 8001, "our.one"); MobileGateway.publishService(root, "two", 8002, "our.two"); MobileGateway.publishService(root, "one", 8003, "our.one");
        var services = Json.MAPPER.readTree(root.resolve(".tethium/service.json").toFile()).path("services");
        assertEquals(2, services.size()); assertEquals("two", services.get(0).path("name").asText()); assertEquals(8003, services.get(1).path("port").asInt());
        assertThrows(IllegalArgumentException.class, () -> MobileGateway.publishService(root, "../bad", 8001, "our.one"));
        assertThrows(IllegalArgumentException.class, () -> MobileGateway.publishService(root, "ok", 0, "our.one"));
        assertThrows(IllegalArgumentException.class, () -> MobileGateway.publishService(root, "ok", 8001, "noDots"));
    }
}
