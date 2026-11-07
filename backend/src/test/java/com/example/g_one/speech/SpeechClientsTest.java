package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.FakeUpstreams;
import com.example.g_one.GOneProperties;
import com.example.g_one.Language;
import com.example.g_one.TestAudio;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Prisma, Timbre and the LLM client against {@link FakeUpstreams}: wire format, auth header and failure mapping. */
class SpeechClientsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private FakeUpstreams fake;
    private GOneProperties props;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeUpstreams();
        props = new GOneProperties(List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri"), "", null,
                new GOneProperties.Gnani(fake.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 5),
                new GOneProperties.Llm(fake.baseUrl(), FakeUpstreams.KEY, "test-model", 5));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    void prismaUploadsTheWavWithTheKeyAndReturnsTheTrimmedTranscript() {
        fake.sttTranscript = " namaste ";
        String text = new PrismClient(props, JSON).transcribe(TestAudio.wav(1, 8000), "hi-IN");

        assertThat(text).isEqualTo("namaste");
        assertThat(fake.prisma).hasSize(1);
        assertThat(fake.prisma.get(0).headers().get("key")).isEqualTo(FakeUpstreams.KEY);
        assertThat(fake.prisma.get(0).bodyText()).contains("filename=\"turn.wav\"").contains("hi-IN");
    }

    @Test
    void prismaMapsSilenceAndServerErrorsToStableStages() {
        PrismClient prisma = new PrismClient(props, JSON);
        fake.sttTranscript = "";
        assertThatThrownBy(() -> prisma.transcribe(TestAudio.wav(1, 8000), "hi-IN"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.stage()).isEqualTo("no_speech"));

        fake.prismaFailures.set(1);
        assertThatThrownBy(() -> prisma.transcribe(TestAudio.wav(1, 8000), "hi-IN"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.stage()).isEqualTo("stt_failed"))
                .hasMessage("Speech service returned HTTP 500 (TRANSCRIPTION_FAILED).");
    }

    @Test
    void timbreSendsTheLanguagesVoiceReturnsWavAndRejectsAFailedCall() {
        TimbreClient timbre = new TimbreClient(props);
        Language hindi = new Language.Registry(props).require("hi-IN");

        byte[] wav = timbre.synthesize("namaste", hindi);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(fake.timbre.get(0).bodyText()).contains("\"voice\":\"Nalini\"").contains("\"language\":\"hi-IN\"");

        fake.timbreFailures.set(1);
        assertThatThrownBy(() -> timbre.synthesize("namaste", hindi))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.stage()).isEqualTo("tts_failed"));
    }

    @Test
    void llmReturnsTheModelsJsonObjectAndRejectsAnythingElse() {
        LlmClient llm = new LlmClient(props, JSON);
        fake.llmContent = "{\"text\":\"bhav\"}";
        assertThat(llm.askJson("test_failed", "system", "user").path("text").asText()).isEqualTo("bhav");
        assertThat(fake.llm.get(0).headers().get("auth")).isEqualTo("Bearer " + FakeUpstreams.KEY);

        fake.llmContent = "not json";
        assertThatThrownBy(() -> llm.askJson("test_failed", "system", "user"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.stage()).isEqualTo("test_failed"));

        fake.llmFailures.set(1);
        assertThatThrownBy(() -> llm.askJson("test_failed", "system", "user"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.stage()).isEqualTo("test_failed"))
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void anUnknownLanguageIsRejected() {
        assertThatThrownBy(() -> new Language.Registry(props).require("xx-XX"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
