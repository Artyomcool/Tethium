package dev.tethium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.tethium.json.Json;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UsageLedgerTest {
    @TempDir Path dir;
    private static com.fasterxml.jackson.databind.JsonNode count(long n){return Json.object("totalTokens",n,"inputTokens",n-10,"cachedInputTokens",20,"outputTokens",10,"reasoningOutputTokens",2);}
    @Test void differencesAreDeduplicatedBeforeFilteringAndPersistAcrossRestart()throws Exception{
        long now=System.currentTimeMillis();String period;
        try(var ledger=new UsageLedger(dir.resolve("usage.db"))){
            ledger.snapshot(Json.object("updatedAt",now,"buckets",List.of(Json.object("id","codex","label","Codex","windows",List.of(Json.object("kind","primary","durationMinutes",1,"resetsAt",(now+10_000)/1000))))));
            ledger.sample("a","p1","thread-a",now-120_000,count(100),count(100));ledger.sample("a","p1","thread-a",now-10_000,count(130),count(30));ledger.sample("a","p1","thread-a",now-9_000,count(130),count(30));ledger.sample("b","p2","thread-b",now-5_000,count(50),count(50));
            var all=ledger.report("all",null,null);assertEquals(180,all.path("total").asLong());assertEquals(130,all.path("projects").path("p1").asLong());
            var mine=ledger.report("all","p1","a");assertEquals(120,mine.path("input").asLong());assertEquals(20,mine.path("cached").asLong());assertEquals(10,mine.path("output").asLong());assertEquals(2,mine.path("reasoning").asLong());
            var window=ledger.report(null,"p1","a");period=window.path("period").asText();assertEquals(30,window.path("tokens").asLong());assertEquals(80,window.path("total").asLong());assertEquals(37.5,window.path("share").asDouble());
        }
        try(var ledger=new UsageLedger(dir.resolve("usage.db"))){assertEquals(30,ledger.report(period,"p1","a").path("tokens").asLong());}
    }
    @Test void lateSamplesDoNotChangeTheSum()throws Exception{
        try(var ledger=new UsageLedger(dir.resolve("usage.db"))){ledger.sample("a","p","thread",300,count(300),count(50));assertEquals(50,ledger.report("all",null,null).path("tokens").asLong());ledger.sample("a","p","thread",100,count(100),count(100));ledger.sample("a","p","thread",250,count(250),count(150));assertEquals(50,ledger.report("all",null,null).path("tokens").asLong());}
    }
    @Test void tokensAreAttributedToTheExecutorOfTheirThreadAndLegacyThreadsCountAsCodex()throws Exception{
        try(var ledger=new UsageLedger(dir.resolve("usage.db"))){
            long now=System.currentTimeMillis();
            ledger.sample("a","p","thread-legacy",now-3000,count(100),count(100));
            ledger.sample("a","p","thread-codex",now-2000,count(50),count(50),"CODEX");
            ledger.sample("b","p","thread-claude",now-1000,count(70),count(70),"CLAUDE");ledger.sample("b","p","thread-claude",now-500,count(100),count(30),"CLAUDE");
            var report=ledger.report("all",null,null);
            assertEquals(250,report.path("total").asLong());
            assertEquals(150,report.path("executors").path("codex").path("total").asLong());assertEquals(150,report.path("executors").path("codex").path("sessions").path("a").asLong());
            assertEquals(100,report.path("executors").path("claude").path("total").asLong());assertEquals(100,report.path("executors").path("claude").path("sessions").path("b").asLong());assertEquals(100,report.path("executors").path("claude").path("projects").path("p").asLong());
            assertEquals(100,ledger.report("all","p","b").path("executors").path("claude").path("tokens").asLong());
            assertEquals(80,ledger.sidebar().path("executors").path("claude").path("sessions").path("b").asLong(),"the bars leave out the cache");
        }
    }
}
