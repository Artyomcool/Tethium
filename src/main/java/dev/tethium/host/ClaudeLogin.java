package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.util.*;

/** Starts the official Claude CLI login once; the CLI opens the account page in the laptop browser. */
final class ClaudeLogin {
    private final Path data, log;
    private final List<String> executable;
    private final Runnable finished;
    private Process process;
    private long startedAt;
    private Integer exitCode;
    private String error="";

    ClaudeLogin(Path data,List<String> executable,Runnable finished){
        this.data=data;this.log=data.resolve("claude-auth.log");this.executable=List.copyOf(executable);this.finished=finished;
    }
    synchronized JsonNode start()throws Exception{
        if(process!=null&&process.isAlive())return snapshot();
        Files.createDirectories(data);Files.deleteIfExists(log);
        var command=new ArrayList<>(executable);command.addAll(List.of("auth","login","--claudeai"));
        var builder=new ProcessBuilder(command).directory(data.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("CLAUDECODE");
        process=builder.start();startedAt=System.currentTimeMillis();exitCode=null;error="";
        Process launched=process;
        Thread.ofVirtual().name("claude-auth-login").start(()->observe(launched));
        return snapshot();
    }
    synchronized JsonNode snapshot(){return Json.object("running",process!=null&&process.isAlive(),"startedAt",startedAt,"exitCode",exitCode,"error",error);}
    private void observe(Process launched){
        int code;
        try{code=launched.waitFor();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();code=-1;}
        synchronized(this){if(process==launched){process=null;exitCode=code;error=code==0?"":"Claude CLI не завершил авторизацию";}}
        try{finished.run();}catch(RuntimeException ignored){}
    }
}
