package com.example.g_one.fakes;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.TextToSpeech;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A {@link TextToSpeech} for tests that "speaks" silence: 20 ms frames whose total length grows with the text
 * ({@link #msPerChar} per character, rounded up to whole frames). By default the frames are delivered on the calling
 * thread before {@code speak} returns. After {@link #holdOutput()} they wait until {@link #releaseAll()}, so a test
 * can barge in while the assistant is still "speaking".
 */
public final class FakeTextToSpeech implements TextToSpeech {
    public static final int FRAME_MS = 20;

    public record Request(String text, Language language, int sampleRate) {}

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<FakeSynthesis> held = new CopyOnWriteArrayList<>();
    private volatile int msPerChar = 50;
    private volatile boolean hold;

    public FakeTextToSpeech msPerChar(int ms) {
        if (ms <= 0) {
            throw new IllegalArgumentException("ms must be positive");
        }
        this.msPerChar = ms;
        return this;
    }

    /** From now on, frames wait for {@link #releaseAll()}. */
    public FakeTextToSpeech holdOutput() {
        this.hold = true;
        return this;
    }

    /** Delivers every held synthesis that was not cancelled, oldest first. */
    public void releaseAll() {
        List<FakeSynthesis> now = new ArrayList<>(held);
        held.removeAll(now);
        now.forEach(FakeSynthesis::deliver);
    }

    /** The silent frames this fake produces for {@code text}. At least one frame. */
    public static List<AudioFrame> framesFor(String text, int sampleRate, int msPerChar) {
        long totalMs = (long) Math.max(1, text.codePointCount(0, text.length())) * msPerChar;
        int count = (int) ((totalMs + FRAME_MS - 1) / FRAME_MS);
        List<AudioFrame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            frames.add(AudioFrame.silence(sampleRate, FRAME_MS, (long) i * FRAME_MS));
        }
        return frames;
    }

    @Override
    public Synthesis speak(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
        requests.add(new Request(text, language, sampleRate));
        FakeSynthesis s = new FakeSynthesis(framesFor(text, sampleRate, msPerChar), sink);
        if (hold) {
            held.add(s);
        } else {
            s.deliver();
        }
        return s;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Syntheses waiting for {@link #releaseAll()}. */
    public int heldCount() {
        return held.size();
    }

    private static final class FakeSynthesis implements Synthesis {
        private final List<AudioFrame> frames;
        private final Consumer<AudioFrame> sink;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private boolean cancelled;

        FakeSynthesis(List<AudioFrame> frames, Consumer<AudioFrame> sink) {
            this.frames = frames;
            this.sink = sink;
        }

        void deliver() {
            for (AudioFrame f : frames) {
                synchronized (this) {
                    if (cancelled) {
                        return;
                    }
                    sink.accept(f);
                }
            }
            done.complete(null);
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
            done.cancel(false);
        }

        @Override
        public CompletableFuture<Void> done() {
            return done;
        }
    }
}
