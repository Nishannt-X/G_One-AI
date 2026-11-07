package com.example.g_one.speech;

/**
 * A recording that failed validation. {@code code} is a stable machine code: {@code bad_audio} when the bytes are not
 * usable 16-bit PCM WAV, {@code too_long} when the recording is longer than the allowed length.
 */
public class BadAudioException extends IllegalArgumentException {
    private final String code;

    public BadAudioException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
