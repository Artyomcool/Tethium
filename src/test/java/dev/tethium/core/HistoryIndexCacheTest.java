package dev.tethium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HistoryIndexCacheTest {
    @TempDir Path root;

    @Test void appendedAndEditedCardsReuseUnchangedMessageMetadata()throws Exception{
        try(var store=new SessionStore(root.resolve("db"))){
            String id=store.create(store.project(root),null,"History",root).id();
            String longText="Old answer 🙂\n\n".repeat(2000);
            store.append(id,"assistant","CODEX",longText);
            var original=store.historyIndex(id);
            store.appendSessionUpdate(id,"source:run","First update","");
            var appended=store.historyIndex(id);
            for(int i=0;i<original.size();i++)assertSame(original.get(i),appended.get(i));
            var previousCard=appended.getLast();
            store.appendSessionUpdate(id,"source:run","","More details\n\n".repeat(1000));
            var edited=store.historyIndex(id);
            for(int i=0;i<original.size();i++)assertSame(original.get(i),edited.get(i),"A different card's edit must retain the old metadata and revision");
            assertNotEquals(previousCard.path("revision"),edited.get(original.size()).path("revision"));
            assertEquals("First update\n\n"+"More details\n\n".repeat(1000),store.messages(id,0,10).getLast().text());
            long editedSequence=previousCard.path("sequence").asLong();
            store.deleteMessages(id,List.of(editedSequence));
            assertEquals(original,store.historyIndex(id));
        }
    }

    @Test void rolledBackMetadataDoesNotSurviveWhenSqliteReusesTheSequence()throws Exception{
        try(var store=new SessionStore(root.resolve("rollback.db"))){
            String id=store.create(store.project(root),null,"History",root).id();
            assertThrows(IllegalStateException.class,()->store.transaction(()->{
                store.append(id,"user","USER","rolled back text");store.historyIndex(id);
                throw new IllegalStateException("rollback");
            }));
            String replacement="🙂".repeat(330)+"\nlast line";
            store.append(id,"user","USER",replacement);
            var row=store.historyIndex(id).getFirst();
            assertEquals(replacement.codePointCount(0,replacement.length()),row.path("length").asInt());
            assertEquals("🙂".repeat(320),row.path("title").asText());
        }
    }
}
