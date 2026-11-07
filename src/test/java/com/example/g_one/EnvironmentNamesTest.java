package com.example.g_one;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** Deployments set before the rename still use AISETU_* names, and a missed fallback could silently drop a secret. */
class EnvironmentNamesTest {
    private static final Map<String, String> PROPERTY_BY_SUFFIX = Map.ofEntries(
            Map.entry("LANGUAGES", "g-one.languages"),
            Map.entry("ACCESS_CODE", "g-one.access-code"),
            Map.entry("MAX_TURNS_PER_ROOM", "g-one.max-turns-per-room"),
            Map.entry("MAX_ROOMS", "g-one.max-rooms"),
            Map.entry("ROOM_IDLE_MINUTES", "g-one.room-idle-minutes"),
            Map.entry("MANDI_SNAPSHOT_FILE", "g-one.mandi-snapshot-file"),
            Map.entry("AGMARKNET_BASE_URL", "g-one.agmarknet.base-url"),
            Map.entry("AGMARKNET_MARKET_ID", "g-one.agmarknet.market-id"),
            Map.entry("AGMARKNET_STATE_ID", "g-one.agmarknet.state-id"),
            Map.entry("AGMARKNET_COMMODITY_ID", "g-one.agmarknet.commodity-id"),
            Map.entry("AGMARKNET_COMMODITY", "g-one.agmarknet.commodity"),
            Map.entry("AGMARKNET_MARKET", "g-one.agmarknet.market"),
            Map.entry("AGMARKNET_VARIETY", "g-one.agmarknet.variety"),
            Map.entry("AGMARKNET_TIMEOUT_SECONDS", "g-one.agmarknet.timeout-seconds"),
            Map.entry("AGMARKNET_MAX_AGE_DAYS", "g-one.agmarknet.max-age-days"),
            Map.entry("AGMARKNET_CACHE_MINUTES", "g-one.agmarknet.cache-minutes"),
            Map.entry("LLM_BASE_URL", "g-one.llm.base-url"),
            Map.entry("LLM_API_KEY", "g-one.llm.api-key"),
            Map.entry("LLM_MODEL", "g-one.llm.model"),
            Map.entry("LLM_TIMEOUT_SECONDS", "g-one.llm.timeout-seconds"));

    private static MockEnvironment environment(Map<String, Object> variables) throws IOException {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        for (PropertySource<?> yml : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(yml);
        }
        return env;
    }

    @Test
    void everyVariableInTheConfigurationIsCovered() throws IOException {
        String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(yml.split("\\$\\{G_ONE_", -1)).hasSize(PROPERTY_BY_SUFFIX.size() + 1);
        assertThat(yml.split("\\$\\{AISETU_", -1)).hasSize(PROPERTY_BY_SUFFIX.size() + 1);
    }

    @Test
    void theFormerNameStillWorksOnItsOwn() throws IOException {
        for (var e : PROPERTY_BY_SUFFIX.entrySet()) {
            var env = environment(Map.of("AISETU_" + e.getKey(), "former"));
            assertThat(env.getProperty(e.getValue())).as(e.getKey()).isEqualTo("former");
        }
    }

    @Test
    void theNewNameWinsOverTheFormerName() throws IOException {
        for (var e : PROPERTY_BY_SUFFIX.entrySet()) {
            var env = environment(Map.of("G_ONE_" + e.getKey(), "new", "AISETU_" + e.getKey(), "former"));
            assertThat(env.getProperty(e.getValue())).as(e.getKey()).isEqualTo("new");
        }
    }

    @Test
    void defaultsAreUnchangedWhenNeitherNameIsSet() throws IOException {
        var env = environment(Map.of());
        assertThat(env.getProperty("g-one.max-rooms")).isEqualTo("20");
        assertThat(env.getProperty("g-one.max-turns-per-room")).isEqualTo("50");
        assertThat(env.getProperty("g-one.agmarknet.base-url")).isEqualTo("https://api.agmarknet.gov.in");
        assertThat(env.getProperty("g-one.languages")).isEqualTo("hi-IN:Hindi:Nalini,en-IN:English:Kaveri,ta-IN:Tamil:Asmita,kn-IN:Kannada:Saanvi");
        assertThat(env.getProperty("g-one.access-code")).isEmpty();
    }

    /** A blank new name beside a real former code would switch the access check off, so the guard refuses to start. */
    @Test
    void aBlankNewNameBesideARealFormerCodeFailsClosed() {
        for (String blank : new String[] {"", "  "}) {
            var env = new MockEnvironment().withProperty("G_ONE_ACCESS_CODE", blank)
                    .withProperty("AISETU_ACCESS_CODE", "former-secret");
            assertThatThrownBy(() -> AccessCodeGuard.verify(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("G_ONE_ACCESS_CODE")
                    .hasMessageNotContaining("former-secret");
        }
        new ApplicationContextRunner().withUserConfiguration(AccessCodeGuard.class)
                .withPropertyValues("G_ONE_ACCESS_CODE=", "AISETU_ACCESS_CODE=former-secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void everyOtherAccessCodeCombinationStillStarts() {
        String[][] cases = {
            {null, null}, {null, "former"}, {"new", null}, {"new", "former"}, {"new", ""}, {"", null}, {"", ""}, {null, ""},
        };
        for (String[] c : cases) {
            var env = new MockEnvironment();
            if (c[0] != null) env.setProperty("G_ONE_ACCESS_CODE", c[0]);
            if (c[1] != null) env.setProperty("AISETU_ACCESS_CODE", c[1]);
            assertThatCode(() -> AccessCodeGuard.verify(env)).as(String.join("|", String.valueOf(c[0]), String.valueOf(c[1])))
                    .doesNotThrowAnyException();
        }
    }
}
