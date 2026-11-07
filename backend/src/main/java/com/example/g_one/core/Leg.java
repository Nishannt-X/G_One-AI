package com.example.g_one.core;

import java.util.function.Consumer;

/**
 * One phone connection between us and a person (a Twilio media stream or a call simulator socket). Implementations
 * must be safe to call from any thread.
 */
public interface Leg {
    LegId id();

    /** The call this leg belongs to. */
    CallId callId();

    LegRole role();

    LegSource source();

    /** The language the person on this leg is spoken to in. */
    Language language();

    /** The hashed number of the person on this leg. Caller ID is not identity. */
    CallerKey callerKey();

    /**
     * Queues audio for the person to hear, in order, without waiting for playback. Implementations convert from the
     * frame's sample rate to what the transport needs. Frames given after the leg ended are dropped.
     */
    void play(AudioFrame frame);

    /** Drops everything queued but not yet heard (barge-in). Harmless when nothing is playing. */
    void clearPlayback();

    /** Ends the leg. Idempotent. Listeners from {@link #onEnded} run once. */
    void hangup();

    /** Adds a listener for the person's audio, delivered in order as it arrives. */
    void onAudio(Consumer<AudioFrame> listener);

    /**
     * Adds a listener that runs once when the leg ends, for any reason (hang-up from either side, stream stop, error).
     * A listener added after the leg ended runs right away.
     */
    void onEnded(Runnable listener);
}
