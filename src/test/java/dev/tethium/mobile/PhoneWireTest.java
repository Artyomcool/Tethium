package dev.tethium.mobile;

import dev.tethium.json.Json;
import dev.tethium.link.Frames;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PhoneWireTest {
    @Test void hotFramesArePositionalAndInactiveSessionsContainOnlyVisibleFields(){
        var full=Json.object("id","s","projectId","p","parentId",null,"title","Session","directory","C:/work",
                "active",true,"technical",false,"running",true,"phase","Working","queuePaused",false,"status","RUNNING",
                "queued",2,"backlog",3,"attention",Json.object("kind","question","version","7","unread",true,"secret","not-on-wire"),
                "progress",Json.object("startedAt",10,"detail","private active detail","recent",List.of("a")),
                "preferences",Json.object("provider","CODEX","model","gpt-6-sol","reasoningEffort","xhigh"),
                "autonomy",Json.object("state","active","goal","private goal"));
        var summary=PhoneWire.sessionSummary(full);
        assertTrue(summary.isArray());assertEquals(14,summary.size());assertFalse(summary.toString().contains("private active detail"));assertFalse(summary.toString().contains("private goal"));
        var state=Json.object("full",true,"revision",4,"sessionRows",List.of(summary));
        var encoded=PhoneWire.encode(Frames.STATE,state);assertTrue(encoded.isArray());assertFalse(encoded.toString().contains("sessionRows"));
        assertEquals(state,PhoneWire.decode(Frames.STATE,encoded));
        var detail=PhoneWire.sessionDetail(full);assertTrue(detail.toString().contains("private active detail"));assertTrue(detail.toString().contains("private goal"));
    }

    @Test void commandAndStreamingEnvelopesRoundTripWithoutFieldNames(){
        for(var sample:List.of(
                java.util.Map.entry(Frames.WATCH,Json.object("session","s","reset",false,"tail",10,"after",3)),
                java.util.Map.entry(Frames.LIVE,Json.object("session","s","run",12,"offset",4,"text","tail","timestamp",9)),
                java.util.Map.entry(Frames.REQUEST,Json.object("id",7,"path","/models","post",false)))){
            var encoded=PhoneWire.encode(sample.getKey(),sample.getValue());assertTrue(encoded.isArray());assertEquals(sample.getValue(),PhoneWire.decode(sample.getKey(),encoded));
        }
    }
}
