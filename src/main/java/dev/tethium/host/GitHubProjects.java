package dev.tethium.host;

import dev.tethium.json.Json;
import dev.tethium.core.ProjectPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class GitHubProjects {
    private static String gh(){String custom=System.getenv("TETHIUM_GH");if(custom!=null)return custom;Path installed=Path.of("C:/Program Files/GitHub CLI/gh.exe");if(Files.isRegularFile(installed))return installed.toString();Path portable=Path.of(System.getenv().getOrDefault("LOCALAPPDATA",System.getProperty("user.home")),"Tethium","tools","gh","bin","gh.exe");return Files.isRegularFile(portable)?portable.toString():"gh";}
    static String run(Path root,String...args)throws Exception{
        var builder=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true);builder.environment().put("GH_PROMPT_DISABLED","1");builder.environment().put("GIT_TERMINAL_PROMPT","0");var process=builder.start();var output=CompletableFuture.supplyAsync(()->{try{return new String(process.getInputStream().readNBytes(500_000),StandardCharsets.UTF_8);}catch(Exception e){return e.getMessage();}});
        if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new java.io.IOException("Команда GitHub не завершилась за минуту");}String text=output.get(5,TimeUnit.SECONDS).strip();if(process.exitValue()!=0)throw new java.io.IOException(text.isBlank()?"Не удалось выполнить "+args[0]:text);return text;
    }
    public static JsonNode status(Path root)throws Exception{
        String remote="",login="",error="";try{remote=run(root,"git","remote","get-url","origin");}catch(Exception ignored){}
        try{login=run(root,gh(),"api","user","--jq",".login");}catch(Exception e){error="Выполните gh auth login на ноутбуке. "+e.getMessage();}
        return Json.object("frequentPush",ProjectPolicy.enabled(root),"file",root.resolve("AGENTS.md").toString(),"remote",remote,"login",login,"error",error);
    }
    public static synchronized JsonNode configure(Path root,JsonNode body)throws Exception{
        Files.createDirectories(root);String repository=body.path("repository").asText().strip();
        if(body.path("createRepository").asBoolean()){
            if(!repository.matches("(?:[A-Za-z0-9_-]+/)?[A-Za-z0-9_.-]+")||repository.startsWith("-"))throw new IllegalArgumentException("Укажите имя репозитория или owner/name");
            run(root,gh(),"auth","status","--hostname","github.com");
            if(!Files.exists(root.resolve(".git")))run(root,"git","init","--initial-branch=main");
            String remote="";try{remote=run(root,"git","remote","get-url","origin");}catch(Exception ignored){}
            if(!remote.isBlank())throw new IllegalArgumentException("У проекта уже есть origin: "+remote);
            // No implicit commit or push of the working directory. gh only creates the repository and remote.
            run(root,gh(),"repo","create",repository,body.path("public").asBoolean()?"--public":"--private","--source",root.toString(),"--remote","origin");
        }
        if(body.has("frequentPush"))ProjectPolicy.set(root,body.path("frequentPush").asBoolean());
        return status(root);
    }
}
