package com.example.g_one.fakes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake OpenAI-compatible {@code POST .../chat/completions} server with tool calling, in the same style as
 * {@code FakeUpstreams} (JDK {@code HttpServer} on 127.0.0.1; nothing leaves the machine). Queue answers with
 * {@link #replyText} and {@link #replyToolCall}; each request takes the next one, and {@code {"text":"ok"}} is the answer
 * when the queue is empty. Every request body is recorded in {@link #requests()}.
 */
public final class FakeLlm implements AutoCloseable {
    public static final String KEY = "test-llm-key-not-a-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConcurrentLinkedQueue<ObjectNode> replies = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger callIds = new AtomicInteger();
    /** The next N requests fail with HTTP 500. */
    public final AtomicInteger failures = new AtomicInteger();
    private final HttpServer server;

    public FakeLlm() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** Use as the LLM base URL; requests go to {@code baseUrl() + "/chat/completions"}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queues a plain assistant message. */
    public FakeLlm replyText(String content) {
        ObjectNode message = JSON.createObjectNode().put("role", "assistant").put("content", content);
        replies.add(choice(message, "stop"));
        return this;
    }

    /** Queues an assistant message that calls one tool. {@code argumentsJson} is the arguments object as text. */
    public FakeLlm replyToolCall(String name, String argumentsJson) {
        ObjectNode message = JSON.createObjectNode().put("role", "assistant").putNull("content");
        ObjectNode call = message.putArray("tool_calls").addObject()
                .put("id", "call_" + callIds.incrementAndGet())
                .put("type", "function");
        call.putObject("function").put("name", name).put("arguments", argumentsJson);
        replies.add(choice(message, "tool_calls"));
        return this;
    }

    /** Request bodies received so far, in order. */
    public List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static ObjectNode choice(ObjectNode message, String finishReason) {
        ObjectNode body = JSON.createObjectNode().put("id", "chatcmpl-fake").put("object", "chat.completion");
        ArrayNode choices = body.putArray("choices");
        choices.addObject().put("index", 0).put("finish_reason", finishReason).set("message", message);
        return body;
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            if (!"POST".equals(x.getRequestMethod()) || !x.getRequestURI().getPath().endsWith("/chat/completions")) {
                send(x, 404, "{\"error\":\"not found\"}");
                return;
            }
            byte[] body = x.getRequestBody().readAllBytes();
            try {
                requests.add(JSON.readTree(body));
            } catch (IOException e) {
                send(x, 400, "{\"error\":\"bad json\"}");
                return;
            }
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                send(x, 500, "{\"error\":\"boom\"}");
                return;
            }
            ObjectNode reply = replies.poll();
            if (reply == null) {
                reply = choice(JSON.createObjectNode().put("role", "assistant").put("content", "{\"text\":\"ok\"}"),
                        "stop");
            }
            send(x, 200, JSON.writeValueAsString(reply));
        }
    }

    private static void send(HttpExchange x, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }
}
