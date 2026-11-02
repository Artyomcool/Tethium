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
            ledger.sample("a","p1","thread-a",now-120_000,count(100),count(100));ledger.sample("a","p1","thread-a",now-10_000,count(130),count(30));ledger.sample("a","p1","thread-a",now-9_000,count(130),count(30));ledger.sample("b","p2","thread-b",now-5_000,count(50),count(50));
            ledger.snapshot(Json.object("updatedAt",now,"buckets",List.of(Json.object("id","codex","label","Codex","windows",List.of(Json.object("kind","primary","durationMinutes",1,"resetsAt",(now+10_000)/1000))))));
            var all=ledger.report("all",null,null);assertEquals(180,all.path("total").asLong());assertEquals(130,all.path("projects").path("p1").asLong());
            var window=ledger.report(null,"p1","a");period=window.path("period").asText();assertEquals(30,window.path("tokens").asLong());assertEquals(80,window.path("total").asLong());assertEquals(37.5,window.path("share").asDouble());
        }
        try(var ledger=new UsageLedger(dir.resolve("usage.db"))){assertEquals(30,ledger.report(period,"p1","a").path("tokens").asLong());}
    }
    @Test void lateHistoricalSamplesReplaceAnUnknownBaselineWithoutDoubleCounting()throws Exception{
        try(var ledger=new UsageLedger(dir.resolve("usage.db"))){ledger.sample("a","p","thread",300,count(300),count(50));assertEquals(50,ledger.report("all",null,null).path("tokens").asLong());ledger.sample("a","p","thread",100,count(100),count(100));ledger.sample("a","p","thread",250,count(250),count(150));assertEquals(300,ledger.report("all",null,null).path("tokens").asLong());assertFalse(ledger.report("all",null,null).path("partial").asBoolean());}
    }
    @Test void cpuCountersSurviveHandoffAndNeverChargePreviousDaemonOwner()throws Exception{
        long now=System.currentTimeMillis();Path db=dir.resolve("cpu.db");
        try(var l=new UsageLedger(db)){
            l.cpuSample("1:1",1,1,"a","p",100,now,"child",false);
            l.cpuSample("1:1",1,1,"a","p",150,now+1,"child",false);
            l.cpuSample("1:1",1,1,"a","p",150,now+2,"child",false);
            l.cpuSample("1:1",1,1,"b","q",200,now+3,"shared",false);
            l.cpuSample("1:1",1,1,"b","q",230,now+4,"shared",false);
        }
        Thread.sleep(10);
        try(var l=new UsageLedger(db)){l.cpuSample("1:1",1,1,"b","q",250,now+5,"shared",false);var r=l.report("all","","");assertEquals(100,r.path("cpu").path("totalNanos").asLong());assertEquals(50,r.path("cpu").path("sessions").path("a").asLong());assertEquals(50,r.path("cpu").path("sessions").path("b").asLong());l.resetBoundary("manual");assertEquals(0,l.report("since-reset","","").path("cpu").path("totalNanos").asLong());assertEquals(100,l.report("all","","").path("cpu").path("totalNanos").asLong());}
    }
}
