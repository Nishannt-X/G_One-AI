package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.FakeUpstreams;
import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.SttSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The realtime Prisma adapter against a scripted WebSocket server speaking the documented wire format. */
class PrismRealtimeSttTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");
    private static final LegId LEG = new LegId("leg-test");

    private FakeGnaniSocket server;
    private RecordingListener listener;

    @BeforeEach
    void start() throws Exception {
        server = new FakeGnaniSocket();
        listener = new RecordingListener();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private SttSession open(String model) {
        GOneProperties.Gnani cfg = new GOneProperties.Gnani(server.baseUrl(), FakeUpstreams.KEY, model, "timbre-v2.5", 5);
        SttSession session = new PrismRealtimeStt(cfg, JSON).open(LEG, LegRole.FARMER, HINDI, listener);
        Wait.until("connection", () -> !server.connections.isEmpty());
        return session;
    }

    private static final String TRANSCRIPT = "{\"type\":\"transcript\",\"text\":\"गेहूं का भाव क्या है\","
            + "\"is_final\":true,\"latency\":320,\"utterance_id\":\"u1\",\"detected_language_code\":\"hi-IN\"}";

    @Test
    void configuresTheStreamThroughUpgradeHeaders() {
        try (SttSession ignored = open("prisma-v3.0")) {
            FakeGnaniSocket.Conn c = server.last();
            assertThat(c.path).isEqualTo("/stt/v3/stream");
            assertThat(c.headers).containsEntry("x-api-key-id", FakeUpstreams.KEY)
                    .containsEntry("model", "prisma-v3.0")
                    .containsEntry("language_code", "hi-IN,en-IN")
                    .containsEntry("format", "transcribe")
                    .containsEntry("x-sample-rate", "8000")
                    .containsEntry("x-vad-threshold", "0.7")
                    .containsEntry("x-min-speech-ms", "200")
                    .containsEntry("x-min-silence-ms", "500")
                    .containsEntry("x-idle-timeout-sec", "180")
                    .containsEntry("x-api-request-id", "leg-test");
        }
    }

    @Test
    void version25TakesASingleLanguageAndNoModelHeaderWhenNoneIsConfigured() {
        try (SttSession ignored = open("")) {
            assertThat(server.last().headers).containsEntry("language_code", "hi-IN").doesNotContainKey("model");
        }
    }

    @Test
    void sendsExactly512SampleFramesWhateverSizeTheCallerFeeds() {
        try (SttSession session = open("prisma-v3.0")) {
            // Four 20 ms frames at 8 kHz = 1,280 bytes: one 1,024-byte frame goes out, 256 bytes wait.
            Tones.frames(Tones.sine(300, 6000, 8000, 80), 8000, 0).forEach(session::feed);
            Wait.until("first frame", () -> server.last().binary.size() == 1);
            assertThat(server.last().binary.get(0)).hasSize(1024);

            // 4 more frames: 2,560 bytes fed in total, so a second frame goes out and 512 bytes wait.
            Tones.frames(Tones.sine(300, 6000, 8000, 80), 8000, 80).forEach(session::feed);
            Wait.until("second frame", () -> server.last().binary.size() == 2);
            Wait.settle();
            assertThat(server.last().binary).hasSize(2).allSatisfy(b -> assertThat(b).hasSize(1024));
        }
    }

    @Test
    void audioFedAtAnotherRateIsConvertedTo8kHzFirst() {
        try (SttSession session = open("prisma-v3.0")) {
            // 160 ms at 16 kHz is 5,120 bytes, which is 2,560 bytes at 8 kHz: two frames of 1,024 plus 512 left over.
            Tones.frames(Tones.sine(300, 6000, 16000, 160), 16000, 0).forEach(session::feed);
            Wait.until("two frames", () -> server.last().binary.size() == 2);
            assertThat(server.last().binary).allSatisfy(b -> assertThat(b).hasSize(1024));
        }
    }

    @Test
    void mapsSpeechStartAndTheFinalTranscriptToListenerEvents() {
        try (SttSession ignored = open("prisma-v3.0")) {
            FakeGnaniSocket.Conn c = server.last();
            c.sendText("{\"type\":\"connected\",\"config\":{\"sample_rate\":8000,\"chunk_size\":512}}");
            c.sendText("{\"type\":\"speech_start\",\"utterance_id\":\"u1\"}");
            Wait.until("speech start", () -> listener.events.contains("speechStart"));
            c.sendText("{\"type\":\"processing\"}");
            c.sendText(TRANSCRIPT);
            Wait.until("final turn", () -> listener.turns.size() == 1);

            assertThat(listener.events).containsExactly("speechStart", "final");
            assertThat(listener.partials).isEmpty(); // Prisma realtime sends none
            var turn = listener.turns.get(0);
            assertThat(turn.text()).isEqualTo("गेहूं का भाव क्या है");
            assertThat(turn.legId()).isEqualTo(LEG);
            assertThat(turn.role()).isEqualTo(LegRole.FARMER);
            assertThat(turn.language()).isEqualTo(HINDI);
            assertThat(turn.endedMs()).isGreaterThanOrEqualTo(turn.startedMs());
        }
    }

    @Test
    void emptyAndNonFinalTranscriptsAreNotReported() {
        try (SttSession ignored = open("prisma-v3.0")) {
            FakeGnaniSocket.Conn c = server.last();
            c.sendText("{\"type\":\"transcript\",\"text\":\"   \",\"is_final\":true}");
            c.sendText("{\"type\":\"transcript\",\"text\":\"abhi\",\"is_final\":false}");
            c.sendText(TRANSCRIPT);
            Wait.until("final turn", () -> listener.turns.size() == 1);
            Wait.settle();
            assertThat(listener.turns).hasSize(1);
        }
    }

    @Test
    void aServiceErrorBecomesOnErrorWithoutTheRawMessage() {
        try (SttSession ignored = open("prisma-v3.0")) {
            server.last().sendText("{\"type\":\"error\",\"message\":\"secret-detail\",\"timestamp\":\"x\"}");
            Wait.until("error", () -> !listener.errors.isEmpty());
            assertThat(listener.errors.get(0)).isInstanceOf(UpstreamException.class)
                    .hasMessageNotContaining("secret-detail");
            assertThat(((UpstreamException) listener.errors.get(0)).stage()).isEqualTo("stt_failed");
        }
    }

    @Test
    void reconnectsWhenTheServerDropsTheConnectionAndKeepsDeliveringTurns() {
        try (SttSession session = open("prisma-v3.0")) {
            server.last().drop();
            Wait.until("second connection", () -> server.connections.size() == 2);

            Tones.frames(Tones.sine(300, 6000, 8000, 80), 8000, 0).forEach(session::feed);
            Wait.until("frame on the new connection", () -> server.last().binary.size() == 1);
            server.last().sendText(TRANSCRIPT);
            Wait.until("final turn", () -> listener.turns.size() == 1);
            assertThat(listener.errors).isEmpty();
        }
    }

    @Test
    void aDeadConnectionIsNoticedByTheNextSendEvenWithoutACloseNotification() {
        try (SttSession session = open("prisma-v3.0")) {
            server.last().drop();
            // Keep feeding, as a live call does: a failed send must trigger the reconnect.
            for (int i = 0; i < 40 && server.connections.size() < 2; i++) {
                Tones.frames(Tones.sine(300, 6000, 8000, 80), 8000, 0).forEach(session::feed);
                Wait.settle();
            }
            assertThat(server.connections.size()).isGreaterThanOrEqualTo(2);
            assertThat(listener.errors).isEmpty();
        }
    }

    @Test
    void givesUpAfterThreeReconnectsInARowAndReportsOneError() {
        try (SttSession ignored = open("prisma-v3.0")) {
            server.onConnect(FakeGnaniSocket.Conn::drop);
            server.last().drop();
            Wait.until("error after retries", () -> !listener.errors.isEmpty());
            assertThat(server.connections.size()).isLessThanOrEqualTo(5);
            Wait.settle();
            assertThat(listener.errors).hasSize(1);
        }
    }

    @Test
    void nothingArrivesAfterClose() {
        SttSession session = open("prisma-v3.0");
        FakeGnaniSocket.Conn c = server.last();
        session.close();
        c.sendText("{\"type\":\"speech_start\"}");
        c.sendText(TRANSCRIPT);
        Wait.settle();
        assertThat(listener.events).isEmpty();

        session.feed(new AudioFrame(new byte[320], 8000, 0)); // dropped, must not throw
        session.close(); // idempotent
    }
}
