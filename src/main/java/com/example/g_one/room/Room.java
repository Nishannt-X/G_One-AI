package com.example.g_one.room;

import com.example.g_one.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.web.socket.WebSocketSession;

/** In-memory state of one two-phone session. ponytail: single instance only; use Redis to scale out. */
public final class Room {
    private static final int MAX_CLIPS = 40;
    private static final int MAX_HISTORY = 100;

    public static final class Participant {
        public final Role role;
        private final String token;
        private volatile String language;
        public volatile WebSocketSession session;

        Participant(Role role, String token, String language) {
            this.role = role;
            this.token = token;
            this.language = language;
        }

        public String token() {
            return token;
        }

        public String language() {
            return language;
        }

        public void language(String code) {
            this.language = code;
        }
    }

    public record Clip(Role owner, byte[] wav) {}

    public final String id;
    private volatile Instant lastActive = Instant.now();
    private final Map<Role, Participant> participants = new EnumMap<>(Role.class);
    private final List<RoomEvent> history = new ArrayList<>();
    private final Map<String, Clip> clips = new LinkedHashMap<>();
    private final AtomicBoolean turnInFlight = new AtomicBoolean();
    private final AtomicInteger turns = new AtomicInteger();
    private final AtomicLong turnSeq = new AtomicLong();
    private final Map<Class<?>, Object> attachments = new ConcurrentHashMap<>();

    Room(String id) {
        this.id = id;
    }

    /** Per-room state owned by another feature (the agreement). Created on first use and discarded with the room. */
    public <T> T attachment(Class<T> type, Supplier<T> create) {
        return type.cast(attachments.computeIfAbsent(type, k -> create.get()));
    }

    public Instant lastActive() {
        return lastActive;
    }

    public void touch() {
        lastActive = Instant.now();
    }

    synchronized Participant add(Role role, String token, String language) {
        if (participants.containsKey(role)) {
            throw new ApiException(409, "role_taken", "That role is already taken in this room.");
        }
        Participant p = new Participant(role, token, language);
        participants.put(role, p);
        return p;
    }

    public synchronized Participant participant(Role role) {
        return participants.get(role);
    }

    public synchronized List<Participant> participants() {
        return List.copyOf(participants.values());
    }

    /** Constant-time token check. */
    synchronized Participant authenticate(String token) {
        if (token == null) return null;
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        for (Participant p : participants.values()) {
            if (MessageDigest.isEqual(given, p.token.getBytes(StandardCharsets.UTF_8))) return p;
        }
        return null;
    }

    public boolean tryStartTurn() {
        return turnInFlight.compareAndSet(false, true);
    }

    public void endTurn() {
        turnInFlight.set(false);
    }

    public boolean turnInFlight() {
        return turnInFlight.get();
    }

    /** Counts an accepted turn against the per-room spend cap. */
    public boolean countTurn(int max) {
        return turns.incrementAndGet() <= max;
    }

    /** Rises with each accepted turn, so results read asynchronously can be ordered by the turn they came from. */
    public long nextTurnSeq() {
        return turnSeq.incrementAndGet();
    }

    public synchronized void record(RoomEvent e) {
        history.add(e);
        if (history.size() > MAX_HISTORY) history.remove(0);
    }

    public synchronized List<RoomEvent> history() {
        return List.copyOf(history);
    }

    public synchronized void putClip(String key, Role owner, byte[] wav) {
        clips.put(key, new Clip(owner, wav));
        if (clips.size() > MAX_CLIPS) clips.remove(clips.keySet().iterator().next());
    }

    public synchronized Clip clip(String key) {
        return clips.get(key);
    }
}
