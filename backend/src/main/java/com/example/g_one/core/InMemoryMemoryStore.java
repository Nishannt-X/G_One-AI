package com.example.g_one.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MemoryStore} that lives in the JVM and is lost on restart. For tests and until the data store decision (#10)
 * lands. Not a Spring bean on its own: the memory area decides which store is wired.
 */
public class InMemoryMemoryStore implements MemoryStore {
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
            gists.add(gist);
            m.gists().stream().filter(g -> !g.callId().equals(gist.callId())).forEach(gists::add);
            return new FarmerMemory(k, m.facts(), gists);
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
                if (at >= 0) {
                    merged.set(at, f);
                } else {
                    merged.add(f);
                }
            }
            return new FarmerMemory(k, merged, m.gists());
        });
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
