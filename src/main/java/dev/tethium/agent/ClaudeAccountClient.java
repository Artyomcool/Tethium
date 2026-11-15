package dev.tethium.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.tethium.json.Json;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Account limits of the signed-in Claude Code user. Reads the CLI's own OAuth token and asks the same usage
 * endpoint its /usage command uses; never starts a conversation and never refreshes or rewrites the token itself.
 * The access token lives eight hours, the login a month: an expired access token is not a lost login. The CLI is
 * asked for its own status (which lets it renew its token the way it always does), and only the CLI saying
 * loggedIn=false, or a missing login, means the user has to sign in again. */
public final class ClaudeAccountClient {
    /** The saved CLI identity is missing or its OAuth session can no longer be used. */
    public static final class AuthenticationRequiredException extends IllegalStateException {
        public AuthenticationRequiredException(String message) { super(message); }
    }
    /** The login is valid but its short-lived access token is stale until the CLI next renews it. */
    public static final class StaleTokenException extends IllegalStateException {
        public StaleTokenException(String message) { super(message); }
    }
    private static final long NUDGE_INTERVAL = 10 * 60_000;
    private final List<String> executable = List.of(System.getenv().getOrDefault("TETHIUM_CLAUDE", "claude"));
    private long nudgedAt;
    private Boolean loggedIn;
    private static final URI USAGE = URI.create("https://api.anthropic.com/api/oauth/usage");
    private final Path credentials;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public ClaudeAccountClient() { this(defaultCredentials()); }
    public ClaudeAccountClient(Path credentials) { this.credentials = credentials; }
    static Path defaultCredentials() {
        String configured = System.getenv("CLAUDE_CONFIG_DIR");
        Path home = configured == null || configured.isBlank() ? Path.of(System.getProperty("user.home"), ".claude") : Path.of(configured);
        return home.resolve(".credentials.json");
    }
    public synchronized JsonNode readUsage() throws Exception {
        var oauth = oauth();
        if (expired(oauth)) {
            nudge(); oauth = oauth();
            if (expired(oauth)) throw stale();
        }
        var response = usage(oauth.path("accessToken").asText(""));
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            // The token can be revoked before its time (a newer login elsewhere); let the CLI renew it, then try once more.
            String before = oauth.path("accessToken").asText("");
            nudge(); oauth = oauth();
            if (!oauth.path("accessToken").asText("").equals(before) && !expired(oauth)) response = usage(oauth.path("accessToken").asText(""));
            if (response.statusCode() == 401 || response.statusCode() == 403) throw stale();
        }
        if (response.statusCode() != 200) throw new IllegalStateException("Сервис квоты Claude ответил " + response.statusCode());
        return Json.parse(response.body());
    }
    private JsonNode oauth() throws Exception {
        if (!Files.isRegularFile(credentials)) throw new AuthenticationRequiredException("Claude Code не авторизован: нет " + credentials);
        var oauth = Json.MAPPER.readTree(credentials.toFile()).path("claudeAiOauth");
        if (oauth.path("accessToken").asText("").isBlank()) throw new AuthenticationRequiredException("Claude Code не авторизован");
        if (oauth.path("refreshTokenExpiresAt").isNumber() && oauth.path("refreshTokenExpiresAt").asLong() <= System.currentTimeMillis()) throw new AuthenticationRequiredException("Вход в Claude истёк");
        return oauth;
    }
    private static boolean expired(JsonNode oauth) { return oauth.path("expiresAt").isNumber() && oauth.path("expiresAt").asLong() <= System.currentTimeMillis() + 60_000; }
    /** Only the CLI's own verdict turns a stale token into "sign in again". */
    private RuntimeException stale() {
        if (Boolean.FALSE.equals(loggedIn)) return new AuthenticationRequiredException("Claude CLI сообщает, что вход потерян");
        return new StaleTokenException("Квота Claude обновится, когда Claude CLI продлит свой токен");
    }
    private HttpResponse<String> usage(String token) throws Exception {
        var request = HttpRequest.newBuilder(USAGE).timeout(Duration.ofSeconds(20)).GET()
                .header("Authorization", "Bearer " + token).header("anthropic-beta", "oauth-2025-04-20").header("Accept", "application/json").header("User-Agent", "Tethium").build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    /** Runs `claude auth status --json` at most every ten minutes; the CLI renews its own token if it needs to. */
    private void nudge() {
        long now = System.currentTimeMillis(); if (now - nudgedAt < NUDGE_INTERVAL) return; nudgedAt = now;
        try {
            var command = new ArrayList<>(executable); command.addAll(List.of("auth", "status", "--json"));
            var builder = new ProcessBuilder(command).redirectErrorStream(true); builder.environment().remove("CLAUDECODE");
            var process = builder.start(); process.getOutputStream().close();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> { try (var in = process.getInputStream()) { return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } catch (java.io.IOException e) { return ""; } });
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return; }
            var status = Json.parse(output.get(5, java.util.concurrent.TimeUnit.SECONDS).strip());
            if (status.has("loggedIn")) loggedIn = status.path("loggedIn").asBoolean();
        } catch (Exception unavailable) { /* No verdict: a stale token stays stale, not "sign in again". */ }
    }
    /** Same bucket shape as the Codex quota: percent remaining, window length and reset time per window. */
    public static ArrayNode normalize(JsonNode usage) {
        var buckets = Json.MAPPER.createArrayNode();
        var windows = Json.MAPPER.createArrayNode();
        window(windows, usage.path("five_hour"), "primary", 300);
        window(windows, usage.path("seven_day"), "secondary", 10080);
        if (!windows.isEmpty()) buckets.add(Json.object("id", "claude", "label", "Claude", "windows", windows));
        for (var limit : usage.path("limits")) {
            String name = limit.path("scope").path("model").path("display_name").asText("");
            if (!limit.path("kind").asText().equals("weekly_scoped") || name.isBlank() || !limit.path("percent").isNumber()) continue;
            var scoped = Json.MAPPER.createArrayNode();
            scoped.add(Json.object("kind", "secondary", "remainingPercent", remaining(limit.path("percent").asDouble()), "durationMinutes", 10080, "resetsAt", epochSeconds(limit.path("resets_at").asText(null))));
            buckets.add(Json.object("id", "claude-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"), "label", "Claude · " + name, "windows", scoped));
        }
        return buckets;
    }
    private static void window(ArrayNode windows, JsonNode value, String kind, int minutes) {
        if (!value.isObject() || !value.path("utilization").isNumber()) return;
        windows.add(Json.object("kind", kind, "remainingPercent", remaining(value.path("utilization").asDouble()), "durationMinutes", minutes, "resetsAt", epochSeconds(value.path("resets_at").asText(null))));
    }
    /** Rounded down: the panel must never promise more remaining quota than the account reports. */
    private static long remaining(double utilization) { return Math.clamp((long) Math.floor(100 - utilization), 0, 100); }
    private static Long epochSeconds(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return OffsetDateTime.parse(iso).toEpochSecond(); } catch (DateTimeException invalid) { return null; }
    }
}
