package com.example.g_one.console;

import com.example.g_one.core.ConsoleEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * {@code /ws/console}: one JSON {@code ConsoleEvent} per text message, server to browser only. A new connection first
 * receives the events of the calls already running, then live events. Same-origin pages may connect, and so may
 * pages served from localhost on any port (the Vite dev server proxies with a different Origin).
 */
@Configuration
@EnableWebSocket
public class ConsoleSocket implements WebSocketConfigurer {
    static final String PATH = "/ws/console";
    private static final String SUBSCRIPTION = "console.subscription";
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int BUFFER_LIMIT_BYTES = 256 * 1024;

    private final ConsoleState state;
    private final ObjectWriter writer;

    public ConsoleSocket(ConsoleState state, ObjectMapper mapper) {
        this.state = state;
        // Writing with the interface type adds the "type" discriminator to the record.
        this.writer = mapper.writerFor(ConsoleEvent.class);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(), PATH)
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    private final class Handler extends TextWebSocketHandler {
        private static final Logger log = LoggerFactory.getLogger(Handler.class);

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS,
                    BUFFER_LIMIT_BYTES);
            Runnable stop = state.subscribe(event -> send(safe, event));
            session.getAttributes().put(SUBSCRIPTION, stop);
            if (!session.isOpen()) stop.run(); // closed while the history was being sent
        }

        private void send(WebSocketSession safe, ConsoleEvent event) {
            if (!safe.isOpen()) return;
            try {
                safe.sendMessage(new TextMessage(writer.writeValueAsString(event)));
            } catch (IOException | RuntimeException e) {
                // A slow or broken client is dropped; the others are not held up.
                log.debug("Dropping a console client after a failed send: {}", e.toString());
                try {
                    safe.close(CloseStatus.SERVER_ERROR);
                } catch (IOException | RuntimeException ignored) {
                    // Already closing.
                }
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            if (session.getAttributes().remove(SUBSCRIPTION) instanceof Runnable stop) stop.run();
        }
    }
}
