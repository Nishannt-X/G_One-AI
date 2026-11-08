package com.example.g_one.memory;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.FarmerMemory;
import java.util.ArrayList;
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

    /**
     * {@code open} is false when the follow-up was acted on in this call (docs/memory.md status DONE). {@code ref} is
     * the {@link OpenFollowUp#id()} of the earlier follow-up this one closes, or null; {@link GistWriter} also matches a
     * DONE follow-up to an earlier one by its text.
     */
    record FollowUp(String text, String dueHint, boolean open, String ref) {
        public FollowUp(String text, String dueHint, boolean open) {
            this(text, dueHint, open, null);
        }
    }

    /** An earlier follow-up still open, as the summarizer is shown it: a short id, the gist it is in, and its text. */
    record OpenFollowUp(String id, CallId callId, String text) {}

    /**
     * The open follow-ups the summarizer is shown: those of the {@value #FOLLOW_UP_GISTS} newest gists, newest first,
     * numbered {@code F1}, {@code F2}, ... A summarizer that closes one refers to it by this id.
     */
    static List<OpenFollowUp> openFollowUps(FarmerMemory current) {
        List<OpenFollowUp> out = new ArrayList<>();
        for (CallGist g : current.gists().stream().limit(FOLLOW_UP_GISTS).toList()) {
            for (String text : g.followUps()) {
                out.add(new OpenFollowUp("F" + (out.size() + 1), g.callId(), text));
            }
        }
        return List.copyOf(out);
    }

    int FOLLOW_UP_GISTS = 3;

    /** A proposed profile fact. Field names follow {@link ProfileFields}; {@code crops} means one crop. */
    record FactUpdate(String field, String value) {}
}
