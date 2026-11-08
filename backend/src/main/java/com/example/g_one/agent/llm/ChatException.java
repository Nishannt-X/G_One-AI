package com.example.g_one.agent.llm;

/**
 * A chat call that produced no usable answer. The caller turns any of these into the spoken apology line; {@link Kind}
 * is for logs and the console. Messages never contain the API key or the prompt.
 */
public class ChatException extends RuntimeException {
    public enum Kind {
        /** G_ONE_LLM_BASE_URL, _API_KEY or _MODEL is missing. */
        NOT_CONFIGURED,
        /** Timeout, refused connection or another network error. */
        UNREACHABLE,
        /** HTTP 429: the provider is rate limiting us. */
        RATE_LIMITED,
        /** Any other non-2xx status. */
        HTTP_ERROR,
        /** 2xx but not a chat completion we can read, or an empty answer. */
        BAD_RESPONSE
    }

    private final Kind kind;
    private final int status;

    public ChatException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status for {@link Kind#RATE_LIMITED} and {@link Kind#HTTP_ERROR}, otherwise 0. */
    public int status() {
        return status;
    }
}
