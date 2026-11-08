package com.example.g_one.core;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

    /**
     * The hashed number of the person on this leg; empty when the caller ID was withheld or anonymous, in which case
     * memory is neither loaded nor saved for the call. Caller ID is not identity.
     */
    Optional<CallerKey> callerKey();

    /**
     * Queues audio for the person to hear, in order, without waiting for playback. Implementations convert from the
     * frame's sample rate to what the transport needs. Frames given after the leg ended are dropped.
     */
    void play(AudioFrame frame);

    /** Drops everything queued but not yet heard (barge-in). Harmless when nothing is playing. */
    void clearPlayback();

    /**
     * Completes when everything {@link #play queued} so far has finished: {@link PlaybackOutcome#PLAYED} once the person
     * has heard all of it, {@link PlaybackOutcome#CLEARED} when {@link #clearPlayback} dropped some of it first,
     * {@link PlaybackOutcome#ENDED} when the leg ended first. Audio queued after this call does not delay the returned
     * future. Returns a future already completed with {@code PLAYED} when nothing is waiting to be heard, or with
     * {@code ENDED} when the leg has ended. The future never completes exceptionally.
     *
     * <p>The deal read-back waits on this for both legs before it counts confirmations. On Twilio this maps to a
     * {@code mark} message sent after the queued media; Twilio also echoes marks for audio dropped by {@code clear}, so
     * an implementation must remember which marks a clear overtook and report those as {@code CLEARED}, not
     * {@code PLAYED}.
     */
    CompletableFuture<PlaybackOutcome> playbackDone();

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
