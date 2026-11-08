package com.example.g_one.core;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Objects;

/**
 * Where a profile fact came from: the call, whether the farmer said it or the assistant inferred it, and when (epoch
 * milliseconds).
 */
public record Provenance(CallId callId, Origin saidOrInferred, long atMs) {

    public Provenance {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(saidOrInferred, "saidOrInferred");
    }

    public static Provenance said(CallId callId, long atMs) {
        return new Provenance(callId, Origin.SAID, atMs);
    }

    public static Provenance inferred(CallId callId, long atMs) {
        return new Provenance(callId, Origin.INFERRED, atMs);
    }

    /** JSON: "said", "inferred". */
    public enum Origin {
        SAID, INFERRED;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
