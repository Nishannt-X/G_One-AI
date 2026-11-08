package com.example.g_one.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fans console events out to subscribers (the console WebSocket, tests). Delivery is synchronous on the publishing
 * thread, in subscription order, so subscribers must return quickly. A subscriber that throws is logged (without the
 * event's content) and does not stop the others or the publisher.
 */
@Component
public class ConsoleBus {
    private static final Logger log = LoggerFactory.getLogger(ConsoleBus.class);

    private final List<Consumer<ConsoleEvent>> subscribers = new CopyOnWriteArrayList<>();

    public void publish(ConsoleEvent event) {
        Objects.requireNonNull(event, "event");
        for (Consumer<ConsoleEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (RuntimeException e) {
                log.warn("A console subscriber failed on a {} event for call {}",
                        event.getClass().getSimpleName(), event.callId(), e);
            }
        }
    }

    /** @return a handle whose {@code close()} stops delivery to this subscriber */
    public Subscription subscribe(Consumer<ConsoleEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        Consumer<ConsoleEvent> wrapped = subscriber::accept; // a distinct instance, so subscribing twice works
        subscribers.add(wrapped);
        return () -> subscribers.remove(wrapped);
    }

    /** Stops delivery. Idempotent. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
