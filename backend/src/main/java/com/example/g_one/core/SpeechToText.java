package com.example.g_one.core;

/**
 * Turns a leg's audio into turns. One implementation can be a realtime Prisma stream with Gnani endpointing, another
 * our own turn detection plus Prisma REST per turn; callers cannot tell them apart.
 */
public interface SpeechToText {
    /**
     * Opens a session for one leg. The leg id and role are copied into every {@link Turn} the session reports.
     *
     * @param language the language to listen for (implementations may add hints for code-mixed speech)
     */
    SttSession open(LegId legId, LegRole role, Language language, SttListener listener);
}
