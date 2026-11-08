package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Which adapters the app wires, by key and mode. Nothing here calls Gnani. */
class SpeechConfigTest {
    private static final GOneProperties PROPS = new GOneProperties(List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri"),
            "", null, new GOneProperties.Gnani("http://127.0.0.1:1", "k", "", "timbre-v2.5", 5),
            new GOneProperties.Llm("http://127.0.0.1:1", "k", "m", 5));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SpeechConfig.class)
            .withBean(GOneProperties.class, () -> PROPS)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PrismClient.class, () -> new PrismClient(PROPS, new ObjectMapper()))
            .withBean(TimbreClient.class, () -> new TimbreClient(PROPS));

    @Test
    void withoutAGnaniKeyThereAreNoSpeechBeans() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(SpeechToText.class);
            assertThat(ctx).doesNotHaveBean(TextToSpeech.class);
        });
    }

    @Test
    void restModeIsTheDefault() {
        runner.withPropertyValues("g-one.gnani.api-key=k").run(ctx -> {
            assertThat(ctx.getBean(SpeechToText.class)).isInstanceOf(PrismRestStt.class);
            assertThat(ctx.getBean(TextToSpeech.class)).isInstanceOf(TimbreRestTts.class);
        });
    }

    @Test
    void realtimeModeSwitchesBothAdapters() {
        runner.withPropertyValues("g-one.gnani.api-key=k", "g-one.speech.mode=realtime").run(ctx -> {
            assertThat(ctx.getBean(SpeechToText.class)).isInstanceOf(PrismRealtimeStt.class);
            assertThat(ctx.getBean(TextToSpeech.class)).isInstanceOf(TimbreRealtimeTts.class);
        });
    }

    @Test
    void anUnknownModeStopsStartupInsteadOfSilentlyPickingOne() {
        runner.withPropertyValues("g-one.gnani.api-key=k", "g-one.speech.mode=fast")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
