package com.example.g_one.agent.pipeline;

import static com.example.g_one.agent.mediation.MediationFixture.NO_CAUTION;
import static com.example.g_one.agent.mediation.MediationFixture.translation;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.pipeline.CallEndHandler.EndedCall;
import com.example.g_one.agent.mediation.MediationFixture;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pipeline's mediation hook (#26) with the real {@link com.example.g_one.agent.mediation.Mediator}: a buyer leg
 * joining a live farmer leg takes the farmer's turns away from the brain until the buyer hangs up.
 */
class CallPipelineMediationTest {
    private final AtomicLong clock = new AtomicLong(1_762_600_000_000L);
    private final ManualExecutor executor = new ManualExecutor();
    private final ScriptedBrain brain = new ScriptedBrain();
    private final FakeSpeechToText stt = new FakeSpeechToText(clock::get);
    private final FakeTextToSpeech tts = new FakeTextToSpeech();
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final List<EndedCall> endedCalls = new CopyOnWriteArrayList<>();
    private final CallId callId = CallId.random();
    private MediationFixture mediation;
    private LegRegistry registry;
    private FakeLeg farmer;
    private FakeSpeechToText.Session farmerSays;

    @BeforeEach
    void setUp() throws Exception {
        mediation = new MediationFixture();
        registry = new LegRegistry();
        CallPipeline pipeline = new CallPipeline(brain, stt, tts, store, mediation.bus, List.of(endedCalls::add),
                executor, clock::get, CallPipeline.DEFAULT_GOODBYE_GRACE, CallPipeline.DEFAULT_HELD_REPLY_RELEASE,
                CallPipeline.DEFAULT_FIXED_GOODBYES, new ManualDelays());
        pipeline.useMediation(mediation.mediator);
        pipeline.attach(registry);

        farmer = FakeLeg.farmer(callId);
        registry.register(farmer);
        executor.runAll(); // memory, speech-to-text session, greeting
        farmerSays = stt.last();
        assertThat(brain.greets).hasSize(1);
    }

    @AfterEach
    void tearDown() {
        mediation.close();
    }

    private FakeLeg buyerJoins() {
        FakeLeg buyer = FakeLeg.buyer(callId);
        registry.register(buyer);
        executor.runAll();
        return buyer;
    }

    @Test
    void aBuyerJoiningStopsTheAssistantAndStartsMediation() {
        assertThat(farmer.played()).isNotEmpty(); // the greeting is still playing
        buyerJoins();

        assertThat(farmer.clearCount()).isEqualTo(1);
        assertThat(mediation.events(ConsoleEvent.LegJoined.class)).extracting(ConsoleEvent.LegJoined::role)
                .containsExactly(LegRole.FARMER, LegRole.BUYER);
    }

    @Test
    void forgetMeSaidDuringMediationIsTranslatedAndNeverReachesTheBrainOrItsTools() {
        FakeLeg buyer = buyerJoins();
        mediation.llm.replyText(NO_CAUTION).replyText(translation("Forget me!", "Forget me!"));

        farmerSays.say("mujhe bhool jao");
        executor.runAll();

        assertThat(brain.responds).isEmpty();
        assertThat(mediation.llm.requests()).hasSize(2)
                .allSatisfy(r -> assertThat(r.has("tools")).isFalse()); // no tool can be called from mediation
        assertThat(mediation.events(ConsoleEvent.ToolCalled.class)).isEmpty();
        assertThat(mediation.events(ConsoleEvent.TurnTranslated.class)).singleElement()
                .satisfies(t -> assertThat(t.text()).isEqualTo("Forget me!"));
        assertThat(buyer.played()).hasSize(MediationFixture.frames("Forget me!"));
        assertThat(store.load(FakeLeg.FARMER_KEY)).isEmpty();

        farmer.remoteHangup();
        executor.runAll();
        assertThat(endedCalls).singleElement().satisfies(c ->
                assertThat(c.transcript()).noneMatch(line -> line.text().contains("bhool")));
        assertThat(buyer.isEnded()).isTrue();
    }

    @Test
    void theFarmerGoesBackToTheAssistantWhenTheBuyerHangsUp() {
        FakeLeg buyer = buyerJoins();
        buyer.remoteHangup();
        executor.runAll();

        farmerSays.say("ab kya karein?");
        executor.runAll();

        assertThat(brain.responds).singleElement()
                .satisfies(c -> assertThat(c.latest().text()).isEqualTo("ab kya karein?"));
        assertThat(mediation.llm.requests()).isEmpty();
    }
}
