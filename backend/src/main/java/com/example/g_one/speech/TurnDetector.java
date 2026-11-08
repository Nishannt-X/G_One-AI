package com.example.g_one.speech;

import com.example.g_one.core.AudioFrame;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Energy-based turn detection for 8 kHz PCM16 phone audio, used when there is no realtime endpointing from Gnani. A
 * turn starts after {@code minSpeechMs} of loud audio and ends after {@code silenceMs} of quiet; a little audio from
 * before the start is kept so the first syllable is not clipped. Not thread-safe: feed it from one thread.
 */
public final class TurnDetector {
    /** Settings, all in milliseconds except the level. */
    public record Config(double rmsThreshold, int minSpeechMs, int silenceMs, int preRollMs, int maxTurnMs) {
        /** A level of 500 (about -36 dBFS) separates speech from line hiss on phone audio. */
        public static Config defaults() {
            return new Config(500, 120, 700, 200, 25_000);
        }

        public Config {
            if (rmsThreshold <= 0 || minSpeechMs < 0 || silenceMs <= 0 || preRollMs < 0 || maxTurnMs <= 0) {
                throw new IllegalArgumentException("Turn detection settings must be positive");
            }
        }
    }

    /** What the detector reports. Called on the thread that feeds the detector. */
    public interface Listener {
        /** Speech was confirmed. Fires after {@code minSpeechMs} of loud audio, as early as is reliable. */
        void speechStart();

        /**
         * A turn ended.
         *
         * @param pcm16le the turn's audio at the fed sample rate
         * @param startedMs wall-clock epoch milliseconds when speech began
         * @param endedMs wall-clock epoch milliseconds when speech ended (before the trailing silence)
         */
        void turn(byte[] pcm16le, int sampleRate, long startedMs, long endedMs);
    }

    private final Config config;
    private final Listener listener;
    private final LongSupplier clock;

    private final Deque<AudioFrame> preRoll = new ArrayDeque<>();
    private long preRollMs;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private long voicedMs;
    private boolean speaking;
    private final ByteArrayOutputStream turn = new ByteArrayOutputStream();
    private long turnMs;
    private long silenceMs;
    private long startedWallMs;
    private int sampleRate;

    public TurnDetector(Config config, Listener listener, LongSupplier clock) {
        this.config = config;
        this.listener = listener;
        this.clock = clock;
    }

    /** Takes the next frame of the person's audio, in order. */
    public void accept(AudioFrame frame) {
        sampleRate = frame.sampleRate();
        boolean loud = rms(frame.pcm16le()) >= config.rmsThreshold();
        long ms = Math.max(1, frame.durationMs());
        if (!speaking) {
            idle(frame, loud, ms);
        } else {
            active(frame, loud, ms);
        }
    }

    private void idle(AudioFrame frame, boolean loud, long ms) {
        if (!loud) {
            voicedMs = 0;
            pending.reset();
            keepPreRoll(frame, ms);
            return;
        }
        voicedMs += ms;
        pending.writeBytes(frame.pcm16le());
        if (voicedMs < config.minSpeechMs()) return;

        speaking = true;
        startedWallMs = clock.getAsLong() - voicedMs;
        turn.reset();
        for (AudioFrame f : preRoll) turn.writeBytes(f.pcm16le());
        turn.writeBytes(pending.toByteArray());
        turnMs = preRollMs + voicedMs;
        silenceMs = 0;
        preRoll.clear();
        preRollMs = 0;
        pending.reset();
        voicedMs = 0;
        listener.speechStart();
    }

    private void active(AudioFrame frame, boolean loud, long ms) {
        turn.writeBytes(frame.pcm16le());
        turnMs += ms;
        silenceMs = loud ? 0 : silenceMs + ms;
        if (silenceMs >= config.silenceMs() || turnMs >= config.maxTurnMs()) {
            long now = clock.getAsLong();
            byte[] audio = turn.toByteArray();
            long started = startedWallMs;
            long ended = Math.max(started, now - silenceMs);
            speaking = false;
            turn.reset();
            turnMs = 0;
            silenceMs = 0;
            listener.turn(audio, sampleRate, started, ended);
        }
    }

    private void keepPreRoll(AudioFrame frame, long ms) {
        preRoll.addLast(frame);
        preRollMs += ms;
        while (preRollMs > config.preRollMs() && !preRoll.isEmpty()) {
            preRollMs -= Math.max(1, preRoll.removeFirst().durationMs());
        }
    }

    static double rms(byte[] pcm16le) {
        int n = pcm16le.length / 2;
        if (n == 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8));
            sum += (double) s * s;
        }
        return Math.sqrt(sum / n);
    }
}
