package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small aggregate-only traffic journal. It deliberately never stores paths, peers, or individual transfers. */
final class TrafficLedger implements AutoCloseable {
    private final Path file;
    private final Map<String,Long> total=new LinkedHashMap<>(),today=new LinkedHashMap<>();
    private String day=LocalDate.now(ZoneOffset.UTC).toString();
    private long lastSave;
    TrafficLedger(Path file){this.file=file;load();}
    synchronized void bytes(boolean remote,boolean upload,long count){
        if(count<=0)return;roll();String key=(remote?"cloudflare":"lan")+(upload?"Up":"Down");
        total.merge(key,count,Long::sum);today.merge(key,count,Long::sum);saveSoon();
    }
    synchronized void relayConnection(){roll();total.merge("workerRequests",1L,Long::sum);today.merge("workerRequests",1L,Long::sum);saveSoon();}
    synchronized void relayMessage(){roll();total.merge("relayMessages",1L,Long::sum);today.merge("relayMessages",1L,Long::sum);saveSoon();}
    synchronized boolean allowRelay(){roll();long connections=today.getOrDefault("workerRequests",0L),messages=today.getOrDefault("relayMessages",0L);return connections<90_000&&connections+(messages+19)/20<90_000;}
    synchronized JsonNode snapshot(){
        roll();long connections=today.getOrDefault("workerRequests",0L),messages=today.getOrDefault("relayMessages",0L);var out=Json.object("day",day,"freeDailyRequests",100_000,"estimatedWorkerRequests",connections,"estimatedDurableRequests",connections+(messages+19)/20,"relayMessages",messages);
        out.set("today",Json.MAPPER.valueToTree(today));out.set("total",Json.MAPPER.valueToTree(total));return out;
    }
    private void roll(){String now=LocalDate.now(ZoneOffset.UTC).toString();if(!now.equals(day)){day=now;today.clear();save();}}
    private void load(){try{if(!Files.isRegularFile(file))return;JsonNode root=Json.MAPPER.readTree(file.toFile());day=root.path("day").asText(day);read(root.path("total"),total);read(root.path("today"),today);roll();}catch(Exception ignored){}}
    private static void read(JsonNode node,Map<String,Long> target){if(node.isObject())node.properties().forEach(e->target.put(e.getKey(),e.getValue().asLong()));}
    private void saveSoon(){long now=System.currentTimeMillis();if(now-lastSave>=10_000){lastSave=now;save();}}
    private void save(){try{Files.createDirectories(file.getParent());Path tmp=file.resolveSibling(file.getFileName()+".tmp");Json.MAPPER.writeValue(tmp.toFile(),Json.object("day",day,"today",today,"total",total));Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException ignored){}}
    public synchronized void close(){save();}
}
