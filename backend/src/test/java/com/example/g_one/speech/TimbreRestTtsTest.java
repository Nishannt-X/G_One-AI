package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.FakeUpstreams;
import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.TextToSpeech;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Timbre REST text to speech against {@link FakeUpstreams}, which answers with 0.5 s of a 24 kHz WAV. */
class TimbreRestTtsTest {
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");

    private FakeUpstreams fake;
    private TimbreClient client;
    private final List<AudioFrame> frames = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        fake = new FakeUpstreams();
        client = new TimbreClient(new GOneProperties(List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri"), "", null,
                new GOneProperties.Gnani(fake.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 5),
                new GOneProperties.Llm(fake.baseUrl(), FakeUpstreams.KEY, "m", 5)));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    void returnsEightKilohertzFramesOfTheRightLength() throws Exception {
        TextToSpeech.Synthesis s = new TimbreRestTts(client).speak("namaste", HINDI, 8000, f -> {
            synchronized (frames) {
                frames.add(f);
            }
        });
        s.done().get(5, TimeUnit.SECONDS);

        // 0.5 s at 8 kHz = 4,000 samples = 25 frames of 160 samples (20 ms).
        assertThat(frames).hasSize(25).allSatisfy(f -> {
            assertThat(f.sampleRate()).isEqualTo(8000);
            assertThat(f.samples()).isEqualTo(160);
        });
        assertThat(frames.get(0).timestampMs()).isZero();
        assertThat(frames.get(24).timestampMs()).isEqualTo(480);
        assertThat(fake.timbre).hasSize(1);
    }

    @Test
    void otherRatesComeOutAtThatRate() throws Exception {
        new TimbreRestTts(client).speak("namaste", HINDI, 16000, frames::add).done().get(5, TimeUnit.SECONDS);
        assertThat(frames).hasSize(25).allSatisfy(f -> assertThat(f.samples()).isEqualTo(320));
    }

    @Test
    void anUpstreamFailureFailsTheSynthesis() {
        fake.timbreFailures.set(1);
        var done = new TimbreRestTts(client).speak("namaste", HINDI, 8000, frames::add).done();
        assertThatThrownBy(() -> done.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(UpstreamException.class);
        assertThat(frames).isEmpty();
    }

    @Test
    void cancelBeforeSynthesisFinishesDeliversNothing() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        Executor manual = queued::add;
        TextToSpeech.Synthesis s = new TimbreRestTts(client, manual).speak("namaste", HINDI, 8000, frames::add);
        s.cancel();
        queued.forEach(Runnable::run); // the work runs late, as it would on a slow network

        assertThat(frames).isEmpty();
        assertThat(s.done()).isCancelled();
        s.cancel(); // idempotent
    }

    @Test
    void blankTextCompletesWithoutCallingTimbre() throws Exception {
        new TimbreRestTts(client).speak("   ", HINDI, 8000, frames::add).done().get(1, TimeUnit.SECONDS);
        assertThat(fake.timbre).isEmpty();
    }
}
