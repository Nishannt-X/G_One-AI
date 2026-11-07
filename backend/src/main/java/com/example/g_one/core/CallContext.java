package com.example.g_one.core;

import java.util.Objects;
import java.util.Optional;

/**
 * What a {@link Tool} may know about the call it runs in: the call, the farmer leg, the farmer's current language and
 * the memory snapshot loaded at call start ({@link FarmerMemory#empty} for a first-time caller,
 * {@link FarmerMemory#withheld} when the caller ID was withheld).
 *
 * <p>Memory tools ({@code save_profile_fact}, {@code forget_me}) must check {@link #callerKey()}: when it is empty they
 * must not read or write any {@link MemoryStore}, and should return a {@link ToolResult#error} saying memory is off for
 * this call.
 */
public record CallContext(CallId callId, Leg farmerLeg, Language language, FarmerMemory memory) {

    public CallContext {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(farmerLeg, "farmerLeg");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(memory, "memory");
    }

    /** The key to read or write this farmer's memory with; empty when the caller ID was withheld (memory is off). */
    public Optional<CallerKey> callerKey() {
        return memory.callerKey();
    }
}
