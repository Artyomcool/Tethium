package dev.tethium.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import dev.tethium.agent.AgentAdapter.Provider;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read only known Tethium threads and their native child threads. Never imports conversation text. */
public final class NativeUsageImport {
    private record Owner(String session,String project){}
    private record Log(Path path,String thread,String parent){}
    public static void read(SessionStore store,UsageLedger ledger,Path directory)throws Exception{
        if(!Files.isDirectory(directory))return;var owners=new HashMap<String,Owner>();
        for(var project:store.projects())for(var session:store.list(project.id())){var binding=store.binding(session.id(),Provider.CODEX);if(binding.conversationId()!=null)owners.put(binding.conversationId(),new Owner(session.id(),project.id()));}
        var logs=new ArrayList<Log>();try(var files=Files.walk(directory)){for(var path:files.filter(p->p.toString().endsWith(".jsonl")).toList()){
            try(var reader=Files.newBufferedReader(path)){String line=reader.readLine();if(line==null)continue;var meta=Json.parse(line).path("payload");String id=meta.path("id").asText();String parent=meta.path("source").path("subagent").path("thread_spawn").path("parent_thread_id").asText();logs.add(new Log(path,id,parent));}catch(Exception ignored){}
        }}
        boolean added;do{added=false;for(var log:logs)if(!owners.containsKey(log.thread)&&owners.containsKey(log.parent)){owners.put(log.thread,owners.get(log.parent));added=true;}}while(added);
        for(var log:logs){var owner=owners.get(log.thread);if(owner==null)continue;
            try(var reader=Files.newBufferedReader(log.path)){String line;while((line=reader.readLine())!=null){
                if(!line.contains("\"token_count\""))continue;
                try{var row=Json.parse(line);var payload=row.path("payload");if(!row.path("type").asText().equals("event_msg")||!payload.path("type").asText().equals("token_count"))continue;
                    long at=Instant.parse(row.path("timestamp").asText()).toEpochMilli();var info=payload.path("info");if(!info.isObject())continue;
                    ledger.sample(owner.session,owner.project,log.thread,at,breakdown(info.path("total_token_usage")),breakdown(info.path("last_token_usage")));
                    var rate=payload.path("rate_limits");var windows=Json.MAPPER.createArrayNode();for(String kind:List.of("primary","secondary")){var w=rate.path(kind);if(w.isObject())windows.add(Json.object("kind",kind,"durationMinutes",w.path("window_minutes"),"resetsAt",w.path("resets_at")));}
                    if(!windows.isEmpty())ledger.snapshot(Json.object("updatedAt",at,"buckets",List.of(Json.object("id",rate.path("limit_id").asText("codex"),"label","Codex","windows",windows))));
                }catch(Exception invalidLine){/* A live rollout may end with an incomplete line; retry on next launch. */}
            }}
        }
    }
    private static JsonNode breakdown(JsonNode n){if(!n.isObject())return Json.object();return Json.object("totalTokens",n.path("total_tokens"),"inputTokens",n.path("input_tokens"),"cachedInputTokens",n.path("cached_input_tokens"),"outputTokens",n.path("output_tokens"),"reasoningOutputTokens",n.path("reasoning_output_tokens"));}
}
