package com.example.g_one.speech;

import com.example.g_one.ApiException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Validates an uploaded recording is plain 16-bit PCM WAV of acceptable length (trust boundary). */
public final class Wav {
    private static final double MIN_SECONDS = 0.3;
    private static final double TOLERANCE_SECONDS = 0.5;

    private Wav() {}

    /** @return the duration in seconds
     *  @throws ApiException 400 when the bytes are not a usable WAV, 413 when longer than the cap */
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
                    throw new ApiException(413, "too_long", "Recording is longer than " + maxSeconds + " seconds.");
                }
                return seconds;
            }
            pos += 8 + (int) Math.min(size + (size & 1), b.length);
        }
        throw bad("WAV has no audio data.");
    }

    private static boolean tag(byte[] b, int off, String t) {
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != t.charAt(i)) return false;
        }
        return true;
    }

    private static ApiException bad(String msg) {
        return new ApiException(400, "bad_audio", msg);
    }
}
