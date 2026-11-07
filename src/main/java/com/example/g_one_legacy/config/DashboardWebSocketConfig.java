package com.example.g_one_legacy.config;

import com.example.g_one_legacy.websocket.DashboardWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class DashboardWebSocketConfig implements WebSocketConfigurer {

    private final DashboardWebSocketHandler dashboardWebSocketHandler;

    public DashboardWebSocketConfig(DashboardWebSocketHandler dashboardWebSocketHandler) {
        this.dashboardWebSocketHandler = dashboardWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
         registry.addHandler(dashboardWebSocketHandler, "/ws/dashboard")
                 .setAllowedOrigins("*");
    }
}

