package com.example.g_one.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;

/** Small helpers the tool adapters share: parsing a schema written as JSON text, and reading model arguments. */
public final class ToolArgs {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolArgs() {}

    /** Parses a JSON Schema written as a text block. A typo fails at class load, so every tool test catches it. */
    public static JsonNode schema(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject() || !"object".equals(node.path("type").asText())) {
                throw new IllegalStateException("A tool schema must be a JSON object with \"type\": \"object\"");
            }
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("A tool schema is not valid JSON", e);
        }
    }

    /** The named argument as trimmed text, or empty when it is missing, not a string, or blank. */
    public static Optional<String> text(JsonNode args, String name) {
        JsonNode v = args == null ? null : args.get(name);
        if (v == null || !v.isTextual()) return Optional.empty();
        String s = v.asText().strip();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }
}
