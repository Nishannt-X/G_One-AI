package com.example.g_one.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MemoryStore} that lives in the JVM and is lost on restart. For tests and until the data store decision (#10)
 * lands. Not a Spring bean on its own: the memory area decides which store is wired. Every write runs inside
 * {@link ConcurrentHashMap#compute}, so writes for one key are atomic and never lose each other.
 */
public class InMemoryMemoryStore implements MemoryStore {
    /** Newest call first; the call id breaks ties so the order never depends on write order. */
    private static final Comparator<CallGist> NEWEST_CALL_FIRST = Comparator
            .comparingLong(CallGist::startedMs).reversed()
            .thenComparing(g -> g.callId().value());

    private final Map<CallerKey, FarmerMemory> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<FarmerMemory> load(CallerKey key) {
        return Optional.ofNullable(byKey.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public void saveGist(CallerKey key, CallGist gist) {
        Objects.requireNonNull(gist, "gist");
        byKey.compute(Objects.requireNonNull(key, "key"), (k, old) -> {
            FarmerMemory m = old == null ? FarmerMemory.empty(k) : old;
            List<CallGist> gists = new ArrayList<>(m.gists().size() + 1);
            m.gists().stream().filter(g -> !g.callId().equals(gist.callId())).forEach(gists::add);
            gists.add(gist);
            gists.sort(NEWEST_CALL_FIRST);
            return FarmerMemory.of(k, m.facts(), gists);
        });
    }

    @Override
    public void upsertFacts(CallerKey key, List<ProfileFact> facts) {
        List<ProfileFact> incoming = List.copyOf(Objects.requireNonNull(facts, "facts"));
        if (incoming.isEmpty()) {
            return;
        }
        byKey.compute(Objects.requireNonNull(key, "key"), (k, old) -> {
            FarmerMemory m = old == null ? FarmerMemory.empty(k) : old;
            List<ProfileFact> merged = new ArrayList<>(m.facts());
            for (ProfileFact f : incoming) {
                int at = indexOf(merged, f.field());
                if (at < 0) {
                    merged.add(f);
                } else if (replaces(f, merged.get(at))) {
                    merged.set(at, f);
                }
            }
            return FarmerMemory.of(k, merged, m.gists());
        });
    }

    @Override
    public boolean forget(CallerKey key) {
        return byKey.remove(Objects.requireNonNull(key, "key")) != null;
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
}
