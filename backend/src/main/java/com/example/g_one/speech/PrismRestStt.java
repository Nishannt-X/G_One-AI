package com.example.g_one.speech;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.Turn;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Speech to text from our own turn detection plus one Prisma REST call per turn. This is the path verified live; the
 * realtime path ({@link PrismRealtimeStt}) is opt-in until it has been probed with the project's key.
 */
public final class PrismRestStt implements SpeechToText {
    private static final Logger log = LoggerFactory.getLogger(PrismRestStt.class);
    static final int RATE = 8000;

    private final PrismClient prism;
    private final TurnDetector.Config detection;
    private final LongSupplier clock;

    public PrismRestStt(PrismClient prism, TurnDetector.Config detection, LongSupplier clock) {
        this.prism = prism;
        this.detection = detection;
        this.clock = clock;
    }

    public PrismRestStt(PrismClient prism) {
        this(prism, TurnDetector.Config.defaults(), System::currentTimeMillis);
    }

    @Override
    public SttSession open(LegId legId, LegRole role, Language language, SttListener listener) {
        return new Session(legId, role, language, new GuardedListener(listener));
    }

    private final class Session implements SttSession {
        private final LegId legId;
        private final LegRole role;
        private final Language language;
        private final GuardedListener events;
        private final TurnDetector detector;
        // One worker keeps the turns in order: a short second turn must not overtake a long first one.
        private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

        Session(LegId legId, LegRole role, Language language, GuardedListener events) {
            this.legId = legId;
            this.role = role;
            this.language = language;
            this.events = events;
            this.detector = new TurnDetector(detection, new TurnDetector.Listener() {
                @Override
                public void speechStart() {
                    events.onSpeechStart();
                }

                @Override
                public void turn(byte[] pcm16le, int sampleRate, long startedMs, long endedMs) {
                    submit(pcm16le, startedMs, endedMs);
                }
            }, clock);
        }

        @Override
        public void feed(AudioFrame frame) {
            if (events.isClosed()) return;
            AudioFrame at8k = frame.sampleRate() == RATE ? frame
                    : new AudioFrame(Resampler.resample(frame.pcm16le(), frame.sampleRate(), RATE), RATE,
                            frame.timestampMs());
            detector.accept(at8k);
        }

        private void submit(byte[] pcm, long startedMs, long endedMs) {
            try {
                worker.execute(() -> transcribe(pcm, startedMs, endedMs));
            } catch (RejectedExecutionException closed) {
                // The session was closed while a turn ended; nobody is listening any more.
            }
        }

        private void transcribe(byte[] pcm, long startedMs, long endedMs) {
            if (events.isClosed()) return;
            try {
                String text = prism.transcribe(Wav.wrap(pcm, RATE), language.code()).trim();
                if (text.isEmpty()) return;
                long done = clock.getAsLong();
                log.info("stt turn leg={} speech_end_to_final_ms={} audio_ms={} path=rest", legId,
                        Math.max(0, done - endedMs), pcm.length / 2 * 1000L / RATE);
                events.onFinalTurn(new Turn(legId, role, language, text, startedMs, Math.max(startedMs, endedMs)));
            } catch (UpstreamException e) {
                if (!"no_speech".equals(e.stage())) events.onError(e);
            } catch (RuntimeException e) {
                events.onError(e);
            }
        }

        @Override
        public void close() {
            events.close();
            worker.shutdownNow();
        }
    }
}
