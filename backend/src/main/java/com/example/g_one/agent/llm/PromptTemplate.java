package com.example.g_one.agent.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prompt read from a classpath resource under {@code /prompts/}, with {@code {{name}}} placeholders. Rendering is
 * strict: a missing value or an unknown name is an error, so a renamed placeholder fails a test instead of sending
 * "{{memory}}" to the model.
 */
public final class PromptTemplate {
    /** The assistant's system prompt. Placeholders: language, today, memory. */
    public static final String ASSISTANT_SYSTEM = "/prompts/assistant-system.md";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+)}}");

    private final String text;
    private final Set<String> names;

    private PromptTemplate(String text) {
        this.text = text;
        Set<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) found.add(m.group(1));
        this.names = Set.copyOf(found);
    }

    /** @throws IllegalStateException when the resource is missing or empty */
    public static PromptTemplate load(String resource) {
        try (InputStream in = PromptTemplate.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Prompt resource not found: " + resource);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (text.isBlank()) throw new IllegalStateException("Prompt resource is empty: " + resource);
            return new PromptTemplate(text);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read prompt resource: " + resource, e);
        }
    }

    public Set<String> placeholders() {
        return names;
    }

    /** @throws IllegalArgumentException when {@code values} does not name exactly this template's placeholders */
    public String render(Map<String, String> values) {
        if (!values.keySet().equals(names)) {
            throw new IllegalArgumentException("Prompt needs values for " + names + " but got " + new TreeSet<>(values.keySet()));
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null) throw new IllegalArgumentException("Prompt value is null: " + m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
