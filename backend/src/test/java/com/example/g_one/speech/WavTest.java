package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.TestAudio;
import org.junit.jupiter.api.Test;

class WavTest {
    @Test
    void acceptsAndMeasuresShortPcm() {
        assertThat(Wav.validate(TestAudio.wav(2, 16000), 30)).isEqualTo(2.0);
    }

    @Test
    void rejectsNonWavTooShortAndTooLong() {
        assertThatThrownBy(() -> Wav.validate("RIFFxxxxWAVE".getBytes(), 30)).isInstanceOfSatisfying(BadAudioException.class, e -> assertThat(e.code()).isEqualTo("bad_audio"));
        assertThatThrownBy(() -> Wav.validate(TestAudio.wav(0.05, 16000), 30)).hasMessageContaining("too short");
        assertThatThrownBy(() -> Wav.validate(TestAudio.wav(31, 16000), 30))
                .isInstanceOfSatisfying(BadAudioException.class, e -> assertThat(e.code()).isEqualTo("too_long"));
    }
}
