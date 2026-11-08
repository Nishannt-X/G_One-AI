package com.example.g_one.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Validates an uploaded recording is plain 16-bit PCM WAV of acceptable length (trust boundary). */
public final class Wav {
    private static final double MIN_SECONDS = 0.3;
    private static final double TOLERANCE_SECONDS = 0.5;

    private Wav() {}

    /** @return the duration in seconds
     *  @throws BadAudioException {@code bad_audio} when the bytes are not a usable WAV, {@code too_long} when longer than the cap */
    public static double validate(byte[] b, int maxSeconds) {
        if (b == null || b.length < 44 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) {
            throw bad("Recording must be a WAV file.");
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int pos = 12;
        while (pos + 8 <= b.length) {
            long size = buf.getInt(pos + 4) & 0xFFFFFFFFL;
            if (tag(b, pos, "fmt ") && pos + 24 <= b.length) {
                if (buf.getShort(pos + 8) != 1) throw bad("WAV must be uncompressed PCM.");
                channels = buf.getShort(pos + 10);
                rate = buf.getInt(pos + 12);
                bits = buf.getShort(pos + 22);
            } else if (tag(b, pos, "data")) {
                if (channels < 1 || rate < 8000 || bits != 16) throw bad("WAV must be 16-bit PCM.");
                long available = Math.min(size, b.length - (pos + 8L));
                double seconds = available / (double) (rate * channels * 2);
                if (seconds < MIN_SECONDS) throw bad("Recording is too short.");
                if (seconds > maxSeconds + TOLERANCE_SECONDS) {
                    throw new BadAudioException("too_long", "Recording is longer than " + maxSeconds + " seconds.");
                }
                return seconds;
            }
            pos += 8 + (int) Math.min(size + (size & 1), b.length);
        }
        throw bad("WAV has no audio data.");
    }

    /** Raw samples and their rate, as read out of a WAV. */
    public record Pcm(byte[] pcm16le, int sampleRate) {}

    /**
     * Reads the samples of a mono 16-bit PCM WAV. Timbre returns an open-ended header (a data length of 0 or
     * 0xFFFFFFFF, or one larger than what arrived), so the samples are taken to run to the end of the bytes then.
     *
     * @throws BadAudioException {@code bad_audio} when the bytes are not mono 16-bit PCM WAV
     */
    public static Pcm pcm(byte[] b) {
        if (b == null || b.length < 44 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) {
            throw bad("Audio must be a WAV file.");
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int pos = 12;
        while (pos + 8 <= b.length) {
            long size = buf.getInt(pos + 4) & 0xFFFFFFFFL;
            if (tag(b, pos, "fmt ") && pos + 24 <= b.length) {
                if (buf.getShort(pos + 8) != 1) throw bad("WAV must be uncompressed PCM.");
                channels = buf.getShort(pos + 10);
                rate = buf.getInt(pos + 12);
                bits = buf.getShort(pos + 22);
            } else if (tag(b, pos, "data")) {
                if (channels != 1 || rate <= 0 || bits != 16) throw bad("WAV must be mono 16-bit PCM.");
                int start = pos + 8;
                long remaining = b.length - (long) start;
                long length = (size == 0 || size > remaining) ? remaining : size;
                length -= length % 2;
                byte[] pcm = new byte[(int) length];
                System.arraycopy(b, start, pcm, 0, pcm.length);
                return new Pcm(pcm, rate);
            }
            pos += 8 + (int) Math.min(size + (size & 1), b.length);
        }
        throw bad("WAV has no audio data.");
    }

    /** Wraps mono PCM16 samples in a 44-byte WAV header with correct lengths. */
    public static byte[] wrap(byte[] pcm16le, int sampleRate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm16le.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + pcm16le.length)
                .put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(pcm16le.length)
                .put(pcm16le);
        return b.array();
    }

    private static boolean tag(byte[] b, int off, String t) {
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != t.charAt(i)) return false;
        }
        return true;
    }

    private static BadAudioException bad(String msg) {
        return new BadAudioException("bad_audio", msg);
    }
}
