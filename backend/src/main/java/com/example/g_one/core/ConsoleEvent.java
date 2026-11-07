package com.example.g_one.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Something the console shows about a live call. Every event has the call id and {@code atMs} (epoch milliseconds when
 * it happened). JSON carries a {@code type} discriminator; the shapes are documented in {@code docs/console-events.md}
 * and mirrored by {@code web/src/types/console.ts}. Language fields hold the language code, such as {@code "hi-IN"}.
 * Synthetic data only: events never carry raw phone numbers or audio.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConsoleEvent.CallStarted.class, name = "call_started"),
        @JsonSubTypes.Type(value = ConsoleEvent.LegJoined.class, name = "leg_joined"),
        @JsonSubTypes.Type(value = ConsoleEvent.LegEnded.class, name = "leg_ended"),
        @JsonSubTypes.Type(value = ConsoleEvent.TurnHeard.class, name = "turn_heard"),
        @JsonSubTypes.Type(value = ConsoleEvent.TurnTranslated.class, name = "turn_translated"),
        @JsonSubTypes.Type(value = ConsoleEvent.AssistantSaid.class, name = "assistant_said"),
        @JsonSubTypes.Type(value = ConsoleEvent.WhisperSent.class, name = "whisper_sent"),
        @JsonSubTypes.Type(value = ConsoleEvent.ToolCalled.class, name = "tool_called"),
        @JsonSubTypes.Type(value = ConsoleEvent.MemoryLoaded.class, name = "memory_loaded"),
        @JsonSubTypes.Type(value = ConsoleEvent.DealUpdated.class, name = "deal_updated"),
        @JsonSubTypes.Type(value = ConsoleEvent.CallEnded.class, name = "call_ended"),
})
public sealed interface ConsoleEvent {
    CallId callId();

    long atMs();

    /** A farmer leg opened a new call. {@code language} is the farmer's starting language. */
    record CallStarted(CallId callId, long atMs, String language) implements ConsoleEvent {}

    /** A leg joined the call (the farmer leg first, then a buyer leg after connect). */
    record LegJoined(CallId callId, long atMs, LegId legId, LegRole role, LegSource source, String language)
            implements ConsoleEvent {

        public static LegJoined of(Leg leg, long atMs) {
            return new LegJoined(leg.callId(), atMs, leg.id(), leg.role(), leg.source(), leg.language().code());
        }
    }

    /** A leg ended. {@code reason} is a short machine word, such as "hangup", "stream_stopped" or "error". */
    record LegEnded(CallId callId, long atMs, LegId legId, LegRole role, String reason) implements ConsoleEvent {}

    /** A finished turn, in the speaker's own words. {@code startedMs}/{@code endedMs} are the turn's epoch times. */
    record TurnHeard(CallId callId, long atMs, LegId legId, LegRole role, String language, String text,
                     long startedMs, long endedMs) implements ConsoleEvent {

        public static TurnHeard of(CallId callId, Turn turn, long atMs) {
            return new TurnHeard(callId, atMs, turn.legId(), turn.role(), turn.language().code(), turn.text(),
                    turn.startedMs(), turn.endedMs());
        }
    }

    /**
     * A heard turn, translated for the other side during mediation. {@code legId}, {@code role} and
     * {@code turnStartedMs} identify the {@link TurnHeard} it translates; {@code toRole} is the listener.
     */
    record TurnTranslated(CallId callId, long atMs, LegId legId, LegRole role, LegRole toRole, String fromLanguage,
                          String toLanguage, String text, long turnStartedMs) implements ConsoleEvent {}

    /** The assistant's own words (greeting, reply, read-back), spoken on leg {@code legId} to {@code role}. */
    record AssistantSaid(CallId callId, long atMs, LegId legId, LegRole role, String language, String text)
            implements ConsoleEvent {}

    /** A private message spoken to the farmer only, on farmer leg {@code legId}. The buyer never hears it. */
    record WhisperSent(CallId callId, long atMs, LegId legId, WhisperKind kind, String language, String text)
            implements ConsoleEvent {}

    /**
     * The assistant called a tool. {@code args} is the arguments object the model sent; {@code result} the content
     * handed back to the model.
     */
    record ToolCalled(CallId callId, long atMs, String tool, JsonNode args, boolean ok, String result,
                      long durationMs) implements ConsoleEvent {}

    /**
     * Memory was loaded at the start of the farmer leg. {@code returning} is true when anything was remembered. Not sent
     * when the caller ID is withheld: no memory is loaded for that call.
     */
    record MemoryLoaded(CallId callId, long atMs, CallerKey callerKey, boolean returning, List<ProfileFact> facts,
                        List<CallGist> gists) implements ConsoleEvent {

        public MemoryLoaded {
            facts = facts == null ? List.of() : List.copyOf(facts);
            gists = gists == null ? List.of() : List.copyOf(gists);
        }

        /** @throws IllegalArgumentException for {@link FarmerMemory#withheld}, which has nothing to show */
        public static MemoryLoaded of(CallId callId, FarmerMemory memory, long atMs) {
            CallerKey key = memory.callerKey().orElseThrow(
                    () -> new IllegalArgumentException("No memory_loaded event for a withheld caller ID"));
            return new MemoryLoaded(callId, atMs, key, !memory.isEmpty(), memory.facts(), memory.gists());
        }
    }

    /**
     * The deal summary changed. Terms are as spoken (null when not stated); {@code price} is rupees per {@code unit}.
     * {@code farmerYes}/{@code buyerYes} are the spoken confirmations of the current terms. Nonbinding.
     */
    record DealUpdated(CallId callId, long atMs, DealState state, String commodity, String variety, String market,
                       String quantity, BigDecimal price, String unit, String pickup, boolean farmerYes,
                       boolean buyerYes) implements ConsoleEvent {}

    /** The call is over (the farmer leg ended). */
    record CallEnded(CallId callId, long atMs, long durationMs, String reason) implements ConsoleEvent {}

    /** JSON: "price_reference", "caution", "tip". */
    enum WhisperKind {
        PRICE_REFERENCE, CAUTION, TIP;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * JSON: "draft" (terms heard), "readback" (being read back), "confirming" (waiting for both yeses), "agreed",
     * "closed" (declined, expired or replaced).
     */
    enum DealState {
        DRAFT, READBACK, CONFIRMING, AGREED, CLOSED;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
