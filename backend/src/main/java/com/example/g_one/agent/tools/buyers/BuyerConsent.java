package com.example.g_one.agent.tools.buyers;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegRole;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Per-call record of what {@code connect_buyer} needs to allow a dial: which buyers {@code find_buyers} suggested, and
 * the farmer's latest finished turn. The farmer's words come from the pipeline's {@link ConsoleEvent.TurnHeard} events,
 * which it publishes before it asks the brain, so the code reads the farmer itself rather than trusting the model.
 *
 * <p>A connection is {@link Decision#GRANTED} only when the buyer was suggested on this call, the farmer has spoken
 * since the suggestion, their latest turn is a clear yes ({@link ConsentReply}), and that turn's yes has not already
 * been used for a connection. Entries go at {@link ConsoleEvent.CallEnded}; at most {@value #MAX_CALLS} calls are kept,
 * oldest dropped first. The farmer's words are never logged.
 */
@Component
public class BuyerConsent implements AutoCloseable {
    static final int MAX_CALLS = 256;

    public enum Decision { GRANTED, NOT_SUGGESTED, NOT_ANSWERED, NO_CONSENT, ALREADY_USED }

    private static final class State {
        int farmerTurns;
        String latestFarmerText = "";
        int suggestedAt = -1;
        Set<String> suggested = Set.of();
        int usedAt = -1;
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

    private synchronized void onEvent(ConsoleEvent event) {
        if (event instanceof ConsoleEvent.TurnHeard t && t.role() == LegRole.FARMER) {
            State s = calls.computeIfAbsent(t.callId(), id -> new State());
            s.farmerTurns++;
            s.latestFarmerText = t.text() == null ? "" : t.text();
        } else if (event instanceof ConsoleEvent.CallEnded e) {
            calls.remove(e.callId());
        }
    }

    /** Records the buyers just suggested on this call; they replace any earlier suggestion. */
    public synchronized void suggested(CallId callId, List<String> buyerIds) {
        State s = calls.computeIfAbsent(callId, id -> new State());
        s.suggested = Set.copyOf(buyerIds);
        s.suggestedAt = s.farmerTurns;
    }

    /** Decides, and on {@link Decision#GRANTED} uses up the farmer's yes so it cannot dial twice. */
    public synchronized Decision tryConsume(CallId callId, String buyerId) {
        State s = calls.get(callId);
        if (s == null || !s.suggested.contains(buyerId)) return Decision.NOT_SUGGESTED;
        if (s.farmerTurns <= s.suggestedAt) return Decision.NOT_ANSWERED;
        if (s.usedAt == s.farmerTurns) return Decision.ALREADY_USED;
        if (!ConsentReply.isClearYes(s.latestFarmerText)) return Decision.NO_CONSENT;
        s.usedAt = s.farmerTurns;
        return Decision.GRANTED;
    }

    @PreDestroy
    @Override
    public void close() {
        subscription.close();
    }
}
