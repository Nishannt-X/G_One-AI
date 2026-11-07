package com.example.g_one.speech;

/** A failed call to Gnani or the translation service. {@code stage} becomes the user-facing error code. */
public class UpstreamException extends RuntimeException {
    private final String stage;

    public UpstreamException(String stage, String message) {
        super(message);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
