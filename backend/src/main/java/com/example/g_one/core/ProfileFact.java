package com.example.g_one.core;

import java.util.Objects;

/**
 * One stable fact in a farmer profile, such as {@code field = "district"}, {@code value = "Nashik"}. A profile holds at
 * most one fact per field. Which fact wins a field is set by {@link MemoryStore#upsertFacts}: said beats inferred,
 * then the newer one wins.
 */
public record ProfileFact(String field, String value, Provenance provenance) {

    public ProfileFact {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
        if (field.isBlank()) {
            throw new IllegalArgumentException("A profile fact needs a field name");
        }
    }
}
