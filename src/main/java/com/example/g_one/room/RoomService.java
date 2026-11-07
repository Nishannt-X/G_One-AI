package com.example.g_one.room;

import com.example.g_one.GOneProperties;
import com.example.g_one.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class RoomService {
    public record Session(Room room, Room.Participant participant) {}

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final GOneProperties props;

    public RoomService(GOneProperties props) {
        this.props = props;
    }

    public Session create(Role role, String language, String accessCode) {
        if (!props.accessCode().isBlank() && !constantTimeEquals(props.accessCode(), accessCode)) {
            throw new ApiException(403, "bad_access_code", "Access code is wrong.");
        }
        purgeIdle();
        if (rooms.size() >= props.maxRooms()) {
            throw new ApiException(503, "too_many_rooms", "The demo is busy. Try again later.");
        }
        Room room = new Room(randomId(16));
        rooms.put(room.id, room);
        return new Session(room, room.add(role, randomId(24), language));
    }

    public Session join(String roomId, String language) {
        Room room = find(roomId);
        synchronized (room) {
            // Join as whichever role the creator did not take.
            Role free = room.participant(Role.FARMER) == null ? Role.FARMER : Role.BUYER;
            if (room.participant(free) != null) {
                throw new ApiException(409, "room_full", "This room already has two people.");
            }
            return new Session(room, room.add(free, randomId(24), language));
        }
    }

    public Room find(String roomId) {
        Room room = roomId == null ? null : rooms.get(roomId);
        if (room == null || expired(room)) {
            throw new ApiException(404, "no_room", "Room not found or expired.");
        }
        return room;
    }

    /** @param authorization the raw "Bearer ..." header */
    public Session authenticate(String roomId, String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        return authenticateToken(roomId, token);
    }

    public Session authenticateToken(String roomId, String token) {
        Room room = find(roomId);
        Room.Participant p = room.authenticate(token);
        if (p == null) throw new ApiException(401, "unauthorized", "Join the room first.");
        room.touch();
        return new Session(room, p);
    }

    private boolean expired(Room room) {
        return Duration.between(room.lastActive(), Instant.now()).toMinutes() >= props.roomIdleMinutes();
    }

    private void purgeIdle() {
        rooms.values().removeIf(this::expired);
    }

    private String randomId(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
