package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.event.TranslationRequestEvent;
import com.example.g_one_legacy.service.DeepgramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time streaming Deepgram STT service with per-speaker language models.
 *
 * Speaker A always speaks Hindi  → uses Deepgram model with language=hi
 * Speaker B always speaks English → uses Deepgram model with language=en
 *
 * Maintains a persistent WebSocket connection per speaker to Deepgram's streaming API.
 * Audio bytes are streamed immediately (no buffering). When Deepgram returns a final
 * transcript, this service publishes a TranslationRequestEvent directly.
 */
@Service
public class DeepgramServiceImpl implements DeepgramService {

    private static final Logger log = LoggerFactory.getLogger(DeepgramServiceImpl.class);

    // Base URL with common parameters — language is appended per speaker
    private static final String DEEPGRAM_WS_BASE =
            "wss://api.deepgram.com/v1/listen"
            + "?encoding=mulaw"
            + "&sample_rate=8000"
            + "&channels=1"
            + "&model=nova-2"
            + "&punctuate=true"
            + "&interim_results=true"
            + "&endpointing=300";  // 300ms pause triggers speech_final

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final HttpClient httpClient;

    /** Active WebSocket connections keyed by speakerId ("A", "B") */
    private final Map<String, WebSocket> activeConnections = new ConcurrentHashMap<>();

    public DeepgramServiceImpl(@Value("${g-one.api.deepgram.key:${voxora.api.deepgram.key:}}") String apiKey,
                               ObjectMapper objectMapper,
                               ApplicationEventPublisher eventPublisher) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.httpClient = HttpClient.newHttpClient();

        log.info("[DEEPGRAM] Initialized with API Key (starts with: {})",
                apiKey != null && apiKey.length() > 4 ? apiKey.substring(0, 4) + "..." : "INVALID_KEY");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Language routing: Speaker A = Hindi, Speaker B = English
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the Deepgram language code for a given speaker.
     * Speaker A always speaks Hindi, Speaker B always speaks English.
     */
    private String getLanguageForSpeaker(String speakerId) {
        return "A".equals(speakerId) ? "hi" : "en";
    }

    /**
     * Builds the full Deepgram WebSocket URL with the correct language for the speaker.
     */
    private String buildWebSocketUrl(String speakerId) {
        return DEEPGRAM_WS_BASE + "&language=" + getLanguageForSpeaker(speakerId);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public void openConnection(String speakerId) {
        // Close any existing connection for this speaker before opening a new one
        closeConnection(speakerId);

        String wsUrl = buildWebSocketUrl(speakerId);
        String lang = getLanguageForSpeaker(speakerId);
        log.info("[DEEPGRAM] Opening streaming WebSocket for speaker {} (language={})", speakerId, lang);

        try {
            CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Token " + apiKey)
                    .buildAsync(URI.create(wsUrl), new DeepgramWebSocketListener(speakerId));

            WebSocket ws = wsFuture.join(); // block until connection is established
            activeConnections.put(speakerId, ws);

            log.info("[DEEPGRAM] ✅ WebSocket OPEN for speaker {} (language={})", speakerId, lang);
        } catch (Exception e) {
            log.error("[DEEPGRAM] ❌ Failed to open WebSocket for speaker {} (language={})", speakerId, lang, e);
        }
    }

    @Override
    public void streamAudio(byte[] audioBytes, String speakerId) {
        WebSocket ws = activeConnections.get(speakerId);
        if (ws == null) {
            log.warn("[DEEPGRAM] No active WebSocket for speaker {}. Opening one now.", speakerId);
            openConnection(speakerId);
            ws = activeConnections.get(speakerId);
        }

        if (ws == null) {
            log.error("[DEEPGRAM] Failed to stream audio — no WebSocket available for speaker {}", speakerId);
            return;
        }

        try {
            // Send raw audio bytes as a binary WebSocket frame — NO BUFFERING
            ws.sendBinary(ByteBuffer.wrap(audioBytes), true).join();
        } catch (Exception e) {
            log.error("[DEEPGRAM] Failed to send audio to Deepgram for speaker {}", speakerId, e);
        }
    }

    @Override
    public void closeConnection(String speakerId) {
        WebSocket ws = activeConnections.remove(speakerId);
        if (ws != null) {
            try {
                // Send a normal close frame
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Stream ended").join();
                log.info("[DEEPGRAM] WebSocket CLOSED for speaker {}", speakerId);
            } catch (Exception e) {
                log.warn("[DEEPGRAM] Error closing WebSocket for speaker {}: {}", speakerId, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[DEEPGRAM] Shutting down — closing all active WebSocket connections");
        activeConnections.keySet().forEach(this::closeConnection);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Deepgram WebSocket Listener (handles incoming transcript messages)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Listener for a single speaker's Deepgram WebSocket.
     *
     * Uses a "Buffer and Flush" strategy:
     * - Accumulates is_final transcript patches into a sentenceBuffer
     * - Only fires TranslationRequestEvent when:
     *   a) Buffer ends with sentence-ending punctuation (.?!।), OR
     *   b) Deepgram signals speech_final=true (speaker paused / endpointing)
     * - Interim results are ignored for the translation pipeline
     */
    private class DeepgramWebSocketListener implements WebSocket.Listener {

        private final String speakerId;
        /** Buffer for reassembling fragmented WebSocket text frames */
        private final StringBuilder wsFrameBuffer = new StringBuilder();
        /** Buffer for accumulating complete sentence from multiple is_final patches */
        private final StringBuilder sentenceBuffer = new StringBuilder();

        DeepgramWebSocketListener(String speakerId) {
            this.speakerId = speakerId;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("[DEEPGRAM][WS] Connection opened for speaker {} (language={})",
                    speakerId, getLanguageForSpeaker(speakerId));
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            wsFrameBuffer.append(data);

            if (last) {
                String fullMessage = wsFrameBuffer.toString();
                wsFrameBuffer.setLength(0);
                processDeepgramMessage(fullMessage);
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("[DEEPGRAM][WS] Connection closed for speaker {}. Status: {}, Reason: {}",
                    speakerId, statusCode, reason);
            // Flush any remaining buffered text before closing
            flushBuffer();
            activeConnections.remove(speakerId);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("[DEEPGRAM][WS] Error on WebSocket for speaker {}", speakerId, error);
            flushBuffer();
            activeConnections.remove(speakerId);
        }

        /**
         * Parse Deepgram's streaming JSON response.
         *
         * Buffer & Flush strategy:
         * 1. Ignore interim results (is_final=false)
         * 2. Append is_final patches to sentenceBuffer
         * 3. Check for sentence-ending punctuation (.?!।) → flush
         * 4. Check for speech_final=true (endpointing pause) → flush
         */
        private void processDeepgramMessage(String message) {
            try {
                JsonNode root = objectMapper.readTree(message);

                String type = root.path("type").asText("");
                if (!"Results".equals(type)) {
                    log.debug("[DEEPGRAM][WS] Non-results message for speaker {}: type={}", speakerId, type);
                    return;
                }

                boolean isFinal = root.path("is_final").asBoolean(false);
                boolean speechFinal = root.path("speech_final").asBoolean(false);
                String transcript = root.path("channel")
                        .path("alternatives")
                        .path(0)
                        .path("transcript")
                        .asText("")
                        .trim();

                // ── Step 1: Ignore interim results ───────────────────────────────
                if (!isFinal) {
                    if (!transcript.isBlank()) {
                        log.debug("[DEEPGRAM][INTERIM] Speaker {} ({}): '{}'",
                                speakerId, getLanguageForSpeaker(speakerId), transcript);
                    }
                    return;
                }

                // ── Step 2: Append is_final patch to sentence buffer ─────────────
                if (!transcript.isBlank()) {
                    if (sentenceBuffer.length() > 0) {
                        sentenceBuffer.append(" "); // space between patches
                    }
                    sentenceBuffer.append(transcript);

                    log.info("[DEEPGRAM][PATCH] Speaker {} ({}): patch='{}', buffer='{}'",
                            speakerId, getLanguageForSpeaker(speakerId),
                            transcript, sentenceBuffer);
                }

                // ── Step 3: Check for flush conditions ───────────────────────────
                boolean shouldFlush = false;
                String bufferText = sentenceBuffer.toString().trim();

                if (!bufferText.isEmpty()) {
                    char lastChar = bufferText.charAt(bufferText.length() - 1);

                    // Flush on sentence-ending punctuation
                    if (lastChar == '.' || lastChar == '?' || lastChar == '!' || lastChar == '।') {
                        log.info("[DEEPGRAM][FLUSH] Punctuation detected ('{}') for speaker {}", lastChar, speakerId);
                        shouldFlush = true;
                    }

                    // Flush on speech_final (Deepgram endpointing — speaker paused)
                    if (speechFinal) {
                        log.info("[DEEPGRAM][FLUSH] speech_final=true (endpointing) for speaker {}", speakerId);
                        shouldFlush = true;
                    }
                }

                if (shouldFlush) {
                    flushBuffer();
                }

            } catch (Exception e) {
                log.error("[DEEPGRAM][WS] Failed to parse message for speaker {}: {}",
                        speakerId, e.getMessage(), e);
            }
        }

        /**
         * Flushes the sentence buffer: fires a TranslationRequestEvent with
         * the accumulated text and clears the buffer.
         */
        private void flushBuffer() {
            String completeText = sentenceBuffer.toString().trim();
            sentenceBuffer.setLength(0);

            if (completeText.isBlank()) {
                return;
            }

            log.info("[DEEPGRAM][FLUSH] ✅ Complete sentence for Speaker {} ({}): '{}'",
                    speakerId, getLanguageForSpeaker(speakerId), completeText);

            // Route to the OTHER speaker (A→B, B→A)
            String targetSpeakerId = "A".equals(speakerId) ? "B" : "A";

            TranslationRequestEvent event = new TranslationRequestEvent(
                    speakerId,
                    targetSpeakerId,
                    completeText
            );
            eventPublisher.publishEvent(event);

            log.info("[PIPELINE][ROUTER] Fired TranslationRequestEvent: Source={} ({}), Target={} ({}), Text='{}'",
                    speakerId, getLanguageForSpeaker(speakerId),
                    targetSpeakerId, getLanguageForSpeaker(targetSpeakerId),
                    completeText);
        }
    }
}
