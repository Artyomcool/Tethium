package dev.tethium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SessionUpdateTest {
    @TempDir Path root;

    @Test void oneSourceRunGrowsOneMessageAndANewRunStartsAnother()throws Exception{
        try(var store=new SessionStore(root.resolve("db"))){
            var project=store.project(root);String target=store.create(project,null,"Target",root).id();
            store.appendSessionUpdate(target,"source:run-1","От «Lane 1»:\nПервый шаг","ignored");
            long sequence=store.lastSequence(target);assertEquals(0,store.messageRevision(target));
            store.appendSessionUpdate(target,"source:run-1","ignored","Второй шаг");
            store.appendSessionUpdate(target,"source:run-1","ignored","Третий шаг");
            var messages=store.messages(target,0,10);assertEquals(1,messages.size());assertEquals(sequence,messages.getFirst().sequence());
            assertEquals("От «Lane 1»:\nПервый шаг\n\nВторой шаг\n\nТретий шаг",messages.getFirst().text());assertEquals(2,store.messageRevision(target));
            store.appendSessionUpdate(target,"source:run-2","Результат сессии «Lane 1» (COMPLETED):\nГотово","ignored");
            assertEquals(2,store.messages(target,0,10).size());
        }
    }

    @Test void deletingTheGrowingMessageLetsTheSameRunStartFresh()throws Exception{
        try(var store=new SessionStore(root.resolve("db2"))){
            var project=store.project(root);String target=store.create(project,null,"Target",root).id();
            store.appendSessionUpdate(target,"source:run","От «Source»:\nA","B");long old=store.lastSequence(target);
            store.deleteMessages(target,java.util.List.of(old));store.appendSessionUpdate(target,"source:run","От «Source»:\nC","D");
            var messages=store.messages(target,0,10);assertEquals(1,messages.size());assertEquals("От «Source»:\nC",messages.getFirst().text());assertNotEquals(old,messages.getFirst().sequence());
        }
    }
}
