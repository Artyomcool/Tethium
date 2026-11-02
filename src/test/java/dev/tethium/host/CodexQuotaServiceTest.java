package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CodexQuotaServiceTest {
    @Test void remainingQuotaUsesReturnedWindowsAndDoesNotInventMissingData() {
        var weekly = Json.object("limitId", "codex", "primary", Json.object("usedPercent", 82, "windowDurationMins", 10080, "resetsAt", 1794207924));
        var other = Json.object("limitName", "Other", "primary", Json.object("windowDurationMins", 300), "secondary", Json.object("usedPercent", 120, "windowDurationMins", null, "resetsAt", null));
        var result = CodexQuotaService.normalize(Json.object("rateLimits", weekly, "rateLimitsByLimitId", Json.object("codex", weekly, "other", other)));
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).path("windows").size());
        var window = result.get(0).path("windows").get(0);
        assertEquals(18, window.path("remainingPercent").asInt());
        assertEquals(10080, window.path("durationMinutes").asInt());
        assertEquals(1794207924L, window.path("resetsAt").asLong());
        assertEquals(1, result.get(1).path("windows").size());
        assertEquals(0, result.get(1).path("windows").get(0).path("remainingPercent").asInt());
        assertTrue(result.get(1).path("windows").get(0).path("resetsAt").isNull());
        assertTrue(CodexQuotaService.normalize(Json.object("rateLimits", Json.object())).isEmpty());
    }
    @Test void failedRefreshRetainsPreviousValueMarksItStaleAndCanRecover() throws Exception {
        var reads = new AtomicInteger(); var gate = new Semaphore(0);
        try (var quota = new CodexQuotaService(() -> {
            int read = reads.incrementAndGet(); gate.acquire();
            if (read == 2) throw new java.io.IOException("offline");
            return Json.object("rateLimits", Json.object("primary", Json.object("usedPercent", read == 1 ? 82 : 90)));
        }, 0)) {
            await(quota, s -> s.path("refreshing").asBoolean());
            for (int i = 0; i < 20; i++) quota.refresh();
            gate.release(); await(quota, s -> s.path("state").asText().equals("ready") && !s.path("refreshing").asBoolean());
            assertEquals(1, reads.get());
            long firstUpdate = quota.snapshot().path("updatedAt").asLong();
            quota.refresh(); gate.release(); await(quota, s -> s.path("state").asText().equals("error") && !s.path("refreshing").asBoolean());
            assertEquals(firstUpdate, quota.snapshot().path("updatedAt").asLong());
            assertEquals(18, remaining(quota));
            quota.refresh(); gate.release(); await(quota, s -> s.path("state").asText().equals("ready") && !s.path("refreshing").asBoolean());
            assertEquals(10, remaining(quota));
        }
    }
    private static int remaining(CodexQuotaService quota) { return quota.snapshot().path("buckets").get(0).path("windows").get(0).path("remainingPercent").asInt(); }
    private static void await(CodexQuotaService quota, java.util.function.Predicate<JsonNode> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < end) { if (condition.test(quota.snapshot())) return; Thread.sleep(10); }
        fail("Quota state did not converge: " + quota.snapshot());
    }
}
