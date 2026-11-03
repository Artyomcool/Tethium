package dev.tethium.worker;

import dev.tethium.agent.*;
import dev.tethium.json.Json;
import dev.tethium.tools.SessionToolsServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One immutable worker process owns one agent turn, its stdio and its MCP endpoint.
 * It never opens tethium.db and does not depend on the lifetime or address of a host. */
public final class WorkerMain {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath();
        try (var file = FileChannel.open(directory.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = file.tryLock()) {
            if (lock == null) return;
            try (var journal = new WorkerJournal(directory)) {
                // A claimed turn is never submitted again, even if its first process died during startup.
                if (journal.state("claimed") != null) return;
                var spec = Json.MAPPER.readTree(directory.resolve("request.json").toFile());
                if (spec.path("protocol").asInt() != WorkerJournal.PROTOCOL) throw new IllegalArgumentException("Unsupported worker protocol");
                var process = ProcessHandle.current();
                journal.state("pid", Long.toString(process.pid()));
                journal.state("processStart", process.info().startInstant().orElseThrow().toString());
                journal.state("claimed", "true");
                var cancellation = new Cancellation();
                try (var resources=dev.tethium.resources.SessionResources.enter(spec.path("resources")); var monitor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
                    monitor.scheduleWithFixedDelay(() -> {
                        try { if ("true".equals(journal.state("cancel"))) cancellation.cancel(); }
                        catch (Exception e) { System.err.println("Worker control: " + e); }
                    }, 0, 100, TimeUnit.MILLISECONDS);
                    var catalog = new ArrayList<Object>(); spec.path("tools").forEach(catalog::add);
                    try (var tools = new SessionToolsServer(catalog, params -> exchange(journal,"tool",params,cancellation))) {
                        var request = request(spec.path("request"), tools.uri(), tools.token(),
                                (method, params, cancel) -> exchange(journal,"question",Json.object("method",method,"params",params),cancel));
                        var executable = new ArrayList<String>(); spec.path("executable").forEach(v -> executable.add(v.asText()));
                        var provider = AgentAdapter.Provider.of(spec.path("provider").asText(null));
                        AgentAdapter adapter = provider == AgentAdapter.Provider.CLAUDE ? new ClaudeAdapter(executable) : new CodexAdapter(executable);
                        var result = adapter.run(request, event -> {
                            try { journal.append("event", event); }
                            catch (Exception e) { throw new IllegalStateException("Cannot journal agent output",e); }
                        }, cancellation);
                        journal.append("result", result);
                    } catch (Exception e) {
                        journal.append("failure", Json.object("message",e.getMessage() == null ? e.toString() : e.getMessage(),"cancelled",cancellation.isCancelled()));
                    }
                    journal.state("finished","true");
                    monitor.shutdownNow();
                }
            }
        }
    }
    private static JsonNode exchange(WorkerJournal journal, String kind, JsonNode params, Cancellation cancel) throws Exception {
        long sequence = journal.append(kind,params);
        while (!cancel.isCancelled()) {
            var reply = journal.reply(sequence); if (reply != null) return reply;
            Thread.sleep(100);
        }
        throw new CancellationException("Run stopped by the user");
    }
    public static JsonNode requestJson(AgentAdapter.Request r) {
        return Json.object("directory",r.directory().toString(),"prompt",r.prompt(),"conversationId",r.conversationId(),"model",r.model(),
                "timeoutMillis",r.timeout().toMillis(),"access",r.access(),"reasoningEffort",r.reasoningEffort(),"images",r.images().stream().map(Path::toString).toList());
    }
    private static AgentAdapter.Request request(JsonNode r, URI tools, String token, AgentAdapter.RequestHandler handler) {
        var images = new ArrayList<Path>(); r.path("images").forEach(p -> images.add(Path.of(p.asText())));
        return new AgentAdapter.Request(Path.of(r.path("directory").asText()), r.path("prompt").asText(), r.path("conversationId").asText(null),
                r.path("model").asText(null),tools,token,Duration.ofMillis(r.path("timeoutMillis").asLong()),r.path("access").asText(),handler,r.path("reasoningEffort").asText(null),images);
    }
}
