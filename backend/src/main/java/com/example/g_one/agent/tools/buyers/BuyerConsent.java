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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Per-call record of what {@code connect_buyer} needs to allow a dial, kept in code so the model alone can never dial a
 * buyer (docs/call-flow.md step 8, "Unclear means no").
 *
 * <ol>
 *   <li>{@code find_buyers} records which buyers it suggested ({@link #suggested}).</li>
 *   <li>{@code ask_connect_buyer} records one consent question for one suggested buyer ({@link #asked}), only while the
 *       brain is answering the farmer's newest turn.</li>
 *   <li>{@code connect_buyer} is {@link Decision#GRANTED} ({@link #tryConsume}) only for that buyer, only while the
 *       brain is answering the very next farmer turn after the question, and only when that turn is a clear yes
 *       ({@link ConsentReply}). The question is then used up, so one yes dials once.</li>
 *   <li>The dial itself runs through {@link #dialWhileLive}, which holds the call's lock and refuses once the farmer
 *       leg or the call has ended, so a hang-up and a dial never interleave.</li>
 * </ol>
 *
 * <p>The farmer's words come from the pipeline's {@link ConsoleEvent.TurnHeard} events (published before it asks the
 * brain) and from {@link AnsweringTurn}, never from the model. Only turns from {@code ctx.farmerLeg().id()} count; turns
 * from any other leg are ignored. After {@link ConsoleEvent.CallEnded} (or the farmer's {@link ConsoleEvent.LegEnded})
 * the call is kept as ended, so a late tool call cannot start it again. At most {@value #MAX_CALLS} calls are kept,
 * oldest dropped first. The farmer's words are never logged.
 */
@Component
public class BuyerConsent implements AutoCloseable {
    static final int MAX_CALLS = 256;

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

    private static final class State {
        /** Written under the state's lock; volatile so {@link #ended(CallId)} can read it without waiting. */
        volatile boolean ended;
        final Map<LegId, Heard> heard = new HashMap<>();
        Set<String> suggested = Set.of();
        Question question;

        Heard heard(LegId leg) {
            return heard.computeIfAbsent(leg, l -> new Heard());
        }
    }

    private final Map<CallId, State> calls = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CallId, State> eldest) {
            return size() > MAX_CALLS;
        }
    };
    private final ConsoleBus.Subscription subscription;

    public BuyerConsent(ConsoleBus bus) {
        this.subscription = bus.subscribe(this::onEvent);
    }

    private void onEvent(ConsoleEvent event) {
        if (event instanceof ConsoleEvent.TurnHeard t && t.role() == LegRole.FARMER && t.legId() != null) {
            State s = state(t.callId());
            synchronized (s) {
                Heard h = s.heard(t.legId());
                h.count++;
                h.latest = TurnKey.of(t);
            }
        } else if (event instanceof ConsoleEvent.LegEnded e && e.role() == LegRole.FARMER) {
            end(e.callId());
        } else if (event instanceof ConsoleEvent.CallEnded e) {
            end(e.callId());
        }
    }

    /** Marks the call ended; waits for a dial in progress on it ({@link #dialWhileLive}) to return first. */
    private void end(CallId callId) {
        State s = state(callId);
        synchronized (s) {
            s.ended = true;
            s.suggested = Set.of();
            s.question = null;
        }
    }

    private synchronized State state(CallId callId) {
        return calls.computeIfAbsent(callId, id -> new State());
    }

    private synchronized Optional<State> existing(CallId callId) {
        return Optional.ofNullable(calls.get(callId));
    }

    /** Records the buyers just suggested on this call; they replace any earlier suggestion and question. */
    public void suggested(CallContext ctx, List<String> buyerIds) {
        State s = state(ctx.callId());
        synchronized (s) {
            if (s.ended) return;
            s.suggested = Set.copyOf(buyerIds);
            s.question = null;
        }
    }

    /**
     * Records that the farmer is being asked whether to call {@code buyerId}, replacing any earlier question. Only the
     * farmer's next turn after the one being answered can say yes to it.
     */
    public AskDecision asked(CallContext ctx, AnsweringTurn answering, String buyerId) {
        LegId leg = ctx.farmerLeg().id();
        State s = state(ctx.callId());
        synchronized (s) {
            if (s.ended) return AskDecision.ENDED;
            if (!s.suggested.contains(buyerId)) return AskDecision.NOT_SUGGESTED;
            Heard h = s.heard(leg);
            if (!fromFarmer(answering, ctx) || !answering.current()
                    || !TurnKey.of(answering.turn()).equals(h.latest)) {
                return AskDecision.STALE; // the farmer has said something newer, or this answer is over
            }
            s.question = new Question(leg, buyerId, h.count);
            return AskDecision.ASKED;
        }
    }

    /** Decides, and on {@link Decision#GRANTED} uses up the question so one yes cannot dial twice. */
    public Decision tryConsume(CallContext ctx, AnsweringTurn answering, String buyerId) {
        LegId leg = ctx.farmerLeg().id();
        Optional<State> found = existing(ctx.callId());
        if (found.isEmpty()) return Decision.NOT_SUGGESTED;
        State s = found.get();
        synchronized (s) {
            if (s.ended) return Decision.ENDED;
            if (!s.suggested.contains(buyerId)) return Decision.NOT_SUGGESTED;
            Question q = s.question;
            if (q == null || !q.farmerLeg().equals(leg)) return Decision.NOT_ASKED;
            if (!q.buyerId().equals(buyerId)) return Decision.OTHER_BUYER;
            Heard h = s.heard(leg);
            if (h.count <= q.afterTurn()) return Decision.NOT_ANSWERED;
            if (!fromFarmer(answering, ctx) || !answering.current()) return Decision.STALE;
            // Only the turn right after the question counts, and it must be the one being answered now.
            if (h.count != q.afterTurn() + 1 || !TurnKey.of(answering.turn()).equals(h.latest)) {
                s.question = null;
                return Decision.MOVED_ON;
            }
            s.question = null; // answered: a no, or an unclear reply, also needs a fresh question
            return ConsentReply.isClearYes(answering.turn().text()) ? Decision.GRANTED : Decision.NO_CONSENT;
        }
    }

    /**
     * Runs {@code dial} under the call's lock unless the call or farmer leg has ended or the answer is no longer
     * current; empty when it did not run. A hang-up that arrives meanwhile waits until {@code dial} returns, so the
     * dial must only start the buyer leg, not wait for it to answer.
     */
    public <T> Optional<T> dialWhileLive(CallContext ctx, AnsweringTurn answering, Supplier<T> dial) {
        Optional<State> found = existing(ctx.callId());
        if (found.isEmpty()) return Optional.empty();
        State s = found.get();
        synchronized (s) {
            if (s.ended || !answering.current()) return Optional.empty();
            return Optional.ofNullable(dial.get());
        }
    }

    /** True once the call's farmer leg or the call itself has ended (a hang-up waiting on a dial is not yet). */
    public boolean ended(CallId callId) {
        return existing(callId).map(s -> s.ended).orElse(false);
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
