package com.example.g_one;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for Gnani Prisma/Timbre and an OpenAI-compatible LLM, speaking the documented wire formats. Tests point
 * the clients' base URLs at {@link #baseUrl()}; nothing leaves the machine.
 */
public final class FakeUpstreams implements AutoCloseable {
    public record Seen(String path, Map<String, String> headers, byte[] body) {
        public String bodyText() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    public static final String KEY = "test-key-not-a-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    public final List<Seen> prisma = new CopyOnWriteArrayList<>();
    public final List<Seen> timbre = new CopyOnWriteArrayList<>();
    public final List<Seen> llm = new CopyOnWriteArrayList<>();
    /** Next N Prisma calls fail with HTTP 500. */
    public final AtomicInteger prismaFailures = new AtomicInteger();
    /** Next N Timbre calls fail with HTTP 500. */
    public final AtomicInteger timbreFailures = new AtomicInteger();
    /** Next N LLM calls fail with HTTP 500. */
    public final AtomicInteger llmFailures = new AtomicInteger();
    /** What Prisma "hears". Empty means no speech. */
    public volatile String sttTranscript = "hello";
    /** The message content the fake LLM answers with (the client expects a JSON object as text). */
    public volatile String llmContent = "{\"text\":\"ok\"}";

    private final HttpServer server;

    public FakeUpstreams() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stt/v3", x -> handle(x, prisma, this::stt));
        server.createContext("/api/v1/tts/inference", x -> handle(x, timbre, this::tts));
        server.createContext("/chat/completions", x -> handle(x, llm, this::chat));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // a slow answer must not hold up the others
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private interface Responder {
        void respond(HttpExchange x, Seen seen) throws IOException;
    }

    private void handle(HttpExchange x, List<Seen> log, Responder r) throws IOException {
        Seen seen = new Seen(x.getRequestURI().getPath(),
                Map.of("key", String.valueOf(x.getRequestHeaders().getFirst("X-API-Key-ID")),
                        "auth", String.valueOf(x.getRequestHeaders().getFirst("Authorization"))),
                x.getRequestBody().readAllBytes());
        log.add(seen);
        r.respond(x, seen);
    }

    private void stt(HttpExchange x, Seen seen) throws IOException {
        if (prismaFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            reply(x, 500, "application/json",
                    "{\"success\":false,\"error\":{\"type\":\"TRANSCRIPTION_FAILED\",\"message\":\"x\"}}");
        } else {
            reply(x, 200, "application/json",
                    "{\"success\":true,\"transcript\":" + JSON.writeValueAsString(sttTranscript) + "}");
        }
    }

    private void tts(HttpExchange x, Seen seen) throws IOException {
        if (timbreFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            reply(x, 500, "application/json", "{\"success\":false,\"message\":\"boom\",\"status_code\":500}");
        } else {
            reply(x, 200, "audio/wav", TestAudio.wav(0.5, 24000));
        }
    }

    private void chat(HttpExchange x, Seen seen) throws IOException {
        if (llmFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            reply(x, 500, "application/json", "{\"error\":\"boom\"}");
            return;
        }
        reply(x, 200, "application/json",
                JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", llmContent))))));
    }

    private static void reply(HttpExchange x, int status, String type, String body) throws IOException {
        reply(x, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, body.length);
        x.getResponseBody().write(body);
        x.close();
    }
}
