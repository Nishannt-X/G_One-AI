package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.FakeUpstreams;
import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.TextToSpeech;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The realtime Timbre adapter against a scripted WebSocket server speaking the documented wire format. */
class TimbreRealtimeTtsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");

    private FakeGnaniSocket server;
    private TextToSpeech tts;
    private final List<AudioFrame> frames = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = new FakeGnaniSocket();
        tts = new TimbreRealtimeTts(new GOneProperties.Gnani(server.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 1),
                JSON);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String audioChunk(int index, byte[] bytes) {
        return "{\"type\":\"audio\",\"data\":{\"chunk_index\":" + index + ",\"audio\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\",\"is_final\":false}}";
    }

    private static final String START = "{\"type\":\"start\",\"message\":\"Streaming started\",\"request_id\":\"r1\"}";
    private static final String COMPLETE = "{\"type\":\"complete\",\"data\":{\"chunk_index\":9,\"audio\":\"\",\"is_final\":true}}";

    /** 100 ms of a tone at 8 kHz as mu-law: 800 bytes. */
    private static byte[] mulawTone() {
        return Mulaw.encode(Tones.sine(440, 9000, 8000, 100));
    }

    @Test
    void sendsTheDocumentedRequestAndStreamsEightKilohertzMulawAsTwentyMillisecondFrames() throws Exception {
        byte[] tone = mulawTone();
        server.onText(request -> {
            server.last().sendText(START);
            server.last().sendText(audioChunk(1, java.util.Arrays.copyOfRange(tone, 0, 500))); // splits mid-frame
            server.last().sendText(audioChunk(2, java.util.Arrays.copyOfRange(tone, 500, 800)));
            server.last().sendText(COMPLETE);
        });

        TextToSpeech.Synthesis s = tts.speak("नमस्ते", HINDI, 8000, frames::add);
        s.done().get(5, java.util.concurrent.TimeUnit.SECONDS);

        FakeGnaniSocket.Conn c = server.last();
        assertThat(c.path).isEqualTo("/api/v1/tts");
        assertThat(c.headers).containsEntry("x-api-key-id", FakeUpstreams.KEY);
        JsonNode req = JSON.readTree(c.text.get(0));
        assertThat(req.path("text").asText()).isEqualTo("नमस्ते");
        assertThat(req.path("voice").asText()).isEqualTo("Nalini");
        assertThat(req.path("model").asText()).isEqualTo("timbre-v2.5");
        assertThat(req.path("language").asText()).isEqualTo("hi-IN");
        assertThat(req.path("audio_config").path("container").asText()).isEqualTo("mulaw");
        assertThat(req.path("audio_config").path("sample_rate").asInt()).isEqualTo(8000);
        assertThat(req.path("audio_config").path("num_channels").asInt()).isEqualTo(1);

        // 800 samples = five 160-sample (20 ms) frames, timestamps counted from the start of the utterance.
        assertThat(frames).hasSize(5).allSatisfy(f -> {
            assertThat(f.sampleRate()).isEqualTo(8000);
            assertThat(f.samples()).isEqualTo(160);
        });
        assertThat(frames).extracting(AudioFrame::timestampMs).containsExactly(0L, 20L, 40L, 60L, 80L);
        byte[] joined = new byte[1600];
        for (int i = 0; i < frames.size(); i++) System.arraycopy(frames.get(i).pcm16le(), 0, joined, i * 320, 320);
        assertThat(joined).isEqualTo(Mulaw.decode(tone));
    }

    @Test
    void aShortTailBecomesOneLastShortFrame() throws Exception {
        byte[] tone = java.util.Arrays.copyOf(mulawTone(), 400); // 50 ms = two full frames and 80 samples
        server.onText(request -> {
            server.last().sendText(audioChunk(1, tone));
            server.last().sendText(COMPLETE);
        });
        tts.speak("x", HINDI, 8000, frames::add).done().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(frames).extracting(AudioFrame::samples).containsExactly(160, 160, 80);
    }

    @Test
    void otherRatesAskForRawPcmInstead() throws Exception {
        byte[] pcm = Tones.sine(440, 9000, 16000, 100);
        server.onText(request -> {
            server.last().sendText(audioChunk(1, pcm));
            server.last().sendText(COMPLETE);
        });
        tts.speak("x", HINDI, 16000, frames::add).done().get(5, java.util.concurrent.TimeUnit.SECONDS);

        JsonNode cfg = JSON.readTree(server.last().text.get(0)).path("audio_config");
        assertThat(cfg.path("container").asText()).isEqualTo("raw");
        assertThat(cfg.path("encoding").asText()).isEqualTo("linear_pcm");
        assertThat(cfg.path("sample_rate").asInt()).isEqualTo(16000);
        assertThat(frames).hasSize(5).allSatisfy(f -> assertThat(f.samples()).isEqualTo(320));
    }

    @Test
    void cancelStopsFramesImmediatelyAndClosesTheSocket() throws Exception {
        byte[] tone = mulawTone();
        server.onText(request -> server.last().sendText(audioChunk(1, java.util.Arrays.copyOfRange(tone, 0, 320))));

        TextToSpeech.Synthesis s = tts.speak("lamba jawab", HINDI, 8000, frames::add);
        Wait.until("first frames", () -> frames.size() == 2);
        s.cancel();
        int atCancel = frames.size();

        server.last().sendText(audioChunk(2, tone)); // late audio for the old utterance
        Wait.settle();
        assertThat(frames).hasSize(atCancel);
        assertThat(s.done()).isCancelled();
        s.cancel(); // idempotent
    }

    @Test
    void cancelAfterCompletionIsHarmless() throws Exception {
        server.onText(request -> {
            server.last().sendText(audioChunk(1, mulawTone()));
            server.last().sendText(COMPLETE);
        });
        TextToSpeech.Synthesis s = tts.speak("x", HINDI, 8000, frames::add);
        s.done().get(5, java.util.concurrent.TimeUnit.SECONDS);
        s.cancel();
        assertThat(s.done()).isNotCancelled().isCompleted();
    }

    @Test
    void aServiceErrorFailsTheSynthesisWithAStableStage() {
        server.onText(request -> server.last().sendText("{\"type\":\"error\",\"message\":\"secret-detail\"}"));
        CompletableFuture<Void> done = tts.speak("x", HINDI, 8000, frames::add).done();
        assertThatThrownBy(() -> done.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(UpstreamException.class)
                .satisfies(e -> {
                    UpstreamException cause = (UpstreamException) e.getCause();
                    assertThat(cause.stage()).isEqualTo("tts_failed");
                    assertThat(cause).hasMessageNotContaining("secret-detail");
                });
    }

    @Test
    void aConnectionThatDiesMidwayFailsTheSynthesis() {
        server.onText(request -> {
            server.last().sendText(audioChunk(1, mulawTone()));
            server.last().drop();
        });
        CompletableFuture<Void> done = tts.speak("x", HINDI, 8000, frames::add).done();
        assertThatThrownBy(() -> done.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(UpstreamException.class);
    }

    @Test
    void aServiceThatGoesQuietFailsTheSynthesisInsteadOfHangingForever() {
        server.onText(request -> { }); // accepts the request, never answers
        CompletableFuture<Void> done = tts.speak("x", HINDI, 8000, frames::add).done();
        assertThatThrownBy(() -> done.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(UpstreamException.class);
    }

    @Test
    void blankTextCompletesWithoutOpeningASocket() throws Exception {
        tts.speak("  ", HINDI, 8000, frames::add).done().get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(server.connections).isEmpty();
        assertThat(frames).isEmpty();
    }
}
