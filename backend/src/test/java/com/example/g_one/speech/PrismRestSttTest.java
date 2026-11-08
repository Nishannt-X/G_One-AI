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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Own turn detection plus Prisma REST per turn, against {@link FakeUpstreams}. */
class PrismRestSttTest {
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");
    private static final LegId LEG = new LegId("leg-rest");

    private FakeUpstreams fake;
    private PrismRestStt stt;
    private RecordingListener listener;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeUpstreams();
        GOneProperties props = new GOneProperties(List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri"), "", null,
                new GOneProperties.Gnani(fake.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 5),
                new GOneProperties.Llm(fake.baseUrl(), FakeUpstreams.KEY, "m", 5));
        stt = new PrismRestStt(new PrismClient(props, new ObjectMapper()));
        listener = new RecordingListener();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static void feed(SttSession session, byte[] pcm, int rate, long startMs) {
        for (AudioFrame f : Tones.frames(pcm, rate, startMs)) session.feed(f);
    }

    @Test
    void oneSpokenTurnReportsSpeechStartThenOneFinalTurnWithoutPartials() {
        fake.sttTranscript = "gehu ka bhav kya hai";
        try (SttSession session = stt.open(LEG, LegRole.FARMER, HINDI, listener)) {
            feed(session, Tones.silence(8000, 300), 8000, 0);
            feed(session, Tones.sine(300, 8000, 8000, 1000), 8000, 300);
            assertThat(listener.events).containsExactly("speechStart"); // before the turn ends: drives barge-in
            feed(session, Tones.silence(8000, 1000), 8000, 1300);
            Wait.until("final turn", () -> listener.turns.size() == 1);

            assertThat(listener.events).containsExactly("speechStart", "final");
            var turn = listener.turns.get(0);
            assertThat(turn.text()).isEqualTo("gehu ka bhav kya hai");
            assertThat(turn.legId()).isEqualTo(LEG);
            assertThat(turn.role()).isEqualTo(LegRole.FARMER);
            assertThat(turn.language()).isEqualTo(HINDI);
            assertThat(turn.endedMs()).isGreaterThanOrEqualTo(turn.startedMs());

            assertThat(fake.prisma).hasSize(1);
            assertThat(fake.prisma.get(0).bodyText()).contains("hi-IN").contains("RIFF");
        }
    }

    @Test
    void aLongTurnUploadsInOneRequestPastTheOldFortyEightKilobyteLimit() {
        try (SttSession session = stt.open(LEG, LegRole.FARMER, HINDI, listener)) {
            feed(session, Tones.sine(300, 8000, 8000, 6000), 8000, 0);       // 96,000 bytes of audio
            feed(session, Tones.silence(8000, 1000), 8000, 6000);
            Wait.until("final turn", () -> listener.turns.size() == 1);
            assertThat(fake.prisma).hasSize(1);
            assertThat(fake.prisma.get(0).body().length).isGreaterThan(96_000);
        }
    }

    @Test
    void audioAtAnotherRateIsConvertedBeforeDetectionAndUpload() {
        try (SttSession session = stt.open(LEG, LegRole.BUYER, HINDI, listener)) {
            feed(session, Tones.sine(300, 8000, 16000, 600), 16000, 0);
            feed(session, Tones.silence(16000, 1000), 16000, 600);
            Wait.until("final turn", () -> listener.turns.size() == 1);
            // 8 kHz WAV header: sample rate 8000 at byte offset 24 of the WAV inside the multipart body.
            assertThat(listener.turns.get(0).role()).isEqualTo(LegRole.BUYER);
            assertThat(fake.prisma.get(0).body().length).isLessThan(16000 * 2 * 2); // far less than 16 kHz audio
        }
    }

    @Test
    void silenceFromPrismaIsNotATurn() {
        fake.sttTranscript = "";
        try (SttSession session = stt.open(LEG, LegRole.FARMER, HINDI, listener)) {
            feed(session, Tones.sine(300, 8000, 8000, 600), 8000, 0);
            feed(session, Tones.silence(8000, 1000), 8000, 600);
            Wait.until("prisma called", () -> fake.prisma.size() == 1);
            Wait.settle();
            assertThat(listener.turns).isEmpty();
            assertThat(listener.errors).isEmpty();
        }
    }

    @Test
    void aFailedUploadIsReportedAndLaterTurnsStillWork() {
        fake.prismaFailures.set(1);
        try (SttSession session = stt.open(LEG, LegRole.FARMER, HINDI, listener)) {
            feed(session, Tones.sine(300, 8000, 8000, 600), 8000, 0);
            feed(session, Tones.silence(8000, 1000), 8000, 600);
            Wait.until("error", () -> listener.errors.size() == 1);
            assertThat(((UpstreamException) listener.errors.get(0)).stage()).isEqualTo("stt_failed");

            feed(session, Tones.sine(300, 8000, 8000, 600), 8000, 1600);
            feed(session, Tones.silence(8000, 1000), 8000, 2200);
            Wait.until("second turn", () -> listener.turns.size() == 1);
        }
    }

    @Test
    void turnsAreReportedInTheOrderTheyWereSpoken() {
        AtomicLong clock = new AtomicLong(1_000_000);
        PrismRestStt ordered = new PrismRestStt(new PrismClient(new GOneProperties(
                List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri"), "", null,
                new GOneProperties.Gnani(fake.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 5),
                new GOneProperties.Llm(fake.baseUrl(), FakeUpstreams.KEY, "m", 5)), new ObjectMapper()),
                TurnDetector.Config.defaults(), clock::get);
        try (SttSession session = ordered.open(LEG, LegRole.FARMER, HINDI, listener)) {
            long t = 0;
            for (int i = 0; i < 3; i++) {
                byte[] pcm = Tones.sine(300, 8000, 8000, 500);
                byte[] quiet = Tones.silence(8000, 900);
                for (AudioFrame f : Tones.frames(pcm, 8000, t)) {
                    clock.addAndGet(20);
                    session.feed(f);
                }
                for (AudioFrame f : Tones.frames(quiet, 8000, t + 500)) {
                    clock.addAndGet(20);
                    session.feed(f);
                }
                t += 1400;
            }
            Wait.until("three turns", () -> listener.turns.size() == 3);
            // The next turn's speechStart may land before the previous turn's transcript (feeding is faster than
            // Prisma answers); what matters is three starts, three finals, and finals in the order spoken.
            assertThat(listener.events.stream().filter("speechStart"::equals).count()).isEqualTo(3);
            assertThat(listener.events.stream().filter("final"::equals).count()).isEqualTo(3);
            assertThat(listener.turns).extracting(com.example.g_one.core.Turn::startedMs).isSorted();
            assertThat(listener.turns.get(2).startedMs()).isGreaterThan(listener.turns.get(0).startedMs());
        }
    }

    @Test
    void nothingArrivesAfterClose() {
        SttSession session = stt.open(LEG, LegRole.FARMER, HINDI, listener);
        feed(session, Tones.sine(300, 8000, 8000, 600), 8000, 0);
        session.close();
        feed(session, Tones.silence(8000, 1000), 8000, 600);
        Wait.settle();
        assertThat(listener.events).containsExactly("speechStart");
        assertThat(fake.prisma).isEmpty();
        session.close();
    }
}
