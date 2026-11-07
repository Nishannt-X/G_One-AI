package com.example.g_one.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What G-One remembers about one caller number: the farmer profile (one fact per field) and past call gists, newest
 * first. A snapshot: it does not change when the store does.
 */
public record FarmerMemory(CallerKey callerKey, List<ProfileFact> facts, List<CallGist> gists) {

    public FarmerMemory {
        Objects.requireNonNull(callerKey, "callerKey");
        facts = facts == null ? List.of() : List.copyOf(facts);
        gists = gists == null ? List.of() : List.copyOf(gists);
    }

    /** Memory for a first-time caller. */
    public static FarmerMemory empty(CallerKey callerKey) {
        return new FarmerMemory(callerKey, List.of(), List.of());
    }

    public boolean isEmpty() {
        return facts.isEmpty() && gists.isEmpty();
    }

    public Optional<ProfileFact> fact(String field) {
        return facts.stream().filter(f -> f.field().equals(field)).findFirst();
    }
}
