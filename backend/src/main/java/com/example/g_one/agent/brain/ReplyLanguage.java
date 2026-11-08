package com.example.g_one.agent.brain;

import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.core.Language;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which offered language a reply is spoken in, read from the script the model wrote it in. The system prompt tells the
 * model to answer in the language the farmer last spoke (Hindi in Devanagari, Bengali in Bengali script, English in
 * Latin), so the script is a reliable signal and costs no extra model call. When the reply's script maps to the
 * current language's prefix (Hinglish "hi-en" and Devanagari, for example) the current language is kept.
 */
final class ReplyLanguage {
    private static final Map<Character.UnicodeScript, String> PREFIX = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.LATIN, "en"),
            Map.entry(Character.UnicodeScript.DEVANAGARI, "hi"),
            Map.entry(Character.UnicodeScript.BENGALI, "bn"),
            Map.entry(Character.UnicodeScript.TAMIL, "ta"),
            Map.entry(Character.UnicodeScript.KANNADA, "kn"),
            Map.entry(Character.UnicodeScript.TELUGU, "te"),
            Map.entry(Character.UnicodeScript.GUJARATI, "gu"),
            Map.entry(Character.UnicodeScript.GURMUKHI, "pa"),
            Map.entry(Character.UnicodeScript.MALAYALAM, "ml"),
            Map.entry(Character.UnicodeScript.ORIYA, "or")));

    private ReplyLanguage() {}

    static Language of(String text, Language current, Language.Registry registry) {
        Optional<String> prefix = dominantPrefix(text);
        if (prefix.isEmpty() || prefix.get().equals(FixedLines.prefix(current.code()))) return current;
        return byPrefix(prefix.get(), registry).orElse(current);
    }

    /** The first offered language with this prefix ("en" → "en-IN"). */
    static Optional<Language> byPrefix(String prefix, Language.Registry registry) {
        return registry.all().stream().filter(l -> FixedLines.prefix(l.code()).equals(prefix)).findFirst();
    }

    /** The prefix of the script with the most characters, counting vowel signs; empty when no mapped script appears. */
    static Optional<String> dominantPrefix(String text) {
        Map<Character.UnicodeScript, Integer> counts = new EnumMap<>(Character.UnicodeScript.class);
        text.codePoints().forEach(cp -> {
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (PREFIX.containsKey(s)) counts.merge(s, 1, Integer::sum);
        });
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> PREFIX.get(e.getKey()));
    }
}
