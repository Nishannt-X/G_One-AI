package com.example.g_one.core;

import java.util.List;
import java.util.Objects;

/**
 * The short summary written at the end of a call: what was asked and what happened ({@code summary}), plus open
 * follow-ups. Times are epoch milliseconds; {@code startedMs} orders a farmer's gists (newest call first). The exact
 * wording rules come from the memory decision (#9).
 */
public record CallGist(CallId callId, long startedMs, long endedMs, String summary, List<String> followUps) {

    public CallGist {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(summary, "summary");
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
    }
}
