package dev.tethium.host;

import dev.tethium.agent.ClaudeAccountClient;
import dev.tethium.json.Json;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ClaudeQuotaTest {
    private static final String USAGE = """
        {"five_hour":{"utilization":37.5,"resets_at":"2026-11-03T23:00:00+00:00"},
         "seven_day":{"utilization":2.0,"resets_at":"2026-11-10T20:00:00.430820+00:00","limit_dollars":null},
         "seven_day_opus":null,
         "limits":[{"kind":"weekly_all","group":"weekly","percent":2,"severity":"normal","resets_at":"2026-11-10T20:00:00.430820+00:00","scope":null,"is_active":false},
                   {"kind":"weekly_scoped","group":"weekly","percent":3,"severity":"normal","resets_at":"2026-11-10T20:00:00.430988+00:00","scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":true}]}
        """;
    @Test void usageEndpointIsNormalisedToTheCodexBucketShape() throws Exception {
        var buckets = ClaudeAccountClient.normalize(Json.parse(USAGE));
        assertEquals(2, buckets.size());
        var claude = buckets.get(0);
        assertEquals("claude", claude.path("id").asText()); assertEquals("Claude", claude.path("label").asText());
        assertEquals(2, claude.path("windows").size());
        var hours = claude.path("windows").get(0); var week = claude.path("windows").get(1);
        assertEquals(300, hours.path("durationMinutes").asInt()); assertEquals(62, hours.path("remainingPercent").asInt()); assertEquals(1793746800L, hours.path("resetsAt").asLong());
        assertEquals(10080, week.path("durationMinutes").asInt()); assertEquals(98, week.path("remainingPercent").asInt()); assertEquals(1794340800L, week.path("resetsAt").asLong());
        var scoped = buckets.get(1);
        assertEquals("claude-fable", scoped.path("id").asText()); assertEquals("Claude · Fable", scoped.path("label").asText());
        assertEquals(97, scoped.path("windows").get(0).path("remainingPercent").asInt());
    }
    @Test void nullWindowsAreSkippedAndEmptyUsageYieldsNoBucket() throws Exception {
        var buckets = ClaudeAccountClient.normalize(Json.parse("{\"five_hour\":null,\"seven_day\":{\"utilization\":0,\"resets_at\":\"2026-11-10T20:00:00+00:00\"},\"limits\":[]}"));
        assertEquals(1, buckets.size()); assertEquals(1, buckets.get(0).path("windows").size()); assertEquals(100, buckets.get(0).path("windows").get(0).path("remainingPercent").asInt());
        assertTrue(ClaudeAccountClient.normalize(Json.parse("{}")).isEmpty());
    }
    @Test void modelQuotaFailureCreatesAVisibleZeroEvenWithoutTheUsageEndpoint() throws Exception {
        try(var quota=new CodexQuotaService(null,null,null,0)){
            assertTrue(quota.claudeExhausted(Json.object("api_error","usage_limit_reached","result","You've hit your weekly limit · resets Dec 31, 11pm (Europe/Berlin)")));
            var bucket=quota.snapshot().path("buckets").get(0);var window=bucket.path("windows").get(0);
            assertEquals("claude",bucket.path("id").asText());assertEquals(0,window.path("remainingPercent").asInt(-1));assertEquals(10080,window.path("durationMinutes").asInt());assertTrue(window.path("resetsAt").asLong()>Instant.now().getEpochSecond());
        }
    }
    @Test void claudeBucketsJoinTheSharedSnapshotAndACodexFailureDoesNotHideThem() throws Exception {
        var codexFails = new boolean[]{false};
        try (var quota = new CodexQuotaService(() -> {
            if (codexFails[0]) throw new IllegalStateException("codex offline");
            return Json.object("rateLimits", Json.object("limitId", "codex", "primary", Json.object("usedPercent", 40, "windowDurationMins", 300, "resetsAt", 1794207924)));
        }, null, () -> Json.parse(USAGE), 0)) {
            var ready = await(quota, s -> s.path("state").asText().equals("ready") && !s.path("refreshing").asBoolean());
            assertEquals(List.of("codex", "claude", "claude-fable"), ready.path("buckets").findValuesAsText("id").subList(0, 3));
            assertEquals("ready", ready.path("sources").path("claude").asText());
            assertTrue(CodexQuotaService.available(ready, 0), "Codex availability is judged by the Codex bucket only");
            assertFalse(CodexQuotaService.canReset(ready));
            codexFails[0] = true; quota.refresh();
            var degraded = await(quota, s -> !s.path("sources").path("codex").asText().equals("ready") && !s.path("refreshing").asBoolean());
            assertEquals("ready", degraded.path("state").asText(), "Claude data keeps the snapshot usable");
            assertEquals("ready", degraded.path("sources").path("claude").asText());
            assertTrue(degraded.path("error").asText().contains("Codex"));
            assertEquals(3, degraded.path("buckets").size(), "The last good Codex bucket is retained");
        }
        try (var claudeOnly = new CodexQuotaService(null, null, () -> { throw new IllegalStateException("Сеанс Claude Code истёк"); }, 0)) {
            var state = await(claudeOnly, s -> s.path("state").asText().equals("error") && !s.path("refreshing").asBoolean());
            assertEquals("Claude: нужна авторизация",state.path("error").asText());
            assertTrue(state.path("authRequired").path("claude").asBoolean());
            assertEquals("auth-required",state.path("sources").path("claude").asText());
            assertFalse(CodexQuotaService.available(state, 0));
            assertTrue(claudeOnly.claudeExhausted(Json.object("api_error","usage_limit_reached","result","You've hit your weekly limit · resets Dec 31, 11pm (Europe/Berlin)")));
            assertTrue(claudeOnly.snapshot().path("authRequired").path("claude").asBoolean(),"restoring a durable quota rejection must not hide the expired login");
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode await(CodexQuotaService quota, java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) { var s = quota.snapshot(); if (condition.test(s)) return s; Thread.sleep(20); }
        return fail("Quota state did not converge: " + quota.snapshot());
    }
}
