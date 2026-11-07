package com.example.g_one.core;

/** A live recognition stream for one leg. Safe to feed from one thread while events arrive on another. */
public interface SttSession extends AutoCloseable {
    /** Sends the next piece of the person's audio, in order. Frames after {@link #close()} are dropped. */
    void feed(AudioFrame frame);

    /** Stops recognition and releases the connection. Idempotent. No events arrive after it returns. */
    @Override
    void close();
}
