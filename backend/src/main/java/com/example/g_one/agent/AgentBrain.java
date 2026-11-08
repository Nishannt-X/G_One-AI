package com.example.g_one.agent;

import com.example.g_one.core.CallContext;
import com.example.g_one.core.Language;
import com.example.g_one.core.Turn;
import java.util.List;
import java.util.Objects;

/**
 * Decides what the assistant says to the farmer. The agent brain (#19) implements it; the call pipeline (#23) calls it
 * once per finished farmer turn.
 *
 * <p>Both methods block while the language model and its tools run, so the pipeline calls them off the audio thread.
 * The pipeline may abandon a result when a newer turn arrives (barge-in); implementations need not support
 * cancellation. Neither method throws: a failure comes back as a short spoken apology in the farmer's language.
 */
public interface AgentBrain {

    /** The first thing the assistant says on a new farmer leg, personalised when {@code ctx.memory()} is known. */
    Reply greet(CallContext ctx);

    /**
     * The assistant's next line after the farmer's finished turn.
     *
     * @param history earlier lines of this call, oldest first, not including {@code latest}
     * @param latest the farmer turn to answer
     */
    Reply respond(CallContext ctx, List<Line> history, Turn latest);

    /** Who said a line of the conversation. */
    enum Speaker { FARMER, BUYER, ASSISTANT }

    /** One line of this call's conversation, as text. */
    record Line(Speaker speaker, String text) {
        public Line {
            Objects.requireNonNull(speaker, "speaker");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * What to say next.
     *
     * @param text the words to speak, with numbers written as words for Timbre; never blank
     * @param language the language to speak them in (the farmer may switch, for example to English)
     * @param endCall true when the assistant should hang up after this line is played
     */
    record Reply(String text, Language language, boolean endCall) {
        public Reply {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(language, "language");
            if (text.isBlank()) {
                throw new IllegalArgumentException("A reply must have something to say");
            }
        }
    }
}
