package dev.tethium.host;

import java.nio.file.*;

public final class AppPaths {
    private AppPaths() {}
    public static Path data() {
        String override = System.getProperty("tethium.data");
        if (override != null) return Path.of(override).toAbsolutePath();
        String local = System.getenv("LOCALAPPDATA");
        return local == null ? Path.of(System.getProperty("user.home"), ".tethium") : Path.of(local, "Tethium");
    }
    public static String javaExecutable(boolean background) {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path executable = bin.resolve(System.getProperty("os.name").startsWith("Windows") ? (background ? "javaw.exe" : "java.exe") : "java");
        return executable.toString();
    }
}

