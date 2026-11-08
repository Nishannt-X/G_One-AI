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
 *
 * <p>The constructor checks that the parts belong together: {@code farmerLeg} is the farmer leg of {@code callId}, and
 * {@code memory} is for the same caller as the leg ({@link FarmerMemory#withheld} exactly when the leg's caller ID is
 * withheld). So {@link #callerKey()} is always the farmer leg's own key, never another caller's.
 */
public record CallContext(CallId callId, Leg farmerLeg, Language language, FarmerMemory memory) {

    public CallContext {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(farmerLeg, "farmerLeg");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(memory, "memory");
        if (!farmerLeg.callId().equals(callId)) {
            throw new IllegalArgumentException("The farmer leg belongs to another call");
        }
        if (farmerLeg.role() != LegRole.FARMER) {
            throw new IllegalArgumentException("The farmer leg must have the farmer role");
        }
        // Messages never print the keys: they are derived from phone numbers.
        Optional<CallerKey> legKey = Objects.requireNonNull(farmerLeg.callerKey(), "farmerLeg.callerKey()");
        if (!legKey.equals(memory.callerKey())) {
            throw new IllegalArgumentException(legKey.isEmpty()
                    ? "The farmer's caller ID is withheld, so memory must be FarmerMemory.withheld()"
                    : "The memory belongs to a different caller than the farmer leg");
        }
    }

    /**
     * The key to read or write this farmer's memory with; empty when the caller ID was withheld (memory is off). Equal
     * to {@code farmerLeg().callerKey()}, which the constructor checked.
     */
    public Optional<CallerKey> callerKey() {
        return memory.callerKey();
    }
}
