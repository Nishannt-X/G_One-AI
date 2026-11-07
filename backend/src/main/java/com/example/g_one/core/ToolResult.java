package com.example.g_one.core;

import java.util.Objects;

/**
 * What a tool gives back to the language model. {@code content} is plain text or a JSON string, written for the model
 * (never shown to the farmer as is). On failure it is a short reason without internals, such as "price not available".
 */
public record ToolResult(boolean ok, String content) {

    public ToolResult {
        Objects.requireNonNull(content, "content");
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult error(String reason) {
        return new ToolResult(false, reason);
    }
}
