package com.example.g_one_legacy.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketHandler.class);

    // Maps Organization ID -> map of WebSocketSession IDs to sessions
    private final Map<Long, Map<String, WebSocketSession>> orgSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public DashboardWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Dashboard websocket connected: sessionId={}", session.getId());
        // In a real app, extract Org ID from session attributes/auth token
        // For now, we put them in a default "org 1" bucket for demonstration
        Long orgId = 1L;

        session.getAttributes().put("orgId", orgId);
        orgSessions.computeIfAbsent(orgId, k -> new ConcurrentHashMap<>()).put(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Handle incoming messages from dashboard if needed
        log.debug("Received message from dashboard (sessionId={}): {}", session.getId(), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Dashboard websocket closed: sessionId={}, status={}", session.getId(), status);
        Long orgId = (Long) session.getAttributes().get("orgId");
        if (orgId != null) {
            Map<String, WebSocketSession> sessions = orgSessions.get(orgId);
            if (sessions != null) {
                sessions.remove(session.getId());
            }
        }
    }

    public void broadcastToDashboard(Long organizationId, String messageType, String payload) {
        Map<String, WebSocketSession> sessions = orgSessions.get(organizationId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        try {
            ObjectNode messageNode = objectMapper.createObjectNode();
            messageNode.put("type", messageType);
            messageNode.put("payload", payload);

            String jsonMessage = objectMapper.writeValueAsString(messageNode);
            TextMessage textMessage = new TextMessage(jsonMessage);

            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(textMessage);
                        }
                    } catch (IOException e) {
                        log.error("Failed to send message to dashboard session {}", session.getId(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to construct broadcast message for orgId {}", organizationId, e);
        }
    }
}

