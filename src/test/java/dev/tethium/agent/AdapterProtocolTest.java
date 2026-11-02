package dev.tethium.agent;

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
    @Test void claudeDoesNotDuplicateStreamedTextInFinalAssistantMessage() throws Exception {
        var result = new ClaudeAdapter(command("claude")).run(request("claude-thread"), e -> {}, new Cancellation());
        assertEquals(AgentAdapter.Status.COMPLETED, result.status());
        assertEquals("Hello world", result.text());
        assertEquals("claude-thread", result.conversationId());
    }
    @Test void claudeErrorFlagTakesPrecedenceOverSuccessSubtype() throws Exception {
        var result = new ClaudeAdapter(command("claude-error")).run(request(null), e -> {}, new Cancellation());
        assertEquals(AgentAdapter.Status.FAILED, result.status());
        assertTrue(result.text().contains("Weekly limit"));
    }
    @Test void claudeCancellationStopsChildProcess() throws Exception {
        var cancel = new Cancellation();
        var result = new ClaudeAdapter(command("claude-cancel")).run(request(null), e -> { if (e.type().equals("text")) cancel.cancel(); }, cancel);
        assertEquals(AgentAdapter.Status.CANCELLED, result.status());
        assertEquals("claude-thread", result.conversationId());
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

