package com.example.g_one.agent.tools.memory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the farmer's own words for a clear yes to the name check, in code, so the model alone can never unlock memory
 * (docs/memory.md: "If the answer is no or unclear", treat the caller as someone else).
 *
 * <p>A clear yes starts with an affirmative word (haan, ji, yes, correct, sahi, हाँ, जी ...) and contains no negation,
 * condition or hedge anywhere (not, don't, if, unless, but, maybe, only, nahi, agar, lekin, नहीं, अगर, लेकिन ...) and
 * no question mark. Anything else is not a yes. The guard words match the deal agreement's reply guard.
 */
public final class NameCheckReply {
    private static final Set<String> YES = Set.of(
            "yes", "yeah", "yep", "yup", "correct", "right", "haan", "haa", "han", "ha", "hanji", "haanji", "ji",
            "jee", "sahi", "bilkul", "हाँ", "हां", "हा", "जी", "हांजी", "हाँजी", "सही", "बिल्कुल", "बिलकुल");
    private static final Set<String> GUARD = Set.of(
            "no", "not", "dont", "don't", "never", "if", "unless", "but", "maybe", "perhaps", "only", "nahi", "nahin",
            "nai", "na", "mat", "agar", "lekin", "par", "shayad", "sirf", "नहीं", "नही", "ना", "मत", "अगर", "लेकिन",
            "पर", "शायद", "सिर्फ़", "सिर्फ");
    /** Splits on anything that is not a letter, a combining mark (Devanagari vowel signs) or an apostrophe. */
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{M}']+");

    private NameCheckReply() {}

    /** True only for a clear affirmative with no negation, condition, hedge or question. */
    public static boolean isClearYes(String text) {
        if (text == null || text.isBlank() || text.indexOf('?') >= 0 || text.indexOf('？') >= 0) return false;
        List<String> words = Arrays.stream(SPLIT.split(text.toLowerCase(Locale.ROOT).replace('’', '\'')))
                .filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty() || !YES.contains(words.get(0))) return false;
        return words.stream().noneMatch(GUARD::contains);
    }
}
