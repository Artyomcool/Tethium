package dev.tethium.core;

import dev.tethium.agent.*;
import dev.tethium.agent.AgentAdapter.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Reports of commands stay in the dialog however a run ends, and never go back to an agent. */
class ActionLinesTest {
    @TempDir Path dir;
    private static final String M = ActionLines.MARK;
    private static Event started(String command) { return new Event("item/started", "", Json.object("method", "item/started", "params", Json.object("item", Json.object("id", command, "type", "commandExecution", "command", command)))); }
    private static Event text(String value) { return new Event("text", value, Json.object()); }
    private static AgentAdapter agent(Provider provider, java.util.function.Function<Request, Result> end, Event... events) {
        return new AgentAdapter() {
            public Provider provider() { return provider; }
            public Result run(Request r, Consumer<Event> sink, Cancellation c) { for (var e : events) sink.accept(e); return end.apply(r); }
        };
    }
    private static String lastAssistant(SessionStore store, String session) throws Exception {
        String last = null; for (var m : store.messages(session, 0, 100)) if (m.role().equals("assistant")) last = m.text(); return last;
    }
    @Test void aFinishedRunKeepsReportsInOrder() throws Exception {
        try (var store = new SessionStore(dir.resolve("t.db"))) {
            Path root = Files.createDirectories(dir.resolve("r")); var s = store.create(store.project(root), null, "s", root);
            new SessionRunner(store).run(s.id(), agent(Provider.CLAUDE, r -> new Result("c", Status.COMPLETED, "Смотрю.\n\nГотово."), text("Смотрю."), started("a"), started("b"), text("Готово.")), "go", null, null, e -> {}, new Cancellation());
            assertEquals("Смотрю.\n\n" + M + "Команда: a  \n" + M + "Команда: b\n\nГотово.", lastAssistant(store, s.id()));
        }
    }
    @Test void anInterruptedRunKeepsThemToo() throws Exception {
        try (var store = new SessionStore(dir.resolve("t.db"))) {
            Path root = Files.createDirectories(dir.resolve("r")); var s = store.create(store.project(root), null, "s", root);
            new SessionRunner(store).run(s.id(), agent(Provider.CLAUDE, r -> new Result("c", Status.CANCELLED, "Начал"), text("Начал"), started("a")), "go", null, null, e -> {}, new Cancellation());
            assertEquals("Начал\n\n" + M + "Команда: a", lastAssistant(store, s.id()), "Stop or Ctrl+Enter with partial text");
            new SessionRunner(store).run(s.id(), agent(Provider.CLAUDE, r -> new Result("c", Status.CANCELLED, ""), started("b")), "go", null, null, e -> {}, new Cancellation());
            assertEquals(M + "Команда: b", lastAssistant(store, s.id()), "nothing but commands yet");
            var failing = new AgentAdapter() {
                public Provider provider() { return Provider.CLAUDE; }
                public Result run(Request r, Consumer<Event> sink, Cancellation c) throws Exception { sink.accept(started("c")); throw new IllegalStateException("killed"); }
            };
            assertThrows(Exception.class, () -> new SessionRunner(store).run(s.id(), failing, "go", null, null, e -> {}, new Cancellation()));
            assertEquals(M + "Команда: c", lastAssistant(store, s.id()), "the run died");
        }
    }
    @Test void anotherExecutorNeverReadsThem() throws Exception {
        try (var store = new SessionStore(dir.resolve("t.db"))) {
            Path root = Files.createDirectories(dir.resolve("r")); var s = store.create(store.project(root), null, "s", root);
            new SessionRunner(store).run(s.id(), agent(Provider.CLAUDE, r -> new Result("c", Status.COMPLETED, "Первый ответ"), text("Первый ответ"), started("secret-command")), "go", null, null, e -> {}, new Cancellation());
            new SessionRunner(store).run(s.id(), agent(Provider.CLAUDE, r -> new Result("c", Status.CANCELLED, ""), started("only-a-command")), "go", null, null, e -> {}, new Cancellation());
            String[] prompt = {""};
            new SessionRunner(store).run(s.id(), agent(Provider.CODEX, r -> { prompt[0] = r.prompt(); return new Result("x", Status.COMPLETED, "ok"); }), "next", null, null, e -> {}, new Cancellation());
            assertTrue(prompt[0].contains("Первый ответ"), prompt[0]);
            assertFalse(prompt[0].contains("secret-command") || prompt[0].contains("only-a-command") || prompt[0].contains(M), prompt[0]);
        }
    }
    @Test void aRunWithoutReportsStoresExactlyTheResult() throws Exception {
        try (var store = new SessionStore(dir.resolve("t.db"))) {
            Path root = Files.createDirectories(dir.resolve("r")); var s = store.create(store.project(root), null, "s", root);
            new SessionRunner(store).run(s.id(), agent(Provider.CODEX, r -> new Result("c", Status.COMPLETED, "Итог"), text("Комментарий"), text("Итог")), "go", null, null, e -> {}, new Cancellation());
            assertEquals("Итог", lastAssistant(store, s.id()));
        }
    }
    private static Event reasoning(String method) { return new Event(method, "", Json.object("method", method, "params", Json.object("item", Json.object("id", "t", "type", "reasoning")))); }
    private static Event thought(String value) { return new Event("thinking", value, Json.object()); }
    @Test void thoughtsStayLikeReportsAndNeverGoBackToAnAgent() throws Exception {
        try (var store = new SessionStore(dir.resolve("t.db"))) {
            Path root = Files.createDirectories(dir.resolve("r")); var s = store.create(store.project(root), null, "s", root);
            new SessionRunner(store).run(s.id(), agent(Provider.CLAUDE, r -> new Result("c", Status.COMPLETED, "Готово."), reasoning("item/started"), thought("Думаю про тесты"), reasoning("item/completed"), started("a"), text("Готово.")), "go", null, null, e -> {}, new Cancellation());
            String kept = lastAssistant(store, s.id());
            assertEquals("```thought\nДумаю про тесты\n```\n\n\n\n" + M + "Команда: a\n\nГотово.", kept);
            new SessionRunner(store).run(s.id(), agent(Provider.CLAUDE, r -> new Result("c", Status.CANCELLED, ""), reasoning("item/started"), thought("Оборванная мысль")), "go", null, null, e -> {}, new Cancellation());
            assertEquals("```thought\nОборванная мысль\n```", lastAssistant(store, s.id()), "a thought cut off by an interruption is closed");
            new SessionRunner(store).run(s.id(), agent(Provider.CLAUDE, r -> new Result("c", Status.COMPLETED, "Текст"), reasoning("item/started"), thought("   "), reasoning("item/completed"), text("Текст")), "go", null, null, e -> {}, new Cancellation());
            assertEquals("Текст", lastAssistant(store, s.id()), "an empty thought leaves nothing");
            String[] prompt = {""};
            new SessionRunner(store).run(s.id(), agent(Provider.CODEX, r -> { prompt[0] = r.prompt(); return new Result("x", Status.COMPLETED, "ok"); }), "next", null, null, e -> {}, new Cancellation());
            assertTrue(prompt[0].contains("Готово."), prompt[0]);
            assertFalse(prompt[0].contains("Думаю про тесты") || prompt[0].contains("Оборванная") || prompt[0].contains("thought"), prompt[0]);
        }
    }
    @Test void mergingAndStripping() {
        String shown = "Смотрю.\n\n" + M + "Команда: a  \n" + M + "Команда: b\n\nГотово.";
        assertEquals("Смотрю.\n\nГотово.", ActionLines.strip(shown));
        assertEquals("— обычная реплика", ActionLines.strip("— обычная реплика"), "a dash of the agent's own is text");
        assertEquals(shown, ActionLines.merge(shown, "Смотрю. Готово."), "only whitespace differs");
        assertEquals("\n\n" + M + "x", ActionLines.piece(false, "x")); assertEquals("  \n" + M + "x", ActionLines.piece(true, "x"));
        assertEquals(M + "Команда: a\n\nИтог", ActionLines.merge("\n\n" + M + "Команда: a", "Итог"), "a result the output lacks is added");
        assertEquals("Итог", ActionLines.merge("Комментарий", "Итог"));
        assertEquals("До\n\nПосле", ActionLines.strip("До\n\n```thought\nмысль\n```\n\nПосле"));
        assertEquals("До", ActionLines.strip("До\n\n```thought\nбез конца"));
    }
}
