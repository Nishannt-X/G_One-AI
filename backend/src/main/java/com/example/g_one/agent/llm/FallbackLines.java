package com.example.g_one.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fixed spoken lines for when the model or a tool fails, so the farmer never hears silence. Read once from
 * {@code /prompts/fallback-lines.json}, keyed by Gnani locale code; unknown languages get the English line.
 */
@Component
public class FallbackLines {
    public static final String RESOURCE = "/prompts/fallback-lines.json";
    public static final String DEFAULT_LANGUAGE = "en-IN";

    private final Map<String, String> apology;

    public FallbackLines(ObjectMapper json) {
        try (InputStream in = FallbackLines.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Fallback lines not found: " + RESOURCE);
            JsonNode node = json.readTree(in).path("apology");
            Map<String, String> lines = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual() && !e.getValue().asText().isBlank()) lines.put(e.getKey(), e.getValue().asText());
            });
            if (!lines.containsKey(DEFAULT_LANGUAGE)) {
                throw new IllegalStateException("Fallback lines need an " + DEFAULT_LANGUAGE + " apology");
            }
            this.apology = Map.copyOf(lines);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fallback lines: " + RESOURCE, e);
        }
    }

    /** The short spoken apology in the farmer's language (Gnani code such as "hi-IN"), English when not available. */
    public String apology(String languageCode) {
        String line = languageCode == null ? null : apology.get(languageCode);
        return line != null ? line : apology.get(DEFAULT_LANGUAGE);
    }

    public Map<String, String> apologies() {
        return apology;
    }
}
