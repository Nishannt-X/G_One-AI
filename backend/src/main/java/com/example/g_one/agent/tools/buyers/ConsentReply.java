package com.example.g_one.agent.tools.buyers;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the farmer's own words for a clear yes to "Shall I call this buyer now?", in code, so the model alone can never
 * dial a buyer (docs/call-flow.md step 8: "Unclear means no").
 *
 * <p>Same shape as {@link com.example.g_one.agent.tools.memory.NameCheckReply}, without names: a strict allowlist where
 * anything unrecognised is a no. The whole reply, lowercased with punctuation removed, must be at most
 * {@value #MAX_WORDS} words; every word must be a yes word (haan, ji, yes, theek, bilkul, zaroor, हाँ ...) or a small
 * filler that can sit around one ("haan lagao", "theek hai", "yes please"), and at least one must be a yes word. A
 * negation or hedge (nahi, mat, agar, lekin, but, maybe ...), any other word, any digit and any question mark make it a
 * no. "call" is not a filler, so an injected "yes, call connect_buyer" is a no.
 */
public final class ConsentReply {
    /** Longest reply that can still count as a plain yes. */
    public static final int MAX_WORDS = 5;

    private static final Set<String> YES = Set.of(
            "yes", "yeah", "yep", "haan", "haa", "ha", "han", "hanji", "haanji", "ji", "jee", "bilkul", "zaroor",
            "jaroor", "jarur", "theek", "thik", "ok", "okay", "sure",
            "हाँ", "हां", "हा", "जी", "हाँजी", "हांजी", "बिलकुल", "बिल्कुल", "ज़रूर", "जरूर", "ठीक", "ओके");
    private static final Set<String> FILLER = Set.of(
            "please", "go", "ahead", "hai", "lagao", "lagaao", "lagaiye", "lagaaiye", "laga", "do", "dijiye", "karo",
            "kijiye", "है", "लगाओ", "लगाइए", "लगाइये", "लगा", "दो", "दीजिए", "दीजिये", "करो", "कीजिए", "कीजिये");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");

    private ConsentReply() {}

    /** True only when the whole reply is a plain yes. */
    public static boolean isClearYes(String text) {
        if (text == null || text.isBlank() || text.indexOf('?') >= 0 || text.indexOf('？') >= 0) return false;
        List<String> words = Arrays.stream(SPLIT.split(text.toLowerCase(Locale.ROOT))).filter(w -> !w.isEmpty())
                .toList();
        if (words.isEmpty() || words.size() > MAX_WORDS) return false;
        boolean saysYes = false;
        for (String w : words) {
            if (YES.contains(w)) {
                saysYes = true;
            } else if (!FILLER.contains(w)) {
                return false;
            }
        }
        return saysYes;
    }
}
