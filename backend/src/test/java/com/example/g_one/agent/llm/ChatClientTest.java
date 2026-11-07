package com.example.g_one.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ChatClient against a scripted fake /chat/completions server speaking the OpenAI wire format. No network. */
class ChatClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "test-key-not-a-secret";

    private record Scripted(int status, String body, long delayMillis) {}

    private HttpServer server;
    private final Deque<Scripted> script = new ConcurrentLinkedDeque<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", x -> {
            requests.add(JSON.readTree(x.getRequestBody().readAllBytes()));
            auth.add(x.getRequestHeaders().getFirst("Authorization"));
            Scripted s = script.poll();
            if (s == null) s = new Scripted(500, "{\"error\":\"no scripted answer\"}", 0);
            try {
                Thread.sleep(s.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = s.body().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            try {
                x.sendResponseHeaders(s.status(), body.length);
                x.getResponseBody().write(body);
            } catch (IOException clientGone) {
                // the client timed out first
            }
            x.close();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ChatClient client(int timeoutSeconds) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ChatClient(new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                new GOneProperties.Llm(base, KEY, "test-model", timeoutSeconds)), JSON);
    }

    private void answer(String json) {
        script.add(new Scripted(200, json, 0));
    }

    private static final JsonNode MANDI_PARAMS = parse("""
            {"type":"object","properties":{"commodity":{"type":"string"},"market":{"type":"string"}},
             "required":["commodity"]}""");

    private static JsonNode parse(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void toolCallRoundTripSendsToolsThenReplaysTheCallAndItsResult() {
        answer("""
                {"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_abc","type":"function",
                    "function":{"name":"get_mandi_price","arguments":"{\\"commodity\\":\\"pyaz\\"}"}}]}}]}""");
        answer("""
                {"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
                  "content":"AGMARKNET ने चार नवंबर को लासलगाँव में लाल प्याज़ का भाव चार हज़ार पाँच सौ रुपये प्रति क्विंटल बताया था।"}}]}""");
        ChatClient chat = client(5);
        List<ToolSpec> tools = List.of(new ToolSpec("get_mandi_price", "Dated mandi reference.", MANDI_PARAMS));
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("You are G-One."), ChatMessage.user("प्याज़ का भाव क्या है?")));

        ChatReply first = chat.complete(ChatRequest.of(messages, tools));
        assertThat(first.wantsTools()).isTrue();
        assertThat(first.content()).isEmpty();
        assertThat(first.finishReason()).isEqualTo("tool_calls");
        ToolCall call = first.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_abc");
        assertThat(call.name()).isEqualTo("get_mandi_price");
        assertThat(call.arguments(JSON).path("commodity").asText()).isEqualTo("pyaz");

        messages.add(ChatMessage.assistant(first));
        messages.add(ChatMessage.tool(call.id(), "AGMARKNET listed four thousand five hundred rupees per quintal ..."));
        ChatReply second = chat.complete(ChatRequest.of(messages, tools));
        assertThat(second.wantsTools()).isFalse();
        assertThat(second.content()).startsWith("AGMARKNET ने").contains("प्याज़");

        // First request: model, auth, tools offered as functions, Hindi survives as UTF-8.
        JsonNode r1 = requests.get(0);
        assertThat(auth).containsOnly("Bearer " + KEY);
        assertThat(r1.path("model").asText()).isEqualTo("test-model");
        assertThat(r1.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(r1.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(r1.path("tools").get(0).path("function").path("name").asText()).isEqualTo("get_mandi_price");
        assertThat(r1.path("tools").get(0).path("function").path("parameters")).isEqualTo(MANDI_PARAMS);
        assertThat(r1.path("messages").get(1).path("content").asText()).isEqualTo("प्याज़ का भाव क्या है?");

        // Second request replays the assistant's tool call and answers it by id.
        JsonNode msgs = requests.get(1).path("messages");
        assertThat(msgs).hasSize(4);
        JsonNode replayed = msgs.get(2);
        assertThat(replayed.path("role").asText()).isEqualTo("assistant");
        assertThat(replayed.path("content").isNull()).isTrue();
        assertThat(replayed.path("tool_calls").get(0).path("id").asText()).isEqualTo("call_abc");
        assertThat(replayed.path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"commodity\":\"pyaz\"}");
        JsonNode toolMsg = msgs.get(3);
        assertThat(toolMsg.path("role").asText()).isEqualTo("tool");
        assertThat(toolMsg.path("tool_call_id").asText()).isEqualTo("call_abc");
        assertThat(toolMsg.path("content").asText()).startsWith("AGMARKNET listed");
    }

    @Test
    void plainChatSendsNoToolsAndStripsInlineThinking() {
        answer("""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "content":"<think>The farmer wants weather.</think>\\n Namaste, aap kis district se bol rahe hain?"}}]}""");
        ChatReply reply = client(5).complete(ChatRequest.of(List.of(ChatMessage.user("hello")), List.of()));
        assertThat(reply.content()).isEqualTo("Namaste, aap kis district se bol rahe hain?");
        assertThat(requests.get(0).has("tools")).isFalse();
        assertThat(requests.get(0).has("tool_choice")).isFalse();
    }

    @Test
    void argumentsSentAsAnObjectAndMissingIdsAreTolerated() {
        answer("""
                {"choices":[{"message":{"role":"assistant","content":"",
                  "tool_calls":[{"type":"function","function":{"name":"get_weather","arguments":{"district":"Nashik"}}},
                                {"type":"function","function":{"name":"end_call"}}]}}]}""");
        ChatReply reply = client(5).complete(ChatRequest.of(List.of(ChatMessage.user("x")), List.of()));
        assertThat(reply.toolCalls()).extracting(ToolCall::id).containsExactly("call_0", "call_1");
        assertThat(reply.toolCalls().get(0).arguments(JSON).path("district").asText()).isEqualTo("Nashik");
        assertThat(reply.toolCalls().get(1).arguments(JSON).isEmpty()).isTrue();
    }

    @Test
    void malformedToolArgumentsAreAClearError() {
        assertThatThrownBy(() -> new ToolCall("c", "get_weather", "{district: Nashik").arguments(JSON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> new ToolCall("c", "get_weather", "[1,2]").arguments(JSON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a JSON object");
    }

    @Test
    void failuresMapToKindsWithoutLeakingTheKey() {
        ChatClient chat = client(5);
        ChatRequest req = ChatRequest.of(List.of(ChatMessage.user("x")), List.of());

        script.add(new Scripted(500, "{\"error\":\"boom\"}", 0));
        assertThatThrownBy(() -> chat.complete(req)).isInstanceOfSatisfying(ChatException.class, e -> {
            assertThat(e.kind()).isEqualTo(ChatException.Kind.HTTP_ERROR);
            assertThat(e.status()).isEqualTo(500);
            assertThat(e.getMessage()).doesNotContain(KEY);
        });

        script.add(new Scripted(429, "{\"error\":\"slow down\"}", 0));
        assertThatThrownBy(() -> chat.complete(req)).isInstanceOfSatisfying(ChatException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.RATE_LIMITED));

        for (String bad : List.of("not json", "{}", "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  \"}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"<think>only thinking</think>\"}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{}\"}}]}}]}")) {
            answer(bad);
            assertThatThrownBy(() -> chat.complete(req)).as(bad).isInstanceOfSatisfying(ChatException.class,
                    e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.BAD_RESPONSE));
        }
    }

    @Test
    void aSlowModelTimesOutAsUnreachable() {
        script.add(new Scripted(200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}", 3500));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client(1).complete(ChatRequest.of(List.of(ChatMessage.user("x")), List.of())))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.UNREACHABLE));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void anUnreachableServerAndMissingConfigAreClearErrors() {
        server.stop(0);
        assertThatThrownBy(() -> client(2).complete(ChatRequest.of(List.of(ChatMessage.user("x")), List.of())))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.UNREACHABLE));

        ChatClient off = new ChatClient(new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                new GOneProperties.Llm("", "", "", 5)), JSON);
        assertThat(off.configured()).isFalse();
        assertThatThrownBy(() -> off.complete(ChatRequest.of(List.of(ChatMessage.user("x")), List.of())))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.NOT_CONFIGURED));
    }
}
