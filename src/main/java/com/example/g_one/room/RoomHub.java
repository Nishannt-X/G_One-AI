package com.example.g_one.room;

import com.example.g_one.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/** WebSocket notifications: one socket per phone, server to phone only. Also builds the per-phone views. */
@Component
public class RoomHub extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RoomHub.class);
    private static final String ROOM = "room";
    private static final String ROLE = "role";

    private final RoomService rooms;
    private final ObjectMapper json;

    public RoomHub(RoomService rooms, ObjectMapper json) {
        this.rooms = rooms;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        var query = UriComponentsBuilder.fromUri(raw.getUri()).build().getQueryParams();
        RoomService.Session s;
        try {
            s = rooms.authenticateToken(query.getFirst("room"), query.getFirst("token"));
        } catch (ApiException e) {
            raw.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        raw.getAttributes().put(ROOM, s.room());
        raw.getAttributes().put(ROLE, s.participant().role);
        WebSocketSession old = s.participant().session;
        s.participant().session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024);
        if (old != null) old.close(CloseStatus.NORMAL);

        List<Map<String, Object>> events = new ArrayList<>();
        for (RoomEvent e : s.room().history()) {
            Map<String, Object> view = e.viewFor(s.room().id, s.participant().role);
            if (view != null) events.add(view);
        }
        Map<String, Object> state = presence(s.room(), "state");
        state.put("you", s.participant().role.wire());
        state.put("events", events);
        send(s.participant(), state);
        broadcastPresence(s.room());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Room room = (Room) raw.getAttributes().get(ROOM);
        Role role = (Role) raw.getAttributes().get(ROLE);
        if (room == null) return;
        Room.Participant p = room.participant(role);
        // Ignore the close of a socket already replaced by a reconnect.
        if (p != null && p.session instanceof ConcurrentWebSocketSessionDecorator d && d.getDelegate() == raw) {
            p.session = null;
            broadcastPresence(room);
        }
    }

    public void broadcast(Room room, RoomEvent event) {
        for (Room.Participant p : room.participants()) {
            Map<String, Object> view = event.viewFor(room.id, p.role);
            if (view != null) send(p, view);
        }
    }

    public void broadcastPresence(Room room) {
        for (Room.Participant p : room.participants()) send(p, presence(room, "presence"));
    }

    private Map<String, Object> presence(Room room, String type) {
        List<Map<String, Object>> people = new ArrayList<>();
        for (Room.Participant p : room.participants()) {
            people.add(Map.of("role", p.role.wire(), "language", p.language(), "connected", p.session != null));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("participants", people);
        m.put("busy", room.turnInFlight());
        return m;
    }

    private void send(Room.Participant p, Map<String, Object> message) {
        WebSocketSession s = p.session;
        if (s == null || !s.isOpen()) return;
        try {
            s.sendMessage(new TextMessage(json.writeValueAsString(message)));
        } catch (JsonProcessingException e) {
            log.error("Could not serialise room message", e);
        } catch (IOException | RuntimeException e) {
            log.debug("Dropping message to a closed or slow socket");
        }
    }
}
