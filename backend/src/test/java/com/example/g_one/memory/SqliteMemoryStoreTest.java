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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @Test
    void anExistingDataDirectoryWithoutAGitignoreGetsOne() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("existing"));
        try (SqliteMemoryStore s = SqliteMemoryStore.open(dir)) {
            assertThat(s.load(SURESH)).isEmpty();
        }

        assertThat(Files.readString(dir.resolve(".gitignore")).lines()).contains("*");
    }

    @Test
    void anExistingGitignoreWithoutTheCatchAllKeepsItsLinesAndGainsTheDatabaseLines() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("partial"));
        Files.writeString(dir.resolve(".gitignore"), "notes.txt");
        try (SqliteMemoryStore s = SqliteMemoryStore.open(dir)) {
            assertThat(s.load(SURESH)).isEmpty();
        }
        String once = Files.readString(dir.resolve(".gitignore"));
        assertThat(once.lines()).startsWith("notes.txt")
                .contains("g-one.db", "g-one.db-wal", "g-one.db-shm", "g-one.db-journal");

        SqliteMemoryStore.open(dir).close();
        assertThat(Files.readString(dir.resolve(".gitignore"))).as("added only once").isEqualTo(once);
    }

    @Test
    void aNegationAfterTheCatchAllIsOverriddenByTrailingDatabaseLines() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("negated"));
        Files.writeString(dir.resolve(".gitignore"), "*\n!g-one.db\n!*-wal\n");
        try (SqliteMemoryStore s = SqliteMemoryStore.open(dir)) {
            assertThat(s.load(SURESH)).isEmpty();
        }
        List<String> lines = Files.readString(dir.resolve(".gitignore")).lines().toList();

        assertThat(lines).startsWith("*", "!g-one.db", "!*-wal");
        assertThat(lines.subList(lines.size() - 4, lines.size()))
                .as("last match wins, so the database lines come after every negation")
                .containsExactly("g-one.db", "g-one.db-wal", "g-one.db-shm", "g-one.db-journal");
        assertThat(SqliteMemoryStore.ignoresDatabaseLast(String.join("\n", lines))).isTrue();

        String once = Files.readString(dir.resolve(".gitignore"));
        SqliteMemoryStore.open(dir).close();
        assertThat(Files.readString(dir.resolve(".gitignore"))).as("added only once").isEqualTo(once);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "'*'                                                   | true",
        "'# data\\n*\\n'                                             | true",
        "'/*'                                                        | true",
        "'g-one.db\\ng-one.db-wal\\ng-one.db-shm\\ng-one.db-journal' | true",
        "'*\\n!g-one.db'                                             | false",
        "'*\\n!keep.txt\\nnotes.txt'                                 | false",
        "'!keep.txt\\n*'                                             | true",
        "'g-one.db'                                                  | false",
        "''                                                          | false",
    })
    void theLastRulesMustIgnoreEveryDatabaseFile(String gitignore, boolean ignored) {
        assertThat(SqliteMemoryStore.ignoresDatabaseLast(gitignore.replace("\\n", "\n"))).isEqualTo(ignored);
    }

    @Test
    void theRealGitAgreesThatTheDatabaseIsIgnored() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Assumptions.assumeTrue(git(repo, "init", "-q") == 0, "git is not on the PATH");
        Path dir = Files.createDirectories(repo.resolve("data"));
        Files.writeString(dir.resolve(".gitignore"), "*\n!g-one.db\n!g-one.db-wal\n!.gitignore\n");
        SqliteMemoryStore.open(dir).close();

        for (String f : List.of("g-one.db", "g-one.db-wal", "g-one.db-shm")) {
            Files.write(dir.resolve(f), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            assertThat(git(repo, "check-ignore", "-q", "data/" + f)).as(f + " ignored").isZero();
        }
    }

    private static int git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    @Test
    void twoStoresOnTheSameFileTakeTurnsWithoutHoldingTheLockWhileIdle() {
        Path dir = tmp.resolve("shared");
        long started = System.nanoTime();
        try (SqliteMemoryStore a = SqliteMemoryStore.open(dir); SqliteMemoryStore b = SqliteMemoryStore.open(dir)) {
            for (int i = 0; i < 5; i++) {
                a.saveGist(SURESH, gist(CallId.of("a-" + i), i, "From A " + i));
                b.saveGist(SURESH, gist(CallId.of("b-" + i), i, "From B " + i));
                b.upsertFacts(OTHER, List.of(new ProfileFact("name", "B" + i,
                        Provenance.said(CallId.of("b-" + i), i))));
                a.upsertFacts(OTHER, List.of(new ProfileFact("village", "A" + i,
                        Provenance.said(CallId.of("a-" + i), i))));
            }
            assertThat(a.load(SURESH).orElseThrow().gists()).hasSize(10);
            assertThat(b.load(OTHER).orElseThrow().facts()).hasSize(2);
            assertThat(b.forget(SURESH, CallId.of("b-9"), 100)).isTrue();
            assertThat(a.load(SURESH)).isEmpty();
        }
        // An idle store holding the write lock would make every write of the other wait out the 5 s busy timeout.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }
}
