package com.example.g_one.agent.tools.memory;

import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.FarmerMemory;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Whether this call may use and write the farmer's memory, shared by the agent brain and the memory tools. Caller ID is
 * not identity, so a returning farmer's memory is used only after they say yes to the name check (docs/memory.md):
 * until then the brain keeps it out of the prompt and the memory tools refuse to write or delete.
 *
 * <p>State is kept per call id. The pipeline should call {@code LlmAgentBrain.callEnded} at hang-up; entries idle for
 * longer than {@link #IDLE_TTL} are dropped anyway, so a missed hang-up cannot leak memory.
 */
@Component
public class MemoryGate {
    /** A call idle this long is over; its state is dropped on the next sweep. */
    public static final Duration IDLE_TTL = Duration.ofHours(3);
    private static final long SWEEP_EVERY_MS = Duration.ofMinutes(5).toMillis();

    public enum State {
        /** First call from this number: nothing is remembered yet. Facts may be saved. */
        NEW,
        /** The caller ID is withheld: memory is off, nothing is loaded or saved. */
        WITHHELD,
        /** Something is remembered but no name to check it with: it is not used. Facts may be saved. */
        UNNAMED,
        /** A returning farmer was asked the name check and has not answered yet. Memory hidden, no writes. */
        NAME_CHECK,
        /** The farmer said yes to the name check: memory is used and facts may be saved. */
        CONFIRMED,
        /** No or unclear answer to the name check: someone else is on the phone. Memory hidden, no writes. */
        OTHER_SPEAKER,
        /** The farmer asked to be forgotten on this call: memory is off for the rest of it. */
        FORGOTTEN;

        /** True when the remembered profile and gists may go into the prompt. */
        public boolean usesMemory() {
            return this == CONFIRMED;
        }

        /** True when {@code save_profile_fact} may write. */
        public boolean maySave() {
            return this == NEW || this == UNNAMED || this == CONFIRMED;
        }

        /** True when {@code forget_me} may delete: never for an unanswered name check or another speaker. */
        public boolean mayForget() {
            return this == NEW || this == UNNAMED || this == CONFIRMED || this == FORGOTTEN;
        }
    }

    private record Entry(State state, long touchedMs) {}

    private final Map<CallId, Entry> calls = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepMs = new AtomicLong();
    private final Clock clock;

    @Autowired
    public MemoryGate() {
        this(Clock.systemUTC());
    }

    public MemoryGate(Clock clock) {
        this.clock = clock;
    }

    /** The call's state, starting it from the loaded memory the first time the call is seen. */
    public State state(CallContext ctx) {
        sweep();
        long now = clock.millis();
        return calls.compute(ctx.callId(), (id, e) ->
                new Entry(e == null ? initial(ctx.memory()) : e.state(), now)).state();
    }

    /** The call's state if the call is known, without starting it. */
    public Optional<State> peek(CallId callId) {
        Entry e = calls.get(callId);
        return e == null ? Optional.empty() : Optional.of(e.state());
    }

    /**
     * Records the answer to the name check. Only an unanswered name check changes; any other state stays as it is.
     *
     * @return the state afterwards
     */
    public State answerNameCheck(CallContext ctx, boolean yes) {
        state(ctx);
        long now = clock.millis();
        return calls.compute(ctx.callId(), (id, e) -> {
            State s = e.state() == State.NAME_CHECK ? (yes ? State.CONFIRMED : State.OTHER_SPEAKER) : e.state();
            return new Entry(s, now);
        }).state();
    }

    /** Memory is off for the rest of the call after {@code forget_me}. */
    public void forgotten(CallContext ctx) {
        calls.put(ctx.callId(), new Entry(State.FORGOTTEN, clock.millis()));
    }

    /** Drops the call's state at hang-up. Harmless for an unknown call. */
    public void end(CallId callId) {
        calls.remove(Objects.requireNonNull(callId, "callId"));
    }

    /** Where a call starts, from the memory loaded at call start. */
    public static State initial(FarmerMemory memory) {
        if (memory.isWithheld()) return State.WITHHELD;
        if (memory.isEmpty()) return State.NEW;
        return MemoryBlock.rememberedName(memory).isPresent() ? State.NAME_CHECK : State.UNNAMED;
    }

    int size() {
        return calls.size();
    }

    private void sweep() {
        long now = clock.millis();
        long last = lastSweepMs.get();
        if (now - last < SWEEP_EVERY_MS || !lastSweepMs.compareAndSet(last, now)) return;
        long cutoff = now - IDLE_TTL.toMillis();
        calls.entrySet().removeIf(e -> e.getValue().touchedMs() < cutoff);
    }
}
