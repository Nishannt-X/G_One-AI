package com.example.g_one.speech;

import com.example.g_one.core.AudioFrame;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Cuts a stream of PCM16 bytes into 20 ms {@link AudioFrame}s (160 samples at 8 kHz) whose timestamps count from the
 * start of the utterance. Bytes may arrive in any chunk sizes; a sample split across chunks is carried over.
 */
final class FrameChunker {
    static final int FRAME_MS = 20;

    private final int sampleRate;
    private final int frameBytes;
    private final Consumer<AudioFrame> sink;
    private final ByteArrayOutputStream carry = new ByteArrayOutputStream();
    private long emittedSamples;

    FrameChunker(int sampleRate, Consumer<AudioFrame> sink) {
        if (sampleRate < 1000 || sampleRate * FRAME_MS % 1000 != 0) {
            throw new IllegalArgumentException("Unsupported sample rate " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.frameBytes = sampleRate * FRAME_MS / 1000 * 2;
        this.sink = sink;
    }

    void add(byte[] pcm16le) {
        carry.writeBytes(pcm16le);
        byte[] all = carry.toByteArray();
        int pos = 0;
        while (all.length - pos >= frameBytes) {
            emit(all, pos, frameBytes);
            pos += frameBytes;
        }
        carry.reset();
        carry.write(all, pos, all.length - pos);
    }

    /** Emits what is left as a final, shorter frame (whole samples only). */
    void flush() {
        byte[] rest = carry.toByteArray();
        carry.reset();
        int usable = rest.length - rest.length % 2;
        if (usable > 0) emit(rest, 0, usable);
    }

    private void emit(byte[] from, int offset, int length) {
        byte[] frame = new byte[length];
        System.arraycopy(from, offset, frame, 0, length);
        long timestampMs = emittedSamples * 1000L / sampleRate;
        emittedSamples += length / 2;
        sink.accept(new AudioFrame(frame, sampleRate, timestampMs));
    }
}
