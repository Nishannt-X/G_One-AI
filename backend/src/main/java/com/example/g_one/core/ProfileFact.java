package com.example.g_one.core;

import java.util.Objects;

/**
 * One stable fact in a farmer profile, such as {@code field = "district"}, {@code value = "Nashik"}. A profile holds at
 * most one fact per field; a newer fact for the same field replaces the older one.
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
