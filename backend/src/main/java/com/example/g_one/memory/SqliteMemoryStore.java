package com.example.g_one.memory;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.sqlite.SQLiteConfig;

/**
 * A {@link MemoryStore} in one SQLite file ({@code <dataDir>/g-one.db}, ADR 0004). Same behaviour as
 * {@link com.example.g_one.core.InMemoryMemoryStore}: said beats inferred, gists newest call first, and forget leaves a
 * tombstone (the forgetting call ids and the latest forget time) that is kept for good, so late writes stay dropped
 * after a restart too.
 *
 * <p>Only {@link CallerKey}s, call ids and the farmer's own words are stored; never a phone number.
 *
 * <p>Thread safety: one connection, and every public method is {@code synchronized} and runs as one transaction. So
 * each write and each forget is atomic, and a write cannot slip between a forget's delete and its tombstone. Memory
 * traffic is a few reads and writes per call, so one connection is plenty.
 *
 * <p>The connection stays in autocommit mode between operations and holds no lock while idle. Each write runs in its
 * own {@code BEGIN IMMEDIATE ... COMMIT} (it takes the write lock up front, so it never fails half-way on a lock
 * upgrade) and each read in a plain {@code BEGIN ... COMMIT} (one consistent snapshot). With WAL and a busy timeout, a
 * second store or process on the same file (a second test context, say) just waits its turn. (sqlite-jdbc's own
 * {@code IMMEDIATE} transaction mode with autocommit off starts the next immediate transaction right after every
 * commit, so an idle store would keep the write lock and block everyone else.)
 */
public final class SqliteMemoryStore implements MemoryStore, AutoCloseable {
    public static final String FILE_NAME = "g-one.db";

    /** Newest call first; the call id breaks ties, the same order as the in-memory store. */
    private static final Comparator<CallGist> NEWEST_CALL_FIRST = Comparator
            .comparingLong(CallGist::startedMs).reversed()
            .thenComparing(g -> g.callId().value());
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS profile_facts (
            caller_key TEXT NOT NULL,
            field      TEXT NOT NULL,
            value      TEXT NOT NULL,
            call_id    TEXT NOT NULL,
            origin     TEXT NOT NULL CHECK (origin IN ('said', 'inferred')),
            at_ms      INTEGER NOT NULL,
            PRIMARY KEY (caller_key, field)
        )""",
        """
        CREATE TABLE IF NOT EXISTS call_gists (
            caller_key TEXT NOT NULL,
            call_id    TEXT NOT NULL,
            started_ms INTEGER NOT NULL,
            ended_ms   INTEGER NOT NULL,
            summary    TEXT NOT NULL,
            follow_ups TEXT NOT NULL,
            PRIMARY KEY (caller_key, call_id)
        )""",
        // Tombstones: the calls a farmer asked to be forgotten during, and the latest forget time per key.
        """
        CREATE TABLE IF NOT EXISTS forget_calls (
            caller_key TEXT NOT NULL,
            call_id    TEXT NOT NULL,
            PRIMARY KEY (caller_key, call_id)
        )""",
        """
        CREATE TABLE IF NOT EXISTS forget_marks (
            caller_key TEXT PRIMARY KEY,
            at_ms      INTEGER NOT NULL
        )""",
        "PRAGMA user_version = 1",
    };

    private final Connection conn;
    private final ObjectMapper json = new ObjectMapper();

    static final String GITIGNORE = "# Farmer memory database (synthetic in this repo, but never committed).\n*\n";
    /** The database file and the files SQLite keeps beside it. */
    static final List<String> DB_FILES =
            List.of(FILE_NAME, FILE_NAME + "-wal", FILE_NAME + "-shm", FILE_NAME + "-journal");
    static final String GITIGNORE_TRAILER = "# Farmer memory database: never committed (keep these lines last).\n"
            + String.join("\n", DB_FILES) + "\n";

    /**
     * Opens (or creates) {@code <dataDir>/g-one.db} and its schema. The data directory always ends up with a
     * {@code .gitignore} that ignores the database and its {@code -wal}/{@code -shm}/{@code -journal} files, so they
     * are never committed by accident: a missing file is written as {@code *}; an existing file whose last rules do
     * not already ignore them (no {@code *} or explicit database lines after its last {@code !} negation, since in git
     * the last matching rule wins) gets the explicit lines appended at the end, keeping its own lines. The root
     * {@code .gitignore} also ignores the default {@code backend/data/}.
     */
    public static SqliteMemoryStore open(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");
        try {
            Files.createDirectories(dataDir);
            Path ignore = dataDir.resolve(".gitignore");
            if (!Files.exists(ignore)) {
                Files.writeString(ignore, GITIGNORE, StandardCharsets.UTF_8);
            } else {
                String existing = Files.readString(ignore, StandardCharsets.UTF_8);
                if (!ignoresDatabaseLast(existing)) {
                    String gap = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
                    Files.writeString(ignore, gap + GITIGNORE_TRAILER, StandardCharsets.UTF_8,
                            StandardOpenOption.APPEND);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the memory data directory", e);
        }
        return new SqliteMemoryStore(dataDir.resolve(FILE_NAME));
    }

    /**
     * True when, after the file's last negation ({@code !...}) rule, a {@code *} line or an explicit line for every
     * database file follows, so git's last-match-wins rule leaves every database file ignored.
     */
    static boolean ignoresDatabaseLast(String gitignore) {
        List<String> rules = gitignore.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        int lastNegation = -1;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).startsWith("!")) {
                lastNegation = i;
            }
        }
        List<String> after = rules.subList(lastNegation + 1, rules.size()).stream()
                .map(l -> l.startsWith("/") ? l.substring(1) : l)
                .toList();
        return after.contains("*") || after.containsAll(DB_FILES);
    }

    private SqliteMemoryStore(Path file) {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setBusyTimeout(5_000);
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        try {
            conn = cfg.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the memory database", e);
        }
        writeTx(() -> {
            for (String sql : SCHEMA) {
                exec(sql);
            }
            return null;
        });
    }

    @Override
    public synchronized Optional<FarmerMemory> load(CallerKey key) {
        Objects.requireNonNull(key, "key");
        return readTx(() -> {
            List<ProfileFact> facts = facts(key);
            List<CallGist> gists = gists(key);
            if (facts.isEmpty() && gists.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(FarmerMemory.of(key, facts, gists));
        });
    }

    @Override
    public synchronized void saveGist(CallerKey key, CallGist gist) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(gist, "gist");
        String followUps = toJson(gist.followUps());
        writeTx(() -> {
            if (covered(key, gist.callId(), gist.startedMs())) {
                return null;
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO call_gists (caller_key, call_id, started_ms, ended_ms, summary, follow_ups)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (caller_key, call_id) DO UPDATE SET
                        started_ms = excluded.started_ms, ended_ms = excluded.ended_ms,
                        summary = excluded.summary, follow_ups = excluded.follow_ups""")) {
                ps.setString(1, key.value());
                ps.setString(2, gist.callId().value());
                ps.setLong(3, gist.startedMs());
                ps.setLong(4, gist.endedMs());
                ps.setString(5, gist.summary());
                ps.setString(6, followUps);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public synchronized void upsertFacts(CallerKey key, List<ProfileFact> facts) {
        Objects.requireNonNull(key, "key");
        List<ProfileFact> incoming = List.copyOf(Objects.requireNonNull(facts, "facts"));
        if (incoming.isEmpty()) {
            return;
        }
        writeTx(() -> {
            for (ProfileFact f : incoming) {
                if (covered(key, f.provenance().callId(), f.provenance().atMs())) {
                    continue;
                }
                Optional<ProfileFact> stored = fact(key, f.field());
                if (stored.isEmpty()) {
                    insertFact(key, f);
                } else if (replaces(f, stored.get())) {
                    // UPDATE keeps the row id, so the field keeps its place in the profile, like the in-memory store.
                    updateFact(key, f);
                }
            }
            return null;
        });
    }

    @Override
    public synchronized boolean forget(CallerKey key, CallId duringCall, long atMs) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(duringCall, "duringCall");
        return writeTx(() -> {
            int deleted = update("DELETE FROM profile_facts WHERE caller_key = ?", key.value())
                    + update("DELETE FROM call_gists WHERE caller_key = ?", key.value());
            update("INSERT OR IGNORE INTO forget_calls (caller_key, call_id) VALUES (?, ?)",
                    key.value(), duringCall.value());
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO forget_marks (caller_key, at_ms) VALUES (?, ?)
                    ON CONFLICT (caller_key) DO UPDATE SET at_ms = max(at_ms, excluded.at_ms)""")) {
                ps.setString(1, key.value());
                ps.setLong(2, atMs);
                ps.executeUpdate();
            }
            return deleted > 0;
        });
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close the memory database", e);
        }
    }

    /** Said beats inferred; within one origin the newer time wins, and a tie goes to the incoming fact. */
    static boolean replaces(ProfileFact incoming, ProfileFact stored) {
        Provenance.Origin in = incoming.provenance().saidOrInferred();
        Provenance.Origin was = stored.provenance().saidOrInferred();
        if (in != was) {
            return in == Provenance.Origin.SAID;
        }
        return incoming.provenance().atMs() >= stored.provenance().atMs();
    }

    // --- helpers; all run inside writeTx() or readTx(), under the store's monitor ---

    private interface Work<T> {
        T run() throws SQLException;
    }

    /** A write: takes the write lock up front ({@code BEGIN IMMEDIATE}) and releases it at commit or rollback. */
    private <T> T writeTx(Work<T> work) {
        return tx("BEGIN IMMEDIATE", work);
    }

    /** A read: one snapshot, no write lock. */
    private <T> T readTx(Work<T> work) {
        return tx("BEGIN", work);
    }

    private <T> T tx(String begin, Work<T> work) {
        boolean begun = false;
        try {
            exec(begin);
            begun = true;
            T result = work.run();
            exec("COMMIT");
            return result;
        } catch (SQLException | RuntimeException e) {
            if (begun) {
                try {
                    exec("ROLLBACK");
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
            }
            // The message never carries the key or the data.
            throw new IllegalStateException("Memory store operation failed", e);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** True when a forget tombstone drops a write from this call stamped at this time. */
    private boolean covered(CallerKey key, CallId call, long timeMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM forget_calls WHERE caller_key = ? AND call_id = ?)
                    OR EXISTS (SELECT 1 FROM forget_marks WHERE caller_key = ? AND ? <= at_ms)""")) {
            ps.setString(1, key.value());
            ps.setString(2, call.value());
            ps.setString(3, key.value());
            ps.setLong(4, timeMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private List<ProfileFact> facts(CallerKey key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT field, value, call_id, origin, at_ms FROM profile_facts
                WHERE caller_key = ? ORDER BY rowid""")) {
            ps.setString(1, key.value());
            try (ResultSet rs = ps.executeQuery()) {
                List<ProfileFact> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(factFrom(rs));
                }
                return out;
            }
        }
    }

    private Optional<ProfileFact> fact(CallerKey key, String field) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT field, value, call_id, origin, at_ms FROM profile_facts
                WHERE caller_key = ? AND field = ?""")) {
            ps.setString(1, key.value());
            ps.setString(2, field);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(factFrom(rs)) : Optional.empty();
            }
        }
    }

    private static ProfileFact factFrom(ResultSet rs) throws SQLException {
        Provenance.Origin origin = Provenance.Origin.valueOf(rs.getString("origin").toUpperCase(Locale.ROOT));
        return new ProfileFact(rs.getString("field"), rs.getString("value"),
                new Provenance(CallId.of(rs.getString("call_id")), origin, rs.getLong("at_ms")));
    }

    private void insertFact(CallerKey key, ProfileFact f) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO profile_facts (value, call_id, origin, at_ms, caller_key, field)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            bindFact(ps, key, f);
            ps.executeUpdate();
        }
    }

    private void updateFact(CallerKey key, ProfileFact f) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE profile_facts SET value = ?, call_id = ?, origin = ?, at_ms = ?
                WHERE caller_key = ? AND field = ?""")) {
            bindFact(ps, key, f);
            ps.executeUpdate();
        }
    }

    private static void bindFact(PreparedStatement ps, CallerKey key, ProfileFact f) throws SQLException {
        ps.setString(1, f.value());
        ps.setString(2, f.provenance().callId().value());
        ps.setString(3, f.provenance().saidOrInferred().wire());
        ps.setLong(4, f.provenance().atMs());
        ps.setString(5, key.value());
        ps.setString(6, f.field());
    }

    private List<CallGist> gists(CallerKey key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT call_id, started_ms, ended_ms, summary, follow_ups FROM call_gists WHERE caller_key = ?""")) {
            ps.setString(1, key.value());
            try (ResultSet rs = ps.executeQuery()) {
                List<CallGist> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new CallGist(CallId.of(rs.getString("call_id")), rs.getLong("started_ms"),
                            rs.getLong("ended_ms"), rs.getString("summary"), fromJson(rs.getString("follow_ups"))));
                }
                // Sorted here, not in SQL, so ties order exactly like the in-memory store (Java string order).
                out.sort(NEWEST_CALL_FIRST);
                return out;
            }
        }
    }

    private int update(String sql, String... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private String toJson(List<String> followUps) {
        try {
            return json.writeValueAsString(followUps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode follow-ups", e);
        }
    }

    private List<String> fromJson(String text) throws SQLException {
        try {
            return json.readValue(text, STRINGS);
        } catch (JsonProcessingException e) {
            throw new SQLException("Stored follow-ups are not a JSON list", e);
        }
    }
}
