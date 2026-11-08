package com.example.g_one.speech;

/**
 * Sample-rate conversion for mono PCM16 with a windowed-sinc low-pass, so downsampling (for example Timbre's 24 kHz to
 * the 8 kHz of a phone line) does not fold high frequencies back into the band as noise. Works on a whole buffer.
 */
public final class Resampler {
    /** Filter reach in output-rate zero crossings; 16 gives a clean cut at telephony quality for little cost. */
    private static final int HALF_WIDTH = 16;

    private Resampler() {}

    /**
     * @param pcm16le little-endian PCM16 with an even length
     * @return PCM16 at {@code toRate}; length is {@code samples * toRate / fromRate} rounded down; the input itself
     *     (a copy) when the rates match
     */
    public static byte[] resample(byte[] pcm16le, int fromRate, int toRate) {
        if (fromRate <= 0 || toRate <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        if (pcm16le.length % 2 != 0) {
            throw new IllegalArgumentException("PCM16 audio needs an even number of bytes");
        }
        if (fromRate == toRate) return pcm16le.clone();

        int n = pcm16le.length / 2;
        short[] in = new short[n];
        for (int i = 0; i < n; i++) {
            in[i] = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8));
        }
        int outLen = (int) ((long) n * toRate / fromRate);
        double step = (double) fromRate / toRate;
        // Cut off at the lower of the two Nyquist frequencies, as a fraction of the input's.
        double cutoff = Math.min(1.0, (double) toRate / fromRate) * 0.95;
        double radius = HALF_WIDTH / cutoff;

        byte[] out = new byte[outLen * 2];
        for (int o = 0; o < outLen; o++) {
            double center = o * step;
            int lo = Math.max(0, (int) Math.ceil(center - radius));
            int hi = Math.min(n - 1, (int) Math.floor(center + radius));
            double sum = 0;
            double weights = 0;
            for (int j = lo; j <= hi; j++) {
                double d = j - center;
                double w = sinc(cutoff * d) * (0.5 + 0.5 * Math.cos(Math.PI * d / radius));
                sum += in[j] * w;
                weights += w;
            }
            // Dividing by the weight sum keeps a steady signal at its own level, so the filter cannot add gain.
            double value = weights == 0 ? 0 : sum / weights;
            int s = (int) Math.round(value);
            s = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
            out[2 * o] = (byte) s;
            out[2 * o + 1] = (byte) (s >> 8);
        }
        return out;
    }

    private static double sinc(double x) {
        if (Math.abs(x) < 1e-9) return 1.0;
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }
}
