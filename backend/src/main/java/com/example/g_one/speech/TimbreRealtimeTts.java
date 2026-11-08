package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.TextToSpeech;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Text to speech over the Timbre realtime WebSocket ({@code /api/v1/tts}). At 8 kHz it asks for raw mu-law, which is
 * decoded to PCM16; at other rates it asks for raw PCM16. One socket per utterance, because there is no cancel
 * message: stopping an utterance closes its socket. Frames reach the sink as chunks arrive, so playback starts before
 * synthesis ends.
 *
 * <p>Documented but not yet probed with the project's key (issue #5), so {@link SpeechConfig} only uses it when asked.
 */
public final class TimbreRealtimeTts implements TextToSpeech {
    private static final Logger log = LoggerFactory.getLogger(TimbreRealtimeTts.class);

    /**
     * Fails a synthesis that goes quiet. The JDK WebSocket can lose the close notification when the last frame and the
     * disconnect arrive together, which would leave the synthesis waiting for ever.
     */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("tts-watchdog").factory());

    private final GOneProperties.Gnani cfg;
    private final ObjectMapper json;
    private final HttpClient http = GnaniSockets.client();

    public TimbreRealtimeTts(GOneProperties.Gnani cfg, ObjectMapper json) {
        this.cfg = cfg;
        this.json = json;
    }

    @Override
    public Synthesis speak(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
        Run run = new Run(sink, sampleRate);
        if (text.isBlank()) {
            run.done.complete(null);
            return run;
        }
        run.open(text, language);
        return run;
    }

    private final class Run implements Synthesis {
        private final Object lock = new Object();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final Consumer<AudioFrame> sink;
        private final int sampleRate;
        private final boolean mulaw;
        private final FrameChunker chunker;
        private final long requestedNanos = System.nanoTime();
        private volatile WebSocket socket;
        private volatile ScheduledFuture<?> watchdog;
        private boolean cancelled;
        private boolean first = true;

        Run(Consumer<AudioFrame> sink, int sampleRate) {
            this.sink = sink;
            this.sampleRate = sampleRate;
            this.mulaw = sampleRate == 8000;
            this.chunker = new FrameChunker(sampleRate, this::deliver);
        }

        void open(String text, Language language) {
            Map<String, Object> audio = mulaw
                    ? Map.of("sample_rate", 8000, "num_channels", 1, "container", "mulaw")
                    : Map.of("sample_rate", sampleRate, "num_channels", 1, "container", "raw", "encoding",
                            "linear_pcm");
            String request;
            try {
                request = json.writeValueAsString(Map.of("text", text, "voice", language.voice(), "model",
                        cfg.ttsModel(), "language", language.code(), "audio_config", audio));
            } catch (Exception e) {
                done.completeExceptionally(e);
                return;
            }
            touch();
            http.newWebSocketBuilder().header("X-API-Key-ID", cfg.apiKey())
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .buildAsync(GnaniSockets.wsUri(cfg.baseUrl(), "/api/v1/tts"), new Listener(request))
                    .whenComplete((ws, error) -> {
                        if (error != null) fail("could not connect");
                    });
        }

        private void deliver(AudioFrame frame) {
            synchronized (lock) {
                if (cancelled) return;
                if (first) {
                    first = false;
                    log.info("tts first_audio_ms={} path=realtime", (System.nanoTime() - requestedNanos) / 1_000_000);
                }
                sink.accept(frame);
            }
        }

        /** (Re)starts the idle timer: any message from the service counts as life. */
        private void touch() {
            ScheduledFuture<?> old = watchdog;
            if (old != null) old.cancel(false);
            if (done.isDone()) return;
            watchdog = WATCHDOG.schedule(() -> {
                fail("no answer for " + cfg.timeoutSeconds() + " s");
                WebSocket ws = socket;
                if (ws != null) ws.abort();
            }, cfg.timeoutSeconds(), TimeUnit.SECONDS);
        }

        private void stopWatchdog() {
            ScheduledFuture<?> w = watchdog;
            if (w != null) w.cancel(false);
        }

        private void fail(String why) {
            if (!done.isDone()) {
                done.completeExceptionally(new UpstreamException("tts_failed", "The voice service failed (" + why + ")."));
            }
            stopWatchdog();
        }

        private void chunk(JsonNode node) {
            String b64 = node.path("data").path("audio").asText("");
            if (b64.isEmpty()) return;
            byte[] bytes = Base64.getDecoder().decode(b64);
            chunker.add(mulaw ? Mulaw.decode(bytes) : bytes);
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                cancelled = true;
            }
            done.cancel(false);
            stopWatchdog();
            WebSocket ws = socket;
            if (ws != null) ws.abort();
        }

        @Override
        public CompletableFuture<Void> done() {
            return done;
        }

        private final class Listener implements WebSocket.Listener {
            private final String request;
            private final StringBuilder text = new StringBuilder();

            Listener(String request) {
                this.request = request;
            }

            @Override
            public void onOpen(WebSocket webSocket) {
                socket = webSocket;
                if (done.isDone()) {
                    webSocket.abort();
                    return;
                }
                webSocket.sendText(request, true);
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                touch();
                text.append(data);
                if (last) {
                    String message = text.toString();
                    text.setLength(0);
                    handle(webSocket, message);
                }
                webSocket.request(1);
                return null;
            }

            private void handle(WebSocket webSocket, String message) {
                try {
                    JsonNode node = json.readTree(message);
                    switch (node.path("type").asText("")) {
                        case "audio" -> chunk(node);
                        case "complete" -> {
                            chunk(node);
                            chunker.flush();
                            done.complete(null);
                            stopWatchdog();
                            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                        }
                        case "error" -> {
                            fail("service error");
                            webSocket.abort();
                        }
                        default -> { }
                    }
                } catch (Exception e) {
                    fail("bad message");
                    webSocket.abort();
                }
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                fail("closed " + statusCode);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                fail("connection error");
            }
        }
    }
}
