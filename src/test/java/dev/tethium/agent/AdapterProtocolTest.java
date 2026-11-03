package dev.tethium.agent;

import dev.tethium.json.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AdapterProtocolTest {
    @TempDir Path root;
    private List<String> command(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return List.of(java, "-cp", System.getProperty("tethium.test.classpath"), ProtocolFixture.class.getName(), mode);
    }
    private AgentAdapter.Request request(String conversation) {
        return new AgentAdapter.Request(root, "hello", conversation, null, null, null, Duration.ofSeconds(5));
    }
    @Test void quotaReadsAccountWithoutStartingAConversationOrTurn() throws Exception {
        var response = new CodexAccountClient(command("quota"), root).readRateLimits();
        assertEquals(82, response.path("rateLimits").path("primary").path("usedPercent").asInt());
        assertEquals(10080, response.path("rateLimits").path("primary").path("windowDurationMins").asInt());
    }
    @Test void catalogFollowsPaginationAndReadsDefaultsWithoutModelTurns() throws Exception {
        var response = new CodexAccountClient(command("models"), root).readModels();
        assertEquals(2, response.path("data").size());
        assertEquals("second-model", response.path("data").get(1).path("model").asText());
        assertEquals("high", response.path("configuredEffort").asText());
    }
    @Test void selectedModelAndEffortReachNewAndResumedTurnsAndDefaultRestoresCodexSetting() throws Exception {
        for (String conversation : new String[]{null, "codex-thread"}) {
            var request = new AgentAdapter.Request(root, "hello", conversation, "chosen-model", null, null, Duration.ofSeconds(5), "danger-full-access", null, "max");
            assertEquals(AgentAdapter.Status.COMPLETED, new CodexAdapter(command("codex-effort")).run(request, e -> {}, new Cancellation()).status());
            assertEquals(AgentAdapter.Status.COMPLETED, new CodexAdapter(command("codex-default-effort")).run(request(conversation), e -> {}, new Cancellation()).status());
        }
    }
    @Test void codexSeparatesRpcResponsesAndNotificationsAndFiltersOtherThreads() throws Exception {
        var events = new ArrayList<AgentAdapter.Event>();
        var result = new CodexAdapter(command("codex")).run(request(null), events::add, new Cancellation());
        assertEquals(AgentAdapter.Status.COMPLETED, result.status());
        assertEquals("codex-thread", result.conversationId());
        assertEquals("Hello world", result.text());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("test/notification")));
    }
    @Test void codexCancellationUsesInterruptProtocol() throws Exception {
        var cancel = new Cancellation();
        var result = new CodexAdapter(command("codex-cancel")).run(request("codex-thread"), e -> { if (e.type().equals("text")) cancel.cancel(); }, cancel);
        assertEquals(AgentAdapter.Status.CANCELLED, result.status());
        assertEquals("Hello world", result.text());
    }
    @Test void screenshotIsSentAsNativeLocalImage() throws Exception {
        var request=new AgentAdapter.Request(root,"look",null,null,null,null,Duration.ofSeconds(5),"danger-full-access",null,null,List.of(root.resolve("screen.png")));
        assertEquals(AgentAdapter.Status.COMPLETED,new CodexAdapter(command("codex-image")).run(request,e->{},new Cancellation()).status());
    }
    @Test void fullAccessDisablesSandboxAndApprovalsOnNewAndResumedThreadsWithInteractiveUi() throws Exception {
        for (String conversation : new String[]{null, "codex-thread"}) {
            var request = new AgentAdapter.Request(root, "hello", conversation, null, null, null, Duration.ofSeconds(5), "danger-full-access",
                    (method, params, cancellation) -> { throw new AssertionError("No approval expected"); });
            var result = new CodexAdapter(command("codex-full-access")).run(request, e -> {}, new Cancellation());
            assertEquals(AgentAdapter.Status.COMPLETED, result.status());
        }
    }
    @Test void claudeStreamsTextOnceAndNormalisesToolActivityToCodexItems() throws Exception {
        var events = new ArrayList<AgentAdapter.Event>();
        var result = new ClaudeAdapter(command("claude")).run(request("claude-thread"), events::add, new Cancellation());
        assertEquals(AgentAdapter.Status.COMPLETED, result.status());
        assertEquals("Hello world\n\nDone", result.text());
        assertEquals("claude-thread", result.conversationId());
        assertEquals("claude-thread", events.stream().filter(e -> e.type().equals("conversation")).findFirst().orElseThrow().text(), "A resumed conversation reports the same binding");
        var started = events.stream().filter(e -> e.type().equals("item/started")).map(e -> e.raw().path("params").path("item")).toList();
        assertEquals(1, started.size());
        assertEquals("commandExecution", started.getFirst().path("type").asText());
        assertEquals("echo ok", started.getFirst().path("command").asText());
        var completed = events.stream().filter(e -> e.type().equals("item/completed")).map(e -> e.raw().path("params").path("item")).toList();
        assertEquals("toolu_1", completed.getFirst().path("id").asText());
        assertEquals("completed", completed.getFirst().path("status").asText());
        var usage = events.stream().filter(e -> e.type().equals("thread/tokenUsage/updated")).map(e -> e.raw().path("params")).toList();
        assertEquals(1, usage.size());
        assertEquals(122, usage.getFirst().path("tokenUsage").path("total").path("totalTokens").asLong());
        assertEquals(100, usage.getFirst().path("tokenUsage").path("last").path("cachedInputTokens").asLong());
        assertTrue(usage.getFirst().path("threadId").asText().startsWith("claude-thread#"), "Per-turn usage keys are scoped to the Claude session");
    }
    @Test void claudeNewConversationAnnouncesItsOwnSessionIdAndPassesModelEffortAndImages() throws Exception {
        var events = new ArrayList<AgentAdapter.Event>();
        var fresh = new ClaudeAdapter(command("claude-new")).run(request(null), events::add, new Cancellation());
        assertEquals(AgentAdapter.Status.COMPLETED, fresh.status());
        assertDoesNotThrow(() -> UUID.fromString(fresh.conversationId()));
        assertEquals(fresh.conversationId(), events.stream().filter(e -> e.type().equals("conversation")).findFirst().orElseThrow().text());
        var request = new AgentAdapter.Request(root, "hello", "claude-thread", "chosen-model", null, null, Duration.ofSeconds(5), "danger-full-access", null, "max", List.of(root.resolve("screen.png")));
        java.nio.file.Files.write(root.resolve("screen.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertEquals(AgentAdapter.Status.COMPLETED, new ClaudeAdapter(command("claude-flags")).run(request, e -> {}, new Cancellation()).status());
    }
    @Test void claudeQuestionsReachTheUserAsCodexStyleRequestsAndAnswersFlowBack() throws Exception {
        var methods = new ArrayList<String>();
        var request = new AgentAdapter.Request(root, "hello", "claude-thread", null, null, null, Duration.ofSeconds(5), "danger-full-access", (method, params, cancellation) -> {
            methods.add(method);
            assertEquals("Which color?", params.path("questions").get(0).path("question").asText());
            assertEquals("Blue", params.path("questions").get(0).path("options").get(1).path("label").asText());
            return Json.object("answers", Json.object(params.path("questions").get(0).path("id").asText(), Json.object("answers", List.of("blue"))));
        });
        var result = new ClaudeAdapter(command("claude-question")).run(request, e -> {}, new Cancellation());
        assertEquals(AgentAdapter.Status.COMPLETED, result.status());
        assertEquals(List.of("item/tool/requestUserInput"), methods);
        assertTrue(result.text().endsWith("Done blue"), result.text());
    }
    @Test void claudeErrorFlagTakesPrecedenceOverSuccessSubtype() throws Exception {
        var result = new ClaudeAdapter(command("claude-error")).run(request(null), e -> {}, new Cancellation());
        assertEquals(AgentAdapter.Status.FAILED, result.status());
        assertTrue(result.text().contains("Weekly limit"));
    }
    @Test void claudeCancellationInterruptsThroughTheControlChannel() throws Exception {
        var cancel = new Cancellation();
        var result = new ClaudeAdapter(command("claude-cancel")).run(request("claude-thread"), e -> { if (e.type().equals("text")) cancel.cancel(); }, cancel);
        assertEquals(AgentAdapter.Status.CANCELLED, result.status());
        assertEquals("claude-thread", result.conversationId());
        assertEquals("Hello world", result.text());
    }
    @Test void malformedTransportFailsInsteadOfHangingAndCloseStopsTheChild() throws Exception {
        try (var process = new JsonProcess(command("malformed"), root, Map.of())) {
            assertThrows(Exception.class, () -> process.next(Duration.ofSeconds(2)));
        }
        var process = new JsonProcess(command("codex"), root, Map.of());
        process.close();
        assertFalse(process.isAlive());
    }
}
