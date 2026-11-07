package com.example.g_one.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryMemoryStoreTest {
    private static final byte[] SALT = "test-salt-not-a-secret".getBytes(StandardCharsets.US_ASCII);
    private static final CallerKey SURESH = CallerKey.fromPhone("+15005550006", SALT);
    private static final CallerKey OTHER = CallerKey.fromPhone("+15005550001", SALT);
    private static final CallId FIRST = CallId.of("call-1");
    private static final CallId SECOND = CallId.of("call-2");

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();

    private static CallGist gist(CallId call, String summary) {
        return new CallGist(call, 1_000, 2_000, summary, List.of("Call back about onion prices"));
    }

    @Test
    void aFirstTimeCallerHasNoMemory() {
        assertThat(store.load(SURESH)).isEmpty();
    }

    @Test
    void aSavedGistIsLoadedOnTheNextCall() {
        store.saveGist(SURESH, gist(FIRST, "Asked about onion prices in Nashik."));

        FarmerMemory m = store.load(SURESH).orElseThrow();

        assertThat(m.callerKey()).isEqualTo(SURESH);
        assertThat(m.gists()).containsExactly(gist(FIRST, "Asked about onion prices in Nashik."));
        assertThat(m.facts()).isEmpty();
        assertThat(m.isEmpty()).isFalse();
    }

    @Test
    void gistsAreNewestFirstAndSavingTheSameCallAgainReplacesIt() {
        store.saveGist(SURESH, gist(FIRST, "first"));
        store.saveGist(SURESH, gist(SECOND, "second"));
        store.saveGist(SURESH, gist(FIRST, "first, rewritten"));

        assertThat(store.load(SURESH).orElseThrow().gists())
                .extracting(CallGist::summary)
                .containsExactly("first, rewritten", "second");
    }

    @Test
    void factsKeepTheirProvenanceAndANewerFactReplacesTheSameField() {
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
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                store.saveGist(SURESH, gist(CallId.of("call-" + n), "call " + n));
                store.upsertFacts(SURESH, List.of(new ProfileFact("f" + n, "v", Provenance.said(CallId.of("c"), n))));
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        FarmerMemory m = store.load(SURESH).orElseThrow();
        assertThat(m.gists()).hasSize(calls);
        assertThat(m.facts()).hasSize(calls);
    }
}
