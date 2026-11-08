package com.example.g_one.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MemoryStore} that lives in the JVM and is lost on restart. For tests and until the data store decision (#10)
 * lands. Not a Spring bean on its own: the memory area decides which store is wired. Every write and every forget runs
 * inside {@link ConcurrentHashMap#compute} for its key, so they are atomic per key and never lose each other, and a
 * write cannot slip between a forget's delete and its tombstone.
 */
public class InMemoryMemoryStore implements MemoryStore {
    /** Newest call first; the call id breaks ties so the order never depends on write order. */
    private static final Comparator<CallGist> NEWEST_CALL_FIRST = Comparator
            .comparingLong(CallGist::startedMs).reversed()
            .thenComparing(g -> g.callId().value());

    private final Map<CallerKey, Slot> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<FarmerMemory> load(CallerKey key) {
        Slot slot = byKey.get(Objects.requireNonNull(key, "key"));
        return slot == null ? Optional.empty() : Optional.ofNullable(slot.memory());
    }

    @Override
    public void saveGist(CallerKey key, CallGist gist) {
        Objects.requireNonNull(gist, "gist");
        byKey.compute(Objects.requireNonNull(key, "key"), (k, old) -> {
            Slot slot = old == null ? Slot.EMPTY : old;
            if (slot.tombstone() != null && slot.tombstone().covers(gist.callId(), gist.startedMs())) {
                return old;
            }
            FarmerMemory m = slot.memoryOr(k);
            List<CallGist> gists = new ArrayList<>(m.gists().size() + 1);
            m.gists().stream().filter(g -> !g.callId().equals(gist.callId())).forEach(gists::add);
            gists.add(gist);
            gists.sort(NEWEST_CALL_FIRST);
            return slot.withMemory(FarmerMemory.of(k, m.facts(), gists));
        });
    }

    @Override
    public void upsertFacts(CallerKey key, List<ProfileFact> facts) {
        List<ProfileFact> incoming = List.copyOf(Objects.requireNonNull(facts, "facts"));
        if (incoming.isEmpty()) {
            return;
        }
        byKey.compute(Objects.requireNonNull(key, "key"), (k, old) -> {
            Slot slot = old == null ? Slot.EMPTY : old;
            List<ProfileFact> allowed = incoming.stream()
                    .filter(f -> slot.tombstone() == null
                            || !slot.tombstone().covers(f.provenance().callId(), f.provenance().atMs()))
                    .toList();
            if (allowed.isEmpty()) {
                return old;
            }
            FarmerMemory m = slot.memoryOr(k);
            List<ProfileFact> merged = new ArrayList<>(m.facts());
            for (ProfileFact f : allowed) {
                int at = indexOf(merged, f.field());
                if (at < 0) {
                    merged.add(f);
                } else if (replaces(f, merged.get(at))) {
                    merged.set(at, f);
                }
            }
            return slot.withMemory(FarmerMemory.of(k, merged, m.gists()));
        });
    }

    @Override
    public boolean forget(CallerKey key, CallId duringCall, long atMs) {
        Objects.requireNonNull(duringCall, "duringCall");
        boolean[] had = new boolean[1];
        byKey.compute(Objects.requireNonNull(key, "key"), (k, old) -> {
            had[0] = old != null && old.memory() != null;
            Tombstone before = old == null ? null : old.tombstone();
            return new Slot(null, Tombstone.add(before, duringCall, atMs));
        });
        return had[0];
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

    private static int indexOf(List<ProfileFact> facts, String field) {
        for (int i = 0; i < facts.size(); i++) {
            if (facts.get(i).field().equals(field)) {
                return i;
            }
        }
        return -1;
    }

    /** What is kept for one key: the memory (null after a forget, until a later call writes) and any tombstone. */
    private record Slot(FarmerMemory memory, Tombstone tombstone) {
        static final Slot EMPTY = new Slot(null, null);

        FarmerMemory memoryOr(CallerKey key) {
            return memory == null ? FarmerMemory.empty(key) : memory;
        }

        Slot withMemory(FarmerMemory m) {
            return new Slot(m, tombstone);
        }
    }

    /** The calls a farmer was forgotten during, and the latest forget time. Immutable. */
    private record Tombstone(Set<CallId> calls, long atMs) {
        static Tombstone add(Tombstone before, CallId call, long atMs) {
            if (before == null) {
                return new Tombstone(Set.of(call), atMs);
            }
            Set<CallId> calls = new HashSet<>(before.calls());
            calls.add(call);
            return new Tombstone(Set.copyOf(calls), Math.max(before.atMs(), atMs));
        }

        /** True when a write from this call at this time must be dropped. */
        boolean covers(CallId call, long timeMs) {
            return calls.contains(call) || timeMs <= atMs;
        }
    }
}
