package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/** Claude Code auto-memory of a project: one Markdown file per fact under ~/.claude/projects/&lt;encoded path&gt;/memory
 *  plus the MEMORY.md index. Deleted files are moved to the Tethium trash, never erased. */
public final class ClaudeMemory {
    private ClaudeMemory() {}
    static final String INDEX = "MEMORY.md";
    /** Claude Code names the project directory by replacing every character outside [A-Za-z0-9] with a dash. */
    public static String encode(Path directory) { return directory.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-"); }
    public static Path directory(Path claudeHome, Path project) { return claudeHome.resolve("projects").resolve(encode(project)).resolve("memory"); }
    public static Path defaultHome() { return Path.of(System.getProperty("user.home")).resolve(".claude"); }

    public static JsonNode list(Path memory) throws IOException {
        var files = Json.MAPPER.createArrayNode();
        if (Files.isDirectory(memory)) try (Stream<Path> stream = Files.list(memory)) {
            for (Path file : stream.sorted().toList()) {
                if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".md")) continue;
                String name = file.getFileName().toString(); var meta = frontMatter(file);
                files.add(Json.object("name", name, "index", name.equals(INDEX), "size", Files.size(file), "modified", Files.getLastModifiedTime(file).toMillis(),
                        "description", meta.getOrDefault("description", ""), "type", meta.getOrDefault("type", "")));
            }
        }
        return Json.object("directory", memory.toString(), "exists", Files.isDirectory(memory), "files", files);
    }
    /** Moves the named files to trash/&lt;stamp&gt;/ and drops their lines from the index; returns what moved. */
    public static JsonNode delete(Path memory, List<String> names, Path trash) throws IOException {
        var moved = Json.MAPPER.createArrayNode();
        Path target = trash.resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.now().atZone(ZoneId.systemDefault())));
        for (String name : names) {
            if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals("..")) throw new IllegalArgumentException("Недопустимое имя файла памяти: " + name);
            Path file = memory.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            Files.createDirectories(target); Files.move(file, target.resolve(name), StandardCopyOption.REPLACE_EXISTING); moved.add(name);
        }
        Path index = memory.resolve(INDEX);
        if (!moved.isEmpty() && Files.isRegularFile(index)) {
            var kept = new ArrayList<String>();
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                boolean refers = false; for (var name : moved) if (!name.asText().equals(INDEX) && line.contains("(" + name.asText() + ")")) refers = true;
                if (!refers) kept.add(line);
            }
            Files.write(index, kept, StandardCharsets.UTF_8);
        }
        return Json.object("moved", moved, "trash", moved.isEmpty() ? "" : target.toString());
    }
    private static Map<String, String> frontMatter(Path file) {
        var values = new LinkedHashMap<String, String>();
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            var it = lines.iterator(); if (!it.hasNext() || !it.next().strip().equals("---")) return values;
            while (it.hasNext()) {
                String line = it.next(); if (line.strip().equals("---")) break;
                int colon = line.indexOf(':'); if (colon <= 0) continue;
                values.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        } catch (Exception ignored) { }
        return values;
    }
}
