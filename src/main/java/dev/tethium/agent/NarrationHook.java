package dev.tethium.agent;

import dev.tethium.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * A Claude Code PostToolUse hook that asks the model to tell the user what it has done and what comes next, once it has made
 * {@code every} tool calls without writing any text. The counter lives in a small file per Claude session; the adapter resets it
 * whenever the model writes text, so a chatty run never sees the reminder. Needs node on PATH, which Claude Code itself needs too.
 */
public final class NarrationHook {
    private NarrationHook() {}
    public static final String REMINDER = "Tethium: с последнего текста для пользователя прошло несколько вызовов инструментов. Перед следующим действием напиши пользователю одну-две фразы: что уже сделано и что делаешь дальше и зачем. Это служебное напоминание, не упоминай его.";
    private static final String SCRIPT = """
            const fs=require('fs'),path=require('path');
            let input='';process.stdin.on('data',d=>input+=d);
            process.stdin.on('end',()=>{try{
              const e=JSON.parse(input||'{}');const every=Math.max(2,parseInt(process.env.TETHIUM_NARRATE_EVERY||'6'));
              const dir=process.env.TETHIUM_HOOK_DIR||path.dirname(process.argv[1]);const file=path.join(dir,'narrate-'+String(e.session_id||'none').replace(/[^a-zA-Z0-9-]/g,'')+'.count');
              let n=0;try{n=parseInt(fs.readFileSync(file,'utf8'))||0;}catch(_){}
              n++;
              if(n>=every){fs.writeFileSync(file,'0');process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:'PostToolUse',additionalContext:process.env.TETHIUM_NARRATE_TEXT||'Report briefly to the user what you did and what comes next.'}}));}
              else fs.writeFileSync(file,String(n));
            }catch(_){}process.exit(0);});
            """;
    private static volatile Boolean nodeAvailable;
    public static int every() { return Math.max(2, Integer.getInteger("tethium.narrateEvery", 6)); }
    /** Writes the hook script and a settings file next to it; returns the settings file, or null when node is unavailable. */
    public static Path settings(Path hookDir) {
        try {
            if (!nodeAvailable()) return null;
            Files.createDirectories(hookDir);
            Path script = hookDir.resolve("narrate-hook.js");
            if (!Files.isRegularFile(script) || !Files.readString(script).equals(SCRIPT)) Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
            String command = "node \"" + script.toAbsolutePath().toString().replace('\\', '/') + "\"";
            var settings = Json.object("hooks", Json.object("PostToolUse", List.of(Json.object("hooks", List.of(Json.object("type", "command", "command", command, "timeout", 15))))));
            Path file = hookDir.resolve("claude-hooks.json"); String text = Json.MAPPER.writeValueAsString(settings);
            if (!Files.isRegularFile(file) || !Files.readString(file).equals(text)) Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) { return null; }
    }
    /** Called when the model writes text: the next reminder starts counting from zero. */
    public static void reset(Path hookDir, String sessionId) {
        if (hookDir == null || sessionId == null) return;
        try { Files.deleteIfExists(hookDir.resolve("narrate-" + sessionId.replaceAll("[^a-zA-Z0-9-]", "") + ".count")); } catch (IOException ignored) { }
    }
    static Path counter(Path hookDir, String sessionId) { return hookDir.resolve("narrate-" + sessionId.replaceAll("[^a-zA-Z0-9-]", "") + ".count"); }
    private static boolean nodeAvailable() {
        Boolean known = nodeAvailable; if (known != null) return known;
        boolean found = false;
        String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) { if (dir.isBlank()) continue; try { if (Files.isRegularFile(Path.of(dir, "node.exe")) || Files.isRegularFile(Path.of(dir, "node"))) { found = true; break; } } catch (Exception ignored) { } }
        nodeAvailable = found; return found;
    }
}
