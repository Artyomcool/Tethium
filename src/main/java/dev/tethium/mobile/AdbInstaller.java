package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tethium.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Silent installs through Android wireless debugging: the laptop pairs with the phone once, then pushes APKs with adb and no
 * confirmation on the phone. The in-app store stays the fallback when adb is not configured or the phone is unreachable.
 * Settings live in mobile/adb.json: adb path, device address (ip:port), the phone's IP, and the last result.
 */
public final class AdbInstaller {
    private final Path file;
    private final ObjectNode config;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("adb-install").factory());
    private volatile String lastResult = "";
    private volatile long lastResultAt;
    AdbInstaller(Path mobileDir) throws IOException {
        file = mobileDir.resolve("adb.json");
        config = Files.isRegularFile(file) && Json.MAPPER.readTree(file.toFile()) instanceof ObjectNode saved ? saved : Json.object();
    }
    private synchronized void save() throws IOException { Path tmp = file.resolveSibling("adb.tmp"); Json.MAPPER.writeValue(tmp.toFile(), config); Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
    /** The phone's LAN address as seen on the link; keeps mdns discovery targeted after the debug port changes. */
    synchronized void phone(String ip) { if (ip != null && !ip.isEmpty() && !ip.equals(config.path("phone").asText())) { config.put("phone", ip); try { save(); } catch (IOException ignored) { } } }
    public synchronized boolean configured() { return !config.path("device").asText().isEmpty(); }
    public synchronized JsonNode status() {
        String adb = adb(); boolean found = adb != null && Files.isRegularFile(Path.of(adb));
        boolean online = found && configured() && online();
        long version = online ? installedVersion("dev.tethium.mobile") : -1;
        return Json.object("adb", adb == null ? "" : adb, "adbFound", found, "device", config.path("device").asText(), "phone", config.path("phone").asText(), "configured", configured(), "online", online, "tethiumVersionCode", version, "lastResult", lastResult, "lastResultAt", lastResultAt);
    }
    public synchronized JsonNode pair(String address, String code) throws Exception {
        if (!address.matches("[0-9.]+:[0-9]{1,5}") || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Нужны адрес сопряжения вида 192.168.1.5:37123 и шестизначный код с телефона");
        var out = run(40, "pair", address, code);
        if (!out.contains("Successfully paired")) throw new IllegalStateException(trim(out));
        config.put("phone", address.substring(0, address.indexOf(':'))); save();
        return Json.object("ok", true, "output", trim(out));
    }
    public synchronized JsonNode connect(String address) throws Exception {
        if (!address.matches("[0-9.]+:[0-9]{1,5}")) throw new IllegalArgumentException("Нужен адрес подключения вида 192.168.1.5:41234 с экрана «Отладка по Wi-Fi»");
        var out = run(20, "connect", address);
        if (!out.contains("connected to") && !out.contains("already connected")) throw new IllegalStateException(trim(out));
        config.put("device", address); config.put("phone", address.substring(0, address.indexOf(':'))); save();
        return Json.object("ok", true, "device", address, "output", trim(out));
    }
    public synchronized JsonNode forget() throws IOException { config.remove("device"); save(); return Json.object("ok", true); }
    /** Installs or updates; same versionCode is replaced too, so dev builds roll forward. */
    public synchronized JsonNode install(Path apk, String packageName) throws Exception {
        if (!configured()) throw new IllegalStateException("Отладка по Wi-Fi не настроена");
        if (!ensureConnected()) throw new IllegalStateException("Телефон недоступен по adb: " + trim(lastResult));
        var out = run(240, "-s", config.path("device").asText(), "install", "-r", "-d", apk.toAbsolutePath().toString());
        boolean ok = out.contains("Success");
        remember((ok ? "Установлено " : "Не установлено ") + packageName + ": " + trim(out));
        if (!ok) throw new IllegalStateException(trim(out));
        return Json.object("ok", true, "packageName", packageName, "versionCode", installedVersion(packageName), "output", trim(out));
    }
    /** Background push that never throws; the store remains available when it fails. */
    public void installLater(Path apk, String packageName, long versionCode) {
        if (!configured()) return;
        worker.execute(() -> { try { if (versionCode > 0 && installedVersion(packageName) >= versionCode && !packageName.equals("dev.tethium.mobile")) return; install(apk, packageName); } catch (Exception e) { remember("Не удалось поставить " + packageName + " через adb: " + e.getMessage()); } });
    }
    public long installedVersion(String packageName) {
        try { if (!ensureConnected()) return -1; return parseVersionCode(run(20, "-s", config.path("device").asText(), "shell", "dumpsys", "package", packageName)); } catch (Exception e) { return -1; }
    }
    static long parseVersionCode(String dumpsys) { var m = Pattern.compile("versionCode=(\\d+)").matcher(dumpsys); return m.find() ? Long.parseLong(m.group(1)) : -1; }
    private boolean online() { try { String device = config.path("device").asText(); for (String line : run(15, "devices").split("\\R")) { var parts = line.trim().split("\\s+"); if (parts.length >= 2 && parts[0].equals(device) && parts[1].equals("device")) return true; } return false; } catch (Exception e) { return false; } }
    private boolean ensureConnected() {
        if (online()) return true;
        String device = config.path("device").asText();
        try { String out = run(15, "connect", device); if ((out.contains("connected to") || out.contains("already connected")) && online()) return true; lastResult = out; } catch (Exception e) { lastResult = e.getMessage(); }
        // The connect port changes when wireless debugging restarts; mdns announces the current one.
        String phone = config.path("phone").asText(); if (phone.isEmpty()) return false;
        try { String found = discover(run(15, "mdns", "services"), phone); if (found == null) return false; String out = run(15, "connect", found); if (out.contains("connected to") || out.contains("already connected")) { synchronized (this) { config.put("device", found); save(); } return online(); } lastResult = out; } catch (Exception e) { lastResult = e.getMessage(); }
        return false;
    }
    static String discover(String mdns, String phone) { for (String line : mdns.split("\\R")) { if (!line.contains("_adb-tls-connect._tcp")) continue; var m = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)").matcher(line); if (m.find() && m.group(1).equals(phone)) return m.group(1) + ":" + m.group(2); } return null; }
    private void remember(String text) { lastResult = text; lastResultAt = System.currentTimeMillis(); }
    private static String trim(String s) { s = s == null ? "" : s.strip().replaceAll("\\s+", " "); return s.length() > 300 ? s.substring(0, 300) : s; }
    private String run(long timeoutSeconds, String... args) throws Exception {
        String adb = adb(); if (adb == null) throw new IllegalStateException("adb не найден: укажите путь к platform-tools в mobile/adb.json");
        var command = new ArrayList<String>(); command.add(adb); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output; try (var in = process.getInputStream()) { output = in.readNBytes(1 << 20); }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("adb не ответил за " + timeoutSeconds + " с"); }
        return new String(output, StandardCharsets.UTF_8);
    }
    /** Explicit path first, then common SDK locations and PATH. */
    synchronized String adb() {
        String explicit = config.path("adb").asText(); if (!explicit.isEmpty() && Files.isRegularFile(Path.of(explicit))) return explicit;
        var candidates = new ArrayList<String>();
        String home = System.getenv("ANDROID_HOME"); if (home != null) candidates.add(home + "/platform-tools/adb.exe");
        String sdk = System.getenv("ANDROID_SDK_ROOT"); if (sdk != null) candidates.add(sdk + "/platform-tools/adb.exe");
        String local = System.getenv("LOCALAPPDATA"); if (local != null) candidates.add(local + "/Android/Sdk/platform-tools/adb.exe");
        String path = System.getenv("PATH"); if (path != null) for (String dir : path.split(Pattern.quote(java.io.File.pathSeparator))) if (!dir.isBlank()) candidates.add(dir + "/adb.exe");
        for (String candidate : candidates) try { if (Files.isRegularFile(Path.of(candidate))) return Path.of(candidate).toString(); } catch (Exception ignored) { }
        return null;
    }
    public void close() { worker.shutdownNow(); }
}
