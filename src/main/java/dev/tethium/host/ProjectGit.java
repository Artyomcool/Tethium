package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Read-only git views of a project for the window and Remote: what changed in the working tree, a file's diff, recent commits
 * and a commit's patch. Nothing here writes to the repository.
 */
final class ProjectGit {
    private static final int LIMIT = 1_500_000;
    private ProjectGit() {}

    static JsonNode read(Path root, String op, String file, String commit) throws Exception {
        return switch (op == null ? "status" : op) {
            case "status" -> status(root);
            case "diff" -> Json.object("text", diff(root, file));
            case "show" -> {
                if (commit == null || !commit.matches("[0-9a-fA-F]{7,40}")) throw new IllegalArgumentException("Некорректный коммит");
                yield Json.object("text", git(root, false, "show", "--no-color", "--stat", "--patch", "--format=commit %H%nAuthor: %an <%ae>%nDate:   %ad%n%n%B", commit));
            }
            default -> throw new IllegalArgumentException("Неизвестный запрос git");
        };
    }

    private static JsonNode status(Path root) throws Exception {
        String inside;
        try { inside = git(root, false, "rev-parse", "--is-inside-work-tree").strip(); } catch (Exception e) { inside = ""; }
        if (!inside.equals("true")) return Json.object("repository", false, "files", List.of(), "commits", List.of());
        String branch = "", ahead = "", stat = "";
        try { branch = git(root, false, "rev-parse", "--abbrev-ref", "HEAD").strip(); } catch (Exception ignored) {}
        try { String[] counts = git(root, false, "rev-list", "--left-right", "--count", "@{upstream}...HEAD").strip().split("\\s+"); ahead = "↑" + counts[1] + " ↓" + counts[0]; } catch (Exception ignored) {}
        try { stat = git(root, false, "diff", "HEAD", "--shortstat").strip(); } catch (Exception ignored) {}
        var files = new ArrayList<JsonNode>();
        String porcelain = git(root, false, "status", "--porcelain=v1", "-z", "--untracked-files=all");
        String[] parts = porcelain.split("\0");
        for (int i = 0; i < parts.length; i++) {
            String entry = parts[i]; if (entry.length() < 4) continue;
            String code = entry.substring(0, 2), path = entry.substring(3), from = "";
            if (code.charAt(0) == 'R' || code.charAt(0) == 'C') from = i + 1 < parts.length ? parts[++i] : "";
            String kind = code.equals("??") ? "untracked" : code.contains("D") ? "deleted" : code.contains("A") ? "added" : code.contains("R") ? "renamed" : "modified";
            files.add(Json.object("path", path, "code", code, "kind", kind, "from", from));
        }
        var commits = new ArrayList<JsonNode>();
        try {
            for (String line : git(root, false, "log", "-n", "60", "--format=%H%x1f%h%x1f%an%x1f%at%x1f%s").split("\n")) {
                String[] f = line.split("\u001f", -1); if (f.length < 5) continue;
                commits.add(Json.object("hash", f[0], "short", f[1], "author", f[2], "time", Long.parseLong(f[3]) * 1000, "subject", f[4]));
            }
        } catch (Exception ignored) {}
        return Json.object("repository", true, "branch", branch, "upstream", ahead, "stat", stat, "files", files, "commits", commits);
    }

    private static String diff(Path root, String file) throws Exception {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("Не указан файл");
        Path target = root.resolve(file).normalize();
        if (!target.startsWith(root.normalize()) || Path.of(file).isAbsolute()) throw new IllegalArgumentException("Файл вне проекта");
        String tracked = git(root, false, "ls-files", "--", file).strip();
        if (tracked.isEmpty()) return git(root, true, "diff", "--no-color", "--no-index", "--", "/dev/null", file);
        try { return git(root, false, "diff", "HEAD", "--no-color", "--", file); }
        catch (IOException noHead) { return git(root, false, "diff", "--cached", "--no-color", "--", file); }
    }

    /** Runs git with UTF-8 paths and a size cap; differencesAreSuccess: `diff --no-index` exits with 1 when files differ. */
    private static String git(Path root, boolean differencesAreSuccess, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git", "-c", "core.quotepath=false", "-c", "color.ui=false")); command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(root.toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0"); builder.environment().put("GIT_PAGER", "cat"); builder.environment().put("LC_ALL", "C.UTF-8");
        var process = builder.start(); process.getOutputStream().close();
        // Warnings (line endings and the like) go to stderr; they belong in an error message, not in the diff.
        var errors = CompletableFuture.supplyAsync(() -> { try (var in = process.getErrorStream()) { return new String(in.readNBytes(65536), StandardCharsets.UTF_8); } catch (IOException e) { return ""; } });
        var output = CompletableFuture.supplyAsync(() -> {
            try (var in = process.getInputStream()) {
                var buffer = new ByteArrayOutputStream(); byte[] chunk = new byte[65536]; int read; boolean cut = false;
                while ((read = in.read(chunk)) >= 0) { if (buffer.size() < LIMIT) buffer.write(chunk, 0, Math.min(read, LIMIT - buffer.size())); else cut = true; }
                String text = buffer.toString(StandardCharsets.UTF_8);
                return cut || buffer.size() >= LIMIT ? text + "\n… вывод обрезан: больше " + LIMIT / 1000 + " КБ" : text;
            } catch (IOException e) { throw new CompletionException(e); }
        });
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("git не ответил за 30 секунд"); }
        String text = output.get(5, TimeUnit.SECONDS);
        int code = process.exitValue();
        if (code != 0 && !(differencesAreSuccess && code == 1)) { String error = errors.get(5, TimeUnit.SECONDS).strip(); throw new IOException(!error.isEmpty() ? error : text.isBlank() ? "git завершился с кодом " + code : text.strip()); }
        return text;
    }
}
