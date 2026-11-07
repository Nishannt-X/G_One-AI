package com.example.g_one_legacy.service;

public interface VoiceAiOrchestratorService {

    void onConnected(String sessionId);

    void onStart(String sessionId, String streamSid, String callSid);

    void onMedia(String sessionId, String streamSid, byte[] audioBytes, String speakerId);

    void onStop(String sessionId, String streamSid);
}
