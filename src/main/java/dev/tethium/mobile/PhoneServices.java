package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import dev.tethium.json.Json;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a project's local service reach its phone app through Tethium: the project declares the service in .tethium/service.json,
 * the gateway forwards /svc/name/... on its HTTP port to 127.0.0.1:port. Only the Tethium app on the phone talks to this path: it
 * proves the calling app's package and signing key against the project's published APK and keeps the service token to itself.
 */
final class PhoneServices implements AutoCloseable {
    static final String FILE = ".tethium/service.json", PREFIX = "/svc/";
    private static final String NAME = "[a-z0-9][a-z0-9-]{0,40}", PACKAGE = "[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+";
    private static final Set<String> NOT_FORWARDED = Set.of("authorization", "connection", "content-length", "expect", "host", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");
    static final String GUIDE = "The phone app never handles an address, pairing or a token: the Tethium app on the phone carries each request. Run the service on 127.0.0.1:port. Copy the source in the client field into the Android app as TethiumLink.java and change its package line. Add <queries><provider android:authorities=\"dev.tethium.mobile.link\"/></queries> to the manifest. On a background thread call TethiumLink.request(context, name, method, path, headers, body): the result holds the status, headers and body of the service; TethiumLink.Unavailable has a reason (no_tethium, not_paired, denied, unreachable, bad_request). Publish the APK with app_publish: only a package and signing key published by this project are served.";
    private record Service(String name, int port, Set<String> packages, Map<String, String> signers, String project) {}
    private final Path tokensFile;
    private final Callable<JsonNode> projects;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile Map<String, Service> known = Map.of();
    private volatile long readAt;
    PhoneServices(Path mobileDir, Callable<JsonNode> projects) throws IOException {
        tokensFile = mobileDir.resolve("service-tokens.json"); this.projects = projects;
        if (Files.isRegularFile(tokensFile)) Json.MAPPER.readTree(tokensFile.toFile()).properties().forEach(e -> tokens.put(e.getKey(), e.getValue().asText()));
    }
    /** Declares or replaces a service of the project; returns what its phone app needs to know. */
    static JsonNode publish(Path project, String name, int port, String packageName) throws IOException {
        if (!name.matches(NAME)) throw new IllegalArgumentException("Имя сервиса: строчные латинские буквы, цифры и дефис");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Некорректный порт");
        if (!packageName.matches(PACKAGE)) throw new IllegalArgumentException("Некорректное имя пакета");
        Path file = project.resolve(FILE); Files.createDirectories(file.getParent());
        var services = Json.MAPPER.createArrayNode();
        if (Files.isRegularFile(file)) for (var old : Json.MAPPER.readTree(file.toFile()).path("services")) if (!old.path("name").asText().equals(name)) services.add(old);
        services.add(Json.object("name", name, "port", port, "packages", List.of(packageName)));
        Path temp = Files.createTempFile(file.getParent(), "service-", ".tmp");
        try { Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), Json.object("services", services)); Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } finally { Files.deleteIfExists(temp); }
        String client; try (var in = PhoneServices.class.getResourceAsStream("/mobile/TethiumLink.java")) { client = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        return Json.object("name", name, "file", FILE, "guide", GUIDE, "client", client);
    }
    private Map<String, Service> read() throws Exception {
        var result = new LinkedHashMap<String, Service>();
        for (var project : projects.call()) {
            try {
                Path root = Path.of(project.path("root").asText()), file = root.resolve(FILE);
                if (!Files.isRegularFile(file)) continue;
                var signers = new HashMap<String, String>(); Path apps = root.resolve(".tethium-apps.json");
                if (Files.isRegularFile(apps)) for (var app : Json.MAPPER.readTree(apps.toFile()).path("apps")) if (app.hasNonNull("signerSha256")) signers.put(app.path("packageName").asText(), app.path("signerSha256").asText());
                for (var s : Json.MAPPER.readTree(file.toFile()).path("services")) {
                    String name = s.path("name").asText(); int port = s.path("port").asInt();
                    if (!name.matches(NAME) || port < 1 || port > 65535 || result.containsKey(name)) continue;
                    var packages = new HashSet<String>(); for (var p : s.path("packages")) packages.add(p.asText());
                    result.put(name, new Service(name, port, packages, signers, project.path("title").asText()));
                }
            } catch (Exception broken) { System.err.println("Phone service in " + project.path("root").asText() + ": " + broken.getMessage()); }
        }
        return result;
    }
    private Service service(String name) throws Exception {
        long now = System.currentTimeMillis(); var found = known.get(name);
        if (found == null && now - readAt > 500 || now - readAt > 5000) { known = read(); readAt = now; found = known.get(name); }
        if (found == null) throw new NoSuchElementException("Сервис не объявлен: " + name);
        return found;
    }
    /** Issues a token for one service to a package whose signing key matches the APK its project published. */
    JsonNode mint(JsonNode body) throws Exception {
        // Rare and security-relevant: always against the current declaration and the APK published just now.
        known = read(); readAt = System.currentTimeMillis();
        String name = body.path("service").asText(), packageName = body.path("package").asText(); var service = service(name);
        if (!service.packages().contains(packageName)) throw new SecurityException("Пакет " + packageName + " не указан у сервиса " + name);
        String expected = service.signers().get(packageName);
        if (expected == null) throw new SecurityException("Приложение " + packageName + " не опубликовано в проекте; выполните app_publish");
        boolean signed = false; var given = new ArrayList<String>();
        for (var signer : body.path("signers")) { given.add(signer.asText()); signed |= expected.equalsIgnoreCase(signer.asText()); }
        if (!signed) throw new SecurityException("Подпись " + packageName + " не совпадает с опубликованной: " + given);
        String token = MobileGateway.randomToken();
        tokens.put(MobileGateway.sha(token.getBytes(StandardCharsets.UTF_8)), name); save();
        return Json.object("token", token, "service", name, "path", PREFIX + name);
    }
    void revoke() throws IOException { tokens.clear(); save(); }
    private synchronized void save() throws IOException {
        Path temp = tokensFile.resolveSibling("service-tokens.tmp"); Json.MAPPER.writeValue(temp.toFile(), tokens);
        Files.move(temp, tokensFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    /** Forwards one request. Authorization stays here: the service never sees a Tethium token. */
    void proxy(HttpExchange e) throws Exception {
        String raw = e.getRequestURI().getRawPath().substring(PREFIX.length()); int slash = raw.indexOf('/');
        String name = slash < 0 ? raw : raw.substring(0, slash), rest = slash < 0 ? "/" : raw.substring(slash);
        String auth = e.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || !name.equals(tokens.get(MobileGateway.sha(auth.substring(7).getBytes(StandardCharsets.UTF_8))))) throw new SecurityException("Нет доступа к сервису " + name);
        var service = service(name);
        String query = e.getRequestURI().getRawQuery();
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + rest + (query == null ? "" : "?" + query))).timeout(Duration.ofMinutes(10));
        e.getRequestHeaders().forEach((header, values) -> { if (!NOT_FORWARDED.contains(header.toLowerCase(Locale.ROOT))) for (String value : values) builder.header(header, value); });
        String length = e.getRequestHeaders().getFirst("Content-Length");
        boolean chunked = "chunked".equalsIgnoreCase(e.getRequestHeaders().getFirst("Transfer-Encoding"));
        HttpRequest.BodyPublisher body = chunked ? HttpRequest.BodyPublishers.ofInputStream(e::getRequestBody)
                : length != null && Long.parseLong(length) > 0 ? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(e::getRequestBody), Long.parseLong(length))
                : HttpRequest.BodyPublishers.noBody();
        builder.method(e.getRequestMethod(), body);
        HttpResponse<java.io.InputStream> response;
        try { response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()); }
        catch (ConnectException | HttpConnectTimeoutException down) { failure(e, 503, "Сервис " + name + " не запущен на ноутбуке"); return; }
        catch (HttpTimeoutException slow) { failure(e, 504, "Сервис " + name + " не ответил"); return; }
        try (var in = response.body()) {
            response.headers().map().forEach((header, values) -> { if (!NOT_FORWARDED.contains(header.toLowerCase(Locale.ROOT))) e.getResponseHeaders().put(header, values); });
            long size = response.headers().firstValueAsLong("Content-Length").orElse(-1); int status = response.statusCode();
            boolean empty = size == 0 || status == 204 || status == 304 || e.getRequestMethod().equals("HEAD");
            e.sendResponseHeaders(status, empty ? -1 : Math.max(size, 0));
            if (!empty) in.transferTo(e.getResponseBody());
        }
    }
    private static void failure(HttpExchange e, int status, String message) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(Json.object("error", message));
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes);
    }
    @Override public void close() { client.close(); }
}
