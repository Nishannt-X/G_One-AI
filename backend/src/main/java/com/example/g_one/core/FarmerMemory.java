package com.example.g_one.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What G-One remembers about one caller number: the farmer profile (one fact per field) and past call gists, newest
 * call first (by {@link CallGist#startedMs()}). A snapshot: it does not change when the store does.
 *
 * <p>When the caller ID is withheld or anonymous there is no {@link CallerKey}: use {@link #withheld()}. That form has
 * an empty {@code callerKey}, no facts and no gists, and memory must not be read or written for the call.
 */
public record FarmerMemory(Optional<CallerKey> callerKey, List<ProfileFact> facts, List<CallGist> gists) {

    private static final FarmerMemory WITHHELD = new FarmerMemory(Optional.empty(), List.of(), List.of());

    public FarmerMemory {
        Objects.requireNonNull(callerKey, "callerKey");
        facts = facts == null ? List.of() : List.copyOf(facts);
        gists = gists == null ? List.of() : List.copyOf(gists);
        if (callerKey.isEmpty() && !(facts.isEmpty() && gists.isEmpty())) {
            throw new IllegalArgumentException("Memory without a caller key cannot hold facts or gists");
        }
    }

    /** Memory for a known caller key. */
    public static FarmerMemory of(CallerKey callerKey, List<ProfileFact> facts, List<CallGist> gists) {
        return new FarmerMemory(Optional.of(Objects.requireNonNull(callerKey, "callerKey")), facts, gists);
    }

    /** Memory for a first-time caller. */
    public static FarmerMemory empty(CallerKey callerKey) {
        return of(callerKey, List.of(), List.of());
    }

    /** No memory at all: the caller ID was withheld or anonymous, so nothing is loaded and nothing may be saved. */
    public static FarmerMemory withheld() {
        return WITHHELD;
    }

    /** True when the caller ID was withheld, so memory is off for this call. */
    public boolean isWithheld() {
        return callerKey.isEmpty();
    }

    /** True when nothing is remembered (always true for {@link #withheld()}). */
    public boolean isEmpty() {
        return facts.isEmpty() && gists.isEmpty();
    }

    public Optional<ProfileFact> fact(String field) {
        return facts.stream().filter(f -> f.field().equals(field)).findFirst();
    }
}
