package com.example.g_one.core;

import java.util.Objects;

/**
 * One person's finished stretch of speech, as text. {@code startedMs} and {@code endedMs} are wall-clock epoch
 * milliseconds (speech start, and end of speech as decided by turn detection). {@code language} is the language the
 * turn was recognised in.
 */
public record Turn(LegId legId, LegRole role, Language language, String text, long startedMs, long endedMs) {

    public Turn {
        Objects.requireNonNull(legId, "legId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(text, "text");
        if (endedMs < startedMs) {
            throw new IllegalArgumentException("A turn cannot end before it starts");
        }
    }
}
