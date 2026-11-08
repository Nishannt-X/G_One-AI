package com.example.g_one.speech;

import com.example.g_one.core.Language;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.TextToSpeech;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Text to speech through Timbre REST: one WAV comes back (24 kHz, with an open-ended length header), is cut down to the
 * requested rate with a low-pass, and is handed to the sink in 20 ms frames. This is the path verified live. It is not
 * streaming: the first frame leaves only after the whole clip has been synthesized.
 */
public final class TimbreRestTts implements TextToSpeech {
    private static final Logger log = LoggerFactory.getLogger(TimbreRestTts.class);

    private final TimbreClient timbre;
    private final Executor executor;

    public TimbreRestTts(TimbreClient timbre, Executor executor) {
        this.timbre = timbre;
        this.executor = executor;
    }

    public TimbreRestTts(TimbreClient timbre) {
        this(timbre, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public Synthesis speak(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
        Run run = new Run();
        executor.execute(() -> run.start(text, language, sampleRate, sink));
        return run;
    }

    private final class Run implements Synthesis {
        private final Object lock = new Object();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private boolean cancelled;

        void start(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
            try {
                if (text.isBlank()) {
                    done.complete(null);
                    return;
                }
                long requested = System.nanoTime();
                Wav.Pcm clip = Wav.pcm(timbre.synthesize(text, language));
                byte[] pcm = Resampler.resample(clip.pcm16le(), clip.sampleRate(), sampleRate);
                FrameChunker chunker = new FrameChunker(sampleRate, frame -> deliver(sink, frame, requested));
                chunker.add(pcm);
                chunker.flush();
                done.complete(null);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        }

        private boolean first = true;

        private void deliver(Consumer<AudioFrame> sink, AudioFrame frame, long requestedNanos) {
            synchronized (lock) {
                if (cancelled) return;
                if (first) {
                    first = false;
                    log.info("tts first_audio_ms={} path=rest", (System.nanoTime() - requestedNanos) / 1_000_000);
                }
                sink.accept(frame);
            }
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                cancelled = true;
            }
            done.cancel(false);
        }

        @Override
        public CompletableFuture<Void> done() {
            return done;
        }
    }
}
