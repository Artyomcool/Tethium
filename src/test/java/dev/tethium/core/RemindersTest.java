package dev.tethium.core;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RemindersTest {
    @TempDir Path dir;
    private static final long MIN=60_000;

    @Test void firesAtItsTimeAndStaysUntilAnswered()throws Exception{
        var changes=new AtomicInteger();long now=1_800_000_000_000L;
        try(var reminders=new Reminders(dir.resolve("t.db"),changes::incrementAndGet,ZoneId.of("Europe/Moscow"))){
            var added=reminders.add("s","p","Позвонить маме",now+120*MIN,now);
            String id=added.path("id").asText();
            assertFalse(added.path("firing").asBoolean());
            assertEquals(0,reminders.tick(now+119*MIN));
            assertEquals(1,reminders.tick(now+120*MIN));
            assertEquals(0,reminders.tick(now+121*MIN),"a fired reminder is reported once");
            // Still active and firing hours later: nothing but an answer removes it.
            var row=reminders.active(now+600*MIN).getFirst();
            assertTrue(row.path("firing").asBoolean());assertEquals("active",row.path("state").asText());

            long answered=now+130*MIN;
            var snoozed=reminders.snooze(id,10,answered,now+120*MIN,"phone",answered);
            assertEquals(answered+10*MIN,snoozed.path("reminder").path("dueAt").asLong());
            assertEquals(1,snoozed.path("reminder").path("snoozes").asInt());
            assertFalse(snoozed.path("reminder").path("firing").asBoolean());
            assertEquals(1,reminders.tick(answered+10*MIN),"a snoozed reminder fires again");

            assertTrue(reminders.done(id,"desktop",answered+11*MIN).path("ok").asBoolean());
            assertTrue(reminders.active(answered+11*MIN).isEmpty());
            assertTrue(changes.get()>=5);
        }
    }

    @Test void onlyFiveAnswersAndStaleAnswersAreIgnored()throws Exception{
        long now=1_800_000_000_000L;
        try(var reminders=new Reminders(dir.resolve("t.db"),null,ZoneId.of("UTC"))){
            String id=reminders.add("s","p","Купить молоко",now,now).path("id").asText();
            assertThrows(IllegalArgumentException.class,()->reminders.snooze(id,15,now,null,"x",now));
            for(int minutes:Reminders.SNOOZE_MINUTES)assertDoesNotThrow(()->reminders.answer(id,"snooze",Json.object("minutes",minutes),now));
            long due=reminders.row(id,now).path("dueAt").asLong();assertEquals(now+1440*MIN,due);
            // A phone that answered offline for an older firing does not move a reminder another device already moved.
            var stale=reminders.snooze(id,60,now,now-5*MIN,"phone",now+MIN);
            assertTrue(stale.path("ignored").asBoolean());assertEquals(due,reminders.row(id,now).path("dueAt").asLong());
            // A device clock far ahead does not push the reminder further than the snooze asked for.
            var ahead=reminders.snooze(id,10,now+3_600_000_000L,due,"phone",now);
            assertEquals(now+10*MIN,ahead.path("reminder").path("dueAt").asLong());
            assertThrows(IllegalArgumentException.class,()->reminders.answer(id,"dismiss",Json.object(),now));
            assertTrue(reminders.answer(id,"remove",Json.object(),now).path("removed").asBoolean());
            assertTrue(reminders.answer(id,"done",Json.object(),now).path("ignored").asBoolean());
        }
    }

    /** A tool call is answered inside a store transaction; the reminder it writes must share that transaction, not race it. */
    @Test void sharesTheStoreTransaction()throws Exception{
        long now=System.currentTimeMillis();
        try(var store=new SessionStore(dir.resolve("s.db"))){
            var reminders=new Reminders(store,null);
            String session=store.create(store.project(dir),null,"S",dir).id();
            assertThrows(IllegalStateException.class,()->store.transaction(()->{reminders.add(session,"p","Откатится",now+MIN,now);store.rename(session,"T");throw new IllegalStateException("rollback");}));
            assertTrue(reminders.active(now).isEmpty());
            store.transaction(()->{reminders.add(session,"p","Останется",now+MIN,now);store.rename(session,"T");return null;});
            assertEquals(1,reminders.active(now).size());
            assertTrue(reminders.active(now).getFirst().path("due").asText().matches("\\d+ [а-я]+ \\d{4}, \\d\\d:\\d\\d, [а-я]+"),reminders.active(now).getFirst().path("due").asText());
        }
    }

    @Test void survivesReopenAndParsesAgentTimes()throws Exception{
        long now=1_800_000_000_000L;var zone=ZoneId.of("Europe/Moscow");
        try(var reminders=new Reminders(dir.resolve("t.db"),null,zone)){
            reminders.add("s","p","Завтра",reminders.dueAt(Json.object("at","2027-01-16T09:00"),now),now);
            assertEquals(now+120*MIN,reminders.dueAt(Json.object("delayMinutes",120),now));
            assertEquals(java.time.OffsetDateTime.parse("2027-01-16T09:00+03:00").toInstant().toEpochMilli(),reminders.dueAt(Json.object("at","2027-01-16T06:00Z"),now));
            assertThrows(IllegalArgumentException.class,()->reminders.dueAt(Json.object(),now));
            assertThrows(IllegalArgumentException.class,()->reminders.dueAt(Json.object("delayMinutes",1,"at","2027-01-16T09:00"),now));
            assertThrows(IllegalArgumentException.class,()->reminders.add("s","p"," ",now,now));
            assertThrows(IllegalArgumentException.class,()->reminders.add("s","p","Прошлое",now-3600_000,now));
        }
        try(var reopened=new Reminders(dir.resolve("t.db"),null,zone)){
            var rows=reopened.active(now);assertEquals(1,rows.size());assertEquals("Завтра",rows.getFirst().path("text").asText());
            assertTrue(rows.getFirst().path("due").asText().contains("09:00"));
        }
    }
}
