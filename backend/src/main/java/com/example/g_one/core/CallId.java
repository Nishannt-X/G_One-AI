package com.example.g_one.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.UUID;

/** Identifies one call (one farmer session, one or two legs). Serialized to JSON as a plain string. */
public record CallId(@JsonValue String value) {

    public CallId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CallId must not be blank");
        }
    }

    @JsonCreator
    public static CallId of(String value) {
        return new CallId(value);
    }

    public static CallId random() {
        return new CallId("call-" + UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value;
    }
}
