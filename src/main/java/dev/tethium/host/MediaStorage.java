package dev.tethium.host;

import java.io.IOException;
import java.nio.file.*;

/** Size of the saved chat images. Nothing is removed automatically; past the limit the window asks for a manual clean-up. */
final class MediaStorage {
    static final long LIMIT = 2L << 30;
    private final Path folder;
    private volatile long bytes = -1;
    MediaStorage(Path data) { folder = data.resolve("chat-media"); }
    Path folder() { return folder; }
    long bytes() { return bytes; }
    private volatile com.fasterxml.jackson.databind.JsonNode disks = dev.tethium.json.Json.MAPPER.createArrayNode();
    private volatile long disksAt;
    /** The drives holding Tethium's data and the projects, fullest first; cached for five minutes, the window asks often. */
    com.fasterxml.jackson.databind.JsonNode disks(java.util.function.Supplier<java.util.List<Path>> projects) {
        long now = System.currentTimeMillis(); if (now - disksAt < 300_000) return disks;
        var stores = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ObjectNode>();
        var paths = new java.util.ArrayList<Path>(); paths.add(folder.getParent()); try { paths.addAll(projects.get()); } catch (Exception ignored) {}
        for (int i = 0; i < paths.size(); i++) {
            try {
                Path path = paths.get(i).toAbsolutePath(); var store = Files.getFileStore(Files.exists(path) ? path : path.getRoot());
                String root = path.getRoot() == null ? store.name() : path.getRoot().toString();
                var row = stores.computeIfAbsent(root + "|" + store.name(), key -> { try { return dev.tethium.json.Json.object("root", root, "label", store.name(), "total", store.getTotalSpace(), "free", store.getUsableSpace(), "data", false, "projects", 0); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } });
                if (i == 0) row.put("data", true); else row.put("projects", row.path("projects").asInt() + 1);
            } catch (Exception skipped) {}
        }
        var rows = new java.util.ArrayList<>(stores.values());
        rows.sort(java.util.Comparator.comparingDouble(row -> -(1 - row.path("free").asDouble() / Math.max(1, row.path("total").asDouble()))));
        var array = dev.tethium.json.Json.MAPPER.createArrayNode(); rows.forEach(array::add);
        disks = array; disksAt = now; return array;
    }
    void measure() {
        if (!Files.isDirectory(folder)) { bytes = 0; return; }
        try (var files = Files.walk(folder)) {
            bytes = files.filter(Files::isRegularFile).mapToLong(file -> { try { return Files.size(file); } catch (IOException gone) { return 0; } }).sum();
        } catch (Exception failure) { System.err.println("Media size: " + failure.getMessage()); }
    }
}
