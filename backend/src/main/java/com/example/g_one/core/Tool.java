package com.example.g_one.core;

import com.fasterxml.jackson.databind.JsonNode;

/** A function the assistant's language model can call, described in the OpenAI-compatible tool format. */
public interface Tool {
    /** The function name the model calls, such as {@code get_mandi_price}. Letters, digits and underscores. */
    String name();

    /** One or two sentences telling the model when to call it. */
    String description();

    /** JSON Schema of the arguments object (the tool's {@code parameters}), with {@code "type": "object"}. */
    JsonNode jsonSchema();

    /**
     * Runs the tool. Bad or missing arguments come back as {@link ToolResult#error}, not an exception. A thrown
     * exception is treated by the caller as an error result.
     *
     * @param args the arguments object the model sent (never null; an empty object when it sent none)
     */
    ToolResult invoke(JsonNode args, CallContext ctx);
}
