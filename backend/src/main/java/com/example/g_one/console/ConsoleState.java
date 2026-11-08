package com.example.g_one.console;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Remembers the events of calls that are still running, so a console opened mid-call (or after a page reload) sees
 * the whole call so far, then the live events. A call is forgotten when its {@code call_ended} event has been
 * delivered. Memory is bounded: at most {@value #MAX_CALLS} calls and {@value #MAX_EVENTS_PER_CALL} events per call.
 * All state changes and deliveries happen under one lock, so a new subscriber never misses or repeats an event.
 */
@Component
public class ConsoleState {
    static final int MAX_CALLS = 20;
    static final int MAX_EVENTS_PER_CALL = 2000;

    private static final Logger log = LoggerFactory.getLogger(ConsoleState.class);

    private final Map<CallId, CallRecord> calls = new LinkedHashMap<>();
    private final List<Consumer<ConsoleEvent>> listeners = new ArrayList<>();
    private final ConsoleBus.Subscription busSubscription;

    public ConsoleState(ConsoleBus bus) {
        this.busSubscription = bus.subscribe(this::accept);
    }

    @PreDestroy
    void close() {
        busSubscription.close();
    }

    private synchronized void accept(ConsoleEvent event) {
        CallRecord record = calls.get(event.callId());
        if (event instanceof ConsoleEvent.CallStarted started) {
            record = new CallRecord(started);
            calls.put(event.callId(), record);
            evictOldest();
        }
        // Events for a call that never started (or already ended) are still delivered live, but not kept.
        if (record != null) record.add(event);
        for (Consumer<ConsoleEvent> listener : List.copyOf(listeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.warn("A console listener failed on a {} event for call {}",
                        event.getClass().getSimpleName(), event.callId(), e);
            }
        }
        if (event instanceof ConsoleEvent.CallEnded) calls.remove(event.callId());
    }

    private void evictOldest() {
        Iterator<CallId> it = calls.keySet().iterator();
        while (calls.size() > MAX_CALLS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /**
     * Sends {@code listener} every kept event of the running calls, then every later event, in order. The listener
     * runs on the publishing thread while the lock is held, so it must be quick.
     *
     * @return a handle that stops delivery; run it once the listener's connection is gone
     */
    public synchronized Runnable subscribe(Consumer<ConsoleEvent> listener) {
        for (CallRecord record : calls.values()) {
            for (ConsoleEvent event : record.events) listener.accept(event);
        }
        listeners.add(listener);
        return () -> {
            synchronized (this) {
                listeners.remove(listener);
            }
        };
    }

    /** The calls that have started and not ended, oldest first. */
    public synchronized List<ActiveCall> activeCalls() {
        return calls.values().stream().map(CallRecord::toActiveCall).toList();
    }

    private static final class CallRecord {
        final ConsoleEvent.CallStarted started;
        final List<ConsoleEvent> events = new ArrayList<>();
        final Map<LegId, ActiveCall.Leg> legs = new LinkedHashMap<>();

        CallRecord(ConsoleEvent.CallStarted started) {
            this.started = started;
        }

        void add(ConsoleEvent event) {
            if (event instanceof ConsoleEvent.LegJoined joined) {
                legs.put(joined.legId(), new ActiveCall.Leg(joined.legId(), joined.role(), joined.source(),
                        joined.language()));
            } else if (event instanceof ConsoleEvent.LegEnded ended) {
                legs.remove(ended.legId());
            }
            if (events.size() < MAX_EVENTS_PER_CALL || event instanceof ConsoleEvent.CallEnded) events.add(event);
        }

        ActiveCall toActiveCall() {
            return new ActiveCall(started.callId(), started.atMs(), started.language(), List.copyOf(legs.values()));
        }
    }
}
