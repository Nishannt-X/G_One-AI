package com.example.g_one.speech;

import com.example.g_one.core.AudioFrame;
import java.util.ArrayList;
import java.util.List;

/** Synthetic audio for tests: sine tones and silence as PCM16, cut into 20 ms frames. */
final class Tones {
    private Tones() {}

    static byte[] sine(double hz, double amplitude, int sampleRate, int millis) {
        int n = sampleRate * millis / 1000;
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short s = (short) Math.round(amplitude * Math.sin(2 * Math.PI * hz * i / sampleRate));
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    static byte[] silence(int sampleRate, int millis) {
        return new byte[sampleRate * millis / 1000 * 2];
    }

    /** Frames of 20 ms, timestamps continuing from {@code startMs}. */
    static List<AudioFrame> frames(byte[] pcm, int sampleRate, long startMs) {
        int bytes = sampleRate * 20 / 1000 * 2;
        List<AudioFrame> out = new ArrayList<>();
        for (int pos = 0, i = 0; pos + bytes <= pcm.length; pos += bytes, i++) {
            byte[] f = new byte[bytes];
            System.arraycopy(pcm, pos, f, 0, bytes);
            out.add(new AudioFrame(f, sampleRate, startMs + i * 20L));
        }
        return out;
    }
}
