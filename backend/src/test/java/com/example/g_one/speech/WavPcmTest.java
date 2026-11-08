package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class WavPcmTest {
    private static byte[] withDataLength(byte[] wav, int declared) {
        byte[] copy = wav.clone();
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(40, declared);
        return copy;
    }

    @Test
    void wrapThenPcmRoundTrips() {
        byte[] pcm = Tones.sine(440, 6000, 8000, 100);
        Wav.Pcm back = Wav.pcm(Wav.wrap(pcm, 8000));
        assertThat(back.sampleRate()).isEqualTo(8000);
        assertThat(back.pcm16le()).isEqualTo(pcm);
        // What wrap() writes is a header validate() accepts: correct lengths, 16-bit mono PCM.
        byte[] half = Tones.sine(440, 6000, 8000, 500);
        assertThat(Wav.validate(Wav.wrap(half, 8000), 30)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void anOpenEndedHeaderMeansTheSamplesRunToTheEnd() {
        byte[] wav = Wav.wrap(Tones.sine(440, 6000, 24000, 100), 24000);
        int expected = wav.length - 44;
        assertThat(Wav.pcm(withDataLength(wav, 0)).pcm16le()).hasSize(expected);
        assertThat(Wav.pcm(withDataLength(wav, -1)).pcm16le()).hasSize(expected);          // 0xFFFFFFFF
        assertThat(Wav.pcm(withDataLength(wav, Integer.MAX_VALUE)).pcm16le()).hasSize(expected);
    }

    @Test
    void aHonestLengthIsRespectedEvenWithTrailingBytes() {
        byte[] pcm = Tones.sine(440, 6000, 8000, 50);
        byte[] wav = Wav.wrap(pcm, 8000);
        byte[] padded = java.util.Arrays.copyOf(wav, wav.length + 10);
        assertThat(Wav.pcm(padded).pcm16le()).hasSize(pcm.length);
    }

    @Test
    void rejectsWhatIsNotMono16BitPcm() {
        assertThatThrownBy(() -> Wav.pcm(new byte[100])).isInstanceOf(BadAudioException.class);
        byte[] stereo = Wav.wrap(new byte[400], 8000);
        ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN).putShort(22, (short) 2);
        assertThatThrownBy(() -> Wav.pcm(stereo)).isInstanceOf(BadAudioException.class);
    }
}
