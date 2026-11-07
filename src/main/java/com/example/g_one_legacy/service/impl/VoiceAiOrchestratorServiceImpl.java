package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.service.DeepgramService;
import com.example.g_one_legacy.service.VoiceAiOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin orchestrator that routes audio directly to Deepgram's streaming
 * WebSocket.
 *
 * No buffering — every 160-byte Twilio chunk is streamed immediately.
 * Transcript handling and event publishing are done inside DeepgramServiceImpl.
 */
@Service
public class VoiceAiOrchestratorServiceImpl implements VoiceAiOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(VoiceAiOrchestratorServiceImpl.class);

    private final DeepgramService deepgramService;

    public VoiceAiOrchestratorServiceImpl(DeepgramService deepgramService) {
        this.deepgramService = deepgramService;
    }

    @Override
    public void onConnected(String sessionId) {
        log.info("[PIPELINE] Twilio stream connected: sessionId={}", sessionId);
    }

    @Override
    public void onStart(String sessionId, String streamSid, String callSid) {
        log.info("[PIPELINE] Twilio stream started: sessionId={}, streamSid={}, callSid={}",
                sessionId, streamSid, callSid);

        // Determine speaker from the session context — for now we derive it from
        // the WebSocket handler which passes it via onMedia. The Deepgram connection
        // will be opened lazily on first streamAudio() call if not already open.
        // If you want eager connection, you can call deepgramService.openConnection()
        // here once the speakerId is known from the start event.
    }

    @Override
    public void onMedia(String sessionId, String streamSid, byte[] audioBytes, String speakerId) {
        int inboundSize = audioBytes == null ? 0 : audioBytes.length;
        // log.debug("[PIPELINE][RECEIVE] Audio from Twilio: sessionId={}, streamSid={},
        // speaker={}, bytes={}",
        // sessionId, streamSid, speakerId, inboundSize);

        if (inboundSize == 0 || "UNKNOWN".equals(speakerId)) {
            log.warn("[PIPELINE][RECEIVE] Empty audio or unknown speaker; skipping. sessionId={}, speaker={}",
                    sessionId, speakerId);
            return;
        }

        // NO BUFFERING! Instantly stream the tiny 160-byte chunks directly to Deepgram.
        try {
            deepgramService.streamAudio(audioBytes, speakerId);
        } catch (Exception e) {
            log.error("[PIPELINE] Failed to send audio to Deepgram for speaker: {}", speakerId, e);
        }
    }

    @Override
    public void onStop(String sessionId, String streamSid) {
        log.info("[PIPELINE] Twilio stream stopped: sessionId={}, streamSid={}", sessionId, streamSid);
        // Close Deepgram connections — we don't know the speakerId here,
        // so we close both. The service handles already-closed connections gracefully.
        deepgramService.closeConnection("A");
        deepgramService.closeConnection("B");
    }
}
