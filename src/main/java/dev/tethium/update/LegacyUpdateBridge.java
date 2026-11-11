package dev.tethium.update;

import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sqlite.SQLiteConnection;
import java.nio.file.*;
import java.sql.*;
import java.util.ArrayList;

/** One-time bridge for 0.2.1 hosts without a drain endpoint. Does not cancel any run. */
public final class LegacyUpdateBridge {
    private LegacyUpdateBridge() {}
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[1]).toRealPath(), destination = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(destination.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            if (args[0].equals("backup")) {
                if (Files.exists(destination)) throw new IllegalArgumentException("Backup destination already exists");
                // One step: a stepwise backup starts over whenever the running core writes, and the usage ledger writes every few
                // seconds, so on a large database it never finished. In WAL a single read snapshot does not hold up writers.
                int result = connection.unwrap(SQLiteConnection.class).getDatabase().backup("main", destination.toString(), null, 100, 100, -1);
                if (result != 0) throw new SQLException("SQLite backup failed: " + result);
                System.out.println("{\"ok\":true}"); return;
            }
            ObjectNode ledger = Files.exists(destination) ? (ObjectNode) Json.MAPPER.readTree(destination.toFile()) : Json.object("sessions", Json.object(), "restored", false);
            var resumed = new ArrayList<String>();
            if (ledger.path("restored").asBoolean()) { System.out.println("{\"resumed\":[]}"); return; }
            statement.execute("BEGIN IMMEDIATE");
            try {
                var entries = (ObjectNode) ledger.path("sessions");
                if (args[0].equals("pause")) {
                    try (var rows = statement.executeQuery("SELECT s.id,COALESCE(q.paused,0),r.id,r.status FROM sessions s LEFT JOIN queue_state q ON q.session_id=s.id LEFT JOIN runs r ON r.rowid=(SELECT MAX(rowid) FROM runs WHERE session_id=s.id)")) {
                        while (rows.next()) if (!entries.has(rows.getString(1))) entries.set(rows.getString(1), Json.object("paused", !ledger.path("initialized").asBoolean() && rows.getBoolean(2), "runId", rows.getString(3), "status", rows.getString(4)));
                    }
                    ledger.put("initialized", true);
                    write(destination, ledger); // Journal is durable before modifying queue flags.
                    statement.executeUpdate("INSERT INTO queue_state(session_id,paused) SELECT id,1 FROM sessions WHERE true ON CONFLICT(session_id) DO UPDATE SET paused=1");
                    statement.execute("CREATE TRIGGER IF NOT EXISTS tethium_hot_reload_pause AFTER INSERT ON jobs BEGIN INSERT INTO queue_state(session_id,paused) VALUES(NEW.session_id,1) ON CONFLICT(session_id) DO UPDATE SET paused=1; END");
                } else if (args[0].equals("restore")) {
                    statement.execute("DROP TRIGGER IF EXISTS tethium_hot_reload_pause");
                    var fields = entries.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next(); if (entry.getValue().path("paused").asBoolean()) continue;
                        boolean stoppedByUser = false;
                        try (var query = connection.prepareStatement("SELECT id,status FROM runs WHERE session_id=? ORDER BY rowid DESC LIMIT 1")) {
                            query.setString(1, entry.getKey());
                            try (var rows = query.executeQuery()) {
                                if (rows.next()) stoppedByUser = rows.getString(2).equals("CANCELLED") && (!rows.getString(1).equals(entry.getValue().path("runId").asText()) || entry.getValue().path("status").asText().equals("RUNNING"));
                            }
                        }
                        if (!stoppedByUser) {
                            try (var update = connection.prepareStatement("UPDATE queue_state SET paused=0 WHERE session_id=?")) { update.setString(1, entry.getKey()); update.executeUpdate(); }
                            resumed.add(entry.getKey());
                        }
                    }
                } else throw new IllegalArgumentException("Expected backup, pause or restore");
                statement.execute("COMMIT");
            } catch (Exception error) { statement.execute("ROLLBACK"); throw error; }
            if (args[0].equals("restore")) { ledger.put("restored", true); write(destination, ledger); }
            System.out.println(Json.object("resumed", resumed));
        }
    }
    private static void write(Path path, ObjectNode value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp"); Json.MAPPER.writeValue(temporary.toFile(), value);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
