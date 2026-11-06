package dev.tethium.agent;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NarrationHookTest {
    @TempDir Path root;
    private String runHook(Path dir, String session) throws Exception {
        var builder = new ProcessBuilder("node", dir.resolve("narrate-hook.js").toString()).redirectErrorStream(true);
        // The test JVM may itself run under a Tethium-started Claude whose environment points at the real hook directory.
        builder.environment().put("TETHIUM_HOOK_DIR", dir.toString()); builder.environment().put("TETHIUM_NARRATE_EVERY", "6"); builder.environment().remove("TETHIUM_NARRATE_TEXT");
        var process = builder.start();
        try (var out = process.getOutputStream()) { out.write(Json.object("session_id", session, "tool_name", "Bash", "hook_event_name", "PostToolUse").toString().getBytes(StandardCharsets.UTF_8)); }
        byte[] output = process.getInputStream().readAllBytes(); assertTrue(process.waitFor(20, TimeUnit.SECONDS)); return new String(output, StandardCharsets.UTF_8);
    }
    @Test void remindsAfterEveryNthToolCallAndRestartsAfterText() throws Exception {
        Path hooks = root.resolve("hooks"); Path settings = NarrationHook.settings(hooks);
        assumeTrue(settings != null, "node is not on PATH");
        var config = Json.MAPPER.readTree(settings.toFile());
        assertTrue(config.path("hooks").path("PostToolUse").get(0).path("hooks").get(0).path("command").asText().contains("narrate-hook.js"));
        // The script reads TETHIUM_NARRATE_EVERY; the test runs node directly with the default of 6 and the counter next to the script.
        String session = "abc-123";
        for (int i = 1; i <= 5; i++) assertEquals("", runHook(hooks, session), "no reminder before the sixth call: " + i);
        String sixth = runHook(hooks, session);
        assertTrue(sixth.contains("\"hookEventName\":\"PostToolUse\"") && sixth.contains("additionalContext"), sixth);
        assertEquals("0", Files.readString(NarrationHook.counter(hooks, session)));
        assertEquals("", runHook(hooks, session));
        NarrationHook.reset(hooks, session);
        assertFalse(Files.exists(NarrationHook.counter(hooks, session)));
        for (int i = 1; i <= 5; i++) assertEquals("", runHook(hooks, session));
        assertTrue(runHook(hooks, session).contains("additionalContext"), "counts again from zero after text");
        assertEquals(List.of("claude-hooks.json", "narrate-abc-123.count", "narrate-hook.js"), Files.list(hooks).map(p -> p.getFileName().toString()).sorted().toList());
    }
}
