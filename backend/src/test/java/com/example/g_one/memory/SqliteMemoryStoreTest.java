package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SQLite against the shared contract, plus what only a file store has: restart persistence and the bytes on disk. */
class SqliteMemoryStoreTest extends MemoryStoreContract {
    private static final String PHONE = "+15005550006";

    @TempDir
    Path tmp;
    private final AtomicInteger dirs = new AtomicInteger();

    @Override
    protected MemoryStore newStore() {
        return SqliteMemoryStore.open(tmp.resolve("store-" + dirs.incrementAndGet()));
    }

    @Override
    protected int raceRounds() {
        return 10;
    }

    @Test
    void memorySurvivesARestart() {
        Path dir = tmp.resolve("restart");
        ProfileFact name = new ProfileFact("name", "Suresh", Provenance.said(CallId.of("c-1028"), 10));
        ProfileFact crop = new ProfileFact("crop:red onion", "red onion", Provenance.inferred(CallId.of("c-1103"), 20));
        CallGist gist = new CallGist(CallId.of("c-1103"), 1_000, 2_000, "Asked the red onion price.",
                List.of("Find a buyer for ~20 quintal red onion (due ~2026-11-08)"));
        try (SqliteMemoryStore first = SqliteMemoryStore.open(dir)) {
            first.upsertFacts(SURESH, List.of(name, crop));
            first.saveGist(SURESH, gist);
        }

        try (SqliteMemoryStore second = SqliteMemoryStore.open(dir)) {
            FarmerMemory m = second.load(SURESH).orElseThrow();
            assertThat(m.facts()).containsExactly(name, crop);
            assertThat(m.gists()).containsExactly(gist);
            // Precedence still applies to what was stored before the restart.
            second.upsertFacts(SURESH, List.of(new ProfileFact("name", "Ramesh",
                    Provenance.inferred(CallId.of("c-2000"), 99))));
            assertThat(second.load(SURESH).orElseThrow().fact("name").orElseThrow()).isEqualTo(name);
        }
    }

    @Test
    void aForgetTombstoneSurvivesARestart() {
        Path dir = tmp.resolve("tombstone");
        CallId during = CallId.of("call-forget");
        try (SqliteMemoryStore first = SqliteMemoryStore.open(dir)) {
            first.saveGist(SURESH, gist(CallId.of("call-1"), 1_000, "first"));
            assertThat(first.forget(SURESH, during, 5_000)).isTrue();
        }

        try (SqliteMemoryStore second = SqliteMemoryStore.open(dir)) {
            second.saveGist(SURESH, new CallGist(during, 8_000, 9_000, "hang-up gist after a restart", List.of()));
            second.saveGist(SURESH, gist(CallId.of("call-0"), 4_000, "late gist from before the forget"));
            assertThat(second.load(SURESH)).isEmpty();

            second.saveGist(SURESH, gist(CallId.of("call-later"), 6_000, "a new call"));
            assertThat(second.load(SURESH).orElseThrow().gists()).extracting(CallGist::summary)
                    .containsExactly("a new call");
        }
    }

    @Test
    void theDatabaseHoldsNoRawPhoneNumber() throws Exception {
        Path dir = tmp.resolve("no-number");
        try (SqliteMemoryStore s = SqliteMemoryStore.open(dir)) {
            s.upsertFacts(SURESH, List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("c-1"), 1))));
            s.saveGist(SURESH, gist(CallId.of("c-1"), 1_000, "Asked the onion price."));
            s.forget(OTHER, CallId.of("c-2"), 5);
        }

        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String bytes = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                assertThat(bytes).as(f.getFileName().toString())
                        .doesNotContain(PHONE).doesNotContain(PHONE.substring(1)).doesNotContain("5005550006");
            }
        }
        try (SqliteMemoryStore s = SqliteMemoryStore.open(dir)) {
            assertThat(s.load(SURESH)).isPresent(); // the key, not the number, finds the farmer
        }
    }

    @Test
    void aNewDataDirectoryIsGitIgnored() throws Exception {
        Path dir = tmp.resolve("fresh/nested");
        try (SqliteMemoryStore s = SqliteMemoryStore.open(dir)) {
            assertThat(s.load(SURESH)).isEmpty();
        }

        assertThat(dir.resolve(SqliteMemoryStore.FILE_NAME)).exists();
        assertThat(Files.readString(dir.resolve(".gitignore"))).contains("*");
    }
}
