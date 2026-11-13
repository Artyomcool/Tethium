package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.ClaudeAccountClient;
import dev.tethium.json.Json;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** One cached account snapshot per host, shared by all projects and sessions. Codex and Claude limits are read
 * independently and shown as separate buckets; reset credits and automatic quota waits remain Codex features. */
public final class CodexQuotaService implements AutoCloseable {
    public static final long REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000;
    @FunctionalInterface public interface Reader { JsonNode read() throws Exception; }
    @FunctionalInterface public interface Resetter { JsonNode consume(String key) throws Exception; }
    private Resetter resetter;
    private JsonNode resetCredits=Json.MAPPER.nullNode();
    private final Reader reader, claude;
    private final ScheduledExecutorService executor;
    private final long refreshCooldown;
    private JsonNode buckets = Json.MAPPER.createArrayNode(), claudeBuckets = Json.MAPPER.createArrayNode();
    private long updatedAt, requestedAt;
    private boolean refreshing, closed, claudeAuthRequired;
    private String error = "", claudeError = "";

    public CodexQuotaService(Reader reader) {
        this(reader, null, null, 5000);
    }
    public CodexQuotaService(Reader reader,Resetter resetter){this(reader, resetter, null, 5000);}
    public CodexQuotaService(Reader reader,Resetter resetter,Reader claude){this(reader, resetter, claude, 5000);}
    CodexQuotaService(Reader reader, long refreshCooldown) { this(reader, null, null, refreshCooldown); }
    CodexQuotaService(Reader reader, Resetter resetter, Reader claude, long refreshCooldown) {
        this.reader = reader; this.resetter = resetter; this.claude = claude;
        this.refreshCooldown = refreshCooldown;
        executor = reader == null && claude == null ? null : Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        if (executor != null) executor.scheduleWithFixedDelay(this::refresh, 0, REFRESH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }
    public synchronized JsonNode snapshot() {
        var all = Json.MAPPER.createArrayNode(); all.addAll((com.fasterxml.jackson.databind.node.ArrayNode) buckets); all.addAll((com.fasterxml.jackson.databind.node.ArrayNode) claudeBuckets);
        String problems = String.join(" ", List.of(error, claudeError).stream().filter(s -> !s.isEmpty()).toList());
        boolean codexOk = reader != null && error.isEmpty(), claudeOk = claude != null && claudeError.isEmpty();
        return Json.object("buckets", all, "updatedAt", updatedAt, "refreshing", refreshing,
                "state", executor == null ? "unavailable" : !codexOk && !claudeOk ? "error" : updatedAt == 0 ? "loading" : all.isEmpty() ? "unavailable" : "ready",
                "error", problems,"resetCredits",resetCredits,"resetSupported",resetter!=null,
                "authRequired",Json.object("claude",claudeAuthRequired),
                "sources", Json.object("codex", reader == null ? "unavailable" : !error.isEmpty() ? "error" : "ready", "claude", claude == null ? "unavailable" : claudeAuthRequired ? "auth-required" : !claudeError.isEmpty() ? "error" : "ready"));
    }
    public synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (closed || executor == null || refreshing || now - requestedAt < refreshCooldown) return;
        requestedAt = now; refreshing = true;
        executor.execute(() -> {
            boolean success = false;
            try {
                if (reader != null) {
                    try {
                        var raw=reader.read();var response = normalize(raw);
                        synchronized (this) { if (!closed) { buckets = response; resetCredits=raw.path("rateLimitResetCredits"); error = ""; success = true; } }
                    } catch (Exception e) {
                        synchronized (this) { if (!closed) error = "Не удалось получить квоту Codex. Проверьте подключение и вход в Codex."; }
                    }
                }
                if (claude != null) {
                    try {
                        var response = ClaudeAccountClient.normalize(claude.read());
                        synchronized (this) { if (!closed) { claudeBuckets = response; claudeError = ""; claudeAuthRequired=false; success = true; } }
                    } catch (Exception e) {
                        boolean auth=authenticationRequired(e);
                        synchronized (this) { if (!closed) {claudeAuthRequired=auth;claudeError=auth?"Claude: нужна авторизация":"Не удалось получить квоту Claude: " + Objects.toString(e.getMessage(), e.toString());} }
                    }
                }
                // A failed source keeps its last values and its stale timestamp; only a fresh answer moves the clock.
                synchronized (this) { if (!closed && success) updatedAt = System.currentTimeMillis(); }
            } finally { synchronized (this) { refreshing = false; } }
        });
    }
    /** A model turn is authoritative when Claude itself rejects it for quota, even if the usage endpoint is unavailable. */
    public synchronized boolean claudeExhausted(JsonNode event) {
        String text=event.toString(),lower=text.toLowerCase(Locale.ROOT);
        long duration=lower.contains("weekly limit")?10080:300;
        Long reset=claudeReset(text);
        if(reset!=null&&reset<=Instant.now().getEpochSecond())return false;
        var next=Json.MAPPER.createArrayNode();boolean found=false;
        for(var bucket:claudeBuckets){
            var copy=(com.fasterxml.jackson.databind.node.ObjectNode)bucket.deepCopy();
            if(copy.path("id").asText().equals("claude")){
                found=true;var windows=(com.fasterxml.jackson.databind.node.ArrayNode)copy.withArray("windows");boolean windowFound=false;
                for(var window:windows)if(window.path("durationMinutes").asLong()==duration){((com.fasterxml.jackson.databind.node.ObjectNode)window).put("remainingPercent",0);if(reset!=null)((com.fasterxml.jackson.databind.node.ObjectNode)window).put("resetsAt",reset);windowFound=true;}
                if(!windowFound)windows.add(Json.object("kind",duration==10080?"secondary":"primary","remainingPercent",0,"durationMinutes",duration,"resetsAt",reset));
            }
            next.add(copy);
        }
        if(!found)next.add(Json.object("id","claude","label","Claude","windows",List.of(Json.object("kind",duration==10080?"secondary":"primary","remainingPercent",0,"durationMinutes",duration,"resetsAt",reset))));
        claudeBuckets=next;claudeError="";updatedAt=System.currentTimeMillis();return true;
    }
    private static boolean authenticationRequired(Throwable failure){
        for(Throwable current=failure;current!=null;current=current.getCause()){
            if(current instanceof ClaudeAccountClient.AuthenticationRequiredException)return true;
            String message=Objects.toString(current.getMessage(),"").toLowerCase(Locale.ROOT);
            if(message.contains("claude")&&(message.contains("не авторизован")||message.contains("сеанс")&&message.contains("истёк")))return true;
        }
        return false;
    }
    /** Authentication completion must not be held back by the ordinary refresh-button debounce. */
    public synchronized void refreshAfterAuthentication(){requestedAt=0;refresh();}
    private static final Pattern CLAUDE_RESET=Pattern.compile("(?i)resets\\s+([a-z]{3})\\s+(\\d{1,2}),\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s*\\(([^)]+)\\)");
    private static Long claudeReset(String text){
        try{
            var match=CLAUDE_RESET.matcher(text);if(!match.find())return null;
            Month month=Month.from(DateTimeFormatter.ofPattern("MMM",Locale.ENGLISH).parse(match.group(1)));
            int hour=Integer.parseInt(match.group(3))%12;if(match.group(5).equalsIgnoreCase("pm"))hour+=12;
            int minute=match.group(4)==null?0:Integer.parseInt(match.group(4));ZoneId zone=ZoneId.of(match.group(6));int year=ZonedDateTime.now(zone).getYear();
            var reset=ZonedDateTime.of(year,month.getValue(),Integer.parseInt(match.group(2)),hour,minute,0,0,zone);if(reset.isBefore(ZonedDateTime.now(zone).minusDays(1)))reset=reset.plusYears(1);
            return reset.toEpochSecond();
        }catch(Exception ignored){return null;}
    }
    public JsonNode consume(String key)throws Exception{
        if(resetter==null)throw new IllegalStateException("Сброс недоступен для этого подключения");
        var result=resetter.consume(key);synchronized(this){requestedAt=0;}refresh();return result;
    }
    public static boolean canReset(JsonNode state){
        if(!state.path("resetSupported").asBoolean()||!state.path("state").asText().equals("ready")||System.currentTimeMillis()-state.path("updatedAt").asLong()>6*60_000||state.path("resetCredits").path("availableCount").asInt()<=0)return false;
        for(var b:state.path("buckets")){if(!b.path("id").asText().equals("codex"))continue;for(var w:b.path("windows"))if(w.path("remainingPercent").asInt(100)<=5)return true;}return false;
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
