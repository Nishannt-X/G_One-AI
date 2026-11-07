package com.example.g_one;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TestAudio {
    private TestAudio() {}

    /** Silent 16-bit mono PCM WAV of the given length. */
    public static byte[] wav(double seconds, int rate) {
        int dataBytes = (int) (seconds * rate) * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes()).put("fmt ".getBytes());
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2)
                .putShort((short) 16).put("data".getBytes()).putInt(dataBytes);
        return b.array();
    }
}
