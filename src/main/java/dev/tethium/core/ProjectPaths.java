package dev.tethium.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public final class ProjectPaths {
    private ProjectPaths() {}
    /** A linked worktree and the main checkout belong to the same project. Plain folders are allowed before git init. */
    public static Path root(Path directory) throws Exception {
        Path real = directory.toRealPath();
        if (!Files.isDirectory(real)) throw new IllegalArgumentException("Выберите каталог проекта");
        Process git;
        try { git = new ProcessBuilder("git", "-C", real.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir").redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch (java.io.IOException unavailable) { return real; }
        if (!git.waitFor(5, TimeUnit.SECONDS)) { git.destroyForcibly(); throw new IllegalStateException("Git не отвечает"); }
        if (git.exitValue() != 0) return real;
        String value = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        Path common = Path.of(value).toRealPath();
        return common.getFileName().toString().equals(".git") ? common.getParent() : real;
    }
}
