package com.example.g_one.agent.mediation;

import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.ChatMessage;
import com.example.g_one.agent.llm.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One JSON-in, JSON-out model call for mediation. What a person said always goes inside the {@code data} object, never
 * into the instructions, and the instructions tell the model to treat it as data. That is hygiene, not a defence against
 * prompt injection. No tools are offered, so nothing said during mediation can reach a brain tool through this path.
 */
final class MediationLlm {
    private static final double TEMPERATURE = 0.0;

    private final ChatClient chat;
    private final ObjectMapper json;

    MediationLlm(ChatClient chat, ObjectMapper json) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.json = Objects.requireNonNull(json, "json");
    }

    ObjectNode data() {
        return json.createObjectNode();
    }

    /**
     * Asks once and reads the answer as a JSON object.
     *
     * @return empty when the model failed or did not answer with a JSON object; never throws for model trouble
     */
    Optional<JsonNode> ask(String instructions, ObjectNode data, int maxTokens) {
        String content;
        try {
            content = chat.complete(new ChatRequest(
                    List.of(ChatMessage.system(instructions), ChatMessage.user(data.toString())),
                    List.of(), TEMPERATURE, maxTokens)).content();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return parseObject(content);
    }

    /** Reads a JSON object, also when the model wrapped it in a Markdown code fence or a sentence. */
    Optional<JsonNode> parseObject(String content) {
        if (content == null) {
            return Optional.empty();
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
