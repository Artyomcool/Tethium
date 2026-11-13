package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import dev.tethium.update.BuildInfo;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public class HostClient implements AutoCloseable {
    public static final class ApiException extends IOException {
        public final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
    }
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile JsonNode descriptor;
    private Path reconnectData;
    public HostClient(JsonNode descriptor) { this.descriptor = descriptor.deepCopy(); }
    public long hostPid() { return descriptor.path("pid").asLong(); }
    public boolean remote(){return false;}
    public static HostClient connect() throws Exception {
        Path data = AppPaths.data(); Files.createDirectories(data);
        var existing = existing(data); if (existing != null) return existing;
        var command = new ArrayList<>(List.of(AppPaths.javaExecutable(true), "--enable-native-access=ALL-UNNAMED", "-Dtethium.data=" + data,
                "-cp", absoluteClasspath(), HostMain.class.getName()));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(data.resolve("host.log").toFile())).start();
        for (int i = 0; i < 100; i++) { Thread.sleep(100); var connection = existing(data); if (connection != null) return connection; if (!process.isAlive() && i > 10) break; }
        throw new IOException("Не удалось запустить ядро. Журнал: " + data.resolve("host.log"));
    }
    private static HostClient existing(Path data) throws IOException {
        try {
            var descriptor = Json.MAPPER.readTree(data.resolve("host.json").toFile());
            var connection = new HostClient(descriptor);
            JsonNode health;
            try { health = connection.get("/health"); } catch (Exception unavailable) { connection.close(); return null; }
            if (!compatible(health)) { connection.close(); throw new IOException("Ядро использует несовместимый протокол. Обновите приложение целиком."); }
            connection.reconnectData = data; return connection;
        } catch (java.nio.file.NoSuchFileException | FileNotFoundException missing) { return null; }
    }
    private static boolean compatible(JsonNode health) { return health.has("protocol") ? health.path("protocol").asInt() == BuildInfo.PROTOCOL : health.path("version").asText().equals("0.2.1"); }
    private synchronized void reconnect() {
        if (reconnectData == null) return;
        try {
            var next = Json.MAPPER.readTree(reconnectData.resolve("host.json").toFile());
            if (next.path("token").asText().equals(descriptor.path("token").asText())) return;
            try (var candidate = new HostClient(next)) { if (compatible(candidate.get("/health"))) descriptor = next; }
        } catch (Exception ignored) { }
    }
    private static String absoluteClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))).map(p -> Path.of(p).toAbsolutePath().toString()).toList());
    }
    public JsonNode get(String path) throws Exception { return call(path, null); }
    public JsonNode post(String path, Object body) throws Exception { return call(path, Json.MAPPER.valueToTree(body)); }
    public byte[] bytes(String path)throws Exception{
        reconnect();var endpoint=descriptor;
        try{return requestBytes(endpoint,path);}catch(IOException failure){reconnect();if(endpoint!=descriptor)return requestBytes(descriptor,path);throw failure;}
    }
    public HttpURLConnection fileConnection(String path)throws Exception{
        if(!path.startsWith("/file-manager/"))throw new IOException("Некорректный путь передачи");
        reconnect();var endpoint=descriptor;var c=(HttpURLConnection)URI.create("http://127.0.0.1:"+endpoint.path("port").asInt()+path).toURL().openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(30_000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Authorization","Bearer "+endpoint.path("token").asText());return c;
    }
    private JsonNode call(String path, JsonNode body) throws Exception {
        reconnect();
        var endpoint = descriptor;
        try { return request(endpoint, path, body); }
        catch (IOException failure) {
            // Retry only reads after a verified endpoint change. Repeating a POST could duplicate work.
            reconnect();
            if (body == null && endpoint != descriptor) return request(descriptor, path, null);
            throw failure;
        }
    }
    private JsonNode request(JsonNode endpoint, String path, JsonNode body) throws Exception {
        String base = "http://127.0.0.1:" + endpoint.path("port").asInt();
        long timeout=path.startsWith("/file-manager/")?300:path.equals("/quota/reset")?65:path.startsWith("/project")?90:10;
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(timeout)).header("Authorization", "Bearer " + endpoint.path("token").asText());
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        var value = Json.parse(response.body());
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), value.path("error").asText("Ошибка ядра: " + response.statusCode()));
        return value;
    }
    private byte[] requestBytes(JsonNode endpoint,String path)throws Exception{
        String base="http://127.0.0.1:"+endpoint.path("port").asInt();var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+endpoint.path("token").asText()).GET().build();var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200)throw new ApiException(response.statusCode(),"Binary asset error: "+response.statusCode());return response.body();
    }
    @Override public void close() { client.close(); }
}
