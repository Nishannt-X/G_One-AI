package com.example.g_one.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The system prompt resource, its strict placeholder rendering, and the fallback apology lines. */
class PromptsTest {
    private static final Map<String, String> VALUES = Map.of(
            "language", "Hindi",
            "today", "the eighth of November, twenty twenty-six",
            "memory", "Name: Ramesh (said, last call). Crops: onion. Last call: asked onion rate at Lasalgaon.");

    @Test
    void systemPromptCarriesTheLanguageMemoryAndHonestyRules() {
        PromptTemplate t = PromptTemplate.load(PromptTemplate.ASSISTANT_SYSTEM);
        assertThat(t.placeholders()).containsExactlyInAnyOrder("language", "today", "memory");

        String prompt = t.render(VALUES);
        assertThat(prompt).doesNotContain("{{");
        assertThat(prompt).contains("G-One")
                .contains("The farmer's language on this call is Hindi.")
                .contains("Hinglish")
                .contains("one or two short spoken sentences")
                .contains("number as words")
                .contains("dated reference")
                .contains("Never mention tools")
                .contains("Name: Ramesh (said, last call).")
                .contains("Today is the eighth of November, twenty twenty-six.");
        // The prompt forbids these phrases; it must never model them as something to say.
        assertThat(prompt).contains("Never say \"today's price\", \"live rate\"");
    }

    @Test
    void renderingIsStrictAboutPlaceholders() {
        PromptTemplate t = PromptTemplate.load(PromptTemplate.ASSISTANT_SYSTEM);
        assertThatThrownBy(() -> t.render(Map.of("language", "Hindi", "today", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memory");
        assertThatThrownBy(() -> t.render(Map.of("language", "Hindi", "today", "x", "memory", "m", "extra", "y")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("extra");
        // A value that itself looks like a placeholder or regex replacement is inserted literally.
        String p = t.render(Map.of("language", "Hindi", "today", "x", "memory", "{{language}} $1 \\"));
        assertThat(p).contains("{{language}} $1 \\");
    }

    @Test
    void aMissingPromptResourceFailsLoudly() {
        assertThatThrownBy(() -> PromptTemplate.load("/prompts/does-not-exist.md"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not found");
    }

    @Test
    void fallbackApologyIsSpokenInTheFarmersLanguageWithEnglishAsDefault() {
        FallbackLines lines = new FallbackLines(new ObjectMapper());
        assertThat(lines.apology("hi-IN")).contains("माफ़ कीजिए");
        assertThat(lines.apology("bn-IN")).contains("দুঃখিত");
        assertThat(lines.apology("en-IN")).startsWith("Sorry");
        assertThat(lines.apology("ta-IN")).isEqualTo(lines.apology("en-IN"));
        assertThat(lines.apology(null)).isEqualTo(lines.apology("en-IN"));
        assertThat(lines.apologies().values()).allSatisfy(l -> assertThat(l).doesNotContainPattern("[0-9]"));
    }
}
