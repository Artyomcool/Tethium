package dev.tethium.worker;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** A per-run durable mailbox. Only the worker writes events; hosts write replies and cancellation.
 * This database is deliberately separate from the host's database. */
public final class WorkerJournal implements AutoCloseable {
    public static final int PROTOCOL = 1;
    public record Entry(long sequence, String kind, JsonNode value) {}
    private final Connection connection;
    public WorkerJournal(Path directory) throws Exception {
        Files.createDirectories(directory);
        connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("journal.db").toAbsolutePath());
        try (var s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS events(sequence INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS replies(sequence INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS state(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }
    public synchronized long append(String kind, Object value) throws Exception {
        try (var q = connection.prepareStatement("INSERT INTO events(kind,payload) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
            q.setString(1, kind); q.setString(2, Json.MAPPER.writeValueAsString(value)); q.executeUpdate();
            try (var r = q.getGeneratedKeys()) { r.next(); return r.getLong(1); }
        }
    }
    public synchronized List<Entry> after(long sequence) throws Exception {
        var result = new ArrayList<Entry>();
        try (var q = connection.prepareStatement("SELECT sequence,kind,payload FROM events WHERE sequence>? ORDER BY sequence LIMIT 256")) {
            q.setLong(1, sequence); try (var r = q.executeQuery()) { while (r.next()) result.add(new Entry(r.getLong(1), r.getString(2), Json.parse(r.getString(3)))); }
        }
        return result;
    }
    public synchronized void reply(long sequence, JsonNode value) throws Exception {
        try (var q = connection.prepareStatement("INSERT OR IGNORE INTO replies(sequence,payload) VALUES(?,?)")) {
            q.setLong(1, sequence); q.setString(2, value.toString()); q.executeUpdate();
        }
    }
    public synchronized JsonNode reply(long sequence) throws Exception {
        try (var q = connection.prepareStatement("SELECT payload FROM replies WHERE sequence=?")) {
            q.setLong(1, sequence); try (var r = q.executeQuery()) { return r.next() ? Json.parse(r.getString(1)) : null; }
        }
    }
    public synchronized void state(String key, String value) throws SQLException {
        try (var q = connection.prepareStatement("INSERT INTO state(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            q.setString(1,key); q.setString(2,value); q.executeUpdate();
        }
    }
    public synchronized String state(String key) throws SQLException {
        try (var q = connection.prepareStatement("SELECT value FROM state WHERE key=?")) {
            q.setString(1,key); try (var r = q.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }
    public synchronized boolean alive() throws SQLException {
        String pid = state("pid"), started = state("processStart");
        if (pid == null || started == null) return false;
        return ProcessHandle.of(Long.parseLong(pid)).filter(ProcessHandle::isAlive)
                .flatMap(p -> p.info().startInstant()).map(t -> t.toString().equals(started)).orElse(false);
    }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
