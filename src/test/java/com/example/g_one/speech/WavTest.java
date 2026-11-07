package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.ApiException;
import com.example.g_one.TestAudio;
import org.junit.jupiter.api.Test;

class WavTest {
    @Test
    void acceptsAndMeasuresShortPcm() {
        assertThat(Wav.validate(TestAudio.wav(2, 16000), 30)).isEqualTo(2.0);
    }

    @Test
    void rejectsNonWavTooShortAndTooLong() {
        assertThatThrownBy(() -> Wav.validate("RIFFxxxxWAVE".getBytes(), 30)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Wav.validate(TestAudio.wav(0.05, 16000), 30)).hasMessageContaining("too short");
        assertThatThrownBy(() -> Wav.validate(TestAudio.wav(31, 16000), 30))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(413));
    }
}
