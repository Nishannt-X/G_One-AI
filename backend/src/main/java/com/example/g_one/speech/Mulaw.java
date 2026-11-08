package com.example.g_one.speech;

/** G.711 mu-law codec. Telephony carries 8 kHz mu-law; the rest of the app works with signed 16-bit PCM. */
public final class Mulaw {
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;
    private static final short[] DECODE = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            int u = ~i & 0xFF;
            int exponent = (u >> 4) & 0x07;
            int mantissa = u & 0x0F;
            int magnitude = ((mantissa << 3) + BIAS) << exponent;
            magnitude -= BIAS;
            DECODE[i] = (short) ((u & 0x80) != 0 ? -magnitude : magnitude);
        }
    }

    private Mulaw() {}

    /** @return little-endian PCM16, twice as long as the input */
    public static byte[] decode(byte[] mulaw) {
        byte[] pcm = new byte[mulaw.length * 2];
        for (int i = 0; i < mulaw.length; i++) {
            short s = DECODE[mulaw[i] & 0xFF];
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    /** @param pcm16le little-endian PCM16 with an even length; @return one mu-law byte per sample */
    public static byte[] encode(byte[] pcm16le) {
        if (pcm16le.length % 2 != 0) {
            throw new IllegalArgumentException("PCM16 audio needs an even number of bytes");
        }
        byte[] out = new byte[pcm16le.length / 2];
        for (int i = 0; i < out.length; i++) {
            short s = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8));
            out[i] = encodeSample(s);
        }
        return out;
    }

    private static byte encodeSample(short sample) {
        int pcm = sample;
        int sign = 0;
        if (pcm < 0) {
            pcm = -pcm;
            sign = 0x80;
        }
        if (pcm > CLIP) pcm = CLIP;
        pcm += BIAS;
        int exponent = 7;
        for (int mask = 0x4000; (pcm & mask) == 0 && exponent > 0; mask >>= 1) {
            exponent--;
        }
        int mantissa = (pcm >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }
}
