package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.util.*;

/** Explicit cold migration checkpoint; ordinary hot updates never use this path. */
final class WorkloadRestart {
    static Path path(Path data,String id){UUID.fromString(id);return data.resolve("restarts").resolve(id+".json");}
    static JsonNode read(Path data,String id)throws Exception{Path file=path(data,id);return Files.exists(file)?Json.MAPPER.readTree(file.toFile()):null;}
    static void save(Path data,String id,JsonNode plan)throws Exception{
        Path file=path(data,id);Files.createDirectories(file.getParent());Path tmp=Files.createTempFile(file.getParent(),"restart-",".tmp");
        try{Json.MAPPER.writeValue(tmp.toFile(),plan);Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(tmp);}
    }
    static boolean alive(long pid,long started){return pid>0&&ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(p->p.info().startInstant()).map(t->t.toEpochMilli()==started).orElse(false);}
    static void process(Map<Long,JsonNode> result,long pid,long started,String session,String command){
        if(pid==ProcessHandle.current().pid()||!alive(pid,started))return;
        var p=ProcessHandle.of(pid).orElseThrow();result.put(pid,Json.object("pid",pid,"started",started,"session",session,"command",command));
        var pending=new ArrayDeque<ProcessHandle>();pending.add(p);var visited=new HashSet<Long>();
        while(!pending.isEmpty()){var parent=pending.remove();if(!visited.add(parent.pid()))continue;long parentStart=parent.info().startInstant().map(t->t.toEpochMilli()).orElse(Long.MAX_VALUE);
            for(var child:parent.children().toList()){var info=child.info();if(info.startInstant().isEmpty()||info.startInstant().get().toEpochMilli()<parentStart)continue;result.putIfAbsent(child.pid(),Json.object("pid",child.pid(),"started",info.startInstant().get().toEpochMilli(),"session",session,"command",info.command().orElse("")));pending.add(child);}}

    }
    static JsonNode restore(SessionStore store,Path data,String id)throws Exception{
        var plan=read(data,id);if(plan==null)throw new IllegalArgumentException("Не найден снимок перезапуска");
        var restored=new ArrayList<String>();
        for(var row:plan.path("sessions")){
            String session=row.path("id").asText();if(store.operationResult(session,id+"-"+session+"-notice","restart-notice")!=null)continue;
            store.transaction(()->{
                store.append(session,"system","TETHIUM","Сорян, перезапустились по просьбе пользователя для миграции CPU-лимитов. История, очередь, черновики и ручные паузы сохранены.");
                if(row.path("resume").asBoolean()&&store.get(session).active()){
                    Path context=Files.createDirectories(data.resolve("restarts").resolve(id)).resolve(session+".json");
                    Json.MAPPER.writeValue(context.toFile(),Json.object("restartAlreadyPerformed",true,"restartId",id,"session",row));
                    String prompt="Сорян, перезапустились по просьбе пользователя для миграции CPU-лимитов. Продолжи прежнюю задачу с сохранённого места. Снимок твоего контекста: "+context+". Сначала прочитай его и проверь уже полученные результаты. Нужные фоновые сервисы остановлены: перезапусти их из этой сессии. Саму миграцию повторять не нужно; ручные паузы других сессий не снимай.";
                    // The caller owns this transaction; keep insert and notice receipt atomic.
                    store.restartContinuation(session,prompt,id,row.path("source").isNull()?null:row.path("source").asText(null));restored.add(session);
                }
                store.rememberRestartNotice(session,id);return null;
            });
        }
        var complete=((com.fasterxml.jackson.databind.node.ObjectNode)plan).put("restoredAt",System.currentTimeMillis()).put("completed",true);save(data,id,complete);
        return Json.object("restored",restored,"checkpoint",path(data,id).toString());
    }
}
