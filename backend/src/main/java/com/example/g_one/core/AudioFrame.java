package com.example.g_one.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * A short piece of mono audio as signed 16-bit little-endian PCM. Telephony legs use 8000 Hz; a 20 ms telephony frame is
 * 160 samples (320 bytes). {@code timestampMs} is the position on the leg's own media clock (milliseconds since its
 * audio started), not wall-clock time. Treat {@code pcm16le} as read-only once the frame is made: it is not copied.
 */
public record AudioFrame(byte[] pcm16le, int sampleRate, long timestampMs) {

    public AudioFrame {
        Objects.requireNonNull(pcm16le, "pcm16le");
        if (pcm16le.length % 2 != 0) {
            throw new IllegalArgumentException("PCM16 audio needs an even number of bytes");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must not be negative");
        }
    }

    /** A frame of digital silence lasting {@code durationMs} at {@code sampleRate}. */
    public static AudioFrame silence(int sampleRate, int durationMs, long timestampMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        return new AudioFrame(new byte[(int) ((long) sampleRate * durationMs / 1000) * 2], sampleRate, timestampMs);
    }

    public int samples() {
        return pcm16le.length / 2;
    }

    /** Length of the audio in milliseconds, rounded down. */
    public long durationMs() {
        return samples() * 1000L / sampleRate;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AudioFrame f && sampleRate == f.sampleRate && timestampMs == f.timestampMs
                && Arrays.equals(pcm16le, f.pcm16le);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(sampleRate, timestampMs) + Arrays.hashCode(pcm16le);
    }

    @Override
    public String toString() {
        return "AudioFrame[" + pcm16le.length + " bytes, " + sampleRate + " Hz, at " + timestampMs + " ms]";
    }
}
