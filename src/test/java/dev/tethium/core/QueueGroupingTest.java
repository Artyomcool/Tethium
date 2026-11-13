package dev.tethium.core;

import dev.tethium.json.Json;
import dev.tethium.agent.AgentAdapter.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QueueGroupingTest {
    @TempDir Path root;
    @Test void nonAdjacentMergeRestoresOriginalRowsAfterRestartAndRetriesAreIdempotent() throws Exception {
        String session,a,b,c,d; var db=root.resolve("db");
        try(var store=new SessionStore(db)) {
            session=store.create(store.project(root),null,"test",root).id();
            a=store.submit(session,"A",true); b=store.submit(session,"B",true);
            c=store.submit(session,"C\n\n---\n\nLiteral separator",true); d=store.submit(session,"D",true);
            var selected=Json.MAPPER.valueToTree(List.of(Json.object("id",c,"prompt","C\n\n---\n\nLiteral separator"),Json.object("id",a,"prompt","A")));
            assertEquals(store.mergeQueued(session,selected,"merge"),store.mergeQueued(session,selected,"merge"));
            assertEquals(List.of(a,b,d),store.queued(session).stream().map(SessionStore.Job::id).toList());
            assertEquals(2,store.queued(session).getFirst().partsCount());
        }
        try(var store=new SessionStore(db)) {
            String prompt=store.queued(session).getFirst().prompt();
            var result=store.splitQueued(session,a,prompt,"split"); assertEquals(result,store.splitQueued(session,a,prompt,"split"));
            assertEquals(List.of(a,b,c,d),store.queued(session).stream().map(SessionStore.Job::id).toList());
            assertEquals(1,store.queued(session).get(2).partsCount());
            assertThrows(IllegalStateException.class,()->store.splitQueued(session,b,"stale","bad"));
        }
    }
    @Test void appendedSubmissionsSplitWithoutTreatingUserSeparatorsAsBoundaries() throws Exception {
        try(var s=new SessionStore(root.resolve("db"))) {
            String id=s.create(s.project(root),null,"test",root).id(); String a=s.submit(id,"one\n\n---\n\ntwo",true);
            s.submit(id,"second",false); s.submit(id,"third",false);
            s.splitQueued(id,a,s.queued(id).getFirst().prompt(),"split");
            assertEquals(List.of("one\n\n---\n\ntwo","second","third"),s.queued(id).stream().map(SessionStore.Job::prompt).toList());
        }
    }
    @Test void seenVersionSurvivesRestartAndOldAcknowledgementDoesNotHideNewResult() throws Exception {
        String id; long first;
        try(var s=new SessionStore(root.resolve("db"))) {
            id=s.create(s.project(root),null,"test",root).id();
            assertFalse(s.attention(id).path("unread").asBoolean());
            s.finishRun(s.beginRun(id,Provider.CODEX),"COMPLETED"); first=s.attention(id).path("version").asLong();
            assertTrue(s.attention(id).path("unread").asBoolean()); s.seen(id,first); assertFalse(s.attention(id).path("unread").asBoolean());
        }
        try(var s=new SessionStore(root.resolve("db"))) {
            assertFalse(s.attention(id).path("unread").asBoolean());
            s.finishRun(s.beginRun(id,Provider.CODEX),"COMPLETED"); s.seen(id,first);
            assertTrue(s.attention(id).path("unread").asBoolean()); assertTrue(s.get(id).active());
        }
    }
    @Test void runningAndCancelledNewerRunsDoNotEraseUnreadResult() throws Exception {
        try(var s=new SessionStore(root.resolve("db"))) {
            String id=s.create(s.project(root),null,"test",root).id();
            String completed=s.beginRun(id,Provider.CODEX);s.finishRun(completed,"COMPLETED");
            long result=s.attention(id).path("version").asLong();
            String running=s.beginRun(id,Provider.CODEX);
            assertEquals(result,s.attention(id).path("version").asLong());
            assertTrue(s.attention(id).path("unread").asBoolean());
            s.finishRun(running,"CANCELLED");
            assertEquals(result,s.attention(id).path("version").asLong());
            assertTrue(s.attention(id).path("unread").asBoolean());
            s.seen(id,result);
            assertFalse(s.attention(id).path("unread").asBoolean());
        }
    }
}
