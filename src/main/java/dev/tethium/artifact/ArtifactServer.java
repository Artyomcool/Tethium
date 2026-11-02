package dev.tethium.artifact;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Separate preview origin, with access limited to one explicitly registered bundle. */
public final class ArtifactServer implements AutoCloseable {
    private final Path root;
    private final String prefix = "/" + UUID.randomUUID() + "/";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public ArtifactServer(Path root) throws IOException {
        this.root = root.toRealPath();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.setExecutor(executor); server.start();
    }
    public URI baseUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + prefix); }
    public URI uri(String file) {
        try { return baseUri().resolve(new URI(null, null, file, null)); }
        catch (URISyntaxException e) { throw new IllegalArgumentException(e); }
    }
    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(405, -1); return; }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(prefix)) { exchange.sendResponseHeaders(404, -1); return; }
            String relative = path.substring(prefix.length());
            Path target;
            try { target = root.resolve(relative).normalize().toRealPath(); }
            catch (IOException | InvalidPathException e) { exchange.sendResponseHeaders(404, -1); return; }
            if (!target.startsWith(root) || !Files.isRegularFile(target)) { exchange.sendResponseHeaders(403, -1); return; }
            String name = target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            byte[] rendered = MarkdownPreview.supports(target) ? MarkdownPreview.render(target) : null;
            String contentType = (rendered != null || name.endsWith(".html") || name.endsWith(".htm")) ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : name.endsWith(".css") ? "text/css; charset=utf-8" : name.endsWith(".json") ? "application/json"
                    : name.endsWith(".svg") ? "image/svg+xml" : Files.probeContentType(target);
            exchange.getResponseHeaders().set("Content-Type", contentType == null ? "application/octet-stream" : contentType);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200, -1); return; }
            exchange.sendResponseHeaders(200, rendered == null ? Files.size(target) : rendered.length);
            if (rendered == null) Files.copy(target, exchange.getResponseBody()); else exchange.getResponseBody().write(rendered);
        }
    }
    @Override public void close() { server.stop(0); executor.close(); }
}

