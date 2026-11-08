package com.example.g_one.agent.llm;

import java.util.List;

/**
 * One message in an OpenAI-compatible chat. {@code role} is "system", "user", "assistant" or "tool". An assistant
 * message may carry {@code toolCalls} (and then often no content); a tool message answers one call by
 * {@code toolCallId}.
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of(), null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, List.of(), null);
    }

    /** The model's turn exactly as it came back, tool calls included, so it can be replayed in the next request. */
    public static ChatMessage assistant(ChatReply reply) {
        return new ChatMessage("assistant", reply.content().isEmpty() ? null : reply.content(), reply.toolCalls(), null);
    }

    /** The result of one tool call, sent back to the model. */
    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, List.of(), toolCallId);
    }
}
