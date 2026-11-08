package com.example.g_one.fakes;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.PlaybackOutcome;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A {@link Leg} for tests. It records what was played and how often playback was cleared, and lets the test push the
 * person's audio in ({@link #pushAudio}), finish playback ({@link #finishPlayback}) or hang up from the far side
 * ({@link #remoteHangup}). Queued audio counts as heard only when the test calls {@link #finishPlayback}. Listeners and
 * {@link #playbackDone} futures complete on the test's thread.
 */
public final class FakeLeg implements Leg {
    public static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");
    public static final Language ENGLISH = new Language("en-IN", "English", "Kaveri");
    /** Test salt (32 bytes, the minimum). Real salts are random bytes from configuration. */
    public static final byte[] SALT = "test-salt-not-a-secret-32-bytes!".getBytes(StandardCharsets.US_ASCII);
    /** Twilio's documented magic test numbers: synthetic, never a real person. */
    public static final CallerKey FARMER_KEY = CallerKey.fromPhone("+15005550006", SALT);
    public static final CallerKey BUYER_KEY = CallerKey.fromPhone("+15005550001", SALT);

    private final LegId id;
    private final CallId callId;
    private final LegRole role;
    private final LegSource source;
    private final Language language;
    private final CallerKey callerKey;

    private final List<AudioFrame> played = new CopyOnWriteArrayList<>();
    private final List<Consumer<AudioFrame>> audioListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> endListeners = new ArrayList<>();
    private final AtomicInteger clears = new AtomicInteger();
    private final List<CompletableFuture<PlaybackOutcome>> playbackWaiters = new ArrayList<>();
    private int unheardFrames;
    private boolean ended;
    private boolean hungUpByUs;
    private long pushedMs;

    /** @param callerKey the hashed caller number, or null for a withheld caller ID */
    public FakeLeg(CallId callId, LegRole role, LegSource source, Language language, CallerKey callerKey) {
        this.id = LegId.random();
        this.callId = callId;
        this.role = role;
        this.source = source;
        this.language = language;
        this.callerKey = callerKey;
    }

    /** A Hindi-speaking farmer on a simulated Twilio leg. */
    public static FakeLeg farmer(CallId callId) {
        return new FakeLeg(callId, LegRole.FARMER, LegSource.TWILIO, HINDI, FARMER_KEY);
    }

    /** A Hindi-speaking farmer whose caller ID is withheld: memory is off. */
    public static FakeLeg withheldFarmer(CallId callId) {
        return new FakeLeg(callId, LegRole.FARMER, LegSource.TWILIO, HINDI, null);
    }

    /** An English-speaking buyer on a simulated Twilio leg. */
    public static FakeLeg buyer(CallId callId) {
        return new FakeLeg(callId, LegRole.BUYER, LegSource.TWILIO, ENGLISH, BUYER_KEY);
    }

    @Override public LegId id() { return id; }
    @Override public CallId callId() { return callId; }
    @Override public LegRole role() { return role; }
    @Override public LegSource source() { return source; }
    @Override public Language language() { return language; }
    @Override public Optional<CallerKey> callerKey() { return Optional.ofNullable(callerKey); }

    @Override
    public void play(AudioFrame frame) {
        synchronized (this) {
            if (ended) {
                return;
            }
            played.add(frame);
            unheardFrames++;
        }
    }

    @Override
    public void clearPlayback() {
        clears.incrementAndGet();
        completeWaiters(PlaybackOutcome.CLEARED, false);
    }

    @Override
    public CompletableFuture<PlaybackOutcome> playbackDone() {
        synchronized (this) {
            if (ended) {
                return CompletableFuture.completedFuture(PlaybackOutcome.ENDED);
            }
            if (unheardFrames == 0) {
                return CompletableFuture.completedFuture(PlaybackOutcome.PLAYED);
            }
            CompletableFuture<PlaybackOutcome> done = new CompletableFuture<>();
            playbackWaiters.add(done);
            return done;
        }
    }

    /** The person has heard everything queued so far: pending {@link #playbackDone} futures complete as PLAYED. */
    public void finishPlayback() {
        completeWaiters(PlaybackOutcome.PLAYED, false);
    }

    /** Frames played but not yet heard or cleared. */
    public synchronized int unheardFrames() {
        return unheardFrames;
    }

    private void completeWaiters(PlaybackOutcome outcome, boolean endLeg) {
        List<CompletableFuture<PlaybackOutcome>> toComplete;
        synchronized (this) {
            if (ended && !endLeg) {
                return;
            }
            unheardFrames = 0;
            toComplete = List.copyOf(playbackWaiters);
            playbackWaiters.clear();
        }
        toComplete.forEach(f -> f.complete(outcome));
    }

    @Override
    public void hangup() {
        end(true);
    }

    @Override
    public void onAudio(Consumer<AudioFrame> listener) {
        audioListeners.add(listener);
    }

    @Override
    public void onEnded(Runnable listener) {
        boolean runNow;
        synchronized (this) {
            runNow = ended;
            if (!ended) {
                endListeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    /** The person speaks: delivers one frame to every audio listener. Ignored after the leg ended. */
    public void pushAudio(AudioFrame frame) {
        if (isEnded()) {
            return;
        }
        for (Consumer<AudioFrame> l : audioListeners) {
            l.accept(frame);
        }
    }

    /** Pushes {@code count} 20 ms frames of 8 kHz silence with rising timestamps. */
    public void pushSilence(int count) {
        for (int i = 0; i < count; i++) {
            long at;
            synchronized (this) {
                at = pushedMs;
                pushedMs += 20;
            }
            pushAudio(AudioFrame.silence(8000, 20, at));
        }
    }

    /** The person hangs up (or the stream stops). */
    public void remoteHangup() {
        end(false);
    }

    private void end(boolean byUs) {
        List<Runnable> toRun;
        synchronized (this) {
            if (ended) {
                return;
            }
            ended = true;
            hungUpByUs = byUs;
            toRun = List.copyOf(endListeners);
            endListeners.clear();
        }
        completeWaiters(PlaybackOutcome.ENDED, true);
        toRun.forEach(Runnable::run);
    }

    /** Every frame given to {@link #play}, in order (clearing playback does not remove them). */
    public List<AudioFrame> played() {
        return List.copyOf(played);
    }

    /** Total length of the played audio in milliseconds. */
    public long playedMs() {
        return played.stream().mapToLong(AudioFrame::durationMs).sum();
    }

    public int clearCount() {
        return clears.get();
    }

    public synchronized boolean isEnded() {
        return ended;
    }

    /** True when the leg ended through {@link #hangup()} rather than {@link #remoteHangup()}. */
    public synchronized boolean hungUpByUs() {
        return hungUpByUs;
    }
}
