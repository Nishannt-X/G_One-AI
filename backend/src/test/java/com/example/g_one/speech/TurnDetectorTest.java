package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.AudioFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnDetectorTest {
    record Ended(byte[] pcm, int rate, long startedMs, long endedMs) {}

    /** Feeds audio on a fake wall clock that advances by the audio fed, like a live call. */
    private static final class Harness {
        long now = 1_000_000;
        long mediaMs;
        int speechStarts;
        final List<Ended> turns = new ArrayList<>();
        final List<String> order = new ArrayList<>();
        final TurnDetector detector;

        Harness(TurnDetector.Config config) {
            detector = new TurnDetector(config, new TurnDetector.Listener() {
                @Override
                public void speechStart() {
                    speechStarts++;
                    order.add("start");
                }

                @Override
                public void turn(byte[] pcm16le, int sampleRate, long startedMs, long endedMs) {
                    turns.add(new Ended(pcm16le, sampleRate, startedMs, endedMs));
                    order.add("turn");
                }
            }, () -> now);
        }

        void feed(byte[] pcm) {
            for (AudioFrame f : Tones.frames(pcm, 8000, mediaMs)) {
                now += 20;
                mediaMs += 20;
                detector.accept(f);
            }
        }

        void speech(int ms) {
            feed(Tones.sine(300, 8000, 8000, ms));
        }

        void quiet(int ms) {
            feed(Tones.silence(8000, ms));
        }
    }

    @Test
    void oneSpokenTurnGivesOneSpeechStartThenOneTurn() {
        Harness h = new Harness(TurnDetector.Config.defaults());
        h.quiet(300);
        h.speech(1000);
        h.quiet(1000);

        assertThat(h.order).containsExactly("start", "turn");
        Ended turn = h.turns.get(0);
        assertThat(turn.rate()).isEqualTo(8000);
        // pre-roll (200 ms) + speech (1000 ms) + the silence it took to decide (700 ms)
        assertThat(turn.pcm().length / 2 * 1000L / 8000).isBetween(1850L, 2000L);
        assertThat(turn.endedMs() - turn.startedMs()).isBetween(950L, 1050L);
    }

    @Test
    void speechStartFiresAfterTheMinimumSpeechNotAtTheEndOfTheTurn() {
        Harness h = new Harness(new TurnDetector.Config(500, 120, 700, 200, 25_000));
        h.speech(160);
        assertThat(h.speechStarts).isEqualTo(1);
        assertThat(h.turns).isEmpty();
    }

    @Test
    void aBlipShorterThanTheMinimumIsIgnored() {
        Harness h = new Harness(TurnDetector.Config.defaults());
        h.speech(60);
        h.quiet(1500);
        assertThat(h.speechStarts).isZero();
        assertThat(h.turns).isEmpty();
    }

    @Test
    void aShortPauseInsideATurnDoesNotSplitIt() {
        Harness h = new Harness(TurnDetector.Config.defaults());
        h.speech(600);
        h.quiet(400);
        h.speech(600);
        h.quiet(1000);
        assertThat(h.turns).hasSize(1);
        assertThat(h.speechStarts).isEqualTo(1);
    }

    @Test
    void theSilenceThatEndsATurnIsConfigurable() {
        Harness h = new Harness(new TurnDetector.Config(500, 120, 300, 100, 25_000));
        h.speech(600);
        h.quiet(400);
        h.speech(600);
        h.quiet(500);
        assertThat(h.turns).hasSize(2);
    }

    @Test
    void aVeryLongTurnIsCutAtTheCap() {
        Harness h = new Harness(new TurnDetector.Config(500, 120, 700, 200, 3000));
        h.speech(5000);
        assertThat(h.turns).hasSize(1);
        assertThat(h.turns.get(0).pcm().length / 2 * 1000L / 8000).isBetween(2900L, 3200L);
    }

    @Test
    void twoTurnsInARowAreTwoSeparateTurns() {
        Harness h = new Harness(TurnDetector.Config.defaults());
        h.speech(500);
        h.quiet(1000);
        h.speech(500);
        h.quiet(1000);
        assertThat(h.order).containsExactly("start", "turn", "start", "turn");
    }
}
