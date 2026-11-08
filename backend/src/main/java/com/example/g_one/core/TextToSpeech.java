package com.example.g_one.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Speaks text as audio, streamed in frames so playback can start before synthesis ends. */
public interface TextToSpeech {

    /**
     * Starts speaking {@code text} and returns without waiting for the audio. Frames go to {@code sink} in order, one
     * at a time, at {@code sampleRate} (8000 for telephony legs).
     */
    Synthesis speak(String text, Language language, int sampleRate, Consumer<AudioFrame> sink);

    /** One running synthesis. */
    interface Synthesis {
        /**
         * Stops it (barge-in): no frame reaches the sink after this returns, and {@link #done()} completes as
         * cancelled. Idempotent; harmless after completion.
         */
        void cancel();

        /** Completes after the last frame was given to the sink; completes exceptionally on failure or cancel. */
        CompletableFuture<Void> done();
    }
}
