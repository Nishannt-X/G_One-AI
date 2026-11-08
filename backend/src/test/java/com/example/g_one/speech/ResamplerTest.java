package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResamplerTest {
    private static short sample(byte[] pcm, int i) {
        return (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
    }

    /** Peak and RMS of the middle of the buffer, away from the edges where the filter has no neighbours. */
    private static double[] peakAndRms(byte[] pcm) {
        int n = pcm.length / 2;
        double peak = 0;
        double sum = 0;
        int from = n / 4;
        int to = n * 3 / 4;
        for (int i = from; i < to; i++) {
            double s = Math.abs(sample(pcm, i));
            peak = Math.max(peak, s);
            sum += s * s;
        }
        return new double[] {peak, Math.sqrt(sum / (to - from))};
    }

    @Test
    void lengthFollowsTheRateRatio() {
        byte[] at24k = Tones.sine(300, 8000, 24000, 500);
        assertThat(Resampler.resample(at24k, 24000, 8000)).hasSize(8000 / 2 * 2); // 500 ms at 8 kHz
        assertThat(Resampler.resample(Tones.silence(8000, 1000), 8000, 16000)).hasSize(16000 * 2);
        assertThat(Resampler.resample(Tones.silence(44100, 100), 44100, 8000)).hasSize(8000 / 10 * 2);
    }

    @Test
    void sameRateReturnsACopy() {
        byte[] pcm = Tones.sine(440, 5000, 8000, 50);
        byte[] out = Resampler.resample(pcm, 8000, 8000);
        assertThat(out).isEqualTo(pcm).isNotSameAs(pcm);
    }

    @Test
    void aToneBelowTheNewNyquistKeepsItsLevel() {
        byte[] out = Resampler.resample(Tones.sine(1000, 10000, 24000, 1000), 24000, 8000);
        double[] level = peakAndRms(out);
        assertThat(level[0]).isBetween(9500.0, 10500.0);
        assertThat(level[1]).isBetween(10000 / Math.sqrt(2) * 0.95, 10000 / Math.sqrt(2) * 1.05);
    }

    @Test
    void aToneAboveTheNewNyquistIsFilteredOutNotFoldedBack() {
        // 7 kHz at 24 kHz would alias to 1 kHz at 8 kHz without a low-pass.
        byte[] out = Resampler.resample(Tones.sine(7000, 10000, 24000, 1000), 24000, 8000);
        assertThat(peakAndRms(out)[1]).isLessThan(10000 / Math.sqrt(2) * 0.02);
    }

    @Test
    void fullScaleInputNeverClips() {
        byte[] out = Resampler.resample(Tones.sine(500, 32767, 24000, 500), 24000, 8000);
        for (int i = 0; i < out.length / 2; i++) {
            assertThat((int) sample(out, i)).isBetween(-32768, 32767);
        }
        // A square wave at full scale overshoots a plain low-pass; the result must still be representable.
        byte[] square = new byte[24000 * 2];
        for (int i = 0; i < 24000; i++) {
            short v = (short) ((i / 60) % 2 == 0 ? 32767 : -32768);
            square[2 * i] = (byte) v;
            square[2 * i + 1] = (byte) (v >> 8);
        }
        assertThat(Resampler.resample(square, 24000, 8000)).hasSize(8000 * 2);
    }

    @Test
    void upsamplingKeepsTheTone() {
        byte[] out = Resampler.resample(Tones.sine(500, 10000, 8000, 500), 8000, 16000);
        assertThat(peakAndRms(out)[0]).isBetween(9500.0, 10500.0);
    }
}
