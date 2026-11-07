package com.example.g_one;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for Gnani Prisma/Timbre and an OpenAI-compatible LLM, speaking the documented wire formats. */
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
    public final AtomicInteger timbreFailures = new AtomicInteger();
    /** JSON the fake LLM returns for the offer-extraction prompt. */
    public volatile String offerJson = "{\"status\":\"none\"}";
    /** Per-transcript overrides of {@link #offerJson}. */
    public final Map<String, String> offers = new ConcurrentHashMap<>();
    /** How long the fake LLM takes to read the offer in this transcript, in milliseconds. */
    public final Map<String, Long> offerDelays = new ConcurrentHashMap<>();
    /** JSON the fake LLM returns for the threat-check prompt. */
    public volatile String threatJson = "{\"status\":\"none\"}";
    /**
     * When true the fake classifier answers secretRequest only if the system prompt it was sent actually tells it to
     * count typing or entering a PIN/OTP and approving a collect request to receive money, and the JSON transcript it
     * was sent mentions a PIN, OTP or collect request. Removing that part of the prompt makes the answer "none".
     */
    public volatile boolean promptAwareClassifier;
    /** Next N threat-check LLM calls fail with HTTP 500. */
    public final AtomicInteger threatFailures = new AtomicInteger();
    /** JSON the fake LLM returns for the agreement-reply prompt, keyed by the spoken transcript; default is "none". */
    public final Map<String, String> replies = new ConcurrentHashMap<>();
    /** Next N agreement-reply LLM calls fail with HTTP 500. */
    public final AtomicInteger replyFailures = new AtomicInteger();
    /** While non-empty, Timbre fails (HTTP 500) for any request whose text contains this. */
    public volatile String timbreFailOnText = "";
    public volatile String sttTranscript = "hello";

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
            reply(x, 500, "application/json", "{\"success\":false,\"error\":{\"type\":\"TRANSCRIPTION_FAILED\",\"message\":\"x\"}}");
        } else {
            reply(x, 200, "application/json", "{\"success\":true,\"transcript\":" + JSON.writeValueAsString(sttTranscript) + "}");
        }
    }

    private void tts(HttpExchange x, Seen seen) throws IOException {
        String failOn = timbreFailOnText;
        if (timbreFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0
                || (!failOn.isEmpty() && seen.bodyText().contains(failOn))) {
            reply(x, 500, "application/json", "{\"success\":false,\"message\":\"boom\",\"status_code\":500}");
        } else {
            reply(x, 200, "audio/wav", TestAudio.wav(0.5, 24000));
        }
    }

    private void chat(HttpExchange x, Seen seen) throws IOException {
        JsonNode req = JSON.readTree(seen.body());
        String system = req.path("messages").path(0).path("content").asText();
        String user = req.path("messages").path(1).path("content").asText();
        if (system.contains("threat or intimidation")
                && threatFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            reply(x, 500, "application/json", "{\"error\":\"boom\"}");
            return;
        }
        if (system.contains("agreement reply check")) {
            if (replyFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                reply(x, 500, "application/json", "{\"error\":\"boom\"}");
                return;
            }
            reply(x, 200, "application/json", JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                    Map.of("content", replies.getOrDefault(user, "{\"intent\":\"none\",\"newTerms\":false}")))))));
            return;
        }
        if (system.contains("price offer") && offerDelays.containsKey(user)) {
            try {
                Thread.sleep(offerDelays.get(user));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String content = system.contains("price offer") ? offers.getOrDefault(user, offerJson)
                : system.contains("threat or intimidation") ? (promptAwareClassifier ? promptAware(system, user) : threatJson)
                : JSON.writeValueAsString(Map.of("text", "T[" + user + "]", "spoken", "S[" + user + "]"));
        reply(x, 200, "application/json",
                JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content))))));
    }

    private static String promptAware(String system, String user) throws IOException {
        String said = JSON.readTree(user).path("transcript").asText("").toLowerCase();
        boolean promptCovers = system.contains("type or enter a secret")
                && system.contains("approve or accept a UPI collect request as the way to receive money");
        boolean aboutSecretOrCollect = said.contains("pin") || said.contains("otp") || said.contains("collect request");
        return promptCovers && aboutSecretOrCollect ? "{\"status\":\"none\",\"secretRequest\":true}"
                : "{\"status\":\"none\"}";
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
