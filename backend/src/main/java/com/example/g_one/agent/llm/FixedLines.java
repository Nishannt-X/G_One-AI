package com.example.g_one.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Lines the assistant speaks word for word, without the language model: the greetings, the memory notice, the
 * withheld-number line, the returning-farmer name check, the forget-me confirmation and a goodbye. Read once from
 * {@code /prompts/fixed-lines.json} (wording from docs/call-flow.md and docs/memory.md).
 */
@Component
public class FixedLines {
    public static final String RESOURCE = "/prompts/fixed-lines.json";
    public static final String DEFAULT_LANGUAGE = "en-IN";
    public static final String NAME = "{name}";

    /** One language's lines. {@code code} is the resource key they came from, which may differ from the one asked. */
    public record Lines(String code, String welcome, String memoryNotice, String withheld, String nameCheck,
                        String forgotten, String goodbye) {

        /** The name check with the farmer's remembered name filled in. */
        public String nameCheck(String name) {
            return nameCheck.replace(NAME, name);
        }
    }

    private final Map<String, Lines> byCode;

    public FixedLines(ObjectMapper json) {
        try (InputStream in = FixedLines.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Fixed lines not found: " + RESOURCE);
            Map<String, Lines> lines = new LinkedHashMap<>();
            json.readTree(in).path("lines").fields().forEachRemaining(e -> lines.put(e.getKey(), read(e.getKey(), e.getValue())));
            if (!lines.containsKey(DEFAULT_LANGUAGE)) {
                throw new IllegalStateException("Fixed lines need an " + DEFAULT_LANGUAGE + " entry");
            }
            this.byCode = Map.copyOf(lines);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fixed lines: " + RESOURCE, e);
        }
    }

    private static Lines read(String code, JsonNode n) {
        Lines l = new Lines(code, need(n, code, "welcome"), need(n, code, "memoryNotice"), need(n, code, "withheld"),
                need(n, code, "nameCheck"), need(n, code, "forgotten"), need(n, code, "goodbye"));
        if (!l.nameCheck().contains(NAME)) {
            throw new IllegalStateException("Fixed line nameCheck for " + code + " must contain " + NAME);
        }
        return l;
    }

    private static String need(JsonNode n, String code, String key) {
        String v = n.path(key).asText("");
        if (v.isBlank()) throw new IllegalStateException("Fixed line " + key + " is missing for " + code);
        return v;
    }

    /** Lines for a Gnani locale code: the exact code, else the same language prefix ("hi-en" uses "hi-IN"), else en-IN. */
    public Lines forLanguage(String code) {
        return byCode.get(bestKey(byCode.keySet(), code, DEFAULT_LANGUAGE));
    }

    public Set<String> languages() {
        return byCode.keySet();
    }

    /** The exact key, else the first key with the same language prefix (before the dash), else {@code fallback}. */
    public static String bestKey(Set<String> keys, String code, String fallback) {
        if (code == null) return fallback;
        if (keys.contains(code)) return code;
        String prefix = prefix(code);
        return keys.stream().filter(k -> prefix(k).equals(prefix)).sorted().findFirst().orElse(fallback);
    }

    /** "hi-IN" → "hi". */
    public static String prefix(String code) {
        int dash = code.indexOf('-');
        return (dash < 0 ? code : code.substring(0, dash)).toLowerCase(Locale.ROOT);
    }
}
