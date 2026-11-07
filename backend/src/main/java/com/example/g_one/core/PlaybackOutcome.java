package com.example.g_one.core;

/** How the audio queued on a {@link Leg} finished, as reported by {@link Leg#playbackDone()}. */
public enum PlaybackOutcome {
    /** The person heard all of it. */
    PLAYED,
    /** Some of it was dropped by {@link Leg#clearPlayback()} (barge-in) before it was heard. */
    CLEARED,
    /** The leg ended before all of it was heard. */
    ENDED
}
