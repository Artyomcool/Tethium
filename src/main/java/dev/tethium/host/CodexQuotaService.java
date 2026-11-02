package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.util.*;
import java.util.concurrent.*;

/** One cached account snapshot per host, shared by all projects and sessions. */
public final class CodexQuotaService implements AutoCloseable {
    public static final long REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000;
    @FunctionalInterface public interface Reader { JsonNode read() throws Exception; }
    @FunctionalInterface public interface Resetter { JsonNode consume(String key) throws Exception; }
    private Resetter resetter;
    private JsonNode resetCredits=Json.MAPPER.nullNode();
    private final Reader reader;
    private final ScheduledExecutorService executor;
    private final long refreshCooldown;
    private JsonNode buckets = Json.MAPPER.createArrayNode();
    private long updatedAt, requestedAt;
    private boolean refreshing, closed;
    private String error = "";

    public CodexQuotaService(Reader reader) {
        this(reader, 5000);
    }
    public CodexQuotaService(Reader reader,Resetter resetter){this(reader);this.resetter=resetter;}
    CodexQuotaService(Reader reader, long refreshCooldown) {
        this.reader = reader;
        this.refreshCooldown = refreshCooldown;
        executor = reader == null ? null : Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        if (executor != null) executor.scheduleWithFixedDelay(this::refresh, 0, REFRESH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }
    public synchronized JsonNode snapshot() {
        return Json.object("buckets", buckets, "updatedAt", updatedAt, "refreshing", refreshing,
                "state", reader == null ? "unavailable" : !error.isEmpty() ? "error" : updatedAt == 0 ? "loading" : buckets.isEmpty() ? "unavailable" : "ready",
                "error", error,"resetCredits",resetCredits,"resetSupported",resetter!=null);
    }
    public synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (closed || executor == null || refreshing || now - requestedAt < refreshCooldown) return;
        requestedAt = now; refreshing = true;
        executor.execute(() -> {
            try {
                var raw=reader.read();var response = normalize(raw);
                synchronized (this) { if (!closed) { buckets = response; resetCredits=raw.path("rateLimitResetCredits"); updatedAt = System.currentTimeMillis(); error = ""; } }
            } catch (Exception e) {
                synchronized (this) { if (!closed) error = "Не удалось получить квоту. Проверьте подключение и вход в Codex."; }
            } finally { synchronized (this) { refreshing = false; } }
        });
    }
    public JsonNode consume(String key)throws Exception{
        if(resetter==null)throw new IllegalStateException("Сброс недоступен для этого подключения");
        var result=resetter.consume(key);synchronized(this){requestedAt=0;}refresh();return result;
    }
    public static boolean canReset(JsonNode state){
        if(!state.path("resetSupported").asBoolean()||!state.path("state").asText().equals("ready")||System.currentTimeMillis()-state.path("updatedAt").asLong()>6*60_000||state.path("resetCredits").path("availableCount").asInt()<=0)return false;
        for(var b:state.path("buckets"))for(var w:b.path("windows"))if(w.path("remainingPercent").asInt(100)<=5)return true;return false;
    }
    public static boolean available(JsonNode state,long after){
        if(!state.path("state").asText().equals("ready")||state.path("updatedAt").asLong()<=after||System.currentTimeMillis()-state.path("updatedAt").asLong()>6*60_000)return false;
        boolean has=false;for(var b:state.path("buckets")){if(!b.path("id").asText().equals("codex"))continue;for(var w:b.path("windows")){has=true;if(w.path("remainingPercent").asInt()<=0)return false;}}
        return has;
    }
    public static JsonNode normalize(JsonNode response) {
        var source = new LinkedHashMap<String, JsonNode>();
        var multiple = response.path("rateLimitsByLimitId");
        if (multiple.isObject()) multiple.fields().forEachRemaining(e -> { if (e.getValue().isObject()) source.put(e.getKey(), e.getValue()); });
        var single = response.path("rateLimits");
        if (single.isObject() && (source.isEmpty() || single.hasNonNull("limitId"))) source.putIfAbsent(single.path("limitId").asText("codex"), single);
        var result = Json.MAPPER.createArrayNode();
        source.forEach((id, bucket) -> {
            var windows = Json.MAPPER.createArrayNode();
            for (String key : List.of("primary", "secondary")) {
                var window = bucket.path(key);
                if (!window.path("usedPercent").isNumber()) continue;
                windows.add(Json.object("kind", key, "remainingPercent", Math.clamp(100 - window.path("usedPercent").asLong(), 0, 100),
                        "durationMinutes", window.path("windowDurationMins"), "resetsAt", window.path("resetsAt")));
            }
            if (windows.isEmpty() && !bucket.path("credits").isObject()) return;
            String label = bucket.path("limitName").asText("");
            if (label.isBlank()) label = id.equals("codex") ? "Codex" : id;
            result.add(Json.object("id", id, "label", label, "windows", windows, "credits", bucket.path("credits"), "limitReached", bucket.path("rateLimitReachedType")));
        });
        return result;
    }
    @Override public synchronized void close() { closed = true; if (executor != null) executor.shutdownNow(); }
}
