package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import dev.tethium.host.AppPaths;
import dev.tethium.json.Json;
import dev.tethium.update.BuildInfo;
import javax.swing.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.*;

/** Authenticated local handoff endpoint. A ready window has applied real host state on the EDT. */
final class DesktopControl implements AutoCloseable {
    private final WorkspaceFrame frame;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String token = UUID.randomUUID().toString();
    private final Path descriptor;
    DesktopControl(WorkspaceFrame frame, String instanceId, String appPath) throws Exception {
        UUID.fromString(instanceId); this.frame = frame;
        Path clients = Files.createDirectories(AppPaths.data().resolve("clients")); descriptor = clients.resolve(instanceId + ".json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
        server.createContext("/", this::handle); server.start();
        var value = Json.object("pid", ProcessHandle.current().pid(), "port", server.getAddress().getPort(), "token", token, "appPath", appPath,
                "build", BuildInfo.BUILD, "hostBuild", BuildInfo.HOST, "instanceId", instanceId);
        Path temporary = clients.resolve(instanceId + ".tmp"); Json.MAPPER.writeValue(temporary.toFile(), value);
        Files.move(temporary, descriptor, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        var task = new FutureTask<>(work); SwingUtilities.invokeLater(task); return task.get(15, TimeUnit.SECONDS);
    }
    private void handle(HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            int code = 200; Object response;
            try {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8)) || exchange.getRequestHeaders().containsKey("Origin")) {
                    code = 403; response = Json.object("error", "Forbidden");
                } else if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/state")) {
                    response = edt(frame::reloadReadiness);
                } else if (exchange.getRequestMethod().equals("POST")) {
                    byte[] bytes = exchange.getRequestBody().readNBytes(2_000_001); if (bytes.length > 2_000_000) throw new IllegalArgumentException("Handoff is too large");
                    JsonNode body = bytes.length == 0 ? Json.object() : Json.MAPPER.readTree(bytes);
                    response = switch (exchange.getRequestURI().getPath()) {
                        case "/prepare" -> edt(frame::prepareHandoff).get(15, TimeUnit.SECONDS);
                        case "/adopt" -> edt(() -> { frame.adoptHandoff(body); return Json.object("ok", true); });
                        case "/updating" -> edt(() -> { frame.setUpdating(body.path("value").asBoolean()); return Json.object("ok", true); });
                        case "/activate" -> edt(() -> { if(body.path("handoff").asBoolean())frame.completeHandoff();else frame.activateAfterHandoff();return frame.reloadReadiness(); });
                        case "/resume" -> edt(() -> { frame.completeHandoff();return frame.reloadReadiness(); });
                        case "/tray" -> edt(() -> { frame.minimizeToTray();return frame.reloadReadiness(); });
                        case "/close" -> { SwingUtilities.invokeLater(frame::closeWindow); yield Json.object("ok", true); }
                        default -> throw new IllegalArgumentException("Unknown action");
                    };
                } else { code = 405; response = Json.object("error", "Method not allowed"); }
            } catch (Exception e) { code = 400; response = Json.object("error", e.toString()); }
            byte[] output = Json.MAPPER.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, output.length); exchange.getResponseBody().write(output);
        }
    }
    @Override public void close() {
        server.stop(0); executor.shutdown(); try { Files.deleteIfExists(descriptor); } catch (Exception ignored) { }
    }
}
