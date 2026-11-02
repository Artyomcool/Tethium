package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.nio.file.Path;
import java.util.*;

/** Shared, read-only catalog cache, including project-specific Codex defaults. */
public final class CodexModelCatalog {
    @FunctionalInterface public interface Reader { JsonNode read(Path directory) throws Exception; }
    private record Entry(JsonNode value, long expires) {}
    private final Reader reader;
    private final Map<Path, Entry> cache = new HashMap<>();
    public CodexModelCatalog(Reader reader) { this.reader = reader; }
    public synchronized JsonNode get(Path directory) {
        long now = System.currentTimeMillis(); var previous = cache.get(directory);
        if (previous != null && previous.expires > now) return previous.value;
        if (reader == null) return Json.object("available", false, "data", List.of(), "error", "Каталог моделей недоступен");
        JsonNode value; long lifetime;
        try { value = reader.read(directory); lifetime = 600_000; }
        catch (Exception e) {
            value = Json.object("available", false, "data", List.of(), "error", "Не удалось загрузить модели Codex: " + e.getMessage()); lifetime = 30_000;
        }
        cache.put(directory, new Entry(value, now + lifetime)); return value;
    }
}
