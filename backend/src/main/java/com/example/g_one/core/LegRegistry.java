package com.example.g_one.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The live legs, looked up per call. Telephony and the call simulator {@link #register} each new leg; the call pipeline
 * listens with {@link #onLegAdded}, so it cannot tell where a leg came from. A leg is removed when it ends.
 */
@Component
public class LegRegistry {
    private static final Logger log = LoggerFactory.getLogger(LegRegistry.class);

    private final Map<LegId, Leg> byId = new ConcurrentHashMap<>();
    private final List<Consumer<Leg>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Adds a live leg, then tells every {@link #onLegAdded} listener on the calling thread. A listener that throws is
     * logged and does not stop the others.
     *
     * @throws IllegalStateException when a leg with the same id is already registered
     */
    public void register(Leg leg) {
        if (byId.putIfAbsent(leg.id(), leg) != null) {
            throw new IllegalStateException("Leg already registered: " + leg.id());
        }
        leg.onEnded(() -> byId.remove(leg.id(), leg));
        for (Consumer<Leg> l : listeners) {
            try {
                l.accept(leg);
            } catch (RuntimeException e) {
                log.warn("A leg listener failed for leg {}", leg.id(), e);
            }
        }
    }

    /** Runs for every leg registered after this call. */
    public void onLegAdded(Consumer<Leg> listener) {
        listeners.add(listener);
    }

    public Optional<Leg> find(LegId id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The live leg with this role in the call, if any. */
    public Optional<Leg> find(CallId callId, LegRole role) {
        return byId.values().stream().filter(l -> l.callId().equals(callId) && l.role() == role).findFirst();
    }

    /** The call's live legs, farmer first. */
    public List<Leg> legs(CallId callId) {
        return byId.values().stream()
                .filter(l -> l.callId().equals(callId))
                .sorted(Comparator.comparing(Leg::role))
                .toList();
    }

    /** Calls that have at least one live leg. */
    public Set<CallId> activeCalls() {
        return byId.values().stream().map(Leg::callId).collect(Collectors.toUnmodifiableSet());
    }
}
