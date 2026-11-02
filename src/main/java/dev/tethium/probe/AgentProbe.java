package dev.tethium.probe;

import dev.tethium.agent.*;
import dev.tethium.core.*;
import dev.tethium.json.Json;
import dev.tethium.tools.SessionToolsServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit opt-in smoke test: real CLI/model requests. Never invoked by Gradle test. */
public final class AgentProbe {
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "help" : args[0];
        if (!Set.of("codex", "claude", "both").contains(mode)) {
            System.out.println("Usage: gradlew run --args=codex|claude|both (uses signed-in CLI accounts)"); return;
        }
        Path root = Path.of(".local", "probes", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(root);
        var report = Json.object("java", Runtime.version().toString(), "directory", root.toString());
        var checks = Json.MAPPER.createArrayNode(); report.set("checks", checks);
        try (var store = new SessionStore(root.resolve("tethium.db"))) {
            var project = store.project(root);
            var session = store.create(project, null, "Integration probe", root);
            store.create(project, session.id(), "Visible sibling", root);
            var runner = new SessionRunner(store);
            try (var tools = new SessionToolsServer(store, session.id())) {
                var adapters = new ArrayList<AgentAdapter>();
                if (!mode.equals("claude")) adapters.add(new CodexAdapter());
                if (!mode.equals("codex")) adapters.add(new ClaudeAdapter());
                String marker = "tethium-" + UUID.randomUUID();
                for (var adapter : adapters) {
                    int callsBefore = tools.calls();
                    var chunks = new AtomicInteger();
                    System.out.println(adapter.provider() + ": start + streaming + MCP");
                    var first = runner.run(session.id(), adapter,
                            "This is an integration test. Remember the marker " + marker + ". Call the Tethium probe_echo tool with that exact marker as value, then call session_list. Reply with the marker and the number of sessions. Do not use any other tools.",
                            tools.uri(), tools.token(), event -> { if (event.type().equals("text")) chunks.incrementAndGet(); }, new Cancellation());
                    require(first.status() == AgentAdapter.Status.COMPLETED, adapter.provider() + " first turn failed: " + first.text());
                    require(first.text().contains(marker), "Marker missing");
                    require(chunks.get() > 0, "No streamed text");
                    require(tools.calls() >= callsBefore + 2, "MCP tools were not called");
                    checks.add(Json.object("provider", adapter.provider().name(), "check", "stream-and-tools", "passed", true));
                    System.out.println(adapter.provider() + ": resume after child process restart");
                    var resumed = runner.run(session.id(), adapter, "Reply with only the marker you remembered. Do not call tools.", tools.uri(), tools.token(), event -> {}, new Cancellation());
                    require(resumed.status() == AgentAdapter.Status.COMPLETED && resumed.text().contains(marker), "Resume lost history");
                    require(Objects.equals(first.conversationId(), resumed.conversationId()), "Resume changed conversation id");
                    require(store.get(session.id()).active(), "Completing a turn deactivated the session");
                    checks.add(Json.object("provider", adapter.provider().name(), "check", "resume", "passed", true));
                    System.out.println(adapter.provider() + ": cancellation");
                    var cancellation = new Cancellation();
                    var cancelled = runner.run(session.id(), adapter, "Write the numbers from 1 to 10000, one per line. Do not use tools.", tools.uri(), tools.token(), event -> {
                        if (event.type().equals("text")) cancellation.cancel();
                    }, cancellation);
                    require(cancelled.status() == AgentAdapter.Status.CANCELLED, "Cancellation did not stop the turn");
                    checks.add(Json.object("provider", adapter.provider().name(), "check", "cancel", "passed", true));
                }
                if (adapters.size() == 2) {
                    System.out.println("CODEX -> CLAUDE -> CODEX: context transfer");
                    String other = "handoff-" + UUID.randomUUID();
                    var claude = runner.run(session.id(), adapters.get(1), "Remember this additional marker: " + other + ". Reply OK; no tools.", tools.uri(), tools.token(), event -> {}, new Cancellation());
                    require(claude.status() == AgentAdapter.Status.COMPLETED, "Claude handoff setup failed");
                    var codex = runner.run(session.id(), adapters.get(0), "What additional marker did Claude just receive? Reply with only that marker. Do not call tools.", tools.uri(), tools.token(), event -> {}, new Cancellation());
                    require(codex.status() == AgentAdapter.Status.COMPLETED && codex.text().contains(other), "Provider switch lost context");
                    checks.add(Json.object("check", "provider-switch", "passed", true));
                }
            }
        } catch (Exception e) {
            report.put("passed", false); report.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(), report);
            throw e;
        }
        report.put("passed", true);
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(), report);
        System.out.println("PASS: " + root.resolve("report.json"));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
