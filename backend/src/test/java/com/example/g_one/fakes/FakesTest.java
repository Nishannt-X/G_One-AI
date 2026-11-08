package com.example.g_one.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fakes behave as their docs say, so other areas can trust them in their own tests. */
class FakesTest {

    private static final class Recorder implements SttListener {
        final List<String> events = new ArrayList<>();
        final List<Turn> turns = new ArrayList<>();

        @Override
        public void onSpeechStart() {
            events.add("start");
        }

        @Override
        public void onPartial(String text) {
            events.add("partial:" + text);
        }

        @Override
        public void onFinalTurn(Turn turn) {
            events.add("final:" + turn.text());
            turns.add(turn);
        }

        @Override
        public void onError(Exception error) {
            events.add("error");
        }
    }

    @Test
    void aFakeLegRecordsPlaybackDeliversPushedAudioAndEndsOnce() {
        FakeLeg leg = FakeLeg.farmer(CallId.random());
        List<AudioFrame> heard = new ArrayList<>();
        List<String> ended = new ArrayList<>();
        leg.onAudio(heard::add);
        leg.onEnded(() -> ended.add("first"));

        leg.pushSilence(3);
        leg.play(AudioFrame.silence(8000, 20, 0));
        leg.clearPlayback();
        leg.hangup();
        leg.hangup();
        leg.remoteHangup();
        leg.onEnded(() -> ended.add("late"));
        leg.play(AudioFrame.silence(8000, 20, 20));
        leg.pushSilence(1);

        assertThat(heard).extracting(AudioFrame::timestampMs).containsExactly(0L, 20L, 40L);
        assertThat(leg.played()).hasSize(1);
        assertThat(leg.playedMs()).isEqualTo(20);
        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(ended).containsExactly("first", "late");
        assertThat(leg.hungUpByUs()).isTrue();
    }

    @Test
    void playbackDoneReportsPlayedClearedOrEnded() {
        FakeLeg leg = FakeLeg.farmer(CallId.random());

        assertThat(leg.playbackDone()).isCompletedWithValue(PlaybackOutcome.PLAYED);

        leg.play(AudioFrame.silence(8000, 20, 0));
        var heard = leg.playbackDone();
        assertThat(heard).isNotDone();
        leg.play(AudioFrame.silence(8000, 20, 20));
        leg.finishPlayback();
        assertThat(heard).isCompletedWithValue(PlaybackOutcome.PLAYED);
        assertThat(leg.unheardFrames()).isZero();

        leg.play(AudioFrame.silence(8000, 20, 40));
        var bargedIn = leg.playbackDone();
        leg.clearPlayback();
        leg.finishPlayback();
        assertThat(bargedIn).isCompletedWithValue(PlaybackOutcome.CLEARED);

        leg.play(AudioFrame.silence(8000, 20, 60));
        var cutOff = leg.playbackDone();
        leg.remoteHangup();
        assertThat(cutOff).isCompletedWithValue(PlaybackOutcome.ENDED);
        assertThat(leg.playbackDone()).isCompletedWithValue(PlaybackOutcome.ENDED);
    }

    @Test
    void aWithheldFarmerHasNoCallerKey() {
        assertThat(FakeLeg.withheldFarmer(CallId.random()).callerKey()).isEmpty();
        assertThat(FakeLeg.farmer(CallId.random()).callerKey()).contains(FakeLeg.FARMER_KEY);
    }

    @Test
    void scriptedSpeechTurnsFeedingAudioIntoSpeechStartThenOneTurn() {
        FakeLeg leg = FakeLeg.farmer(CallId.random());
        long[] now = {1_000};
        FakeSpeechToText stt = new FakeSpeechToText(() -> now[0]).script("pyaaz", "haan").turnEvery(3);
        Recorder r = new Recorder();
        var session = stt.open(leg.id(), LegRole.FARMER, FakeLeg.HINDI, r);
        leg.onAudio(session::feed);

        leg.pushSilence(1);
        now[0] = 1_060;
        leg.pushSilence(2);

        assertThat(r.events).containsExactly("start", "final:pyaaz");
        assertThat(r.turns.get(0)).isEqualTo(new Turn(leg.id(), LegRole.FARMER, FakeLeg.HINDI, "pyaaz", 1_000, 1_060));
        assertThat(stt.remainingScript()).isEqualTo(1);

        session.close();
        leg.pushSilence(3);

        assertThat(r.events).hasSize(2);
        assertThat(session.fed()).hasSize(3);
    }

    @Test
    void aSpeechSessionCanBeDrivenByHand() {
        FakeSpeechToText stt = new FakeSpeechToText().script("namaste");
        Recorder r = new Recorder();
        var session = stt.open(FakeLeg.farmer(CallId.random()).id(), LegRole.FARMER, FakeLeg.HINDI, r);

        session.emitSpeechStart();
        session.emitPartial("nam");
        session.emitTurn("namaste ji");
        session.sayNext();
        session.emitError(new IllegalStateException("x"));

        assertThat(r.events).containsExactly("start", "partial:nam", "final:namaste ji", "start", "final:namaste",
                "error");
        assertThat(stt.last()).isSameAs(session);
    }

    @Test
    void fakeSpeechIsSilenceSizedByTheText() {
        FakeTextToSpeech tts = new FakeTextToSpeech().msPerChar(50);
        List<AudioFrame> out = new ArrayList<>();

        TextToSpeech.Synthesis s = tts.speak("namaste", FakeLeg.HINDI, 8000, out::add);

        // 7 characters x 50 ms = 350 ms -> 18 frames of 20 ms (rounded up).
        assertThat(out).hasSize(18);
        assertThat(out).allSatisfy(f -> {
            assertThat(f.sampleRate()).isEqualTo(8000);
            assertThat(f.samples()).isEqualTo(160);
        });
        assertThat(s.done()).isCompleted();
        assertThat(tts.requests()).containsExactly(new FakeTextToSpeech.Request("namaste", FakeLeg.HINDI, 8000));
    }

    @Test
    void heldSpeechWaitsForReleaseAndACancelledOneNeverReachesTheSink() {
        FakeTextToSpeech tts = new FakeTextToSpeech().holdOutput();
        List<AudioFrame> kept = new ArrayList<>();
        List<AudioFrame> cancelled = new ArrayList<>();

        TextToSpeech.Synthesis a = tts.speak("haan", FakeLeg.HINDI, 8000, kept::add);
        TextToSpeech.Synthesis b = tts.speak("nahin", FakeLeg.HINDI, 8000, cancelled::add);
        assertThat(kept).isEmpty();
        assertThat(tts.heldCount()).isEqualTo(2);

        b.cancel();
        tts.releaseAll();

        assertThat(kept).isNotEmpty();
        assertThat(a.done()).isCompleted();
        assertThat(cancelled).isEmpty();
        assertThat(b.done()).isCancelled();
        assertThat(tts.heldCount()).isZero();
    }

    @Test
    void theFakeLlmAnswersWithQueuedToolCallsThenText() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (FakeLlm llm = new FakeLlm()) {
            llm.replyToolCall("get_mandi_price", "{\"commodity\":\"Onion\"}").replyText("Teen hazaar chhe sau.");
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(llm.baseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"m\",\"messages\":[]}"))
                    .build();

            JsonNode first = json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            JsonNode second = json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            JsonNode third = json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            llm.failures.set(1);
            int failed = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();

            JsonNode call = first.at("/choices/0/message/tool_calls/0");
            assertThat(first.at("/choices/0/finish_reason").asText()).isEqualTo("tool_calls");
            assertThat(call.at("/function/name").asText()).isEqualTo("get_mandi_price");
            assertThat(json.readTree(call.at("/function/arguments").asText()).get("commodity").asText())
                    .isEqualTo("Onion");
            assertThat(second.at("/choices/0/message/content").asText()).isEqualTo("Teen hazaar chhe sau.");
            assertThat(third.at("/choices/0/message/content").asText()).isEqualTo("{\"text\":\"ok\"}");
            assertThat(failed).isEqualTo(500);
            assertThat(llm.requests()).hasSize(4);
            assertThat(llm.requests().get(0).get("model").asText()).isEqualTo("m");
        }
    }
}
