package com.example.g_one_legacy.event;

public class TranslationRequestEvent {
    private final String sourceSpeakerId;
    private final String targetSpeakerId;
    private final String originalText;

    public TranslationRequestEvent(String sourceSpeakerId, String targetSpeakerId, String originalText) {
        this.sourceSpeakerId = sourceSpeakerId;
        this.targetSpeakerId = targetSpeakerId;
        this.originalText = originalText;
    }

    public String getSourceSpeakerId() {
        return sourceSpeakerId;
    }

    public String getTargetSpeakerId() {
        return targetSpeakerId;
    }

    public String getOriginalText() {
        return originalText;
    }
}

