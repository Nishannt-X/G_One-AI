package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.brain.LlmAgentBrain.AnsweringTurn;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.Turn;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-call record of what {@code connect_buyer} needs to allow a dial, kept in code so the model alone can never dial a
 * buyer (docs/call-flow.md step 8, "Unclear means no").
 *
 * <ol>
 *   <li>{@code find_buyers} records which buyers it suggested ({@link #suggested}).</li>
 *   <li>{@code ask_connect_buyer} checks one consent question for one suggested buyer ({@link #asked}), only while the
 *       brain is answering the farmer's newest turn. The question is recorded only once the brain has returned it as
 *       its reply ({@link AnsweringTurn#onReplied}); a turn that ends in an apology records nothing.</li>
 *   <li>{@code connect_buyer} is {@link Decision#GRANTED} ({@link #tryConsume}) only for that buyer, only while the
 *       brain is answering the very next farmer turn after the question, and only when that turn is a clear yes
 *       ({@link ConsentReply}). The question is then used up, so one yes dials once.</li>
 *   <li>The dial itself runs through {@link #dialWhileLive}, which holds the call's lock and refuses once the farmer
 *       leg or the call has ended, or once the farmer has said anything newer, so a hang-up and a dial never
 *       interleave.</li>
 * </ol>
 *
 * <p>Each call has one state object that is also its lifecycle guard: hang-up events and dials lock the same object.
 * Every use of it counts as in-flight work, and a state is dropped only when nothing is in flight and either the call
 * ended at least {@link #TOMBSTONE} ago or nothing happened on it for {@link #IDLE}. So a state is never replaced while
 * a dial or a hang-up still uses it.
 *
 * <p>The farmer's words come from the pipeline's {@link ConsoleEvent.TurnHeard} events (published before it asks the
 * brain) and from {@link AnsweringTurn}, never from the model. Only turns from {@code ctx.farmerLeg().id()} count; turns
 * from any other leg are ignored. After {@link ConsoleEvent.CallEnded} (or the farmer's {@link ConsoleEvent.LegEnded})
 * the call is kept as ended, so a late tool call cannot start it again. The farmer's words are never logged.
 */
@Component
public class BuyerConsent implements AutoCloseable {
    /** How long an ended call is kept, so late tool calls and events still find it ended. */
    static final Duration TOMBSTONE = Duration.ofMinutes(10);
    /** A call with no event or tool call for this long is treated as gone. */
    static final Duration IDLE = Duration.ofHours(3);

    public enum Decision {
        GRANTED, NOT_SUGGESTED, NOT_ASKED, OTHER_BUYER, NOT_ANSWERED, MOVED_ON, STALE, NO_CONSENT, ENDED
    }

    public enum AskDecision { ASKED, NOT_SUGGESTED, STALE, ENDED }

    /** Identifies one finished turn across {@link Turn} and {@link ConsoleEvent.TurnHeard}. */
    private record TurnKey(LegId legId, long startedMs, long endedMs, String text) {
        static TurnKey of(Turn t) {
            return new TurnKey(t.legId(), t.startedMs(), t.endedMs(), t.text());
        }

        static TurnKey of(ConsoleEvent.TurnHeard t) {
            return new TurnKey(t.legId(), t.startedMs(), t.endedMs(), t.text() == null ? "" : t.text());
        }
    }

    /** Farmer-role turns heard on one leg. */
    private static final class Heard {
        int count;
        TurnKey latest;
    }

    private record Question(LegId farmerLeg, String buyerId, int afterTurn) {}

    /** One call's state and lifecycle guard. */
    private static final class State {
        /** Guarded by the {@link BuyerConsent} instance (the map lock). */
        int inFlight;
        long lastUsedMs;
        /** Written under the state's lock; volatile so the map lock and {@link #ended(CallId)} can read them. */
        volatile boolean ended;
        volatile long endedAtMs;
        /** The rest is guarded by the state's own lock. */
        final Map<LegId, Heard> heard = new HashMap<>();
        Set<String> suggested = Set.of();
        Question question;

        Heard heard(LegId leg) {
            return heard.computeIfAbsent(leg, l -> new Heard());
        }

        boolean latestIs(LegId leg, Turn turn) {
            return TurnKey.of(turn).equals(heard(leg).latest);
        }
    }

    private final Map<CallId, State> calls = new HashMap<>();
    private final Clock clock;
    private final ConsoleBus.Subscription subscription;

    @Autowired
    public BuyerConsent(ConsoleBus bus) {
        this(bus, Clock.systemUTC());
    }

    BuyerConsent(ConsoleBus bus, Clock clock) {
        this.clock = clock;
        this.subscription = bus.subscribe(this::onEvent);
    }

    private void onEvent(ConsoleEvent event) {
        if (event instanceof ConsoleEvent.TurnHeard t && t.role() == LegRole.FARMER && t.legId() != null) {
            withCall(t.callId(), true, s -> {
                Heard h = s.heard(t.legId());
                h.count++;
                h.latest = TurnKey.of(t);
                return null;
            }, null);
        } else if (event instanceof ConsoleEvent.LegEnded e && e.role() == LegRole.FARMER) {
            end(e.callId());
        } else if (event instanceof ConsoleEvent.CallEnded e) {
            end(e.callId());
        }
    }

    /** Marks the call ended; waits for a dial in progress on it ({@link #dialWhileLive}) to return first. */
    private void end(CallId callId) {
        withCall(callId, true, s -> {
            if (!s.ended) s.endedAtMs = clock.millis();
            s.ended = true;
            s.suggested = Set.of();
            s.question = null;
            return null;
        }, null);
    }

    /**
     * Runs {@code body} under the call's state lock while holding its guard, so the state cannot be dropped meanwhile.
     * Returns {@code absent} when the call has no state and {@code create} is false.
     */
    private <T> T withCall(CallId callId, boolean create, Function<State, T> body, T absent) {
        State s;
        synchronized (this) {
            sweep();
            s = calls.get(callId);
            if (s == null) {
                if (!create) return absent;
                s = new State();
                calls.put(callId, s);
            }
            s.inFlight++;
        }
        try {
            synchronized (s) {
                return body.apply(s);
            }
        } finally {
            synchronized (this) {
                s.inFlight--;
                s.lastUsedMs = clock.millis();
            }
        }
    }

    /** Drops states with nothing in flight that ended long enough ago or went idle. The caller holds the map lock. */
    private void sweep() {
        long now = clock.millis();
        calls.values().removeIf(s -> s.inFlight == 0 && (s.ended
                ? now - s.endedAtMs >= TOMBSTONE.toMillis()
                : now - s.lastUsedMs >= IDLE.toMillis()));
    }

    /** True while this call has state (for tests of the lifecycle guard). */
    synchronized boolean tracks(CallId callId) {
        sweep();
        return calls.containsKey(callId);
    }

    /** Records the buyers just suggested on this call; they replace any earlier suggestion and question. */
    public void suggested(CallContext ctx, List<String> buyerIds) {
        withCall(ctx.callId(), true, s -> {
            if (!s.ended) {
                s.suggested = Set.copyOf(buyerIds);
                s.question = null;
            }
            return null;
        }, null);
    }

    /**
     * Checks that the farmer may be asked now whether to call {@code buyerId}. On {@link AskDecision#ASKED} the question
     * is recorded only when the brain returns it as the reply ({@link AnsweringTurn#onReplied}), and only if the call is
     * still live and the turn being answered is still the farmer's newest; it then replaces any earlier question. Only
     * the farmer's next turn after the one being answered can say yes to it.
     */
    public AskDecision asked(CallContext ctx, AnsweringTurn answering, String buyerId) {
        LegId leg = ctx.farmerLeg().id();
        AskDecision d = withCall(ctx.callId(), true, s -> {
            if (s.ended) return AskDecision.ENDED;
            if (!s.suggested.contains(buyerId)) return AskDecision.NOT_SUGGESTED;
            if (!fromFarmer(answering, ctx) || !answering.current() || !s.latestIs(leg, answering.turn())) {
                return AskDecision.STALE; // the farmer has said something newer, or this answer is over
            }
            return AskDecision.ASKED;
        }, AskDecision.ENDED);
        if (d == AskDecision.ASKED) answering.onReplied(() -> record(ctx, answering, buyerId));
        return d;
    }

    /** The question was returned as the brain's reply: record it unless the call or the turn moved on meanwhile. */
    private void record(CallContext ctx, AnsweringTurn answering, String buyerId) {
        LegId leg = ctx.farmerLeg().id();
        withCall(ctx.callId(), false, s -> {
            if (s.ended || !s.suggested.contains(buyerId) || !answering.current()
                    || !s.latestIs(leg, answering.turn())) {
                return null;
            }
            s.question = new Question(leg, buyerId, s.heard(leg).count);
            return null;
        }, null);
    }

    /** Decides, and on {@link Decision#GRANTED} uses up the question so one yes cannot dial twice. */
    public Decision tryConsume(CallContext ctx, AnsweringTurn answering, String buyerId) {
        LegId leg = ctx.farmerLeg().id();
        return withCall(ctx.callId(), false, s -> {
            if (s.ended) return Decision.ENDED;
            if (!s.suggested.contains(buyerId)) return Decision.NOT_SUGGESTED;
            Question q = s.question;
            if (q == null || !q.farmerLeg().equals(leg)) return Decision.NOT_ASKED;
            if (!q.buyerId().equals(buyerId)) return Decision.OTHER_BUYER;
            Heard h = s.heard(leg);
            if (h.count <= q.afterTurn()) return Decision.NOT_ANSWERED;
            if (!fromFarmer(answering, ctx) || !answering.current()) return Decision.STALE;
            // Only the turn right after the question counts, and it must be the one being answered now.
            if (h.count != q.afterTurn() + 1 || !s.latestIs(leg, answering.turn())) {
                s.question = null;
                return Decision.MOVED_ON;
            }
            s.question = null; // answered: a no, or an unclear reply, also needs a fresh question
            return ConsentReply.isClearYes(answering.turn().text()) ? Decision.GRANTED : Decision.NO_CONSENT;
        }, Decision.NOT_SUGGESTED);
    }

    /**
     * Runs {@code dial} under the call's lock unless the call or farmer leg has ended, the answer is no longer current,
     * or the farmer has said something newer than the turn being answered; empty when it did not run. A hang-up that
     * arrives meanwhile waits until {@code dial} returns, so the dial must only start the buyer leg, not wait for it to
     * answer.
     */
    public <T> Optional<T> dialWhileLive(CallContext ctx, AnsweringTurn answering, Supplier<T> dial) {
        LegId leg = ctx.farmerLeg().id();
        return withCall(ctx.callId(), false, s -> {
            if (s.ended || !fromFarmer(answering, ctx) || !answering.current()
                    || !s.latestIs(leg, answering.turn())) {
                return Optional.<T>empty();
            }
            return Optional.ofNullable(dial.get());
        }, Optional.empty());
    }

    /** True once the call's farmer leg or the call itself has ended (a hang-up waiting on a dial is not yet). */
    public synchronized boolean ended(CallId callId) {
        State s = calls.get(callId);
        return s != null && s.ended;
    }

    private static boolean fromFarmer(AnsweringTurn answering, CallContext ctx) {
        return answering != null && answering.callId().equals(ctx.callId())
                && answering.turn().role() == LegRole.FARMER
                && answering.turn().legId().equals(ctx.farmerLeg().id());
    }

    @PreDestroy
    @Override
    public void close() {
        subscription.close();
    }
}
