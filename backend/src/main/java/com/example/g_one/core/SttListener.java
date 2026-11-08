package com.example.g_one.core;

/**
 * Events from one {@link SttSession}. Callbacks for one session never overlap and arrive in order; they may come on any
 * thread, so return quickly.
 */
public interface SttListener {
    /** The person started speaking. Fires as early as possible: it drives barge-in. */
    default void onSpeechStart() {}

    /** Text so far for the current turn. Optional: Prisma realtime sends none. Never treat it as a finished turn. */
    default void onPartial(String text) {}

    /** A finished turn. Empty transcripts are not reported. */
    void onFinalTurn(Turn turn);

    /** Something went wrong. The session may still be usable unless it also closed. */
    void onError(Exception error);
}
