package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Project-owned interactive panel. Discovery never executes commands or starts a browser. */
final class ProjectPanels {
    private record Cached(long checked, JsonNode value) {}
    private final Map<Path,Cached> cache = new HashMap<>();

    synchronized JsonNode read(Path root) {
        long now = System.currentTimeMillis();
        var old = cache.get(root);
        if (old != null && now - old.checked < 2000) return old.value;
        var value = readManifest(root);
        cache.put(root, new Cached(now, value));
        return value;
    }

    static JsonNode readManifest(Path root) {
        Path manifest = root.resolve(".tethium/panel.json").toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifest)) return Json.MAPPER.nullNode();
        var result = Json.object("title", "Панель проекта", "path", manifest.toString());
        try {
            Path realRoot = root.toRealPath();
            if (!manifest.toRealPath().startsWith(realRoot)) throw new IllegalArgumentException("Манифест вне проекта");
            if (Files.size(manifest) > 16_384) throw new IllegalArgumentException("Манифест больше 16 КБ");
            var config = Json.MAPPER.readTree(manifest.toFile());
            if (config.path("disabled").asBoolean()) return Json.MAPPER.nullNode();
            if (config.path("version").asInt(1) != 1) throw new IllegalArgumentException("Неизвестная версия панели");
            String title = config.path("title").asText("Панель проекта").strip();
            result.put("title", title.isEmpty() ? "Панель проекта" : title);
            String entry = config.path("entry").asText("").strip(), url = config.path("url").asText("").strip();
            if (entry.isEmpty() == url.isEmpty()) throw new IllegalArgumentException("Укажите ровно одно: entry или url");
            if (!entry.isEmpty()) {
                Path file = manifest.getParent().resolve(entry).normalize().toRealPath();
                if (!file.startsWith(realRoot) || !Files.isRegularFile(file)) throw new IllegalArgumentException("HTML должен находиться внутри проекта");
                if (!file.toString().toLowerCase(Locale.ROOT).matches(".*\\.html?")) throw new IllegalArgumentException("entry должен указывать на HTML");
                result.put("entry", file.toString());
            } else {
                URI uri = URI.create(url);
                if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) ||
                    !Set.of("127.0.0.1", "[::1]", "localhost").contains(Objects.toString(uri.getHost(), "")) ||
                    uri.getPort() <= 0 || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
                    throw new IllegalArgumentException("url: нужен HTTP(S) сервис на loopback с явным портом");
                result.put("url", uri.toASCIIString());
            }
        } catch (Exception e) { result.put("error", Objects.toString(e.getMessage(), "Некорректная панель")); }
        return result;
    }
}
