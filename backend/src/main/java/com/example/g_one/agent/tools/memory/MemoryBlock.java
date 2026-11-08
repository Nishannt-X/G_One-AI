package com.example.g_one.agent.tools.memory;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.ProfileFact;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The compact memory text the model sees (docs/memory.md, "Prompt budget"): the profile, the three newest call gists
 * and their follow-ups, capped at {@value #MAX_TOKENS} tokens. When over the cap the oldest gist goes first.
 *
 * <p>Tokens are estimated as UTF-8 bytes divided by three, which over-counts English (about four bytes a token) and is
 * close for Devanagari (three bytes a character), so the real count stays under the cap without a tokenizer.
 *
 * <p>Everything stored came from a phone call, so every value is flattened to one line and length-capped before it goes
 * into the prompt: a remembered value cannot add instructions on lines of its own.
 */
public final class MemoryBlock {
    public static final int MAX_TOKENS = 500;
    static final int MAX_GISTS = 3;
    static final int MAX_FACT_CHARS = 80;
    static final int MAX_NAME_CHARS = 40;
    static final int MAX_GIST_CHARS = 600;
    static final int MAX_FOLLOW_UP_CHARS = 160;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Profile fields in the order docs/memory.md lists them; anything else follows alphabetically. */
    private static final List<String> FIELD_ORDER =
            List.of("name", "village", "district", "state", "crops", "usualMandi", "language");
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private MemoryBlock() {}

    /** The name the farmer gave on an earlier call, cleaned for speaking; empty when none is usable. */
    public static Optional<String> rememberedName(FarmerMemory memory) {
        return memory.fact("name")
                .map(f -> clean(f.value(), MAX_NAME_CHARS))
                .filter(n -> n.codePoints().anyMatch(Character::isLetter) && n.chars().noneMatch(Character::isDigit));
    }

    /** The {@code memory} value of the system prompt for this call's state. */
    public static String forPrompt(MemoryGate.State state, FarmerMemory memory) {
        return switch (state) {
            case NEW -> "Nothing is remembered about this caller yet: this is the first call from this number.";
            case WITHHELD -> "Memory is off for this call because the caller ID is withheld. Nothing is remembered "
                    + "and nothing can be saved.";
            case UNNAMED -> "Nothing remembered may be used on this call. Treat the caller as a new farmer.";
            case NAME_CHECK -> "The greeting asked whether you are speaking with "
                    + rememberedName(memory).orElse("the remembered farmer") + " ji. When the farmer answers, first "
                    + "call confirm_name: confirmed true only for a clear yes, false for a no or anything unclear. "
                    + "Until then, do not use, mention or hint at anything remembered, and do not save facts.";
            case CONFIRMED -> render(memory);
            case OTHER_SPEAKER -> "The person on this call is not the remembered farmer. Treat them as a new farmer, "
                    + "never mention anything remembered, and do not save facts.";
            case FORGOTTEN -> "The farmer asked to be forgotten on this call, and everything remembered was deleted. "
                    + "Memory is off for the rest of this call: do not save facts.";
        };
    }

    /** The full block for a confirmed farmer, within {@value #MAX_TOKENS} estimated tokens. */
    public static String render(FarmerMemory memory) {
        for (int gists = Math.min(MAX_GISTS, memory.gists().size()); gists >= 0; gists--) {
            String text = build(memory, gists);
            if (estimateTokens(text) <= MAX_TOKENS) return text;
        }
        return truncate(build(memory, 0));
    }

    /** Conservative token estimate: UTF-8 bytes / 3, rounded up. */
    public static int estimateTokens(String text) {
        return (text.getBytes(StandardCharsets.UTF_8).length + 2) / 3;
    }

    private static String build(FarmerMemory memory, int gists) {
        StringBuilder out = new StringBuilder();
        out.append("FARMER MEMORY (caller-ID match only, NOT verified identity)\n");
        out.append("The farmer said yes to the name check on this call. Never read this list aloud. Ask before "
                + "assuming, and do not ask again for what it already says.\n");
        List<ProfileFact> facts = new ArrayList<>(memory.facts());
        facts.sort(Comparator.comparingInt((ProfileFact f) -> rank(f.field())).thenComparing(ProfileFact::field));
        List<String> parts = new ArrayList<>();
        for (ProfileFact f : facts) {
            parts.add(clean(f.field(), 30) + " " + clean(f.value(), MAX_FACT_CHARS) + " ("
                    + f.provenance().saidOrInferred().wire() + ", " + day(f.provenance().atMs()) + ")");
        }
        out.append("Profile: ").append(parts.isEmpty() ? "nothing saved" : String.join(" | ", parts)).append('\n');
        List<CallGist> recent = memory.gists().subList(0, gists);
        if (!recent.isEmpty()) {
            out.append("Recent calls (newest first):\n");
            for (CallGist g : recent) {
                out.append("- ").append(day(g.startedMs())).append(": ").append(clean(g.summary(), MAX_GIST_CHARS))
                        .append('\n');
            }
            List<String> followUps = recent.stream().flatMap(g -> g.followUps().stream())
                    .map(s -> clean(s, MAX_FOLLOW_UP_CHARS)).filter(s -> !s.isEmpty()).toList();
            if (!followUps.isEmpty()) {
                out.append("Open follow-ups:\n");
                followUps.forEach(s -> out.append("- ").append(s).append('\n'));
            }
        }
        return out.toString().stripTrailing();
    }

    private static String truncate(String text) {
        int[] cps = text.codePoints().toArray();
        int n = cps.length;
        String cut = text;
        while (n > 0 && estimateTokens(cut + "…") > MAX_TOKENS) {
            n = Math.max(0, n - Math.max(1, (n / 10)));
            cut = new String(cps, 0, n);
        }
        return cut + "…";
    }

    private static int rank(String field) {
        int i = FIELD_ORDER.indexOf(field);
        return i < 0 ? FIELD_ORDER.size() : i;
    }

    private static String day(long epochMs) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), INDIA).toString();
    }

    /** One line, single spaces, at most {@code max} code points. */
    static String clean(String s, int max) {
        if (s == null) return "";
        String flat = SPACES.matcher(UNSAFE.matcher(s).replaceAll(" ")).replaceAll(" ").strip();
        if (flat.codePointCount(0, flat.length()) <= max) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, max)).stripTrailing() + "…";
    }
}
