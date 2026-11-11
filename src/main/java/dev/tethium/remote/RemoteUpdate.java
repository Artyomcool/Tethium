package dev.tethium.remote;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;

/**
 * Updates the plain-jar Remote from the laptop it is paired with. The laptop serves the archive; the installed one is known by the
 * SHA-256 of the archive it came from. The swap happens after this process exits, by a small script that then starts the new copy.
 */
public final class RemoteUpdate {
    private RemoteUpdate() {}
    /** The folder holding the launch script and lib/, or null when this Remote was not installed from the plain-jar archive. */
    public static Path installDir() {
        try {
            Path jar = Path.of(RemoteUpdate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = jar.getParent() == null ? null : jar.getParent().getParent();
            return dir != null && Files.isRegularFile(dir.resolve("tethium-remote")) && Files.isDirectory(dir.resolve("lib")) ? dir : null;
        } catch (Exception e) { return null; }
    }
    public static String installed() {
        Path dir = installDir();
        try { return dir == null ? "" : Files.readString(dir.resolve("version.txt")).strip(); } catch (IOException e) { return ""; }
    }
    /** The archive the laptop offers now, or empty when it has none. */
    public static String available(LanConnection connection) throws Exception {
        return connection.json("/remote/version", null).path("jvm").asText("");
    }
    /** Downloads and unpacks the new copy next to the current one and starts the script that swaps them once this process exits. */
    public static void prepare(LanConnection connection, String expected) throws Exception {
        Path dir = installDir(); if (dir == null) throw new IOException("Это не установка из архива Linux/macOS");
        Path parent = dir.getParent(), staging = Files.createTempDirectory(parent, ".tethium-remote-update-"), zip = staging.resolve("update.zip");
        var request = connection.open("/tethium-remote-jvm.zip");
        try {
            if (request.getResponseCode() != 200) throw new IOException("Ноутбук не отдал архив: " + request.getResponseCode());
            try (var in = request.getInputStream()) { Files.copy(in, zip); }
        } finally { request.disconnect(); }
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(zip)) { byte[] buffer = new byte[1 << 16]; for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n); }
        String sha = HexFormat.of().formatHex(digest.digest());
        if (!expected.isEmpty() && !sha.equals(expected)) throw new IOException("Архив повреждён при передаче");
        try (var in = new ZipInputStream(Files.newInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                Path target = staging.resolve(entry.getName()).normalize();
                if (!target.startsWith(staging)) throw new IOException("Подозрительный путь в архиве");
                if (entry.isDirectory()) Files.createDirectories(target); else { Files.createDirectories(target.getParent()); Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        Path fresh = staging.resolve("tethium-remote");
        if (!Files.isRegularFile(fresh.resolve("tethium-remote"))) throw new IOException("В архиве нет Remote");
        Files.writeString(fresh.resolve("version.txt"), sha + "\n");
        Path script = staging.resolve("swap.sh");
        Files.writeString(script, String.join("\n",
            "#!/bin/sh",
            "PID=\"$1\"; DIR=\"$2\"; NEW=\"$3\"; STAGING=\"$4\"",
            "while kill -0 \"$PID\" 2>/dev/null; do sleep 0.3; done",
            "rm -rf \"$DIR.old\" && mv \"$DIR\" \"$DIR.old\" && mv \"$NEW\" \"$DIR\" && chmod +x \"$DIR/tethium-remote\" && rm -rf \"$DIR.old\" \"$STAGING\"",
            "nohup \"$DIR/tethium-remote\" >/dev/null 2>&1 &", ""), StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var swap = new ProcessBuilder("sh", "-c", "nohup sh \"$0\" \"$1\" \"$2\" \"$3\" \"$4\" >/dev/null 2>&1 &", script.toString(), Long.toString(ProcessHandle.current().pid()), dir.toString(), fresh.toString(), staging.toString());
        swap.environment().put("JAVA", java);
        swap.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
    }
}
