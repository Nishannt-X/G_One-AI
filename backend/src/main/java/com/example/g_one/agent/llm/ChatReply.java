package com.example.g_one.agent.llm;

import java.util.List;

/**
 * The model's answer: spoken text, tool calls, or both. {@code finishReason} is passed through as the provider sent it
 * ("stop", "tool_calls", "length", ...), or empty when absent.
 */
public record ChatReply(String content, List<ToolCall> toolCalls, String finishReason) {
    public ChatReply {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? "" : finishReason;
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
