package com.example.g_one.core;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** Guards {@link #pending} and {@link #announcing}, and is held while listeners run. */
    private final Object announceLock = new Object();
    private final Deque<Pending> pending = new ArrayDeque<>();
    private boolean announcing;

    /**
     * Adds a live leg, then tells every {@link #onLegAdded} listener on the calling thread.
     *
     * <p>Guarantees for listeners:
     * <ul>
     *   <li>Listener calls never overlap: registrations from many threads are announced one at a time, each leg to all
     *       listeners in the order they were added, before the next leg is announced. Keep listeners quick; hand slow
     *       work to another thread.</li>
     *   <li>A listener may itself call {@code register}. That call adds the leg and returns at once; the leg is
     *       announced on the same thread after the current leg has been announced to every listener, never from inside a
     *       listener call. Legs are announced in the order they were registered.</li>
     *   <li>A leg that has already ended when its announcement starts (when it is registered, or while it waits behind
     *       another announcement) is not announced and is not kept.</li>
     *   <li>A listener sees a leg before any end of that leg it observes: if the leg ends while it is being announced, an
     *       {@link Leg#onEnded} listener added from {@code onLegAdded} still runs (right away, per the {@link Leg}
     *       contract).</li>
     * </ul>
     * A listener that throws is logged and does not stop the others.
     *
     * @throws IllegalStateException when a leg with the same id is already registered
     */
    public void register(Leg leg) {
        if (byId.putIfAbsent(leg.id(), leg) != null) {
            throw new IllegalStateException("Leg already registered: " + leg.id());
        }
        Pending entry = new Pending(leg, new AtomicBoolean());
        // Runs right away when the leg has already ended, so the check in announceQueued sees it.
        leg.onEnded(() -> {
            entry.ended().set(true);
            byId.remove(leg.id(), leg);
        });
        synchronized (announceLock) {
            pending.addLast(entry);
            if (announcing) {
                // Called from a listener on this thread (the lock is reentrant): the running loop announces it next.
                return;
            }
            announcing = true;
            try {
                announceQueued();
            } finally {
                announcing = false;
            }
        }
    }

    /** Announces queued legs one at a time until none are left. Runs with {@link #announceLock} held. */
    private void announceQueued() {
        Pending next;
        while ((next = pending.pollFirst()) != null) {
            Leg leg = next.leg();
            if (next.ended().get()) {
                log.debug("Leg {} ended before it was announced; not announcing it", leg.id());
                continue;
            }
            for (Consumer<Leg> l : listeners) {
                try {
                    l.accept(leg);
                } catch (RuntimeException e) {
                    log.warn("A leg listener failed for leg {}", leg.id(), e);
                }
            }
        }
    }

    private record Pending(Leg leg, AtomicBoolean ended) {
    }

    /** Runs for every leg registered after this call (see {@link #register} for ordering). */
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
