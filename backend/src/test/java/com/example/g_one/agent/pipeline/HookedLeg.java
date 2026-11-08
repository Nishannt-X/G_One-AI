package com.example.g_one.agent.pipeline;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.fakes.FakeLeg;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link Leg} that behaves like its {@link FakeLeg} except where a test overrides {@link #playbackDone()} or
 * {@link #play}, for example to behave the way a broken transport would.
 */
final class HookedLeg implements Leg {
    final FakeLeg fake;
    /** Replaces the fake's {@link #playbackDone()} when set. */
    volatile Supplier<CompletableFuture<PlaybackOutcome>> playbackDone;
    /** Replaces the fake's {@link #play} when set. */
    volatile Consumer<AudioFrame> play;

    HookedLeg(FakeLeg fake) {
        this.fake = fake;
    }

    @Override public LegId id() { return fake.id(); }
    @Override public CallId callId() { return fake.callId(); }
    @Override public LegRole role() { return fake.role(); }
    @Override public LegSource source() { return fake.source(); }
    @Override public Language language() { return fake.language(); }
    @Override public Optional<CallerKey> callerKey() { return fake.callerKey(); }
    @Override
    public void play(AudioFrame frame) {
        Consumer<AudioFrame> override = play;
        if (override != null) {
            override.accept(frame);
        } else {
            fake.play(frame);
        }
    }

    @Override public void clearPlayback() { fake.clearPlayback(); }
    @Override public void hangup() { fake.hangup(); }
    @Override public void onAudio(Consumer<AudioFrame> listener) { fake.onAudio(listener); }
    @Override public void onEnded(Runnable listener) { fake.onEnded(listener); }

    @Override
    public CompletableFuture<PlaybackOutcome> playbackDone() {
        Supplier<CompletableFuture<PlaybackOutcome>> override = playbackDone;
        return override != null ? override.get() : fake.playbackDone();
    }
}
