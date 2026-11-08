package com.example.g_one.agent.pipeline;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.fakes.FakeTextToSpeech;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A {@link TextToSpeech} that speaks like {@link FakeTextToSpeech}, except for the texts a test marks with
 * {@link #fail}: those fail in the chosen way. It records every request, failed or not.
 */
final class FlakyTextToSpeech implements TextToSpeech {
    /** How the synthesis of a marked text goes wrong. */
    enum Failure {
        /** {@code speak} throws. */
        THROWS_ON_START,
        /** One frame reaches the sink, then {@code done()} completes exceptionally. */
        FAILS_AFTER_ONE_FRAME,
        /** One frame reaches the sink, then {@code done()} completes as cancelled, as if someone else cancelled it. */
        CANCELLED_AFTER_ONE_FRAME,
        /** {@code done()} completes normally, but no frame ever reaches the sink. */
        NO_FRAMES
    }

    private final FakeTextToSpeech delegate = new FakeTextToSpeech();
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    FlakyTextToSpeech fail(String text, Failure how) {
        failures.put(text, how);
        return this;
    }

    /** The text of every request, in order. */
    List<String> spoken() {
        return List.copyOf(requests);
    }

    @Override
    public Synthesis speak(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
        requests.add(text);
        Failure how = failures.get(text);
        if (how == null) {
            return delegate.speak(text, language, sampleRate, sink);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        switch (how) {
            case THROWS_ON_START -> throw new IllegalStateException("TTS down (test)");
            case FAILS_AFTER_ONE_FRAME -> {
                sink.accept(AudioFrame.silence(sampleRate, FakeTextToSpeech.FRAME_MS, 0));
                done.completeExceptionally(new IllegalStateException("TTS dropped mid-sentence (test)"));
            }
            case CANCELLED_AFTER_ONE_FRAME -> {
                sink.accept(AudioFrame.silence(sampleRate, FakeTextToSpeech.FRAME_MS, 0));
                done.cancel(false);
            }
            case NO_FRAMES -> done.complete(null);
        }
        return new Synthesis() {
            @Override
            public void cancel() {
                done.cancel(false);
            }

            @Override
            public CompletableFuture<Void> done() {
                return done;
            }
        };
    }
}
