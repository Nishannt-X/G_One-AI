package com.example.g_one.simulator;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.PlaybackOutcome;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Leg} whose far end is a browser tab on {@code /ws/simulator}. The pipeline sees the same contract as for a
 * Twilio leg.
 *
 * <p>Wire format, always mono PCM16 little-endian at 8000 Hz (what Twilio carries, minus the mulaw):
 * <ul>
 *   <li>browser to server: binary messages of microphone audio (the page sends 20 ms = 320 bytes); text
 *       {@code {"type":"mark","id":n}} when everything up to mark {@code n} has been heard; text
 *       {@code {"type":"hangup"}}.</li>
 *   <li>server to browser: binary messages of 20 ms of playback audio; text {@code {"type":"ready",...}} once on
 *       connect, {@code {"type":"mark","id":n}} after queued audio, {@code {"type":"clear"}} to drop whatever is not
 *       yet heard, {@code {"type":"end"}} when we hang up.</li>
 * </ul>
 * Like Twilio marks, a mark is echoed once the audio before it has played; {@link #playbackDone()} is built on them.
 * Safe to call from any thread; {@link #receive} must be called from one thread at a time (a socket does that).
 */
public final class SimulatorLeg implements Leg {
    private static final Logger log = LoggerFactory.getLogger(SimulatorLeg.class);

    /** Sample rate of every audio message in both directions. */
    public static final int WIRE_RATE = 8000;
    /** Playback audio goes out in pieces of 20 ms, like Twilio media. */
    static final int CHUNK_BYTES = WIRE_RATE / 50 * 2;
    static final String CLEAR = "{\"type\":\"clear\"}";
    static final String END = "{\"type\":\"end\"}";

    /** Where the leg sends to. Implementations throw {@link IOException} when the browser is gone. */
    public interface Channel {
        void sendAudio(byte[] pcm16le) throws IOException;

        void sendText(String json) throws IOException;

        /** Closes the socket. Idempotent; never throws. */
        void close();
    }

    private final LegId id = LegId.random();
    private final CallId callId;
    private final LegRole role;
    private final Language language;
    private final CallerKey callerKey;
    private final Channel channel;

    private final List<Consumer<AudioFrame>> audioListeners = new CopyOnWriteArrayList<>();
    private long receivedSamples;

    /** Guards everything below and the order of messages sent to the browser. */
    private final Object lock = new Object();
    private final List<Runnable> endListeners = new ArrayList<>();
    /** Marks sent and not yet echoed or overtaken by a clear, in order. */
    private final TreeMap<Long, CompletableFuture<PlaybackOutcome>> outstanding = new TreeMap<>();
    private long markSeq;
    /** Audio sent after the last mark (or the last clear). */
    private boolean audioSinceMark;
    private boolean ended;

    /** @param callerKey the hashed simulated number, or null for a withheld caller ID */
    public SimulatorLeg(CallId callId, LegRole role, Language language, CallerKey callerKey, Channel channel) {
        this.callId = callId;
        this.role = role;
        this.language = language;
        this.callerKey = callerKey;
        this.channel = channel;
    }

    @Override public LegId id() { return id; }
    @Override public CallId callId() { return callId; }
    @Override public LegRole role() { return role; }
    @Override public LegSource source() { return LegSource.SIMULATOR; }
    @Override public Language language() { return language; }
    @Override public Optional<CallerKey> callerKey() { return Optional.ofNullable(callerKey); }

    @Override
    public void play(AudioFrame frame) {
        byte[] pcm = toWireRate(frame);
        if (pcm.length == 0) {
            return;
        }
        boolean failed = false;
        synchronized (lock) {
            if (ended) {
                return;
            }
            try {
                for (int at = 0; at < pcm.length; at += CHUNK_BYTES) {
                    channel.sendAudio(Arrays.copyOfRange(pcm, at, Math.min(pcm.length, at + CHUNK_BYTES)));
                }
                audioSinceMark = true;
            } catch (IOException | RuntimeException e) {
                log.debug("Simulator leg {} lost its browser while playing: {}", id, e.toString());
                failed = true;
            }
        }
        if (failed) {
            end(true);
        }
    }

    @Override
    public void clearPlayback() {
        List<CompletableFuture<PlaybackOutcome>> overtaken;
        boolean failed = false;
        synchronized (lock) {
            if (ended || (!audioSinceMark && outstanding.isEmpty())) {
                return;
            }
            audioSinceMark = false;
            overtaken = List.copyOf(outstanding.values());
            outstanding.clear();
            try {
                channel.sendText(CLEAR);
            } catch (IOException | RuntimeException e) {
                failed = true;
            }
        }
        // The browser echoes marks it dropped too; those late echoes find nothing and are ignored.
        overtaken.forEach(f -> f.complete(PlaybackOutcome.CLEARED));
        if (failed) {
            end(true);
        }
    }

    @Override
    public CompletableFuture<PlaybackOutcome> playbackDone() {
        CompletableFuture<PlaybackOutcome> waiting;
        boolean failed = false;
        synchronized (lock) {
            if (ended) {
                return CompletableFuture.completedFuture(PlaybackOutcome.ENDED);
            }
            if (audioSinceMark) {
                long mark = ++markSeq;
                waiting = new CompletableFuture<>();
                outstanding.put(mark, waiting);
                audioSinceMark = false;
                try {
                    channel.sendText("{\"type\":\"mark\",\"id\":" + mark + "}");
                } catch (IOException | RuntimeException e) {
                    failed = true;
                }
            } else if (!outstanding.isEmpty()) {
                waiting = outstanding.lastEntry().getValue();
            } else {
                return CompletableFuture.completedFuture(PlaybackOutcome.PLAYED);
            }
        }
        if (failed) {
            end(true);
        }
        // A copy, so a caller cannot complete or cancel the future other callers share.
        return waiting.copy();
    }

    /** The browser says it has played everything up to mark {@code id}. Unknown or already overtaken marks are ignored. */
    public void onMark(long id) {
        List<CompletableFuture<PlaybackOutcome>> played;
        synchronized (lock) {
            var upToMark = outstanding.headMap(id, true);
            played = List.copyOf(upToMark.values());
            upToMark.clear();
        }
        played.forEach(f -> f.complete(PlaybackOutcome.PLAYED));
    }

    /**
     * The person's audio arrived from the browser. Odd-length or empty messages are dropped. Frames are timed on the
     * leg's own media clock, by the samples received so far.
     */
    public void receive(byte[] pcm16le) {
        if (pcm16le.length == 0 || pcm16le.length % 2 != 0) {
            return;
        }
        boolean live;
        synchronized (lock) {
            live = !ended;
        }
        if (!live) {
            return;
        }
        AudioFrame frame = new AudioFrame(pcm16le, WIRE_RATE, receivedSamples * 1000L / WIRE_RATE);
        receivedSamples += pcm16le.length / 2;
        for (Consumer<AudioFrame> listener : audioListeners) {
            try {
                listener.accept(frame);
            } catch (RuntimeException e) {
                log.warn("An audio listener failed on simulator leg {}", id, e);
            }
        }
    }

    @Override
    public void hangup() {
        end(true);
    }

    /** The browser went away (socket closed or errored). Ends the leg without sending anything. */
    public void remoteClosed() {
        end(false);
    }

    @Override
    public void onAudio(Consumer<AudioFrame> listener) {
        audioListeners.add(listener);
    }

    @Override
    public void onEnded(Runnable listener) {
        boolean runNow;
        synchronized (lock) {
            runNow = ended;
            if (!ended) {
                endListeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    private void end(boolean toBrowser) {
        List<CompletableFuture<PlaybackOutcome>> waiting;
        List<Runnable> toRun;
        synchronized (lock) {
            if (ended) {
                return;
            }
            ended = true;
            if (toBrowser) {
                try {
                    channel.sendText(END);
                } catch (IOException | RuntimeException e) {
                    // The browser is gone already; closing below is all that is left.
                }
            }
            waiting = List.copyOf(outstanding.values());
            outstanding.clear();
            toRun = List.copyOf(endListeners);
            endListeners.clear();
        }
        if (toBrowser) {
            channel.close();
        }
        waiting.forEach(f -> f.complete(PlaybackOutcome.ENDED));
        for (Runnable r : toRun) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("An end listener failed on simulator leg {}", id, e);
            }
        }
    }

    /** The frame's samples at {@link #WIRE_RATE}: averaged when going down, interpolated when going up. */
    static byte[] toWireRate(AudioFrame frame) {
        if (frame.sampleRate() == WIRE_RATE) {
            return frame.pcm16le();
        }
        int inCount = frame.samples();
        int outCount = (int) ((long) inCount * WIRE_RATE / frame.sampleRate());
        byte[] in = frame.pcm16le();
        byte[] out = new byte[outCount * 2];
        double ratio = (double) frame.sampleRate() / WIRE_RATE;
        for (int i = 0; i < outCount; i++) {
            double value;
            if (ratio >= 1) {
                int from = (int) Math.round(i * ratio);
                int to = Math.min(inCount, Math.max(from + 1, (int) Math.round((i + 1) * ratio)));
                long sum = 0;
                for (int s = from; s < to; s++) {
                    sum += sample(in, s);
                }
                value = (double) sum / (to - from);
            } else {
                double pos = i * ratio;
                int s = (int) pos;
                double frac = pos - s;
                value = sample(in, s) * (1 - frac) + sample(in, Math.min(inCount - 1, s + 1)) * frac;
            }
            short v = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private static short sample(byte[] pcm, int index) {
        return (short) ((pcm[2 * index] & 0xff) | (pcm[2 * index + 1] << 8));
    }
}
