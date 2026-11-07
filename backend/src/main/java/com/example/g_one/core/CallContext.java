package com.example.g_one.core;

import java.util.Objects;

/**
 * What a {@link Tool} may know about the call it runs in: the call, the farmer leg, the farmer's current language and
 * the memory snapshot loaded at call start ({@link FarmerMemory#empty} for a first-time caller).
 */
public record CallContext(CallId callId, Leg farmerLeg, Language language, FarmerMemory memory) {

    public CallContext {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(farmerLeg, "farmerLeg");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(memory, "memory");
    }

    /** The key to read or write this farmer's memory with. */
    public CallerKey callerKey() {
        return memory.callerKey();
    }
}
