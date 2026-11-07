package com.example.g_one_legacy.config;

import com.example.g_one_legacy.websocket.TwilioAudioWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TwilioAudioWebSocketHandler twilioAudioWebSocketHandler;

    @Autowired
    public WebSocketConfig(TwilioAudioWebSocketHandler twilioAudioWebSocketHandler) {
        this.twilioAudioWebSocketHandler = twilioAudioWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(twilioAudioWebSocketHandler, "/api/voice/stream", "/api/voice/stream/{speakerId}")
                .setAllowedOrigins("*");
    }
}
