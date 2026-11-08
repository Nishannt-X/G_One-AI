package com.example.g_one.memory;

import com.example.g_one.core.CallId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.Turn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the gist writer gets at hang-up: the call's lines (farmer and buyer turns plus what the assistant said), kept in
 * RAM only. docs/memory.md: transcripts are never stored; this object is dropped once the gist is written.
 *
 * @param otherSpeaker true when the name check failed (someone else on the farmer's phone): the gist is still written,
 *     flagged, but no profile fact is changed
 * @param outcome what the call ended with, when code knows it (the deal state machine); used by the template gist
 */
public record CallTranscript(CallId callId, long startedMs, long endedMs, List<Line> lines, boolean otherSpeaker,
                             Optional<Outcome> outcome) {

    public enum Speaker { FARMER, BUYER, ASSISTANT }

    /** docs/memory.md CallGist.outcome. */
    public enum Outcome { INFO_ONLY, NO_ANSWER, BUYER_DECLINED, DEAL_CONFIRMED, DEAL_UNCONFIRMED }

    /** One line of the call, as text (translated text for the other leg is fine). */
    public record Line(Speaker speaker, String text, long atMs) {
        public Line {
            Objects.requireNonNull(speaker, "speaker");
            Objects.requireNonNull(text, "text");
        }

        public static Line of(Turn turn) {
            return new Line(turn.role() == LegRole.FARMER ? Speaker.FARMER : Speaker.BUYER, turn.text(),
                    turn.startedMs());
        }

        public static Line assistant(String text, long atMs) {
            return new Line(Speaker.ASSISTANT, text, atMs);
        }
    }

    public CallTranscript {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(outcome, "outcome");
        if (endedMs < startedMs) {
            throw new IllegalArgumentException("A call cannot end before it starts");
        }
        List<Line> sorted = new ArrayList<>(lines == null ? List.of() : lines);
        sorted.sort(Comparator.comparingLong(Line::atMs)); // stable: equal times keep their given order
        lines = List.copyOf(sorted);
    }

    /** A transcript from the call's turns and assistant lines, merged in time order. */
    public static CallTranscript of(CallId callId, long startedMs, long endedMs, List<Turn> turns,
                                    List<Line> assistantLines) {
        List<Line> all = new ArrayList<>();
        turns.forEach(t -> all.add(Line.of(t)));
        all.addAll(assistantLines);
        return new CallTranscript(callId, startedMs, endedMs, all, false, Optional.empty());
    }

    public CallTranscript withOtherSpeaker(boolean other) {
        return new CallTranscript(callId, startedMs, endedMs, lines, other, outcome);
    }

    public CallTranscript withOutcome(Outcome o) {
        return new CallTranscript(callId, startedMs, endedMs, lines, otherSpeaker, Optional.of(o));
    }
}
