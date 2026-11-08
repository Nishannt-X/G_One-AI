package com.example.g_one.memory;

import com.example.g_one.core.FarmerMemory;
import java.util.List;

/**
 * Turns a finished call into a draft gist, follow-ups and profile updates. The draft is untrusted (it usually comes from
 * an LLM): {@link GistWriter} checks the field whitelist, the 80-word cap and the secret scrub before anything is stored.
 */
public interface CallSummarizer {

    /** False when this summarizer cannot run (for example, no LLM configured); the writer then uses a template gist. */
    boolean available();

    /**
     * @param transcript the call, already scrubbed of secret-looking numbers
     * @param current what is stored for the farmer now (empty for a first-time caller)
     * @throws RuntimeException on any failure; the writer retries once, then falls back to a template gist
     */
    Draft summarize(CallTranscript transcript, FarmerMemory current);

    /** The summarizer's raw answer. Lists may be empty, never null. */
    record Draft(String gist, List<FollowUp> followUps, List<FactUpdate> profileUpdates) {
        public Draft {
            gist = gist == null ? "" : gist;
            followUps = followUps == null ? List.of() : List.copyOf(followUps);
            profileUpdates = profileUpdates == null ? List.of() : List.copyOf(profileUpdates);
        }
    }

    /** {@code open} is false when the follow-up was acted on in this call (docs/memory.md status DONE). */
    record FollowUp(String text, String dueHint, boolean open) {}

    /** A proposed profile fact. Field names follow {@link ProfileFields}; {@code crops} means one crop. */
    record FactUpdate(String field, String value) {}
}
