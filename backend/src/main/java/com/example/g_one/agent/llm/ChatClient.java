package com.example.g_one.agent.llm;

import com.example.g_one.GOneProperties;
import com.example.g_one.speech.Http;
import com.example.g_one.speech.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI-compatible /chat/completions with function calling, for the agent brain. Endpoint, key, model and timeout come
 * from G_ONE_LLM_*. Uses the shared HTTP/1.1 client with bounded timeouts. Every failure becomes a
 * {@link ChatException}; nothing is retried here, because a phone turn cannot afford a second timeout.
 */
@Component
public class ChatClient {
    private static final String STAGE = "agent_llm";
    /** A chat answer is a few kilobytes at most; anything far larger is not a reply we want to buffer. */
    private static final int MAX_BODY_BYTES = 512 * 1024;
    /** Some reasoning models put their thinking inline. It must never be spoken. */
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    private final GOneProperties.Llm cfg;
    private final RestClient client;
    private final ObjectMapper json;

    public ChatClient(GOneProperties props, ObjectMapper json) {
        this.cfg = props.llm();
        this.client = cfg != null && cfg.configured() ? Http.client(cfg.baseUrl(), cfg.timeoutSeconds()) : null;
        this.json = json;
    }

    public boolean configured() {
        return client != null;
    }

    /** @throws ChatException when there is no usable answer (not configured, network, HTTP status, unreadable) */
    public ChatReply complete(ChatRequest request) {
        if (client == null) {
            throw new ChatException(ChatException.Kind.NOT_CONFIGURED, 0,
                    "The assistant's language model is not configured.");
        }
        Http.Result r;
        try {
            r = Http.call(STAGE, client.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(utf8(body(request))), MAX_BODY_BYTES);
        } catch (UpstreamException e) {
            throw new ChatException(ChatException.Kind.UNREACHABLE, 0,
                    "The language model could not be reached (timeout or network error).");
        }
        if (r.status() == 429) {
            throw new ChatException(ChatException.Kind.RATE_LIMITED, 429,
                    "The language model is rate limiting requests (HTTP 429).");
        }
        if (!r.ok()) {
            throw new ChatException(ChatException.Kind.HTTP_ERROR, r.status(),
                    "The language model returned HTTP " + r.status() + ".");
        }
        if (r.body().length > MAX_BODY_BYTES) throw bad("The language model's answer was too large.");
        return parse(r.body());
    }

    /** The JSON request body. Package-private so tests can check the wire format without a server. */
    ObjectNode body(ChatRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", cfg.model());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", m.role());
            if (m.content() == null) node.putNull("content");
            else node.put("content", m.content());
            if (!m.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall c : m.toolCalls()) {
                    ObjectNode call = calls.addObject();
                    call.put("id", c.id());
                    call.put("type", "function");
                    String args = c.argumentsJson() == null || c.argumentsJson().isBlank() ? "{}" : c.argumentsJson();
                    call.putObject("function").put("name", c.name()).put("arguments", args);
                }
            }
            if (m.toolCallId() != null) node.put("tool_call_id", m.toolCallId());
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.parameters() == null ? json.createObjectNode().put("type", "object") : t.parameters());
            }
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private ChatReply parse(byte[] raw) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (IOException e) {
            throw bad("The language model returned an unreadable answer.");
        }
        JsonNode choice = root == null ? null : root.path("choices").path(0);
        if (choice == null || !choice.isObject() || !choice.path("message").isObject()) {
            throw bad("The language model's answer had no message.");
        }
        JsonNode message = choice.path("message");
        String content = message.path("content").isTextual() ? clean(message.path("content").asText()) : "";
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : message.path("tool_calls")) {
            JsonNode fn = c.path("function");
            String name = fn.path("name").asText("");
            if (name.isBlank()) throw bad("The language model asked for a tool without a name.");
            JsonNode args = fn.path("arguments");
            // The spec says a JSON string; a few compatible servers send the object itself.
            String argsJson = args.isTextual() ? args.asText() : args.isMissingNode() || args.isNull() ? "" : args.toString();
            String id = c.path("id").asText("");
            calls.add(new ToolCall(id.isBlank() ? "call_" + calls.size() : id, name, argsJson));
        }
        if (content.isEmpty() && calls.isEmpty()) throw bad("The language model returned an empty answer.");
        return new ChatReply(content, calls, choice.path("finish_reason").asText(""));
    }

    /** Bytes, not a String: a String body would go through a converter whose default charset is not UTF-8. */
    private static byte[] utf8(ObjectNode body) {
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String clean(String content) {
        return THINK.matcher(content).replaceAll("").trim();
    }

    private static ChatException bad(String message) {
        return new ChatException(ChatException.Kind.BAD_RESPONSE, 0, message);
    }
}
