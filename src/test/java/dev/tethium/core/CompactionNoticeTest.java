package dev.tethium.core;

import dev.tethium.agent.AgentAdapter;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CompactionNoticeTest {
    @TempDir Path root;
    private AgentAdapter.Event event(String type,String id,long time){
        return new AgentAdapter.Event(type,"",Json.object("method",type,"params",Json.object("threadId","thread","turnId","turn","item",Json.object("type","contextCompaction","id",id),type.equals("item/completed")?"completedAtMs":"startedAtMs",time)));
    }
    @Test void finishEditsTheOriginalNoticeAndSurvivesRestart()throws Exception{
        String id,run;Path db=root.resolve("db");
        try(var store=new SessionStore(db)){
            id=store.create(store.project(root),null,"Chat",root).id();run=store.beginRun(id,AgentAdapter.Provider.CODEX);
            store.append(id,"user","USER","Work");
            store.recordEvent(run,id,event("item/started","compact-1",1000));
            store.recordEvent(run,id,event("item/started","compact-1",1000));
            var messages=store.messages(id,0,10);assertEquals(2,messages.size());
            assertEquals("Сжатие контекста началось.",messages.getLast().text());assertEquals("system",messages.getLast().role());assertEquals(1000,messages.getLast().timestamp());
        }
        try(var store=new SessionStore(db)){
            store.recordEvent(run,id,event("item/started","compact-1",1000));
            store.recordEvent(run,id,event("item/completed","compact-1",2000));
            store.recordEvent(run,id,event("item/completed","compact-1",2000));
            store.recordEvent(run,id,event("item/started","compact-1",1000));
            var messages=store.messages(id,0,10);assertEquals(2,messages.size());assertEquals("Контекст сжат за 1 с.",messages.getLast().text());assertEquals("TETHIUM",messages.getLast().provider());assertEquals(1000,messages.getLast().timestamp());
            assertEquals(2,store.historyIndex(id).size());assertEquals(1,store.messageRevision(id));
            assertEquals(1000,store.historyIndex(id).getLast().path("timestamp").asLong());
            store.recordEvent(run,id,event("item/started","compact-2",3000));store.recordEvent(run,id,event("item/completed","compact-2",4000));
            assertEquals(3,store.messages(id,0,10).size());
        }
    }
    @Test void completionInvalidatesHistoryWithoutMovingTheMessageAndRollbackRestoresIt()throws Exception{
        try(var store=new SessionStore(root.resolve("db"))){
            String id=store.create(store.project(root),null,"Chat",root).id(),run=store.beginRun(id,AgentAdapter.Provider.CODEX);
            store.recordEvent(run,id,event("item/started","compact",1000));long original=store.lastSequence(id);
            store.append(id,"user","USER","A later clarification");long last=store.lastSequence(id);
            var index=store.historyIndex(id);
            assertThrows(IllegalStateException.class,()->store.transaction(()->{store.recordEvent(run,id,event("item/completed","compact",163000));store.historyIndex(id);throw new IllegalStateException("rollback");}));
            assertEquals(0,store.messageRevision(id));assertEquals(index,store.historyIndex(id));
            store.recordEvent(run,id,event("item/completed","compact",163000));
            var messages=store.messages(id,0,10);assertEquals(2,messages.size());assertEquals(original,messages.getFirst().sequence());assertEquals(last,store.lastSequence(id));
            assertEquals("Контекст сжат за 2 мин 42 с.",messages.getFirst().text());
            assertNotEquals(index,store.historyIndex(id));assertEquals(messages.getFirst().text(),store.historyChunk(id,original,0,8000));
        }
    }
    @Test void completionCanReplaceAStartWrittenByThePreviousCore()throws Exception{
        Path db=root.resolve("legacy.db");String id,run;long sequence;
        try(var store=new SessionStore(db)){
            id=store.create(store.project(root),null,"Chat",root).id();run=store.beginRun(id,AgentAdapter.Provider.CODEX);store.recordEvent(run,id,event("item/started","compact",1000));sequence=store.lastSequence(id);
        }
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);var statement=connection.createStatement()){statement.execute("DROP TABLE compaction_messages");statement.execute("DROP TABLE message_revisions");}
        try(var store=new SessionStore(db)){
            store.recordEvent(run,id,event("item/completed","compact",4000));var messages=store.messages(id,0,10);
            assertEquals(1,messages.size());assertEquals(sequence,messages.getFirst().sequence());assertEquals("Контекст сжат за 3 с.",messages.getFirst().text());
        }
    }
    @Test void receiptRollsBackWithMessageAndOrdinaryItemsDoNotCreateNotices()throws Exception{
        try(var store=new SessionStore(root.resolve("db"))){
            String id=store.create(store.project(root),null,"Chat",root).id(),run=store.beginRun(id,AgentAdapter.Provider.CODEX);
            assertThrows(IllegalStateException.class,()->store.transaction(()->{store.recordEvent(run,id,event("item/completed","compact",2000));throw new IllegalStateException("rollback");}));
            assertTrue(store.messages(id,0,10).isEmpty());
            store.recordEvent(run,id,event("item/completed","compact",2000));
            assertEquals(1,store.messages(id,0,10).size(),"Completion without a start must still be visible");
            store.recordEvent(run,id,new AgentAdapter.Event("item/completed","",Json.object("params",Json.object("item",Json.object("id","tool","type","commandExecution","command","echo contextCompaction")))));
            assertEquals(1,store.messages(id,0,10).size());
            store.recordEvent(run,id,event("item/started","",3000));assertEquals(1,store.messages(id,0,10).size());
        }
    }
}
