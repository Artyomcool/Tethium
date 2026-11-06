package dev.tethium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class UsageCpuTotalsTest {
    @TempDir Path dir;
    @Test void sumsPerSessionAndStartAnewAtReset() throws Exception {
        try (var ledger = new UsageLedger(dir.resolve("usage.db"))) {
            ledger.cpuAdd("a", "p", 50); ledger.cpuAdd("a", "p", 25); ledger.cpuAdd("b", "q", 25);
            var all = ledger.report("all", "", "").path("cpu");
            assertEquals(100, all.path("totalNanos").asLong());
            assertEquals(75, all.path("sessions").path("a").asLong());
            assertEquals(25, all.path("projects").path("q").asLong());
            assertEquals(75, ledger.report("all", "p", "a").path("cpu").path("nanos").asLong());
            Thread.sleep(2);
            ledger.resetBoundary("manual");
            assertEquals(0, ledger.report("since-reset", "", "").path("cpu").path("totalNanos").asLong());
            ledger.cpuAdd("a", "p", 7);
            assertEquals(7, ledger.report("since-reset", "", "").path("cpu").path("totalNanos").asLong());
            assertEquals(107, ledger.report("all", "", "").path("cpu").path("totalNanos").asLong());
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode tokens(long total) { return dev.tethium.json.Json.object("totalTokens", total, "inputTokens", total, "cachedInputTokens", 0, "outputTokens", 0, "reasoningOutputTokens", 0); }
    @Test void tokensAreRunningSumsVisibleAtOnce() throws Exception {
        try (var ledger = new UsageLedger(dir.resolve("usage.db"))) {
            long now = System.currentTimeMillis();
            ledger.cpuAdd("a", "p", 10); ledger.sample("a", "p", "t", now, tokens(100), tokens(100), "claude");
            ledger.cpuAdd("a", "p", 20); ledger.sample("a", "p", "t", now + 1, tokens(130), tokens(30), "claude"); ledger.sample("b", "q", "u", now + 2, tokens(5), tokens(5));
            var at = ledger.sidebar();
            assertEquals(30, at.path("cpu").path("totalNanos").asLong());
            assertEquals(135, at.path("total").asLong());
            assertEquals(130, at.path("executors").path("claude").path("sessions").path("a").asLong());
            assertEquals(5, at.path("executors").path("codex").path("total").asLong());
            var full = ledger.report("since-reset", "", "");
            assertEquals(full.path("total"), at.path("total")); assertEquals(full.path("projects"), at.path("projects")); assertEquals(full.path("sessions"), at.path("sessions")); assertEquals(full.path("executors"), at.path("executors"));
        }
    }
    @Test void onlyGrowthCountsAndResetStartsANewSum() throws Exception {
        Path db = dir.resolve("usage.db");
        try (var ledger = new UsageLedger(db)) {
            long now = System.currentTimeMillis();
            ledger.sample("a", "p", "t", now, tokens(100), tokens(40), "claude");
            assertEquals(40, ledger.sidebar().path("total").asLong(), "what came before the first sight of a thread is not ours");
            ledger.sample("a", "p", "t", now + 1, tokens(130), tokens(30), "claude");
            assertEquals(70, ledger.sidebar().path("total").asLong());
            ledger.sample("a", "p", "t", now - 5, tokens(50), tokens(10), "claude");
            ledger.sample("a", "p", "t", now + 1, tokens(130), tokens(30), "claude");
            assertEquals(70, ledger.sidebar().path("total").asLong(), "late and repeated samples are already in the sum");
            ledger.sample("a", "p", "t", now + 2, tokens(150), tokens(20), "codex");
            assertEquals(70, ledger.sidebar().path("executors").path("claude").path("total").asLong(), "another executor takes only new tokens");
            assertEquals(20, ledger.sidebar().path("executors").path("codex").path("total").asLong());
            Thread.sleep(2); ledger.resetBoundary("manual");
            assertEquals(0, ledger.sidebar().path("total").asLong());
            ledger.sample("b", "q", "old", now - 3_000_000, tokens(9), tokens(9));
            assertEquals(0, ledger.sidebar().path("total").asLong(), "an old date goes to the past, not to this period");
            ledger.sample("a", "p", "t", System.currentTimeMillis() + 1, tokens(170), tokens(20), "codex"); ledger.cpuAdd("a", "p", 3);
            assertEquals(20, ledger.sidebar().path("total").asLong());
            assertEquals(3, ledger.sidebar().path("cpu").path("totalNanos").asLong());
        }
        try (var ledger = new UsageLedger(db)) { assertEquals(20, ledger.sidebar().path("total").asLong(), "the sum survives reopening without any count"); }
    }
    @Test void existingSamplesAreSummedOnce() throws Exception {
        Path db = dir.resolve("old.db");
        long now = System.currentTimeMillis();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
            s.execute("CREATE TABLE token_samples(thread TEXT NOT NULL,counter INTEGER NOT NULL,session_id TEXT NOT NULL,project_id TEXT NOT NULL,at INTEGER NOT NULL,input INTEGER NOT NULL,cached INTEGER NOT NULL,output INTEGER NOT NULL,reasoning INTEGER NOT NULL,baseline INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(thread,counter))");
            s.execute("CREATE TABLE token_executors(thread TEXT PRIMARY KEY,executor TEXT NOT NULL)");
            s.execute("INSERT INTO token_executors VALUES('t','claude')");
            s.execute("INSERT INTO token_samples VALUES('t',60,'a','p'," + (now - 3) + ",50,5,10,1,1),('t',100,'a','p'," + (now - 2) + ",80,6,20,2,0),('u',7,'b','q'," + (now - 1) + ",7,0,0,0,0)");
        }
        for (int open = 0; open < 2; open++) try (var ledger = new UsageLedger(db)) {
            var at = ledger.sidebar(); var full = ledger.report("since-reset", "", "");
            assertEquals(47, full.path("total").asLong(), "reopening does not add it again");
            assertEquals(46, at.path("total").asLong(), "the bars leave out the cache");
            assertEquals(37, full.path("input").asLong()); assertEquals(1, full.path("cached").asLong()); assertEquals(10, full.path("output").asLong()); assertEquals(1, full.path("reasoning").asLong());
        }
        try (var ledger = new UsageLedger(db)) {
            ledger.sample("a", "p", "t", now, tokens(110), tokens(10), "claude");
            assertEquals(57, ledger.report("since-reset", "", "").path("total").asLong(), "counting goes on from where the samples ended");
        }
    }
}
