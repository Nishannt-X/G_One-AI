package com.example.g_one_legacy.websocket;

import com.example.g_one_legacy.service.VoiceAiOrchestratorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TwilioAudioWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TwilioAudioWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final VoiceAiOrchestratorService voiceAiOrchestratorService;

    // Stores active sessions for Speaker A and Speaker B
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, String> streamSidsBySpeaker = new ConcurrentHashMap<>();

    @Autowired
    public TwilioAudioWebSocketHandler(ObjectMapper objectMapper,
            VoiceAiOrchestratorService voiceAiOrchestratorService) {
        this.objectMapper = objectMapper;
        this.voiceAiOrchestratorService = voiceAiOrchestratorService;
    }

    private String getSpeakerId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        if (path.endsWith("/A")) {
            return "A";
        } else if (path.endsWith("/B")) {
            return "B";
        }
        return "UNKNOWN";
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String speakerId = getSpeakerId(session);
        if (!"UNKNOWN".equals(speakerId)) {
            activeSessions.put(speakerId, session);
            log.info("WebSocket connected for Speaker {}. SessionID: {}", speakerId, session.getId());
        }
        voiceAiOrchestratorService.onConnected(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String event = root.path("event").asText("").toLowerCase();

            switch (event) {
                case "connected":
                    handleConnectedEvent(session, root);
                    break;
                case "start":
                    handleStartEvent(session, root);
                    break;
                case "media":
                    handleMediaEvent(session, root);
                    break;
                case "stop":
                    handleStopEvent(session, root);
                    break;
                default:
                    log.debug("Ignoring unsupported Twilio event: {}", event);
                    break;
            }
        } catch (Exception ex) {
            log.warn("Failed to process Twilio websocket message for sessionId={}", session.getId(), ex);
        }
    }

    private void handleConnectedEvent(WebSocketSession session, JsonNode root) {
        String streamSid = root.path("streamSid").asText("");
        log.info("Twilio connected event: sessionId={}, streamSid={}", session.getId(), streamSid);
        voiceAiOrchestratorService.onConnected(session.getId());
    }

    private void handleStartEvent(WebSocketSession session, JsonNode root) {
        JsonNode startNode = root.path("start");
        String streamSid = startNode.path("streamSid").asText(root.path("streamSid").asText(""));
        String callSid = startNode.path("callSid").asText("");

        String speakerId = getSpeakerId(session);
        if (!"UNKNOWN".equals(speakerId) && !streamSid.isBlank()) {
            streamSidsBySpeaker.put(speakerId, streamSid);
        }

        voiceAiOrchestratorService.onStart(session.getId(), streamSid, callSid);
    }

    private void handleMediaEvent(WebSocketSession session, JsonNode root) {
        JsonNode mediaNode = root.path("media");
        String payload = mediaNode.path("payload").asText("");
        String streamSid = root.path("streamSid").asText("");
        String speakerId = getSpeakerId(session);

        if (payload.isBlank()) {
            log.debug("Received media event without payload: sessionId={}", session.getId());
            return;
        }

        // log.info("Twilio media payload received. sessionId={}, streamSid={},
        // speakerId={}, payloadLength={}",
        // session.getId(), streamSid, speakerId, payload.length());

        try {
            byte[] inboundAudioBytes = Base64.getDecoder().decode(payload);
            // log.info("[PIPELINE][RECEIVE] Decoded inbound Twilio audio bytes:
            // sessionId={}, streamSid={}, speakerId={}, bytes={}",
            // session.getId(), streamSid, speakerId, inboundAudioBytes.length);

            // Pass execution to the orchestrator (async, no return expected)
            voiceAiOrchestratorService.onMedia(session.getId(), streamSid, inboundAudioBytes, speakerId);

        } catch (Exception e) {
            log.error("Error extracting or processing media payload. sessionId={}, streamSid={}, error={}",
                    session.getId(), streamSid, e.getMessage(), e);
        }
    }

    public void sendAudioToSpeaker(String speakerId, byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0 || "UNKNOWN".equals(speakerId)) {
            return;
        }
        WebSocketSession session = activeSessions.get(speakerId);
        if (session == null || !session.isOpen()) {
            log.warn("[PIPELINE][TWILIO_SEND] Target session not active for speakerId={}", speakerId);
            return;
        }

        String streamSid = streamSidsBySpeaker.getOrDefault(speakerId, "unknown");

        try {
            String outboundPayloadBase64 = Base64.getEncoder().encodeToString(audioBytes);
            JsonNode response = objectMapper.createObjectNode()
                    .put("event", "media")
                    .put("streamSid", streamSid)
                    .set("media", objectMapper.createObjectNode().put("payload", outboundPayloadBase64));

            String responseJson = objectMapper.writeValueAsString(response);
            synchronized (session) { // protect concurrent writes
                session.sendMessage(new TextMessage(responseJson));
            }

            log.info(
                    "[PIPELINE][TWILIO_SEND] Sent base64 media payload to Twilio. targetSpeaker={}, streamSid={}, bytes={}, payloadLength={}",
                    speakerId, streamSid, audioBytes.length, outboundPayloadBase64.length());
        } catch (Exception e) {
            log.error("[PIPELINE][TWILIO_SEND] Failed to send media payload. targetSpeaker={}, error={}",
                    speakerId, e.getMessage(), e);
        }
    }

    private void handleStopEvent(WebSocketSession session, JsonNode root) {
        String streamSid = root.path("streamSid").asText(root.path("stop").path("streamSid").asText(""));
        voiceAiOrchestratorService.onStop(session.getId(), streamSid);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String speakerId = getSpeakerId(session);
        if (!"UNKNOWN".equals(speakerId)) {
            activeSessions.remove(speakerId);
            streamSidsBySpeaker.remove(speakerId);
            log.info("WebSocket disconnected for Speaker {}. SessionID: {}", speakerId, session.getId());
        }
        log.info("Twilio websocket closed: sessionId={}, status={}", session.getId(), status);
    }
}
