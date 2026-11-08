package com.example.g_one.agent.pipeline;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs once when a farmer leg ends, with what was said on the call. The gist writer (#20) implements it to write the
 * call gist and profile updates; declare an implementation as a Spring bean and the pipeline calls it. With no bean,
 * nothing happens at hang-up.
 *
 * <p>The pipeline calls handlers off the leg's threads, on its own executor, so a handler may block (for example on
 * the gist LLM call). A handler that throws is logged and does not affect other handlers. The transcript lives only in
 * this call: the pipeline drops its copy afterwards, and handlers must not store it (memory keeps gists, not
 * transcripts; see {@code docs/memory.md}).
 */
@FunctionalInterface
public interface CallEndHandler {

    /** A handler that does nothing, for tests and placeholders. */
    CallEndHandler NONE = call -> {};

    void callEnded(EndedCall call);

    /**
     * A finished farmer leg.
     *
     * @param context the call context at hang-up: the farmer leg, the language last spoken and the memory snapshot
     *     loaded at the start. When {@link #callerKey()} is empty the caller ID was withheld: memory is off, so a
     *     handler must not read or write any memory store.
     * @param transcript every line of the call in order (farmer turns and the assistant's lines, including a greeting
     *     or reply that a barge-in cut short), as text
     * @param startedMs when the farmer leg joined, epoch milliseconds
     * @param endedMs when it ended, epoch milliseconds
     * @param reason a short machine word: {@code "hangup"} when the far side ended the leg, {@code "assistant_hangup"}
     *     when the assistant ended it after a closing line
     */
    record EndedCall(CallContext context, List<AgentBrain.Line> transcript, long startedMs, long endedMs,
                     String reason) {

        public EndedCall {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(reason, "reason");
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
        }

        public CallId callId() {
            return context.callId();
        }

        /** The key to write memory under; empty when the caller ID was withheld (memory is off for this call). */
        public Optional<CallerKey> callerKey() {
            return context.callerKey();
        }
    }
}
