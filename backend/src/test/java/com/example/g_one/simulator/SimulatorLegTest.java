package com.example.g_one.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.fakes.FakeLeg;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The simulator leg alone, with a fake socket: audio in, audio out, clear, marks and ending. */
class SimulatorLegTest {

    /** Records what the leg sends; can be told the browser is gone. */
    static final class FakeChannel implements SimulatorLeg.Channel {
        final List<byte[]> audio = new CopyOnWriteArrayList<>();
        /** Audio and text in the order sent, audio shown as "audio". */
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean broken;
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void sendAudio(byte[] pcm16le) throws IOException {
            if (broken) throw new IOException("gone");
            audio.add(pcm16le);
            sent.add("audio");
        }

        @Override
        public void sendText(String json) throws IOException {
            if (broken) throw new IOException("gone");
            sent.add(json);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private final FakeChannel channel = new FakeChannel();
    private final SimulatorLeg leg = new SimulatorLeg(CallId.random(), LegRole.FARMER, FakeLeg.HINDI,
            FakeLeg.FARMER_KEY, channel);

    private static byte[] pcm(int samples, int value) {
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            b[2 * i] = (byte) value;
            b[2 * i + 1] = (byte) (value >> 8);
        }
        return b;
    }

    @Test
    void identifiesItselfAsASimulatorLeg() {
        assertThat(leg.source()).isEqualTo(LegSource.SIMULATOR);
        assertThat(leg.role()).isEqualTo(LegRole.FARMER);
        assertThat(leg.language()).isEqualTo(FakeLeg.HINDI);
        assertThat(leg.callerKey()).contains(FakeLeg.FARMER_KEY);
    }

    @Test
    void aWithheldCallerIdHasNoKey() {
        var anonymous = new SimulatorLeg(CallId.random(), LegRole.FARMER, FakeLeg.HINDI, null, channel);
        assertThat(anonymous.callerKey()).isEmpty();
    }

    @Test
    void microphoneFramesReachListenersInOrderOnTheLegsOwnClock() {
        List<AudioFrame> heard = new ArrayList<>();
        leg.onAudio(heard::add);

        leg.receive(pcm(160, 1));
        leg.receive(pcm(160, 2));
        leg.receive(pcm(160, 3));

        assertThat(heard).extracting(AudioFrame::timestampMs).containsExactly(0L, 20L, 40L);
        assertThat(heard).extracting(AudioFrame::sampleRate).containsOnly(8000);
        assertThat(heard).extracting(AudioFrame::samples).containsOnly(160);
        assertThat(heard.get(2).pcm16le()).isEqualTo(pcm(160, 3));
    }

    @Test
    void emptyAndOddMessagesAreDroppedAndAFailingListenerDoesNotStopTheOthers() {
        List<AudioFrame> heard = new ArrayList<>();
        leg.onAudio(f -> { throw new IllegalStateException("boom"); });
        leg.onAudio(heard::add);

        leg.receive(new byte[0]);
        leg.receive(new byte[5]);
        leg.receive(pcm(160, 1));

        assertThat(heard).hasSize(1);
    }

    @Test
    void audioAfterTheLegEndedIsDropped() {
        List<AudioFrame> heard = new ArrayList<>();
        leg.onAudio(heard::add);
        leg.remoteClosed();
        leg.receive(pcm(160, 1));
        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));
        assertThat(heard).isEmpty();
        assertThat(channel.sent).isEmpty();
    }

    @Test
    void playbackGoesOutAs20MsPiecesAt8kHz() {
        leg.play(new AudioFrame(pcm(400, 7), 8000, 0)); // 50 ms

        assertThat(channel.audio).extracting(b -> b.length).containsExactly(320, 320, 160);
        assertThat(channel.audio.get(0)).isEqualTo(pcm(160, 7));
    }

    @Test
    void otherSampleRatesAreConvertedToEightKilohertz() {
        leg.play(new AudioFrame(pcm(2400, 1000), 24_000, 0)); // 100 ms of Timbre-rate audio

        int bytes = channel.audio.stream().mapToInt(b -> b.length).sum();
        assertThat(bytes).isEqualTo(800 * 2);
        // A steady level stays at that level.
        assertThat(channel.audio.get(0)[0] & 0xff).isEqualTo(1000 & 0xff);
        assertThat(channel.audio.get(0)[1]).isEqualTo((byte) (1000 >> 8));
    }

    @Test
    void upsamplingKeepsTheLength() {
        assertThat(SimulatorLeg.toWireRate(new AudioFrame(pcm(40, 5), 4000, 0))).hasSize(80 * 2);
    }

    @Test
    void playbackDoneIsPlayedAtOnceWhenNothingIsQueued() {
        assertThat(leg.playbackDone()).isCompletedWithValue(PlaybackOutcome.PLAYED);
        assertThat(channel.sent).isEmpty();
    }

    @Test
    void playbackDoneWaitsForTheBrowsersMark() {
        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));
        var done = leg.playbackDone();

        assertThat(channel.sent).containsExactly("audio", "{\"type\":\"mark\",\"id\":1}");
        assertThat(done).isNotDone();

        leg.onMark(1);
        assertThat(done).isCompletedWithValue(PlaybackOutcome.PLAYED);
        assertThat(leg.playbackDone()).isCompletedWithValue(PlaybackOutcome.PLAYED);
    }

    @Test
    void twoWaitersWithoutNewAudioShareOneMarkAndAnEarlierMarkIsCoveredByALaterOne() {
        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));
        var first = leg.playbackDone();
        leg.play(new AudioFrame(pcm(160, 1), 8000, 20));
        var second = leg.playbackDone();
        var third = leg.playbackDone();

        assertThat(channel.sent).containsExactly("audio", "{\"type\":\"mark\",\"id\":1}", "audio",
                "{\"type\":\"mark\",\"id\":2}");
        leg.onMark(2); // marks come back in order; the later one proves the earlier
        assertThat(first).isCompletedWithValue(PlaybackOutcome.PLAYED);
        assertThat(second).isCompletedWithValue(PlaybackOutcome.PLAYED);
        assertThat(third).isCompletedWithValue(PlaybackOutcome.PLAYED);
    }

    @Test
    void anUnknownMarkIsIgnored() {
        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));
        var done = leg.playbackDone();
        leg.onMark(0); // there is no mark 0
        assertThat(done).isNotDone();
    }

    @Test
    void clearDropsWhatIsQueuedAndReportsClearedNotPlayed() {
        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));
        var done = leg.playbackDone();

        leg.clearPlayback();

        assertThat(channel.sent).endsWith(SimulatorLeg.CLEAR);
        assertThat(done).isCompletedWithValue(PlaybackOutcome.CLEARED);
        leg.onMark(1); // the browser's echo of a dropped mark arrives late
        assertThat(done).isCompletedWithValue(PlaybackOutcome.CLEARED);
        assertThat(leg.playbackDone()).isCompletedWithValue(PlaybackOutcome.PLAYED);
    }

    @Test
    void audioQueuedAfterAClearIsPlayedNormally() {
        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));
        leg.clearPlayback();
        leg.play(new AudioFrame(pcm(160, 2), 8000, 20));
        var done = leg.playbackDone();

        leg.onMark(1);
        assertThat(done).isCompletedWithValue(PlaybackOutcome.PLAYED);
    }

    @Test
    void clearIsHarmlessWhenNothingIsPlaying() {
        leg.clearPlayback();
        assertThat(channel.sent).isEmpty();

        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));
        leg.playbackDone();
        leg.onMark(1);
        leg.clearPlayback(); // everything was heard already
        assertThat(channel.sent).doesNotContain(SimulatorLeg.CLEAR);
    }

    @Test
    void hangupTellsTheBrowserClosesAndEndsOnce() {
        AtomicInteger ended = new AtomicInteger();
        leg.onEnded(ended::incrementAndGet);
        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));
        var done = leg.playbackDone();

        leg.hangup();
        leg.hangup();
        leg.remoteClosed();

        assertThat(channel.sent).endsWith(SimulatorLeg.END);
        assertThat(channel.closes).hasValue(1);
        assertThat(ended).hasValue(1);
        assertThat(done).isCompletedWithValue(PlaybackOutcome.ENDED);
        assertThat(leg.playbackDone()).isCompletedWithValue(PlaybackOutcome.ENDED);
    }

    @Test
    void theBrowserClosingEndsTheLegWithoutSendingAnything() {
        AtomicInteger ended = new AtomicInteger();
        leg.onEnded(ended::incrementAndGet);

        leg.remoteClosed();

        assertThat(channel.sent).isEmpty();
        assertThat(channel.closes).hasValue(0);
        assertThat(ended).hasValue(1);
    }

    @Test
    void anEndListenerAddedAfterTheEndRunsAtOnce() {
        leg.remoteClosed();
        AtomicInteger ended = new AtomicInteger();
        leg.onEnded(ended::incrementAndGet);
        assertThat(ended).hasValue(1);
    }

    @Test
    void aFailedSendEndsTheLeg() {
        AtomicInteger ended = new AtomicInteger();
        leg.onEnded(ended::incrementAndGet);
        channel.broken = true;

        leg.play(new AudioFrame(pcm(160, 1), 8000, 0));

        assertThat(ended).hasValue(1);
        assertThat(channel.closes).hasValue(1);
    }
}
