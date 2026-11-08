package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Speech to text over the Prisma realtime WebSocket ({@code /stt/v3/stream}), with Gnani doing the endpointing. All
 * configuration goes in the upgrade headers; audio goes in as PCM16 at 8 kHz in exactly 512-sample (1,024-byte) frames.
 * There are no partial transcripts. A dropped connection is reopened up to three times in a row; audio fed while it
 * reopens waits for it.
 *
 * <p>Documented but not yet probed with the project's key (issue #5), so {@link SpeechConfig} only uses it when asked.
 */
public final class PrismRealtimeStt implements SpeechToText {
    private static final Logger log = LoggerFactory.getLogger(PrismRealtimeStt.class);
    static final int RATE = 8000;
    static final int FRAME_BYTES = 1024;
    private static final int MAX_RECONNECTS = 3;

    private final GOneProperties.Gnani cfg;
    private final ObjectMapper json;
    private final LongSupplier clock;
    private final HttpClient http = GnaniSockets.client();

    public PrismRealtimeStt(GOneProperties.Gnani cfg, ObjectMapper json, LongSupplier clock) {
        this.cfg = cfg;
        this.json = json;
        this.clock = clock;
    }

    public PrismRealtimeStt(GOneProperties.Gnani cfg, ObjectMapper json) {
        this(cfg, json, System::currentTimeMillis);
    }

    @Override
    public SttSession open(LegId legId, LegRole role, Language language, SttListener listener) {
        Session session = new Session(legId, role, language, new GuardedListener(listener));
        session.connect();
        return session;
    }

    /** Prisma v2.5 takes one language; v3.0 takes hints, and Hindi or Bengali callers mix in English. */
    static String languageHeader(String code, String model) {
        boolean v3 = model.startsWith("prisma-v3");
        return v3 && !code.equals("en-IN") ? code + ",en-IN" : code;
    }

    private final class Session implements SttSession {
        private final LegId legId;
        private final LegRole role;
        private final Language language;
        private final GuardedListener events;
        private final Object feedLock = new Object();
        private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        private volatile Connection current;
        private volatile boolean closed;
        private volatile int reconnects;
        private volatile long startedMs = -1;
        private volatile long endedMs = -1;

        Session(LegId legId, LegRole role, Language language, GuardedListener events) {
            this.legId = legId;
            this.role = role;
            this.language = language;
            this.events = events;
        }

        /** One WebSocket. Sends are chained so each waits for the connection and for the one before it. */
        private final class Connection {
            final CompletableFuture<WebSocket> ws;
            CompletableFuture<Void> tail;

            Connection(CompletableFuture<WebSocket> ws) {
                this.ws = ws;
                this.tail = ws.handle((w, e) -> null);
            }
        }

        void connect() {
            if (closed) return;
            WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(java.time.Duration.ofSeconds(5));
            builder.header("x-api-key-id", cfg.apiKey());
            if (!cfg.sttModel().isBlank()) builder.header("model", cfg.sttModel());
            builder.header("language_code", languageHeader(language.code(), cfg.sttModel()));
            builder.header("format", "transcribe");
            builder.header("x-sample-rate", String.valueOf(RATE));
            builder.header("x-vad-threshold", "0.7");
            builder.header("x-min-speech-ms", "200");
            builder.header("x-min-silence-ms", "500");
            builder.header("x-idle-timeout-sec", "180");
            builder.header("x-api-request-id", legId.value());

            Listener listener = new Listener();
            CompletableFuture<WebSocket> ws = builder.buildAsync(GnaniSockets.wsUri(cfg.baseUrl(), "/stt/v3/stream"),
                    listener);
            Connection connection = new Connection(ws);
            listener.connection = connection;
            current = connection;
            ws.whenComplete((w, error) -> {
                if (error != null) down(connection, "could not connect");
            });
        }

        private void down(Connection connection, String why) {
            if (closed || connection != current) return;
            if (reconnects++ < MAX_RECONNECTS) {
                log.info("stt connection down leg={} ({}), reconnecting ({}/{})", legId, why, reconnects, MAX_RECONNECTS);
                connect();
            } else {
                events.onError(new UpstreamException("stt_failed", "The speech connection was lost."));
            }
        }

        @Override
        public void feed(AudioFrame frame) {
            if (closed) return;
            byte[] pcm = frame.sampleRate() == RATE ? frame.pcm16le()
                    : Resampler.resample(frame.pcm16le(), frame.sampleRate(), RATE);
            synchronized (feedLock) {
                buffered.writeBytes(pcm);
                byte[] all = buffered.toByteArray();
                int pos = 0;
                while (all.length - pos >= FRAME_BYTES) {
                    send(ByteBuffer.wrap(all, pos, FRAME_BYTES).slice());
                    pos += FRAME_BYTES;
                }
                buffered.reset();
                buffered.write(all, pos, all.length - pos);
            }
        }

        private void send(ByteBuffer chunk) {
            ByteBuffer copy = ByteBuffer.allocate(chunk.remaining()).put(chunk).flip();
            Connection c = current;
            // A failed send means the connection is dead even if no close notification arrived (the JDK can lose it
            // when the last frame and the disconnect arrive together). Audio is fed every few milliseconds, so this
            // notices fast.
            c.tail = c.tail.thenCompose(v -> c.ws.thenCompose(w -> w.sendBinary(copy, true)))
                    .handle((w, e) -> {
                        if (e != null) down(c, "send failed");
                        return null;
                    });
        }

        @Override
        public void close() {
            closed = true;
            events.close();
            Connection c = current;
            if (c != null) {
                c.ws.thenAccept(w -> w.sendClose(WebSocket.NORMAL_CLOSURE, "done")).exceptionally(e -> null);
            }
        }

        private final class Listener implements WebSocket.Listener {
            volatile Connection connection;
            private final StringBuilder text = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    String message = text.toString();
                    text.setLength(0);
                    handle(message);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                down(connection, "closed " + statusCode);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                down(connection, "error");
            }
        }

        private void handle(String message) {
            JsonNode node;
            try {
                node = json.readTree(message);
            } catch (Exception e) {
                return; // not JSON: nothing we understand
            }
            switch (node.path("type").asText("")) {
                case "connected" -> reconnects = 0;
                case "speech_start" -> {
                    startedMs = clock.getAsLong();
                    endedMs = -1;
                    events.onSpeechStart();
                }
                case "processing" -> endedMs = clock.getAsLong();
                case "transcript" -> transcript(node);
                case "error" -> events.onError(new UpstreamException("stt_failed", "The speech service reported an error."));
                default -> { }
            }
        }

        private void transcript(JsonNode node) {
            if (!node.path("is_final").asBoolean(true)) return;
            String text = node.path("text").asText("").trim();
            if (text.isEmpty()) return;
            long now = clock.getAsLong();
            long started = startedMs >= 0 ? startedMs : now;
            long ended = Math.max(started, endedMs >= 0 ? endedMs : now);
            startedMs = -1;
            endedMs = -1;
            log.info("stt turn leg={} speech_end_to_final_ms={} gnani_latency_ms={} path=realtime", legId,
                    Math.max(0, now - ended), node.path("latency").asLong(-1));
            events.onFinalTurn(new Turn(legId, role, language, text, started, ended));
        }
    }
}
