package com.example.g_one;

import com.example.g_one.room.RoomHub;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer {
    private final RoomHub hub;

    public WebConfig(RoomHub hub) {
        this.hub = hub;
    }

    /** No allowed-origins override: Spring's default accepts same-origin WebSocket handshakes only. */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub, "/ws");
    }

    @Bean
    OncePerRequestFilter securityHeaders() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                res.setHeader("Content-Security-Policy",
                        "default-src 'self'; connect-src 'self' ws: wss:; media-src 'self' blob:; "
                                + "img-src 'self' data:; frame-ancestors 'none'");
                res.setHeader("X-Content-Type-Options", "nosniff");
                // The room id travels in the join link's query string; do not leak it in Referer.
                res.setHeader("Referrer-Policy", "no-referrer");
                res.setHeader("Permissions-Policy", "microphone=(self)");
                if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
                chain.doFilter(req, res);
            }
        };
    }
}
