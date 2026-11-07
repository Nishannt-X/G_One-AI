package com.example.g_one.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.UUID;

/** Identifies one leg of a call. Unique across calls. Serialized to JSON as a plain string. */
public record LegId(@JsonValue String value) {

    public LegId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("LegId must not be blank");
        }
    }

    @JsonCreator
    public static LegId of(String value) {
        return new LegId(value);
    }

    public static LegId random() {
        return new LegId("leg-" + UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value;
    }
}
