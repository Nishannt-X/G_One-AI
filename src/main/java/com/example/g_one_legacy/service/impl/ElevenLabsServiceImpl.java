package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.service.ElevenLabsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ElevenLabs TTS service using the multilingual v2 model.
 *
 * Supports both Hindi (Devanagari) and English text input.
 * Output is mu-law 8kHz audio (ulaw_8000) for direct Twilio streaming.
 */
@Service
public class ElevenLabsServiceImpl implements ElevenLabsService {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsServiceImpl.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String voiceId;

    public ElevenLabsServiceImpl(WebClient.Builder webClientBuilder,
                                 @Value("${g-one.api.elevenlabs.key:${voxora.api.elevenlabs.key:}}") String apiKey,
                                 @Value("${g-one.api.elevenlabs.voiceId:${voxora.api.elevenlabs.voiceId:}}") String voiceId,
                                 ObjectMapper objectMapper) {
        this.voiceId = voiceId;
        this.objectMapper = objectMapper;

        log.info("[ELEVENLABS] Initialized with Voice ID '{}' and API Key (starts with: {})",
                 voiceId, apiKey != null && apiKey.length() > 4 ? apiKey.substring(0, 4) + "..." : "INVALID_KEY");

        this.webClient = webClientBuilder
                .baseUrl("https://api.elevenlabs.io/v1")
                .defaultHeader("xi-api-key", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public CompletableFuture<byte[]> synthesizeAudio(String text) {
        log.info("[ELEVENLABS] Sending text for TTS. Length: {}, Preview: '{}'",
                text.length(), text.length() > 80 ? text.substring(0, 80) + "..." : text);

        try {
            // Build JSON body safely with ObjectMapper — critical for Hindi/Devanagari text
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "text", text,
                    "model_id", "eleven_multilingual_v2"
            ));

            return webClient.post()
                    .uri("/text-to-speech/" + voiceId + "/stream?output_format=ulaw_8000")
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .toFuture()
                    .thenApply(audioResponse -> {
                        log.info("[ELEVENLABS] ✅ Received {} synthesized audio bytes", audioResponse.length);
                        return audioResponse;
                    })
                    .exceptionally(ex -> {
                        log.error("[ELEVENLABS] ❌ TTS synthesis failed: {}", ex.getMessage(), ex);
                        return new byte[0];
                    });

        } catch (Exception e) {
            log.error("[ELEVENLABS] Failed to build TTS request body: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture(new byte[0]);
        }
    }
}
