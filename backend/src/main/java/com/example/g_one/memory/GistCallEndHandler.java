package com.example.g_one.memory;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.brain.LlmAgentBrain;
import com.example.g_one.agent.pipeline.CallEndHandler;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.FarmerMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Writes the farmer's memory when a farmer leg ends, as the brain's name check allows (docs/memory.md "Shared phone"
 * and "Write timing"). It reads {@link LlmAgentBrain#memoryState} for the call:
 * <ul>
 *   <li>{@code WITHHELD} or {@code FORGOTTEN}: nothing is written.</li>
 *   <li>{@code OTHER_SPEAKER}: the gist is written with an empty prior memory and flagged, and the profile is left
 *       alone ({@link CallTranscript#otherSpeaker()}).</li>
 *   <li>{@code NAME_CHECK} (the farmer hung up before answering) and {@code UNNAMED} (memory exists but has no name to
 *       check, so the caller was never confirmed): handled like {@code OTHER_SPEAKER}, failing closed, because the
 *       brain also never let this caller use or change the stored profile.</li>
 *   <li>{@code NEW} and {@code CONFIRMED}: a normal gist with profile updates ({@link GistWriter}).</li>
 * </ul>
 * When the brain never saw the call (the farmer hung up before the greeting), the state is worked out from the memory
 * loaded at call start ({@link MemoryGate#initial}), with the same rules.
 *
 * <p>Afterwards, whatever happened, it calls {@link LlmAgentBrain#callEnded} so the brain drops the call's state. It
 * therefore runs last among the end handlers ({@link Ordered#LOWEST_PRECEDENCE}): a handler that needs the memory
 * state must run before it. The pipeline calls it on its own executor, so the summarizer call may block here.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class GistCallEndHandler implements CallEndHandler {
    private static final Logger log = LoggerFactory.getLogger(GistCallEndHandler.class);

    /** What the handler does for one call. */
    enum Action { SKIP, WRITE, WRITE_AS_OTHER_SPEAKER }

    private final GistWriter writer;
    private final LlmAgentBrain brain;

    public GistCallEndHandler(GistWriter writer, LlmAgentBrain brain) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.brain = Objects.requireNonNull(brain, "brain");
    }

    @Override
    public void callEnded(EndedCall call) {
        try {
            FarmerMemory atStart = call.context().memory();
            MemoryGate.State state = brain.memoryState(call.callId()).orElseGet(() -> MemoryGate.initial(atStart));
            Action action = action(state, atStart);
            if (action == Action.SKIP) {
                log.info("No memory written for call {} (memory state {})", call.callId(), state);
                return;
            }
            GistWriter.Result result = writer.write(atStart, transcript(call, action == Action.WRITE_AS_OTHER_SPEAKER));
            log.info("Memory for call {}: {} ({} gist, {} profile facts)", call.callId(), result.status(),
                    result.source(), result.facts().size());
        } finally {
            brain.callEnded(call.callId());
        }
    }

    static Action action(MemoryGate.State state, FarmerMemory atStart) {
        if (atStart.isWithheld()) {
            return Action.SKIP; // memory is off for a withheld caller ID, whatever the brain thinks
        }
        return switch (state) {
            case WITHHELD, FORGOTTEN -> Action.SKIP;
            case OTHER_SPEAKER, NAME_CHECK, UNNAMED -> Action.WRITE_AS_OTHER_SPEAKER;
            case NEW, CONFIRMED -> Action.WRITE;
        };
    }

    /**
     * The pipeline's lines as a gist-writer transcript. The lines carry no times, so all are stamped with the call's
     * start; {@link CallTranscript}'s stable sort then keeps the pipeline's order.
     */
    static CallTranscript transcript(EndedCall call, boolean otherSpeaker) {
        List<CallTranscript.Line> lines = new ArrayList<>();
        for (AgentBrain.Line line : call.transcript()) {
            CallTranscript.Speaker speaker = switch (line.speaker()) {
                case FARMER -> CallTranscript.Speaker.FARMER;
                case BUYER -> CallTranscript.Speaker.BUYER;
                case ASSISTANT -> CallTranscript.Speaker.ASSISTANT;
            };
            lines.add(new CallTranscript.Line(speaker, line.text(), call.startedMs()));
        }
        long ended = Math.max(call.startedMs(), call.endedMs());
        return new CallTranscript(call.callId(), call.startedMs(), ended, lines, otherSpeaker, Optional.empty());
    }
}
