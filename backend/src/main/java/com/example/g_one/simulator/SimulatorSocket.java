package com.example.g_one.simulator;

import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * {@code /ws/simulator?role=farmer|buyer&lang=hi-IN[&caller=%2B919876543210][&callId=...]}: a browser tab acts as a phone
 * leg (see {@link SimulatorLeg} for the wire format). The leg is registered in the {@link LegRegistry} like a Twilio
 * leg. A farmer starts a new call; a buyer joins the call named by {@code callId}, or the only call that has a farmer
 * and no buyer yet. {@code caller} must be one of {@link SimulatorCallers}; leave it out to withhold the caller ID.
 * Turn it off with {@code g-one.simulator.enabled=false}. Same-origin pages may connect, and so may pages served from
 * localhost on any port (the Vite dev server proxies with a different Origin).
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "g-one.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class SimulatorSocket implements WebSocketConfigurer {
    static final String PATH = "/ws/simulator";
    private static final String LEG = "simulator.leg";
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int BUFFER_LIMIT_BYTES = 4 * 1024 * 1024;
    /** Each open simulator socket can start paid speech and model calls, so only a few at once. */
    static final int MAX_OPEN_LEGS = 8;

    private final LegRegistry registry;
    private final Language.Registry languages;
    private final SimulatorCallers callers;
    private final ObjectMapper json;
    private final AtomicInteger open = new AtomicInteger();

    public SimulatorSocket(LegRegistry registry, Language.Registry languages, SimulatorCallers callers,
                           ObjectMapper json) {
        this.registry = registry;
        this.languages = languages;
        this.callers = callers;
        this.json = json;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry handlers) {
        handlers.addHandler(new Handler(), PATH)
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    /** Why a connection is refused; the text goes to the browser as the close reason. */
    private static final class Refused extends Exception {
        Refused(String reason) {
            super(reason);
        }
    }

    private final class Handler extends AbstractWebSocketHandler {
        private static final Logger log = LoggerFactory.getLogger(Handler.class);

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws IOException {
            if (open.incrementAndGet() > MAX_OPEN_LEGS) {
                open.decrementAndGet();
                session.close(CloseStatus.SERVICE_OVERLOAD.withReason("Too many simulator calls are open."));
                return;
            }
            WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS,
                    BUFFER_LIMIT_BYTES);
            SimulatorLeg leg;
            try {
                leg = newLeg(session, safe);
            } catch (Refused e) {
                open.decrementAndGet();
                session.close(CloseStatus.POLICY_VIOLATION.withReason(e.getMessage()));
                return;
            }
            session.getAttributes().put(LEG, leg);
            leg.onEnded(open::decrementAndGet);
            // The browser needs its ids before the pipeline can start talking.
            send(safe, ready(leg));
            try {
                registry.register(leg);
            } catch (IllegalStateException e) {
                leg.hangup();
            }
        }

        private SimulatorLeg newLeg(WebSocketSession session, WebSocketSession safe) throws Refused {
            MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
            LegRole role;
            try {
                role = LegRole.valueOf(param(query, "role").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new Refused("role must be farmer or buyer.");
            }
            Language language;
            try {
                language = languages.require(param(query, "lang"));
            } catch (IllegalArgumentException e) {
                throw new Refused(e.getMessage());
            }
            Optional<CallerKey> key;
            try {
                key = callers.keyFor(param(query, "caller"));
            } catch (IllegalArgumentException e) {
                throw new Refused(e.getMessage());
            }
            CallId call = role == LegRole.FARMER ? CallId.random() : callToJoin(param(query, "callId"));
            return new SimulatorLeg(call, role, language, key.orElse(null), new SocketChannel(safe));
        }

        /** The farmer's call a buyer joins. */
        private CallId callToJoin(String requested) throws Refused {
            if (!requested.isBlank()) {
                CallId call = CallId.of(requested);
                if (registry.find(call, LegRole.FARMER).isEmpty()) throw new Refused("No farmer is on that call.");
                if (registry.find(call, LegRole.BUYER).isPresent()) throw new Refused("That call already has a buyer.");
                return call;
            }
            List<CallId> waiting = registry.activeCalls().stream()
                    .filter(c -> registry.find(c, LegRole.FARMER).isPresent() && registry.find(c, LegRole.BUYER).isEmpty())
                    .toList();
            if (waiting.isEmpty()) throw new Refused("No farmer call is waiting for a buyer.");
            if (waiting.size() > 1) throw new Refused("Several calls are waiting; give the call id.");
            return waiting.get(0);
        }

        private String ready(Leg leg) {
            ObjectNode node = json.createObjectNode();
            node.put("type", "ready");
            node.put("callId", leg.callId().value());
            node.put("legId", leg.id().value());
            node.put("role", leg.role().wire());
            node.put("language", leg.language().code());
            node.put("sampleRate", SimulatorLeg.WIRE_RATE);
            node.put("memory", leg.callerKey().isPresent());
            return node.toString();
        }

        private String param(MultiValueMap<String, String> query, String name) {
            String value = query.getFirst(name);
            return value == null ? "" : UriUtils.decode(value, StandardCharsets.UTF_8).strip();
        }

        private void send(WebSocketSession safe, String text) {
            try {
                safe.sendMessage(new TextMessage(text));
            } catch (IOException | RuntimeException e) {
                log.debug("Could not send to a simulator client: {}", e.toString());
            }
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            if (session.getAttributes().get(LEG) instanceof SimulatorLeg leg) {
                byte[] pcm = new byte[message.getPayloadLength()];
                message.getPayload().get(pcm);
                leg.receive(pcm);
            }
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            if (!(session.getAttributes().get(LEG) instanceof SimulatorLeg leg)) return;
            try {
                JsonNode node = json.readTree(message.getPayload());
                switch (node.path("type").asText()) {
                    case "mark" -> leg.onMark(node.path("id").asLong(-1));
                    case "hangup" -> leg.hangup();
                    default -> { }
                }
            } catch (IOException e) {
                // Not JSON: not ours.
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable error) {
            log.debug("Simulator socket error: {}", error.toString());
            end(session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            end(session);
        }

        private void end(WebSocketSession session) {
            if (session.getAttributes().get(LEG) instanceof SimulatorLeg leg) leg.remoteClosed();
        }
    }

    /** Sends a leg's messages down the socket. */
    private record SocketChannel(WebSocketSession session) implements SimulatorLeg.Channel {
        @Override
        public void sendAudio(byte[] pcm16le) throws IOException {
            session.sendMessage(new BinaryMessage(pcm16le));
        }

        @Override
        public void sendText(String text) throws IOException {
            session.sendMessage(new TextMessage(text));
        }

        @Override
        public void close() {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (IOException | RuntimeException ignored) {
                // Already closing.
            }
        }
    }
}
