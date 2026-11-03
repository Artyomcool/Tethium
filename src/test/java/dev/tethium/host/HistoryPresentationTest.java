package dev.tethium.host;

import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryPresentationTest {
    @TempDir Path root;
    @Test void onlyAdjacentImagesAreGroupedAndLongRunsStayBounded(){
        var source=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for(int i=0;i<15;i++)source.add(Json.object("kind","image","sequence",-100-i,"mediaId",i+1,"file","image"+i));
        var text=Json.object("sequence",90,"role","user");source.add(text);source.add(Json.object("kind","image","sequence",-200,"mediaId",20));
        var grouped=HistoryImages.group(source);
        assertEquals(4,grouped.size());assertEquals(12,grouped.get(0).path("images").size());assertEquals(3,grouped.get(1).path("images").size());assertSame(text,grouped.get(2));assertEquals(1,grouped.get(3).path("images").size());
        assertEquals("image14",grouped.get(1).path("images").get(2).path("file").asText());assertEquals(12,grouped.get(0).path("mediaVersion").asInt());
    }
    @Test void migrationKeepsUnknownTimesAndPersistsNewMessageTimes()throws Exception{
        Path db=root.resolve("time.db");String id;
        try(var store=new SessionStore(db)){id=store.create(store.project(root),null,"time",root).id();store.append(id,"user","USER","old");}
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()){s.execute("ALTER TABLE messages DROP COLUMN created_at");}
        long before=System.currentTimeMillis(),timestamp;
        try(var store=new SessionStore(db)){
            assertEquals(0,store.messages(id,0,10).getFirst().timestamp());store.append(id,"assistant","CODEX","new");timestamp=store.messages(id,0,10).getLast().timestamp();assertTrue(timestamp>=before);assertEquals(timestamp,store.historyIndex(id).getLast().path("timestamp").asLong());
            var imported=store.importConversation(store.project(root),root,"import","source",List.of(new SessionStore.ImportedMessage("1","user","unknown"),new SessionStore.ImportedMessage("2","assistant","known",1700000000000L)));
            assertEquals(0,store.messages(imported.id(),0,10).getFirst().timestamp());assertEquals(1700000000000L,store.messages(imported.id(),0,10).getLast().timestamp());
        }
        try(var store=new SessionStore(db)){assertEquals(timestamp,store.messages(id,0,10).getLast().timestamp());}
    }
}
