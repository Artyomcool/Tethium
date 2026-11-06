package dev.tethium.probe;

import dev.tethium.agent.*;
import dev.tethium.host.*;
import dev.tethium.mobile.MobileGateway;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** Isolated emulator fixture, never calls a model or opens the user's database. */
public final class MobileProbe {
    public static void main(String[] args)throws Exception{
        Path root=Files.createDirectories(Path.of(".local/mobile-probe/"+UUID.randomUUID()).toAbsolutePath());
        var adapter=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> events,Cancellation cancel)throws Exception{
            if(r.prompt().endsWith("long")){events.accept(new Event("text","Работаю…",Json.object()));while(!cancel.isCancelled())Thread.sleep(50);return new Result("probe",Status.CANCELLED,"Работаю…");}
            if(r.prompt().endsWith("ask")){
                var question=r.requests().handle("item/tool/requestUserInput",Json.object("questions",List.of(
                    Json.object("id","one","header","Сборка","question","Какой вариант собирать?","options",List.of(Json.object("label","Быстрый","description","Без проверок"),Json.object("label","Полный","description","Со всеми тестами"))),
                    Json.object("id","many","question","Что ещё сделать?","multiSelect",true,"options",List.of(Json.object("label","Обновить документы"),Json.object("label","Опубликовать"))),
                    Json.object("id","free","question","Что написать в заметке?"))),cancel);
                var approval=r.requests().handle("item/commandExecution/requestApproval",Json.object("command","Bash {\"command\":\"rm -rf build\"}"),cancel);
                var form=r.requests().handle("mcpServer/elicitation/request",Json.object("message","Нужен адрес сервера","requestedSchema",Json.object("properties",Json.object("host",Json.object("title","Адрес")))),cancel);
                return new Result("probe",Status.COMPLETED,"Ответы: "+question+" "+approval+" "+form);
            }
            return new Result("probe",Status.COMPLETED,"Готово: "+r.prompt().substring(Math.max(0,r.prompt().length()-100)));
        }};
        var catalog=new CodexModelCatalog(directory->Json.object("available",true,"configuredModel","probe-model","data",List.of(Json.object("model","probe-model","displayName","Probe model","isDefault",true,"defaultReasoningEffort","medium","supportedReasoningEfforts",List.of(Json.object("reasoningEffort","medium"),Json.object("reasoningEffort","high"))))));
        try(var host=new HostService(root,adapter,new CodexQuotaService(null),false,catalog);var local=new HostClient(host.descriptor());var mobile=new MobileGateway(root,host.feed(),0,0)){
            var done=new CountDownLatch(1);host.onShutdown(done::countDown);
            String project=local.post("/projects",Json.object("path",root.toString())).path("id").asText();String session=local.post("/sessions",Json.object("projectId",project,"title","Проверка Android")).path("id").asText();
            Path html=Files.writeString(root.resolve("preview.html"),"<!doctype html><meta charset=utf-8><h1>HTML работает</h1><button onclick=\"this.textContent='Нажато'\">Проверить JavaScript</button>");local.post("/sessions/"+session+"/artifact",Json.object("path",html.toString(),"title","HTML с JavaScript"));
            var info=mobile.pairInfo();String pairing=info.path("pairing").asText().replace(info.path("address").asText()+":"+mobile.port(),"10.0.2.2:"+mobile.port());
            Json.MAPPER.writeValue(root.resolve("server.json").toFile(),Json.object("root",root.toString(),"host",host.descriptor(),"session",session,"pairing",pairing,"port",mobile.port(),"downloadPort",mobile.downloadPort()));
            Files.writeString(Path.of(".local/mobile-probe/current.txt"),root.toString());System.out.println(root);done.await();
        }
    }
}
