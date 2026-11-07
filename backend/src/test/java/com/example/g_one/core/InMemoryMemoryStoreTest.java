package com.example.g_one.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.fakes.FakeLeg;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryMemoryStoreTest {
    private static final CallerKey SURESH = CallerKey.fromPhone("+15005550006", FakeLeg.SALT);
    private static final CallerKey OTHER = CallerKey.fromPhone("+15005550001", FakeLeg.SALT);
    private static final CallId FIRST = CallId.of("call-1");
    private static final CallId SECOND = CallId.of("call-2");

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();

    private static CallGist gist(CallId call, long startedMs, String summary) {
        return new CallGist(call, startedMs, startedMs + 1_000, summary, List.of("Call back about onion prices"));
    }

    private static CallGist gist(CallId call, String summary) {
        return gist(call, 1_000, summary);
    }

    private static ProfileFact said(String field, String value, long atMs) {
        return new ProfileFact(field, value, Provenance.said(FIRST, atMs));
    }

    private static ProfileFact inferred(String field, String value, long atMs) {
        return new ProfileFact(field, value, Provenance.inferred(FIRST, atMs));
    }

    private String district() {
        return store.load(SURESH).orElseThrow().fact("district").orElseThrow().value();
    }

    @Test
    void aFirstTimeCallerHasNoMemory() {
        assertThat(store.load(SURESH)).isEmpty();
    }

    @Test
    void aSavedGistIsLoadedOnTheNextCall() {
        store.saveGist(SURESH, gist(FIRST, "Asked about onion prices in Nashik."));

        FarmerMemory m = store.load(SURESH).orElseThrow();

        assertThat(m.callerKey()).contains(SURESH);
        assertThat(m.isWithheld()).isFalse();
        assertThat(m.gists()).containsExactly(gist(FIRST, "Asked about onion prices in Nashik."));
        assertThat(m.facts()).isEmpty();
        assertThat(m.isEmpty()).isFalse();
    }

    @Test
    void gistsAreOrderedByCallTimeNotWriteTimeAndStayInPlaceWhenReplaced() {
        CallId third = CallId.of("call-3");
        // Written out of order, as late async gist writes can be.
        store.saveGist(SURESH, gist(SECOND, 2_000, "second"));
        store.saveGist(SURESH, gist(third, 3_000, "third"));
        store.saveGist(SURESH, gist(FIRST, 1_000, "first"));

        assertThat(store.load(SURESH).orElseThrow().gists()).extracting(CallGist::summary)
                .containsExactly("third", "second", "first");

        store.saveGist(SURESH, gist(FIRST, 1_000, "first, rewritten"));
        store.saveGist(SURESH, gist(SECOND, 2_000, "second, rewritten"));

        assertThat(store.load(SURESH).orElseThrow().gists()).extracting(CallGist::summary)
                .containsExactly("third", "second, rewritten", "first, rewritten");
    }

    @Test
    void gistsWithTheSameStartTimeKeepAFixedOrder() {
        store.saveGist(SURESH, gist(SECOND, 1_000, "b"));
        store.saveGist(SURESH, gist(FIRST, 1_000, "a"));
        List<CallGist> once = store.load(SURESH).orElseThrow().gists();

        store.saveGist(SURESH, gist(SECOND, 1_000, "b"));

        assertThat(store.load(SURESH).orElseThrow().gists()).isEqualTo(once);
    }

    @Test
    void factsKeepTheirProvenanceAndANewerSaidFactReplacesTheSameField() {
        ProfileFact name = new ProfileFact("name", "Suresh", Provenance.said(FIRST, 10));
        ProfileFact district = new ProfileFact("district", "Nasik", Provenance.inferred(FIRST, 11));
        ProfileFact districtFixed = new ProfileFact("district", "Nashik", Provenance.said(SECOND, 20));

        store.upsertFacts(SURESH, List.of(name, district));
        store.upsertFacts(SURESH, List.of(districtFixed));

        FarmerMemory m = store.load(SURESH).orElseThrow();
        assertThat(m.facts()).containsExactly(name, districtFixed);
        assertThat(m.fact("district").orElseThrow().provenance())
                .isEqualTo(new Provenance(SECOND, Provenance.Origin.SAID, 20));
        assertThat(m.fact("crop")).isEmpty();
    }

    @Test
    void anInferredFactNeverReplacesASaidOneEvenWhenNewer() {
        store.upsertFacts(SURESH, List.of(said("district", "Nashik", 10)));
        store.upsertFacts(SURESH, List.of(inferred("district", "Pune", 99)));

        assertThat(district()).isEqualTo("Nashik");
    }

    @Test
    void aSaidFactReplacesAnInferredOneEvenWhenOlder() {
        store.upsertFacts(SURESH, List.of(inferred("district", "Pune", 99)));
        store.upsertFacts(SURESH, List.of(said("district", "Nashik", 10)));

        assertThat(district()).isEqualTo("Nashik");
    }

    @Test
    void withinOneOriginTheNewerFactWinsWhateverTheWriteOrder() {
        store.upsertFacts(SURESH, List.of(said("district", "Nashik", 20)));
        store.upsertFacts(SURESH, List.of(said("district", "Nasik", 10)));
        assertThat(district()).isEqualTo("Nashik");

        store.upsertFacts(SURESH, List.of(inferred("state", "Maharashtra", 20), inferred("state", "Gujarat", 10)));
        assertThat(store.load(SURESH).orElseThrow().fact("state").orElseThrow().value()).isEqualTo("Maharashtra");

        store.upsertFacts(SURESH, List.of(said("district", "Nashik district", 20)));
        assertThat(district()).as("equal times: the incoming fact wins").isEqualTo("Nashik district");
    }

    @Test
    void concurrentFactWritesKeepTheBestFact() throws Exception {
        List<ProfileFact> writes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            writes.add(said("district", "said-" + i, i));
            writes.add(inferred("district", "inferred-" + i, 1_000 + i));
        }
        Collections.shuffle(writes, new Random(7));

        runConcurrently(writes.stream().<Runnable>map(f -> () -> store.upsertFacts(SURESH, List.of(f))).toList());

        assertThat(district()).isEqualTo("said-99");
    }

    @Test
    void forgetDeletesFactsAndGistsForThatKeyOnly() {
        store.upsertFacts(SURESH, List.of(said("name", "Suresh", 1)));
        store.saveGist(SURESH, gist(FIRST, "first"));
        store.saveGist(OTHER, gist(SECOND, "someone else"));

        assertThat(store.forget(SURESH)).isTrue();

        assertThat(store.load(SURESH)).isEmpty();
        assertThat(store.load(OTHER)).isPresent();
        assertThat(store.forget(SURESH)).as("nothing left to forget").isFalse();

        store.saveGist(SURESH, gist(SECOND, "a new call"));
        assertThat(store.load(SURESH).orElseThrow().facts()).isEmpty();
        assertThat(store.load(SURESH).orElseThrow().gists()).extracting(CallGist::summary).containsExactly("a new call");
    }

    @Test
    void factsAndGistsDoNotDisturbEachOther() {
        ProfileFact crop = new ProfileFact("crop", "Onion", Provenance.said(FIRST, 10));
        store.upsertFacts(SURESH, List.of(crop));
        store.saveGist(SURESH, gist(FIRST, "first"));

        FarmerMemory m = store.load(SURESH).orElseThrow();
        assertThat(m.facts()).containsExactly(crop);
        assertThat(m.gists()).hasSize(1);
    }

    @Test
    void callersAreKeptApart() {
        store.saveGist(SURESH, gist(FIRST, "Suresh's call"));

        assertThat(store.load(OTHER)).isEmpty();
    }

    @Test
    void anEmptyFactListStoresNothing() {
        store.upsertFacts(SURESH, List.of());

        assertThat(store.load(SURESH)).isEmpty();
    }

    @Test
    void aLoadedSnapshotDoesNotChangeWhenTheStoreDoes() {
        store.saveGist(SURESH, gist(FIRST, "first"));
        FarmerMemory before = store.load(SURESH).orElseThrow();

        store.saveGist(SURESH, gist(SECOND, "second"));
        store.upsertFacts(SURESH, List.of(new ProfileFact("crop", "Onion", Provenance.said(SECOND, 1))));

        assertThat(before.gists()).hasSize(1);
        assertThat(before.facts()).isEmpty();
    }

    @Test
    void concurrentSavesLoseNothing() throws Exception {
        int calls = 200;
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            int n = i;
            tasks.add(() -> {
                store.saveGist(SURESH, gist(CallId.of("call-" + n), n, "call " + n));
                store.upsertFacts(SURESH, List.of(new ProfileFact("f" + n, "v", Provenance.said(CallId.of("c"), n))));
            });
        }

        runConcurrently(tasks);

        FarmerMemory m = store.load(SURESH).orElseThrow();
        assertThat(m.gists()).hasSize(calls);
        assertThat(m.gists().get(0).summary()).isEqualTo("call " + (calls - 1));
        assertThat(m.facts()).hasSize(calls);
    }

    private static void runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable t : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    t.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
