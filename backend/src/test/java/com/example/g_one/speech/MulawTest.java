package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MulawTest {
    private static short sample(byte[] pcm, int i) {
        return (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
    }

    @Test
    void decodesTheG711ReferenceValues() {
        byte[] pcm = Mulaw.decode(new byte[] {(byte) 0xFF, 0x7F, 0x00, (byte) 0x80});
        assertThat(sample(pcm, 0)).isZero();          // positive zero
        assertThat(sample(pcm, 1)).isZero();          // negative zero
        assertThat(sample(pcm, 2)).isEqualTo((short) -32124); // largest negative
        assertThat(sample(pcm, 3)).isEqualTo((short) 32124);  // largest positive
    }

    @Test
    void roundTripStaysWithinTheCodecsQuantisationError() {
        byte[] pcm = Tones.sine(440, 12000, 8000, 200);
        byte[] back = Mulaw.decode(Mulaw.encode(pcm));
        assertThat(back).hasSameSizeAs(pcm);
        for (int i = 0; i < pcm.length / 2; i++) {
            int x = sample(pcm, i);
            // G.711 step grows with level, but is never larger than 3% of the sample plus the bias.
            assertThat(Math.abs(sample(back, i) - x)).isLessThanOrEqualTo(Math.max(40, Math.abs(x) / 16));
        }
    }

    @Test
    void clipsFullScaleInsteadOfWrapping() {
        byte[] pcm = {(byte) 0xFF, 0x7F, 0x00, (byte) 0x80}; // 32767, -32768
        byte[] back = Mulaw.decode(Mulaw.encode(pcm));
        assertThat(sample(back, 0)).isGreaterThan((short) 30000);
        assertThat(sample(back, 1)).isLessThan((short) -30000);
    }

    @Test
    void rejectsHalfASample() {
        assertThatThrownBy(() -> Mulaw.encode(new byte[3])).isInstanceOf(IllegalArgumentException.class);
    }
}
