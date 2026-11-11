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
    void measure() {
        if (!Files.isDirectory(folder)) { bytes = 0; return; }
        try (var files = Files.walk(folder)) {
            bytes = files.filter(Files::isRegularFile).mapToLong(file -> { try { return Files.size(file); } catch (IOException gone) { return 0; } }).sum();
        } catch (Exception failure) { System.err.println("Media size: " + failure.getMessage()); }
    }
}
