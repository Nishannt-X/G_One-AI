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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Whether this call may use and write the farmer's memory, shared by the agent brain and the memory tools. Caller ID is
 * not identity, so a returning farmer's memory is used only after they say yes to the name check (docs/memory.md):
 * until then the brain keeps it away from the model and the memory tools refuse to write or delete.
 *
 * <p>State is kept per call id. The pipeline calls {@code LlmAgentBrain.callEnded} at hang-up. A background sweep every
 * {@link #SWEEP_EVERY} drops calls idle for longer than {@link #IDLE_TTL} (far longer than any phone call), so a missed
 * hang-up cannot keep state forever. A dropped call id is remembered for {@link #TOMBSTONE_TTL}: if it shows up again
 * it restarts as {@link State#OTHER_SPEAKER}, never from its loaded memory, so a dropped "forgotten" or "someone else"
 * state can never turn back into a name check that unlocks memory.
 */
@Component
public class MemoryGate implements DisposableBean {
    /** A call idle this long is over; its state is dropped on the next sweep. */
    public static final Duration IDLE_TTL = Duration.ofHours(3);
    /** How long a dropped call id still fails closed. */
    public static final Duration TOMBSTONE_TTL = Duration.ofHours(24);
    public static final Duration SWEEP_EVERY = Duration.ofMinutes(10);

    public enum State {
        /** First call from this number: nothing is remembered yet. Facts may be saved. */
        NEW,
        /** The caller ID is withheld: memory is off, nothing is loaded or saved. */
        WITHHELD,
        /**
         * Something is remembered but there is no name to check it with: it is not used, and since nobody can show
         * they own it, nothing is saved or deleted on this call.
         */
        UNNAMED,
        /** A returning farmer was asked the name check and has not answered yet. Memory hidden, no writes. */
        NAME_CHECK,
        /** The farmer said yes to the name check: memory is used and facts may be saved. */
        CONFIRMED,
        /** No or unclear answer to the name check: someone else is on the phone. Memory hidden, no writes. */
        OTHER_SPEAKER,
        /** The farmer asked to be forgotten on this call: memory is off for the rest of it. */
        FORGOTTEN;

        /** True when the remembered profile and gists may go to the model. */
        public boolean usesMemory() {
            return this == CONFIRMED;
        }

        /** True when {@code save_profile_fact} may write: a first-time caller, or a farmer who passed the name check. */
        public boolean maySave() {
            return this == NEW || this == CONFIRMED;
        }

        /** True when {@code forget_me} may delete: never for unowned memory, an unanswered check or another speaker. */
        public boolean mayForget() {
            return this == NEW || this == CONFIRMED || this == FORGOTTEN;
        }
    }

    /** {@code heardYes} is the code's reading of the farmer's latest reply while the name check waits. */
    private record Entry(State state, long touchedMs, boolean heardYes) {}

    private final Map<CallId, Entry> calls = new ConcurrentHashMap<>();
    private final Map<CallId, Long> dropped = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ScheduledExecutorService sweeper;

    @Autowired
    public MemoryGate() {
        this(Clock.systemUTC(), true);
    }

    /** For tests: no background sweep; call {@link #sweep()} directly. */
    public MemoryGate(Clock clock) {
        this(clock, false);
    }

    private MemoryGate(Clock clock, boolean background) {
        this.clock = clock;
        if (background) {
            sweeper = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("memory-gate-sweep").factory());
            long every = SWEEP_EVERY.toMillis();
            sweeper.scheduleWithFixedDelay(this::sweepQuietly, every, every, TimeUnit.MILLISECONDS);
        } else {
            sweeper = null;
        }
    }

    /** The call's state, starting it from the loaded memory the first time the call is seen. */
    public State state(CallContext ctx) {
        long now = clock.millis();
        return calls.compute(ctx.callId(), (id, e) -> e == null
                ? new Entry(dropped.containsKey(id) ? State.OTHER_SPEAKER : initial(ctx.memory()), now, false)
                : new Entry(e.state(), now, e.heardYes())).state();
    }

    /** The call's state if the call is known, without starting it. */
    public Optional<State> peek(CallId callId) {
        Entry e = calls.get(callId);
        return e == null ? Optional.empty() : Optional.of(e.state());
    }

    /**
     * Records the code's reading of the farmer's own reply ({@link NameCheckReply#isClearYes}) while the name check
     * waits, before the model is asked. Only this reading can let {@code confirm_name} unlock memory.
     */
    public void heardNameCheckReply(CallContext ctx, String farmerText) {
        state(ctx);
        boolean yes = NameCheckReply.isClearYes(farmerText);
        calls.computeIfPresent(ctx.callId(), (id, e) ->
                e.state() == State.NAME_CHECK ? new Entry(e.state(), clock.millis(), yes) : e);
    }

    /**
     * The model's answer to the name check, from {@code confirm_name}. Memory unlocks only when the model says yes
     * and the farmer's own reply was a clear yes ({@link #heardNameCheckReply}); anything else means someone else.
     * Only an unanswered name check changes; any other state stays as it is.
     *
     * @return the state afterwards
     */
    public State answerNameCheck(CallContext ctx, boolean modelSaysYes) {
        state(ctx);
        long now = clock.millis();
        return calls.compute(ctx.callId(), (id, e) -> {
            if (e.state() != State.NAME_CHECK) return new Entry(e.state(), now, false);
            return new Entry(modelSaysYes && e.heardYes() ? State.CONFIRMED : State.OTHER_SPEAKER, now, false);
        }).state();
    }

    /** The turn that answered the name check is over: a check still unanswered now counts as someone else. */
    public void closeNameCheck(CallContext ctx) {
        calls.computeIfPresent(ctx.callId(), (id, e) -> e.state() == State.NAME_CHECK
                ? new Entry(State.OTHER_SPEAKER, clock.millis(), false) : e);
    }

    /** Memory is off for the rest of the call after {@code forget_me}. */
    public void forgotten(CallContext ctx) {
        calls.put(ctx.callId(), new Entry(State.FORGOTTEN, clock.millis(), false));
    }

    /** Drops the call's state at hang-up. Harmless for an unknown call. */
    public void end(CallId callId) {
        Objects.requireNonNull(callId, "callId");
        if (calls.remove(callId) != null) dropped.put(callId, clock.millis());
    }

    /** Where a call starts, from the memory loaded at call start. */
    public static State initial(FarmerMemory memory) {
        if (memory.isWithheld()) return State.WITHHELD;
        if (memory.isEmpty()) return State.NEW;
        return MemoryBlock.rememberedName(memory).isPresent() ? State.NAME_CHECK : State.UNNAMED;
    }

    /** Drops calls idle longer than {@link #IDLE_TTL} and tombstones older than {@link #TOMBSTONE_TTL}. */
    public void sweep() {
        long now = clock.millis();
        long idleCutoff = now - IDLE_TTL.toMillis();
        calls.forEach((id, e) -> {
            if (e.touchedMs() < idleCutoff && calls.remove(id, e)) dropped.put(id, now);
        });
        long tombCutoff = now - TOMBSTONE_TTL.toMillis();
        dropped.entrySet().removeIf(t -> t.getValue() < tombCutoff);
    }

    int size() {
        return calls.size();
    }

    int tombstones() {
        return dropped.size();
    }

    @Override
    public void destroy() {
        if (sweeper != null) sweeper.shutdownNow();
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            // A failed sweep is retried on the next tick; it must never stop the scheduler.
        }
    }
}
