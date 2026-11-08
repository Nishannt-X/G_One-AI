package com.example.g_one.agent.pipeline;

import static com.example.g_one.agent.pipeline.ScriptedBrain.GREETING;
import static com.example.g_one.agent.pipeline.ScriptedBrain.replyTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.pipeline.CallEndHandler.EndedCall;
import com.example.g_one.agent.pipeline.FlakyTextToSpeech.Failure;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The call pipeline against the core fakes only. Background steps run on a {@link ManualExecutor} and timers on
 * {@link ManualDelays}, so every test is deterministic: the test decides when memory loads, when the brain answers and in
 * which order. Races between threads are in {@link CallPipelineRaceTest}.
 */
class CallPipelineTest {
    private static final int RATE = CallPipeline.TELEPHONY_SAMPLE_RATE;
    private static final int MS_PER_CHAR = 50; // FakeTextToSpeech default

    private final AtomicLong clock = new AtomicLong(1_762_600_000_000L);
    private final ManualExecutor executor = new ManualExecutor();
    private final ManualDelays delays = new ManualDelays();
    private final ScriptedBrain brain = new ScriptedBrain();
    private final FakeSpeechToText stt = new FakeSpeechToText(clock::get);
    private final FakeTextToSpeech tts = new FakeTextToSpeech();
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final ConsoleBus bus = new ConsoleBus();
    private final List<ConsoleEvent> events = new CopyOnWriteArrayList<>();
    private final List<EndedCall> endedCalls = new CopyOnWriteArrayList<>();
    private final CallId callId = CallId.random();
    private LegRegistry registry;

    @BeforeEach
    void setUp() {
        bus.subscribe(events::add);
        registry = pipelineWith(store, tts, stt, List.of(endedCalls::add));
    }

    private LegRegistry pipelineWith(MemoryStore memory, TextToSpeech speech, SpeechToText hearing,
                                     List<CallEndHandler> handlers) {
        return pipelineWith(memory, speech, hearing, handlers, CallPipeline.DEFAULT_FIXED_GOODBYES);
    }

    private LegRegistry pipelineWith(MemoryStore memory, TextToSpeech speech, SpeechToText hearing,
                                     List<CallEndHandler> handlers, Function<Language, String> goodbyes) {
        LegRegistry r = new LegRegistry();
        new CallPipeline(brain, hearing, speech, memory, bus, handlers, executor, clock::get,
                CallPipeline.DEFAULT_GOODBYE_GRACE, CallPipeline.DEFAULT_HELD_REPLY_RELEASE, goodbyes, delays)
                .attach(r);
        return r;
    }

    /** Registers the leg and runs the start step (memory, STT session, greeting). */
    private <L extends Leg> L join(LegRegistry r, L leg) {
        r.register(leg);
        executor.runAll();
        return leg;
    }

    private FakeLeg join(FakeLeg leg) {
        return join(registry, leg);
    }

    private FakeSpeechToText.Session session() {
        return stt.last();
    }

    private List<String> spoken() {
        return tts.requests().stream().map(FakeTextToSpeech.Request::text).toList();
    }

    private <T extends ConsoleEvent> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<AudioFrame> framesFor(String text) {
        return FakeTextToSpeech.framesFor(text, RATE, MS_PER_CHAR);
    }

    private static Line farmer(String text) {
        return new Line(Speaker.FARMER, text);
    }

    private static Line assistant(String text) {
        return new Line(Speaker.ASSISTANT, text);
    }

    // ---- greeting ----

    @Test
    void newFarmerIsGreetedAfterMemoryLoadsAndTheSessionOpens() {
        FakeLeg leg = FakeLeg.farmer(callId);
        registry.register(leg);

        // Announcing the leg is quick: only the start events, the rest waits for the executor.
        assertThat(events).hasExactlyElementsOfTypes(ConsoleEvent.CallStarted.class, ConsoleEvent.LegJoined.class);
        assertThat(stt.sessions()).isEmpty();
        assertThat(executor.pending()).isEqualTo(1);

        executor.runAll();

        assertThat(events).hasExactlyElementsOfTypes(ConsoleEvent.CallStarted.class, ConsoleEvent.LegJoined.class,
                ConsoleEvent.MemoryLoaded.class, ConsoleEvent.AssistantSaid.class);
        ConsoleEvent.MemoryLoaded loaded = eventsOf(ConsoleEvent.MemoryLoaded.class).get(0);
        assertThat(loaded.callerKey()).isEqualTo(FakeLeg.FARMER_KEY);
        assertThat(loaded.returning()).isFalse();
        assertThat(eventsOf(ConsoleEvent.CallStarted.class).get(0).language()).isEqualTo("hi-IN");

        assertThat(session().legId()).isEqualTo(leg.id());
        assertThat(session().role()).isEqualTo(LegRole.FARMER);
        assertThat(session().language()).isEqualTo(FakeLeg.HINDI);

        assertThat(brain.greets).singleElement().satisfies(ctx -> {
            assertThat(ctx.callId()).isEqualTo(callId);
            assertThat(ctx.farmerLeg()).isSameAs(leg);
            assertThat(ctx.callerKey()).contains(FakeLeg.FARMER_KEY);
            assertThat(ctx.memory().isEmpty()).isTrue();
        });
        assertThat(tts.requests()).containsExactly(new FakeTextToSpeech.Request(GREETING, FakeLeg.HINDI, 8000));
        assertThat(leg.played()).isEqualTo(framesFor(GREETING));
        ConsoleEvent.AssistantSaid said = eventsOf(ConsoleEvent.AssistantSaid.class).get(0);
        assertThat(said.text()).isEqualTo(GREETING);
        assertThat(said.legId()).isEqualTo(leg.id());
        assertThat(said.language()).isEqualTo("hi-IN");
    }

    @Test
    void returningFarmerGreetingSeesTheirMemory() {
        CallGist lastCall = new CallGist(CallId.of("c-1103"), 1_762_100_000_000L, 1_762_100_300_000L,
                "Asked the red onion price at Lasalgaon.", List.of("Find a buyer after harvest"));
        store.saveGist(FakeLeg.FARMER_KEY, lastCall);

        join(FakeLeg.farmer(callId));

        ConsoleEvent.MemoryLoaded loaded = eventsOf(ConsoleEvent.MemoryLoaded.class).get(0);
        assertThat(loaded.returning()).isTrue();
        assertThat(loaded.gists()).containsExactly(lastCall);
        assertThat(brain.greets.get(0).memory().gists()).containsExactly(lastCall);
        assertThat(spoken()).containsExactly(GREETING + " Welcome back.");
    }

    // ---- turns ----

    @Test
    void twoTurnsAreAnsweredInOrderWithTheConversationSoFar() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();

        session().say("pyaaz ka bhaav kya hai");
        // The turn is published right away; the brain runs on the executor, not on the STT thread.
        assertThat(eventsOf(ConsoleEvent.TurnHeard.class)).singleElement()
                .satisfies(t -> assertThat(t.text()).isEqualTo("pyaaz ka bhaav kya hai"));
        assertThat(brain.responds).isEmpty();
        executor.runAll();
        leg.finishPlayback();

        session().say("bees quintal");
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING, replyTo("pyaaz ka bhaav kya hai"), replyTo("bees quintal"));
        assertThat(brain.responds).hasSize(2);
        assertThat(brain.responds.get(0).history()).containsExactly(assistant(GREETING));
        assertThat(brain.responds.get(1).history()).containsExactly(assistant(GREETING),
                farmer("pyaaz ka bhaav kya hai"), assistant(replyTo("pyaaz ka bhaav kya hai")));
        assertThat(brain.responds.get(1).latest().text()).isEqualTo("bees quintal");

        List<String> console = events.stream()
                .filter(e -> e instanceof ConsoleEvent.TurnHeard || e instanceof ConsoleEvent.AssistantSaid)
                .map(e -> e instanceof ConsoleEvent.TurnHeard t ? "farmer: " + t.text()
                        : "assistant: " + ((ConsoleEvent.AssistantSaid) e).text())
                .toList();
        assertThat(console).containsExactly("assistant: " + GREETING, "farmer: pyaaz ka bhaav kya hai",
                "assistant: " + replyTo("pyaaz ka bhaav kya hai"), "farmer: bees quintal",
                "assistant: " + replyTo("bees quintal"));
        assertThat(leg.clearCount()).isZero();
        assertThat(leg.played()).hasSize(framesFor(GREETING).size()
                + framesFor(replyTo("pyaaz ka bhaav kya hai")).size() + framesFor(replyTo("bees quintal")).size());
    }

    @Test
    void replyLanguageCarriesIntoTheNextTurn() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();

        session().say(ScriptedBrain.SWITCH_TO_ENGLISH);
        executor.runAll();
        leg.finishPlayback();
        session().say("onion price");
        executor.runAll();

        assertThat(tts.requests().get(1).language()).isEqualTo(FakeLeg.ENGLISH);
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class).get(1).language()).isEqualTo("en-IN");
        assertThat(brain.responds.get(1).ctx().language()).isEqualTo(FakeLeg.ENGLISH);
    }

    // ---- barge-in and superseded replies ----

    @Test
    void bargeInClearsQueuedAudioAndCancelsTheRunningSynthesis() {
        tts.holdOutput(); // the greeting is still being synthesised
        FakeLeg leg = join(FakeLeg.farmer(callId));
        assertThat(tts.heldCount()).isEqualTo(1);

        session().emitSpeechStart();

        assertThat(leg.clearCount()).isEqualTo(1);
        tts.releaseAll();
        assertThat(leg.played()).as("a cancelled synthesis plays nothing").isEmpty();

        session().emitTurn("ruko");
        executor.runAll();
        tts.releaseAll();
        assertThat(leg.played()).isEqualTo(framesFor(replyTo("ruko")));

        // Speech start while nothing is being said is not a barge-in.
        leg.finishPlayback();
        session().emitSpeechStart();
        assertThat(leg.clearCount()).isEqualTo(1);
    }

    @Test
    void bargeInDuringTheGoodbyeDropsItAndCancelsTheHangUp() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("bye");
        executor.runAll();
        assertThat(leg.unheardFrames()).isPositive();

        session().emitSpeechStart(); // the farmer talks over the goodbye
        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(leg.unheardFrames()).isZero();
        leg.finishPlayback();
        assertThat(leg.isEnded()).as("the dropped goodbye must not hang up").isFalse();

        session().emitTurn("ek aur baat");
        executor.runAll();
        leg.finishPlayback();

        assertThat(spoken()).containsExactly(GREETING, replyTo("bye"), replyTo("ek aur baat"));
        assertThat(leg.isEnded()).isFalse();
    }

    @Test
    void newerTurnSupersedesASlowOlderReply() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        // While the brain is still thinking about "first", the farmer finishes another turn.
        brain.whileThinking = text -> {
            if (text.equals("first")) {
                session().say("second");
            }
        };

        session().say("first");
        executor.runNext(); // "first": the brain answers, but "second" arrived meanwhile
        assertThat(spoken()).containsExactly(GREETING);
        executor.runAll(); // "second"

        assertThat(spoken()).containsExactly(GREETING, replyTo("second"));
        assertThat(brain.responds).extracting(c -> c.latest().text()).containsExactly("first", "second");
        assertThat(brain.responds.get(1).history()).containsExactly(assistant(GREETING), farmer("first"));
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).extracting(ConsoleEvent.AssistantSaid::text)
                .containsExactly(GREETING, replyTo("second"));
        assertThat(leg.played()).hasSize(framesFor(GREETING).size() + framesFor(replyTo("second")).size());
    }

    // ---- one brain call per leg at a time ----

    @Test
    void aTurnHeardWhileTheGreetingIsPreparedIsAskedOnlyAfterTheGreetingCallReturns() {
        AtomicInteger queuedDuringGreeting = new AtomicInteger(-1);
        brain.whileGreeting = () -> {
            session().say("haan");
            queuedDuringGreeting.set(executor.pending());
        };

        join(FakeLeg.farmer(callId));

        assertThat(queuedDuringGreeting).as("no reply is asked for while the greeting call runs").hasValue(0);
        assertThat(brain.responds).extracting(c -> c.latest().text()).containsExactly("haan");
        assertThat(spoken()).containsExactly(replyTo("haan"));
    }

    @Test
    void onlyTheNewestTurnWaitingForTheBrainIsAsked() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        brain.whileThinking = text -> {
            if (text.equals("a")) {
                session().say("b");
                session().say("c");
            }
        };

        session().say("a");
        executor.runAll();

        assertThat(brain.responds).extracting(c -> c.latest().text()).containsExactly("a", "c");
        assertThat(brain.responds.get(1).history())
                .containsExactly(assistant(GREETING), farmer("a"), farmer("b"));
        assertThat(spoken()).containsExactly(GREETING, replyTo("c"));
    }

    @Test
    void aTurnWaitingForTheBrainIsNotAskedAfterTheFarmerHangsUp() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        brain.whileThinking = text -> {
            if (text.equals("a")) {
                session().say("b");
                leg.remoteHangup();
            }
        };

        session().say("a");
        executor.runAll();

        assertThat(brain.responds).extracting(c -> c.latest().text()).containsExactly("a");
        assertThat(spoken()).containsExactly(GREETING);
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).hasSize(1);
    }

    @Test
    void anOlderTurnThatRunsLastIsSkippedWithoutCallingTheBrain() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();

        session().say("a");
        session().say("b");
        executor.runLast(); // "b" first
        executor.runNext(); // then the stale "a"
        executor.runAll(); // the synthesis of the reply to "b"

        assertThat(brain.responds).extracting(c -> c.latest().text()).containsExactly("b");
        assertThat(spoken()).containsExactly(GREETING, replyTo("b"));
    }

    @Test
    void aTurnWhileTheAssistantSpeaksInterruptsItEvenWithoutSpeechStart() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        assertThat(leg.unheardFrames()).isPositive();

        session().emitTurn("haan"); // no speech start event first

        assertThat(leg.clearCount()).isEqualTo(1);
        executor.runAll();
        assertThat(spoken()).containsExactly(GREETING, replyTo("haan"));
    }

    // ---- speech start while the assistant is thinking: the reply is held ----

    private static final long HOLD_MS = CallPipeline.DEFAULT_HELD_REPLY_RELEASE.toMillis();

    @Test
    void aReplyHeldByASpeechStartIsDiscardedByTheTurnThatFollows() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("pehla"); // the brain starts on "pehla"

        session().emitSpeechStart(); // the farmer speaks again before the reply is ready
        assertThat(delays.pendingDelaysMs()).containsExactly(HOLD_MS);
        executor.runAll(); // the reply to "pehla" is ready, but held

        assertThat(spoken()).as("the held reply does not start over the farmer").containsExactly(GREETING);
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).hasSize(1);
        assertThat(leg.played()).hasSize(framesFor(GREETING).size());

        session().emitTurn("doosra"); // ...and it was a real turn
        executor.runAll();
        delays.fireAll(); // the old hold window ends and changes nothing
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING, replyTo("doosra"));
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).extracting(ConsoleEvent.AssistantSaid::text)
                .containsExactly(GREETING, replyTo("doosra"));
        assertThat(brain.responds.get(1).history()).as("the discarded reply is not in the conversation")
                .containsExactly(assistant(GREETING), farmer("pehla"));
        assertThat(leg.clearCount()).as("nothing was playing, so nothing was cleared").isZero();
    }

    @Test
    void aReplyThatIsReadyDuringTheHoldPlaysWhenNoTurnFollowsInTime() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("pehla");
        session().emitSpeechStart(); // a cough
        session().emitSpeechStart(); // and another: the window is not extended
        assertThat(delays.pendingDelaysMs()).containsExactly(HOLD_MS);

        executor.runAll(); // the reply completes during the hold and waits
        assertThat(spoken()).containsExactly(GREETING);
        assertThat(executor.pending()).isZero();

        delays.fireAll(); // the window ends without a final turn
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).extracting(ConsoleEvent.AssistantSaid::text)
                .containsExactly(GREETING, replyTo("pehla"));
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING, replyTo("pehla"));
        assertThat(leg.unheardFrames()).isEqualTo(framesFor(replyTo("pehla")).size());
        leg.remoteHangup();
        executor.runAll();
        assertThat(endedCalls).singleElement().satisfies(c -> assertThat(c.transcript()).containsExactly(
                assistant(GREETING), farmer("pehla"), assistant(replyTo("pehla"))));
    }

    @Test
    void aReplyStillBeingPreparedWhenTheHoldEndsPlaysAsSoonAsItIsReady() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("pehla");
        session().emitSpeechStart();

        delays.fireAll(); // the window ends while the brain is still thinking
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING, replyTo("pehla"));
        assertThat(leg.unheardFrames()).isEqualTo(framesFor(replyTo("pehla")).size());
    }

    @Test
    void aSpeechStartBeforeTheGreetingIsReadyHoldsTheGreeting() {
        FakeLeg leg = FakeLeg.farmer(callId);
        registry.register(leg);
        executor.runNext(); // memory
        executor.runNext(); // the session opens; the greeting is being prepared

        session().emitSpeechStart();
        executor.runAll();
        assertThat(brain.greets).hasSize(1);
        assertThat(spoken()).as("the greeting waits while the farmer may be speaking").isEmpty();

        delays.fireAll();
        executor.runAll();
        assertThat(spoken()).containsExactly(GREETING);
        assertThat(leg.played()).isEqualTo(framesFor(GREETING));
    }

    @Test
    void speechStartWhileTheAssistantSpeaksIsStillABargeInNotAHold() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        assertThat(leg.unheardFrames()).isPositive();

        session().emitSpeechStart(); // over the greeting

        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(leg.unheardFrames()).isZero();
        assertThat(delays.pendingDelaysMs()).as("no hold timer for a barge-in").isEmpty();
        session().emitTurn("ruko");
        executor.runAll();
        assertThat(spoken()).containsExactly(GREETING, replyTo("ruko"));

        leg.finishPlayback();
        session().emitSpeechStart(); // silent, nothing on the way: neither a barge-in nor a hold
        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(delays.pendingDelaysMs()).isEmpty();
    }

    @Test
    void theFarmerHangingUpDuringAHoldDropsTheHeldReply() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("pehla");
        session().emitSpeechStart();
        executor.runAll(); // held

        leg.remoteHangup();
        delays.fireAll();
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING);
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).hasSize(1);
        assertThat(endedCalls).singleElement().satisfies(c -> assertThat(c.transcript())
                .containsExactly(assistant(GREETING), farmer("pehla")));
    }

    // ---- ending ----

    @Test
    void endCallHangsUpOnlyAfterTheGoodbyeWasHeard() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();

        session().say("bye");
        executor.runAll();
        assertThat(spoken()).last().isEqualTo(replyTo("bye"));
        assertThat(leg.isEnded()).as("still playing the goodbye").isFalse();

        leg.finishPlayback();

        // The call is over at once; the transport hang-up runs on the executor.
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("assistant_hangup"));
        executor.runAll();
        assertThat(leg.isEnded()).isTrue();
        assertThat(leg.hungUpByUs()).isTrue();
        assertThat(endedCalls).singleElement().satisfies(c -> assertThat(c.reason()).isEqualTo("assistant_hangup"));
        delays.fireAll(); // the goodbye timeout fires later and changes nothing
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).hasSize(1);
    }

    @Test
    void hangUpClosesSpeechToTextAndHandsTheTranscriptToTheEndHandler() {
        long joinedAt = clock.get();
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("pyaaz bechna hai");
        executor.runAll();
        clock.addAndGet(42_000);

        leg.remoteHangup();

        ConsoleEvent.CallEnded callEnded = eventsOf(ConsoleEvent.CallEnded.class).get(0);
        assertThat(callEnded.durationMs()).isEqualTo(42_000);
        assertThat(callEnded.reason()).isEqualTo("hangup");
        assertThat(eventsOf(ConsoleEvent.LegEnded.class)).singleElement().satisfies(e -> {
            assertThat(e.legId()).isEqualTo(leg.id());
            assertThat(e.role()).isEqualTo(LegRole.FARMER);
        });
        assertThat(endedCalls).as("handlers run on the executor").isEmpty();

        executor.runAll();

        assertThat(session().isClosed()).isTrue();
        assertThat(endedCalls).singleElement().satisfies(c -> {
            assertThat(c.callId()).isEqualTo(callId);
            assertThat(c.callerKey()).contains(FakeLeg.FARMER_KEY);
            assertThat(c.transcript()).containsExactly(assistant(GREETING), farmer("pyaaz bechna hai"),
                    assistant(replyTo("pyaaz bechna hai")));
            assertThat(c.startedMs()).isEqualTo(joinedAt);
            assertThat(c.endedMs()).isEqualTo(joinedAt + 42_000);
            assertThat(c.reason()).isEqualTo("hangup");
        });
    }

    @Test
    void hangUpWhileTheBrainThinksDropsTheReply() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("kitna milega");

        leg.remoteHangup();
        executor.runAll();

        assertThat(brain.responds).isEmpty();
        assertThat(spoken()).containsExactly(GREETING);
        assertThat(endedCalls).singleElement().satisfies(c -> assertThat(c.transcript())
                .containsExactly(assistant(GREETING), farmer("kitna milega")));
    }

    @Test
    void hangUpCancelsTheRunningSynthesis() {
        tts.holdOutput();
        FakeLeg leg = join(FakeLeg.farmer(callId));

        leg.remoteHangup();
        tts.releaseAll();

        assertThat(leg.played()).isEmpty();
        executor.runAll();
        assertThat(endedCalls).hasSize(1);
    }

    @Test
    void legThatEndsBeforeItStartsIsNotGreeted() {
        FakeLeg leg = FakeLeg.farmer(callId);
        registry.register(leg);
        leg.remoteHangup();

        executor.runAll();

        assertThat(stt.sessions()).isEmpty();
        assertThat(brain.greets).isEmpty();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).hasSize(1);
        assertThat(endedCalls).isEmpty();
    }

    /** The issue's acceptance run: audio drives two turns, a barge-in clears the greeting, the gist hand-off runs. */
    @Test
    void endToEndAudioDrivesTurnsBargeInAndTheEndHandler() {
        stt.turnEvery(3).script("pyaaz bechna hai", "bees quintal");
        FakeLeg leg = FakeLeg.farmer(callId);
        registry.register(leg);
        leg.pushSilence(3); // before the session opens: dropped, no turn
        executor.runAll();
        assertThat(session().fed()).isEmpty();
        assertThat(leg.unheardFrames()).as("greeting queued").isPositive();

        leg.pushSilence(1); // speech start over the greeting: barge-in
        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(leg.unheardFrames()).isZero();
        leg.pushSilence(2); // end of the first turn
        executor.runAll();
        leg.finishPlayback();

        leg.pushSilence(3); // second turn, nothing playing: no barge-in
        executor.runAll();
        leg.finishPlayback();
        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(session().fed()).hasSize(6);

        leg.remoteHangup();
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING, replyTo("pyaaz bechna hai"), replyTo("bees quintal"));
        assertThat(endedCalls).singleElement().satisfies(c -> assertThat(c.transcript()).containsExactly(
                assistant(GREETING), farmer("pyaaz bechna hai"), assistant(replyTo("pyaaz bechna hai")),
                farmer("bees quintal"), assistant(replyTo("bees quintal"))));
        assertThat(session().isClosed()).isTrue();
    }

    // ---- withheld caller ID ----

    @Test
    void withheldCallerIdLoadsNoMemoryButIsStillAnswered() {
        LegRegistry r = pipelineWith(new UntouchableStore(), tts, stt, List.of(endedCalls::add));
        FakeLeg leg = join(r, FakeLeg.withheldFarmer(callId));
        leg.finishPlayback();
        session().say("namaste");
        executor.runAll();
        leg.remoteHangup();
        executor.runAll();

        assertThat(eventsOf(ConsoleEvent.MemoryLoaded.class)).isEmpty();
        assertThat(brain.greets.get(0).memory()).isEqualTo(FarmerMemory.withheld());
        assertThat(spoken()).containsExactly(GREETING, replyTo("namaste"));
        assertThat(endedCalls).singleElement().satisfies(c -> assertThat(c.callerKey()).isEmpty());
    }

    // ---- failures never stop the call ----

    @Test
    void brainFailuresBecomeASpokenApologyAndTheCallGoesOn() {
        brain.failGreet = true;
        brain.failRespond = true;
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("x");
        executor.runAll();
        leg.finishPlayback();

        brain.failRespond = false;
        session().say("y");
        executor.runAll();

        assertThat(spoken()).containsExactly(CallPipeline.HINDI_APOLOGY, CallPipeline.HINDI_APOLOGY, replyTo("y"));
    }

    @Test
    void apologyIsInEnglishForAnEnglishLeg() {
        brain.failGreet = true;
        join(new FakeLeg(callId, LegRole.FARMER, LegSource.SIMULATOR, FakeLeg.ENGLISH, FakeLeg.FARMER_KEY));

        assertThat(tts.requests()).containsExactly(
                new FakeTextToSpeech.Request(CallPipeline.ENGLISH_APOLOGY, FakeLeg.ENGLISH, 8000));
    }

    @Test
    void memoryLoadFailureTreatsTheFarmerAsNew() {
        LegRegistry r = pipelineWith(new FailingStore(), tts, stt, List.of(endedCalls::add));
        join(r, FakeLeg.farmer(callId));

        assertThat(eventsOf(ConsoleEvent.MemoryLoaded.class)).isEmpty();
        assertThat(brain.greets.get(0).memory()).isEqualTo(FarmerMemory.empty(FakeLeg.FARMER_KEY));
        assertThat(spoken()).containsExactly(GREETING);
    }


    // ---- a goodbye that cannot be spoken ----

    private static final long GRACE_MS = CallPipeline.DEFAULT_GOODBYE_GRACE.toMillis();

    @ParameterizedTest
    @EnumSource(Failure.class)
    void aGoodbyeWithoutCompleteAudioIsNeverTakenAsHeardAndTheFixedGoodbyeIsSaidInstead(Failure how) {
        FlakyTextToSpeech flaky = new FlakyTextToSpeech().fail(replyTo("bye"), how);
        FakeLeg leg = join(pipelineWith(store, flaky, stt, List.of(endedCalls::add)), FakeLeg.farmer(callId));
        leg.finishPlayback();
        int greetingFrames = leg.played().size();

        session().say("bye");
        executor.runAll();

        assertThat(flaky.spoken()).containsExactly(GREETING, replyTo("bye"), CallPipeline.FALLBACK_GOODBYE);
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).last().satisfies(said -> {
            assertThat(said.text()).isEqualTo(CallPipeline.FALLBACK_GOODBYE);
            assertThat(said.language()).isEqualTo("hi-IN");
        });
        List<AudioFrame> afterGreeting = leg.played().subList(greetingFrames, leg.played().size());
        int partial = how == Failure.FAILS_AFTER_ONE_FRAME || how == Failure.CANCELLED_AFTER_ONE_FRAME ? 1 : 0;
        assertThat(afterGreeting).hasSize(partial + framesFor(CallPipeline.FALLBACK_GOODBYE).size());
        assertThat(afterGreeting.subList(partial, afterGreeting.size()))
                .isEqualTo(framesFor(CallPipeline.FALLBACK_GOODBYE));
        assertThat(leg.isEnded()).as("no hang-up before the fixed goodbye was heard").isFalse();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).isEmpty();
        assertThat(delays.pendingDelaysMs()).as("the timeout covers the partial goodbye and the fixed one")
                .containsExactly(afterGreeting.size() * (long) FakeTextToSpeech.FRAME_MS + GRACE_MS);

        leg.finishPlayback();
        executor.runAll();

        assertThat(leg.hungUpByUs()).isTrue();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo(CallPipeline.ASSISTANT_HANGUP));
        assertThat(endedCalls).singleElement().satisfies(c -> assertThat(c.transcript()).endsWith(
                assistant(replyTo("bye")), assistant(CallPipeline.FALLBACK_GOODBYE)));
    }

    @Test
    void framesWithNoSamplesAreNeverPlayed() {
        AudioFrame empty = new AudioFrame(new byte[0], RATE, 0);
        TextToSpeech padded = (text, language, rate, sink) -> {
            sink.accept(empty);
            return tts.speak(text, language, rate, sink);
        };

        FakeLeg leg = join(pipelineWith(store, padded, stt, List.of(endedCalls::add)), FakeLeg.farmer(callId));

        assertThat(leg.played()).isEqualTo(framesFor(GREETING));
    }

    @Test
    void theFixedGoodbyeIsTheConfiguredOneForTheGoodbyesLanguage() {
        String hindiGoodbye = "धन्यवाद। नमस्ते।";
        FlakyTextToSpeech flaky = new FlakyTextToSpeech().fail(replyTo("bye"), Failure.THROWS_ON_START);
        LegRegistry r = pipelineWith(store, flaky, stt, List.of(endedCalls::add),
                language -> language.equals(FakeLeg.HINDI) ? hindiGoodbye : "wrong language");
        FakeLeg leg = join(r, FakeLeg.farmer(callId));
        leg.finishPlayback();

        session().say("bye");
        executor.runAll();

        assertThat(flaky.spoken()).containsExactly(GREETING, replyTo("bye"), hindiGoodbye);
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).last()
                .satisfies(said -> assertThat(said.language()).isEqualTo("hi-IN"));
        leg.finishPlayback();
        executor.runAll();
        assertThat(leg.hungUpByUs()).isTrue();
    }

    @Test
    void aFixedGoodbyeLookupThatFailsFallsBackToTheEnglishOne() {
        FlakyTextToSpeech flaky = new FlakyTextToSpeech().fail(replyTo("bye"), Failure.NO_FRAMES);
        LegRegistry r = pipelineWith(store, flaky, stt, List.of(endedCalls::add), language -> {
            throw new IllegalStateException("fixed lines missing (test)");
        });
        FakeLeg leg = join(r, FakeLeg.farmer(callId));
        leg.finishPlayback();

        session().say("bye");
        executor.runAll();

        assertThat(flaky.spoken()).containsExactly(GREETING, replyTo("bye"), CallPipeline.FALLBACK_GOODBYE);
    }

    @Test
    void aGoodbyeFrameTheLegCannotPlayCountsAsAGoodbyeNotSpoken() {
        HookedLeg leg = join(registry, new HookedLeg(FakeLeg.farmer(callId)));
        leg.fake.finishPlayback();
        AtomicBoolean failedOnce = new AtomicBoolean();
        leg.play = frame -> {
            if (failedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("transport refused a frame (test)");
            }
            leg.fake.play(frame);
        };

        session().say("bye");
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING, replyTo("bye"), CallPipeline.FALLBACK_GOODBYE);
        assertThat(leg.fake.isEnded()).isFalse();
        leg.fake.finishPlayback();
        executor.runAll();
        assertThat(leg.fake.hungUpByUs()).isTrue();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo(CallPipeline.ASSISTANT_HANGUP));
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void whenTheFixedGoodbyeFailsTooTheCallEndsAfterTheGraceWithoutClaimingItWasHeard(Failure how) {
        FlakyTextToSpeech flaky = new FlakyTextToSpeech().fail(replyTo("bye"), Failure.FAILS_AFTER_ONE_FRAME)
                .fail(CallPipeline.FALLBACK_GOODBYE, how);
        FakeLeg leg = join(pipelineWith(store, flaky, stt, List.of(endedCalls::add)), FakeLeg.farmer(callId));
        leg.finishPlayback();
        int greetingFrames = leg.played().size();

        session().say("bye");
        executor.runAll();

        assertThat(flaky.spoken()).containsExactly(GREETING, replyTo("bye"), CallPipeline.FALLBACK_GOODBYE);
        long queuedMs = (leg.played().size() - greetingFrames) * (long) FakeTextToSpeech.FRAME_MS;
        assertThat(delays.pendingDelaysMs()).containsExactly(queuedMs + GRACE_MS);

        leg.finishPlayback(); // the partial audio played out; that is still no goodbye
        executor.runAll();
        assertThat(leg.isEnded()).isFalse();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).isEmpty();

        delays.fireAll();

        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo(CallPipeline.GOODBYE_FAILED));
        assertThat(eventsOf(ConsoleEvent.LegEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo(CallPipeline.GOODBYE_FAILED));
        executor.runAll();
        assertThat(leg.hungUpByUs()).isTrue();
        assertThat(endedCalls).singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo(CallPipeline.GOODBYE_FAILED));
        assertThat(flaky.spoken()).as("the fixed goodbye is tried once").hasSize(3);
    }

    @Test
    void theFarmerSpeakingWhileAFailedGoodbyeWaitsKeepsTheCallGoing() {
        FlakyTextToSpeech flaky = new FlakyTextToSpeech().fail(replyTo("bye"), Failure.THROWS_ON_START)
                .fail(CallPipeline.FALLBACK_GOODBYE, Failure.THROWS_ON_START);
        FakeLeg leg = join(pipelineWith(store, flaky, stt, List.of(endedCalls::add)), FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("bye");
        executor.runAll();
        assertThat(delays.pendingDelaysMs()).containsExactly(GRACE_MS);

        session().emitSpeechStart(); // the farmer talks before the call ends
        delays.fireAll();
        executor.runAll();
        assertThat(leg.isEnded()).isFalse();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).isEmpty();

        session().emitTurn("ek aur baat");
        executor.runAll();
        assertThat(flaky.spoken()).containsExactly(GREETING, replyTo("bye"), CallPipeline.FALLBACK_GOODBYE,
                replyTo("ek aur baat"));
        assertThat(leg.isEnded()).isFalse();
    }

    @Test
    void speechToTextThatCannotOpenStillGreetsAndEnds() {
        SpeechToText broken = (legId, role, language, listener) -> {
            throw new IllegalStateException("STT down (test)");
        };
        LegRegistry r = pipelineWith(store, tts, broken, List.of(endedCalls::add));
        FakeLeg leg = join(r, FakeLeg.farmer(callId));
        leg.pushSilence(2);
        leg.remoteHangup();
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING);
        assertThat(endedCalls).hasSize(1);
    }

    @Test
    void failingEndHandlerDoesNotStopTheNextOne() {
        CallEndHandler broken = call -> {
            throw new IllegalStateException("gist writer down (test)");
        };
        LegRegistry r = pipelineWith(store, tts, stt, List.of(broken, endedCalls::add));
        FakeLeg leg = join(r, FakeLeg.farmer(callId));
        leg.remoteHangup();
        executor.runAll();

        assertThat(endedCalls).hasSize(1);
    }

    @Test
    void sttErrorsAreLoggedAndTheCallGoesOn() {
        FakeLeg leg = join(FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().emitError(new IllegalStateException("socket hiccup (test)"));
        session().say("phir se");
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING, replyTo("phir se"));
    }

    // ---- buyer legs ----

    @Test
    void buyerLegsAreLeftAloneUntilMediationExists() {
        registry.register(FakeLeg.buyer(callId));

        assertThat(executor.pending()).isZero();
        assertThat(events).isEmpty();
        assertThat(stt.sessions()).isEmpty();
        assertThat(tts.requests()).isEmpty();
    }

    // ---- test stores ----

    /** Fails the test if the pipeline touches memory at all. */
    private static final class UntouchableStore implements MemoryStore {
        @Override
        public Optional<FarmerMemory> load(CallerKey key) {
            throw new AssertionError("memory must not be read for a withheld caller ID");
        }

        @Override
        public void saveGist(CallerKey key, CallGist gist) {
            throw new AssertionError("memory must not be written for a withheld caller ID");
        }

        @Override
        public void upsertFacts(CallerKey key, List<ProfileFact> facts) {
            throw new AssertionError("memory must not be written for a withheld caller ID");
        }

        @Override
        public boolean forget(CallerKey key, CallId duringCall, long atMs) {
            throw new AssertionError("memory must not be written for a withheld caller ID");
        }
    }

    private static final class FailingStore implements MemoryStore {
        @Override
        public Optional<FarmerMemory> load(CallerKey key) {
            throw new IllegalStateException("store down (test)");
        }

        @Override
        public void saveGist(CallerKey key, CallGist gist) {}

        @Override
        public void upsertFacts(CallerKey key, List<ProfileFact> facts) {}

        @Override
        public boolean forget(CallerKey key, CallId duringCall, long atMs) {
            return false;
        }
    }
}
