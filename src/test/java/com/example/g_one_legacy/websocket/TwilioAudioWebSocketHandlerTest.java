package com.example.g_one_legacy.websocket;

import com.example.g_one_legacy.service.VoiceAiOrchestratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TwilioAudioWebSocketHandlerTest {

    @Mock
    private VoiceAiOrchestratorService voiceAiOrchestratorService;

    @Mock
    private WebSocketSession webSocketSession;

    private TwilioAudioWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TwilioAudioWebSocketHandler(new ObjectMapper(), voiceAiOrchestratorService);
        lenient().when(webSocketSession.getId()).thenReturn("session-1");
    }

    @Test
    void shouldHandleStartEvent() throws Exception {
        String payload = """
                {
                  "event": "start",
                  "start": {
                    "streamSid": "MZ123",
                    "callSid": "CA456"
                  }
                }
                """;

        handler.handleMessage(webSocketSession, new TextMessage(payload));

        verify(voiceAiOrchestratorService).onStart("session-1", "MZ123", "CA456");
    }

    @Test
    void shouldDecodeMediaPayloadAndForwardToOrchestrator() throws Exception {
        byte[] rawAudio = "test-audio".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(rawAudio);

        String payload = """
                {
                  "event": "media",
                  "streamSid": "MZ789",
                  "media": {
                    "payload": "%s"
                  }
                }
                """.formatted(encoded);

        handler.handleMessage(webSocketSession, new TextMessage(payload));

        ArgumentCaptor<byte[]> audioCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(voiceAiOrchestratorService).onMedia(eq("session-1"), eq("MZ789"), audioCaptor.capture());
        assertArrayEquals(rawAudio, audioCaptor.getValue());
    }

    @Test
    void shouldHandleStopEvent() throws Exception {
        String payload = """
                {
                  "event": "stop",
                  "stop": {
                    "streamSid": "MZ999"
                  }
                }
                """;

        handler.handleMessage(webSocketSession, new TextMessage(payload));

        verify(voiceAiOrchestratorService).onStop("session-1", "MZ999");
    }

    @Test
    void shouldIgnoreUnsupportedEvent() throws Exception {
        String payload = """
                {
                  "event": "mark"
                }
                """;

        handler.handleMessage(webSocketSession, new TextMessage(payload));

        verify(voiceAiOrchestratorService, never()).onMedia(any(), any(), any());
        verify(voiceAiOrchestratorService, never()).onStart(any(), any(), any());
        verify(voiceAiOrchestratorService, never()).onStop(any(), any());
    }
}
