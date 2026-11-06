package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.Base64;

/** A project owns its icon: {@code .tethium/icon.png} in the project root, versioned by size and modification time
 *  so clients, local or remote, fetch the bytes only when the file changes. */
public final class ProjectIcons {
    private ProjectIcons() {}
    static final String FILE = "icon.png";
    public static Path file(Path root) { return root.resolve(".tethium").resolve(FILE); }
    /** Empty when the project has no icon file. */
    public static String version(Path root) {
        Path file = file(root);
        try { return Files.isRegularFile(file) ? Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis() : ""; }
        catch (IOException e) { return ""; }
    }
    public static JsonNode read(Path root) throws IOException {
        Path file = file(root); String version = version(root);
        if (version.isEmpty()) return Json.object("version", "", "png", "");
        return Json.object("version", version, "png", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
    }
}
