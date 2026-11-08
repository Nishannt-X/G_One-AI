package com.example.g_one.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallId;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.core.LegId;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.Turn;
import com.example.g_one.fakes.FakeSpeechToText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * The simulator end to end on a real port, standing in for the browser: a farmer socket becomes a registered
 * SIMULATOR leg, its microphone frames reach a fake STT session (as a pipeline would), and audio and clear go back down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorSocketTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FakeSpeechToText STT = new FakeSpeechToText().turnEvery(3);
    private static final List<Turn> TURNS = new CopyOnWriteArrayList<>();

    @Autowired
    LegRegistry registry;
    @Autowired
    TestRestTemplate http;
    @LocalServerPort
    int port;

    private static final AtomicBoolean PIPELINE_WIRED = new AtomicBoolean();

    /** Once per Spring context: do what a pipeline does with a new leg, open a speech session and feed it audio. */
    @BeforeEach
    void wirePipeline() {
        STT.script("namaste", "mera gehun", "bhav kya hai", "theek hai", "dhanyavaad", "alvida");
        if (!PIPELINE_WIRED.compareAndSet(false, true)) return;
        registry.onLegAdded(leg -> {
            SttSession session = STT.open(leg.id(), leg.role(), leg.language(), new SttListener() {
                @Override
                public void onFinalTurn(Turn turn) {
                    TURNS.add(turn);
                }

                @Override
                public void onError(Exception error) {
                }
            });
            leg.onAudio(session::feed);
            leg.onEnded(session::close);
        });
    }

    /** A browser stand-in: collects what the server sends. */
    private static final class Browser {
        final BlockingQueue<JsonNode> text = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();
        final BlockingQueue<CloseStatus> closed = new LinkedBlockingQueue<>();
        WebSocketSession session;

        JsonNode nextText() throws InterruptedException {
            JsonNode n = text.poll(5, TimeUnit.SECONDS);
            assertThat(n).as("a text message from the server").isNotNull();
            return n;
        }

        byte[] nextAudio() throws InterruptedException {
            byte[] a = audio.poll(5, TimeUnit.SECONDS);
            assertThat(a).as("an audio message from the server").isNotNull();
            return a;
        }

        void sendFrame(int value) throws Exception {
            byte[] b = new byte[320];
            for (int i = 0; i < 160; i++) {
                b[2 * i] = (byte) value;
            }
            session.sendMessage(new BinaryMessage(b));
        }
    }

    private Browser connect(String query) throws Exception {
        Browser b = new Browser();
        b.session = new StandardWebSocketClient().execute(new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                b.text.add(JSON.readTree(m.getPayload()));
            }

            @Override
            protected void handleBinaryMessage(WebSocketSession s, BinaryMessage m) {
                byte[] bytes = new byte[m.getPayloadLength()];
                m.getPayload().get(bytes);
                b.audio.add(bytes);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                b.closed.add(status);
            }
        }, null, URI.create("ws://localhost:" + port + "/ws/simulator?" + query)).get(5, TimeUnit.SECONDS);
        return b;
    }

    /**
     * The leg named in a "ready" message. The server sends "ready" before it registers the leg (so the browser has its
     * ids before the pipeline starts talking), so wait briefly for the registration instead of racing it.
     */
    private Leg legOf(JsonNode ready) throws InterruptedException {
        LegId id = LegId.of(ready.get("legId").asText());
        awaitTrue(() -> registry.find(id).isPresent());
        return registry.find(id).orElseThrow();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void aFarmerSocketBecomesARegisteredSimulatorLegLikeATwilioOne() throws Exception {
        Browser farmer = connect("role=farmer&lang=hi-IN&caller=%2B919876543210");
        JsonNode ready = farmer.nextText();

        assertThat(ready.get("type").asText()).isEqualTo("ready");
        assertThat(ready.get("sampleRate").asInt()).isEqualTo(8000);
        assertThat(ready.get("role").asText()).isEqualTo("farmer");
        assertThat(ready.get("memory").asBoolean()).isTrue();
        Leg leg = legOf(ready);
        assertThat(leg.source()).isEqualTo(LegSource.SIMULATOR);
        assertThat(leg.role()).isEqualTo(LegRole.FARMER);
        assertThat(leg.language().code()).isEqualTo("hi-IN");
        assertThat(leg.callId().value()).isEqualTo(ready.get("callId").asText());
        assertThat(leg.callerKey()).isPresent();
        assertThat(registry.legs(leg.callId())).containsExactly(leg);
        farmer.session.close();
    }

    @Test
    void theSameNumberOnTwoCallsGivesTheSameMemoryKey() throws Exception {
        Browser first = connect("role=farmer&lang=hi-IN&caller=%2B919876543211");
        Leg a = legOf(first.nextText());
        first.session.close();
        Browser second = connect("role=farmer&lang=en-IN&caller=%2B919876543211");
        Leg b = legOf(second.nextText());

        assertThat(a.callId()).isNotEqualTo(b.callId());
        assertThat(a.callerKey()).isPresent().isEqualTo(b.callerKey());
        second.session.close();
    }

    @Test
    void noCallerMeansWithheldAndNoMemory() throws Exception {
        Browser farmer = connect("role=farmer&lang=hi-IN");
        JsonNode ready = farmer.nextText();
        assertThat(ready.get("memory").asBoolean()).isFalse();
        assertThat(legOf(ready).callerKey()).isEmpty();
        farmer.session.close();
    }

    @Test
    void microphoneFramesReachThePipelineAndFakeSttLogsATurn() throws Exception {
            Browser farmer = connect("role=farmer&lang=hi-IN&caller=%2B919876543212");
        Leg leg = legOf(farmer.nextText());
        int turnsBefore = TURNS.size();

        for (int i = 0; i < 3; i++) farmer.sendFrame(i + 1);

        awaitTrue(() -> TURNS.size() > turnsBefore);
        Turn turn = TURNS.get(turnsBefore);
        assertThat(turn.legId()).isEqualTo(leg.id());
        assertThat(turn.role()).isEqualTo(LegRole.FARMER);
        assertThat(turn.language().code()).isEqualTo("hi-IN");
        assertThat(turn.text()).isNotBlank();
        farmer.session.close();
    }

    @Test
    void audioPlayedOnTheLegReachesTheBrowserAndClearIsSignalled() throws Exception {
        Browser farmer = connect("role=farmer&lang=hi-IN&caller=%2B919876543213");
        Leg leg = legOf(farmer.nextText());

        byte[] pcm = new byte[640];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 100);
        leg.play(new AudioFrame(pcm, 8000, 0));

        byte[] one = farmer.nextAudio();
        byte[] two = farmer.nextAudio();
        assertThat(one).hasSize(320).isEqualTo(Arrays.copyOfRange(pcm, 0, 320));
        assertThat(two).hasSize(320).isEqualTo(Arrays.copyOfRange(pcm, 320, 640));

        var done = leg.playbackDone();
        JsonNode mark = farmer.nextText();
        assertThat(mark.get("type").asText()).isEqualTo("mark");
        assertThat(done).isNotDone();
        farmer.session.sendMessage(new TextMessage("{\"type\":\"mark\",\"id\":" + mark.get("id").asLong() + "}"));
        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(PlaybackOutcome.PLAYED);

        leg.play(new AudioFrame(new byte[320], 8000, 0));
        var interrupted = leg.playbackDone();
        farmer.nextAudio();
        farmer.nextText(); // the mark
        leg.clearPlayback();
        assertThat(farmer.nextText().get("type").asText()).isEqualTo("clear");
        assertThat(interrupted.get(5, TimeUnit.SECONDS)).isEqualTo(PlaybackOutcome.CLEARED);
        farmer.session.close();
    }

    @Test
    void closingTheBrowserEndsAndUnregistersTheLeg() throws Exception {
        Browser farmer = connect("role=farmer&lang=hi-IN");
        Leg leg = legOf(farmer.nextText());
        var done = leg.playbackDone();
        assertThat(done).isCompletedWithValue(PlaybackOutcome.PLAYED);

        farmer.session.close();

        awaitTrue(() -> registry.find(leg.id()).isEmpty());
    }

    @Test
    void aHangupMessageEndsTheLegAndTheServerClosesTheSocket() throws Exception {
        Browser farmer = connect("role=farmer&lang=hi-IN");
        Leg leg = legOf(farmer.nextText());

        farmer.session.sendMessage(new TextMessage("{\"type\":\"hangup\"}"));

        assertThat(farmer.nextText().get("type").asText()).isEqualTo("end");
        assertThat(farmer.closed.poll(5, TimeUnit.SECONDS)).isNotNull();
        awaitTrue(() -> registry.find(leg.id()).isEmpty());
    }

    @Test
    void theLegHangingUpTellsTheBrowser() throws Exception {
        Browser farmer = connect("role=farmer&lang=hi-IN");
        Leg leg = legOf(farmer.nextText());

        leg.hangup();

        assertThat(farmer.nextText().get("type").asText()).isEqualTo("end");
        assertThat(farmer.closed.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void aBuyerJoinsTheWaitingFarmersCallAndAnExplicitCallIdWorks() throws Exception {
        Browser farmer = connect("role=farmer&lang=hi-IN&caller=%2B919876543214");
        Leg farmerLeg = legOf(farmer.nextText());

        Browser buyer = connect("role=buyer&lang=en-IN&callId=" + farmerLeg.callId().value());
        Leg buyerLeg = legOf(buyer.nextText());

        assertThat(buyerLeg.role()).isEqualTo(LegRole.BUYER);
        assertThat(buyerLeg.source()).isEqualTo(LegSource.SIMULATOR);
        assertThat(buyerLeg.callId()).isEqualTo(farmerLeg.callId());
        assertThat(registry.legs(farmerLeg.callId())).containsExactly(farmerLeg, buyerLeg);

        Browser second = connect("role=buyer&lang=en-IN&callId=" + farmerLeg.callId().value());
        assertThat(second.closed.poll(5, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        buyer.session.close();
        farmer.session.close();
    }

    @Test
    void aBuyerWithNoFarmerIsRefused() throws Exception {
        Browser buyer = connect("role=buyer&lang=en-IN&callId=" + CallId.random().value());
        CloseStatus status = buyer.closed.poll(5, TimeUnit.SECONDS);
        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(status.getReason()).contains("farmer");
    }

    @Test
    void badRoleLanguageOrCallerIsRefusedWithAReason() throws Exception {
        for (String query : List.of("role=pilot&lang=hi-IN", "role=farmer&lang=xx-XX",
                "role=farmer&lang=hi-IN&caller=%2B15005550006", "lang=hi-IN")) {
            Browser b = connect(query);
            CloseStatus status = b.closed.poll(5, TimeUnit.SECONDS);
            assertThat(status).as(query).isNotNull();
            assertThat(status.getCode()).as(query).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
            assertThat(status.getReason()).as(query).isNotBlank().doesNotContain("5005550006");
        }
    }

    @Test
    void theConfigEndpointOffersSyntheticCallersAndLanguages() {
        JsonNode config = http.getForObject("/api/simulator/config", JsonNode.class);
        assertThat(config.get("callers")).hasSize(5);
        assertThat(config.get("callers").get(0).get("number").asText()).isEqualTo("+919876543210");
        assertThat(config.get("languages").get(0).get("code").asText()).isEqualTo("hi-IN");
        assertThat(config.get("languages").get(0).has("voice")).isFalse();
    }
}
