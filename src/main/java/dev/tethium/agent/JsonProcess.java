package dev.tethium.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Owns only the child process it creates. Never uses shell command strings. */
public final class JsonProcess implements AutoCloseable {
    private final Process process;
    private final BufferedWriter stdin;
    private final ConcurrentMap<String, CompletableFuture<JsonNode>> requests = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
    private final AtomicLong nextId = new AtomicLong();
    private final StringBuilder stderr = new StringBuilder();
    private volatile Throwable readerFailure;
    private volatile boolean eof;

    public JsonProcess(List<String> command, Path cwd, Map<String, String> environment) throws IOException {
        var builder = new ProcessBuilder(command).directory(cwd.toFile());
        // A null value removes an inherited variable, e.g. a nesting guard of the parent CLI.
        environment.forEach((key, value) -> { if (value == null) builder.environment().remove(key); else builder.environment().put(key, value); });
        String local=System.getenv("LOCALAPPDATA");
        if(local!=null){Path bin=Path.of(local,"Tethium","tools","gh","bin");if(java.nio.file.Files.isRegularFile(bin.resolve("gh.exe"))){String key=builder.environment().keySet().stream().filter(k->k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");builder.environment().put(key,bin+java.io.File.pathSeparator+builder.environment().getOrDefault(key,""));}}
        process = builder.start();
        stdin = process.outputWriter(StandardCharsets.UTF_8);
        Thread.ofVirtual().name("agent-stderr").start(() -> {
            try (var reader = process.errorReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) synchronized (stderr) {
                    stderr.append(line).append('\n');
                    if (stderr.length() > 8192) stderr.delete(0, stderr.length() - 8192);
                }
            } catch (IOException ignored) { }
        });
        Thread.ofVirtual().name("agent-stdout").start(() -> {
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    var message = Json.parse(line);
                    if (message.has("id") && !message.has("method")) {
                        var pending = requests.remove(message.get("id").asText());
                        if (pending != null) {
                            if (message.has("error")) pending.completeExceptionally(new IOException(message.get("error").toString()));
                            else pending.complete(message.path("result"));
                        }
                    } else events.put(message);
                }
            } catch (Throwable failure) { readerFailure = failure; }
            finally {
                eof = true;
                var failure = new IOException("Agent transport closed", readerFailure);
                requests.values().forEach(future -> future.completeExceptionally(failure));
                requests.clear();
            }
        });
    }

    public synchronized void send(JsonNode message) throws IOException {
        stdin.write(message.toString()); stdin.newLine(); stdin.flush();
    }
    public JsonNode call(String method, JsonNode params, Duration timeout) throws Exception {
        long id = nextId.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        requests.put(Long.toString(id), future);
        try {
            send(Json.object("id", id, "method", method, "params", params));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Agent RPC failed (" + method + "): " + diagnostic(), e.getCause());
        } finally { requests.remove(Long.toString(id)); }
    }
    public JsonNode next(Duration timeout) throws Exception {
        var message = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (message == null && eof) throw new IOException("Agent exited without a terminal event: " + diagnostic(), readerFailure);
        return message;
    }
    public synchronized void closeInput() throws IOException { stdin.close(); }
    public String diagnostic() { synchronized (stderr) { return stderr.toString(); } }
    public boolean isAlive() { return process.isAlive(); }
    public void stop() {
        var children = process.descendants().toList();
        children.reversed().forEach(ProcessHandle::destroy);
        process.destroy();
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        children.reversed().forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
    }
    @Override public void close() { stop(); }
}
