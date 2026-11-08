package com.example.g_one.agent.pipeline;

import static com.example.g_one.agent.pipeline.ScriptedBrain.GREETING;
import static com.example.g_one.agent.pipeline.ScriptedBrain.replyTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.pipeline.CallEndHandler.EndedCall;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.TextToSpeech.Synthesis;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Races between the threads that drive one farmer leg: the STT thread, the transport, executor tasks and timers. Each
 * test stops one thread at a {@link Gate} in the middle of a step, lets another thread act, then opens the gate, so the
 * interleaving is forced rather than left to chance. Nothing sleeps; gates time out after {@link #WAIT}.
 */
class CallPipelineRaceTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int RATE = CallPipeline.TELEPHONY_SAMPLE_RATE;
    private static final int MS_PER_CHAR = 50; // FakeTextToSpeech default
    private static final Duration GRACE = Duration.ofSeconds(8);
    private static final Duration HOLD = Duration.ofMillis(1500);

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

    @BeforeEach
    void setUp() {
        bus.subscribe(events::add);
    }

    private LegRegistry pipeline(MemoryStore memory, SpeechToText hearing, TextToSpeech speech) {
        LegRegistry r = new LegRegistry();
        new CallPipeline(brain, hearing, speech, memory, bus, List.of(endedCalls::add), executor, clock::get, GRACE,
                HOLD, CallPipeline.DEFAULT_FIXED_GOODBYES, delays).attach(r);
        return r;
    }

    private LegRegistry pipeline() {
        return pipeline(store, stt, tts);
    }

    /** Registers the leg and runs the start (memory, STT session, greeting). */
    private <L extends Leg> L join(LegRegistry r, L leg) {
        r.register(leg);
        executor.runAll();
        return leg;
    }

    /** A joined farmer who has heard the greeting and said "bye": the goodbye is queued but not heard yet. */
    private FakeLeg farmerHearingTheGoodbye() {
        FakeLeg leg = join(pipeline(), FakeLeg.farmer(callId));
        leg.finishPlayback();
        session().say("bye");
        executor.runAll();
        assertThat(leg.unheardFrames()).isPositive();
        return leg;
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

    private List<String> turnsHeard() {
        return eventsOf(ConsoleEvent.TurnHeard.class).stream().map(ConsoleEvent.TurnHeard::text).toList();
    }

    private static List<AudioFrame> framesFor(String text) {
        return FakeTextToSpeech.framesFor(text, RATE, MS_PER_CHAR);
    }

    private static CompletableFuture<Void> onAnotherThread(Runnable step) {
        return CompletableFuture.runAsync(step, task -> Thread.ofPlatform().daemon().start(task));
    }

    // ---- finding 1: barge-in, cancel, clear and new playback are serialised ----

    @Test
    void aBargeInAndATurnArrivingMidStepWaitTheirTurnAndTheNewestReplySurvives() {
        FakeLeg leg = join(pipeline(), FakeLeg.farmer(callId));
        session().say("first"); // barges in on the greeting (clear 1); the brain call for "first" is queued
        assertThat(leg.clearCount()).isEqualTo(1);
        Gate gate = new Gate();
        bus.subscribe(e -> {
            if (e instanceof ConsoleEvent.AssistantSaid said && said.text().equals(replyTo("first"))) {
                gate.pass();
            }
        });

        // An executor thread accepts the reply to "first" and is held halfway through that step.
        CompletableFuture<Void> worker = onAnotherThread(executor::runNext);
        gate.awaitArrival();

        // Meanwhile the farmer barges in and finishes another turn. Both calls return at once and wait their turn.
        session().emitSpeechStart();
        session().emitTurn("second");
        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(turnsHeard()).containsExactly("first");

        gate.open();
        assertThat(worker).succeedsWithin(WAIT);
        executor.runAll();

        assertThat(turnsHeard()).containsExactly("first", "second");
        assertThat(leg.clearCount()).as("the barge-in cleared the reply to first, once").isEqualTo(2);
        assertThat(spoken()).as("the reply to first was stopped before synthesis").containsExactly(GREETING,
                replyTo("second"));
        assertThat(leg.unheardFrames()).as("no clear came after the newest reply")
                .isEqualTo(framesFor(replyTo("second")).size());
    }

    @Test
    void aCancelThatBlocksHoldsUpNothingAndNeverClearsTheNextReply() {
        Gate gate = new Gate();
        TextToSpeech slowCancel = (text, language, rate, sink) -> {
            Synthesis s = tts.speak(text, language, rate, sink);
            return new Synthesis() {
                @Override
                public void cancel() {
                    gate.pass();
                    s.cancel();
                }

                @Override
                public CompletableFuture<Void> done() {
                    return s.done();
                }
            };
        };
        tts.holdOutput(); // the greeting is still being synthesised
        FakeLeg leg = join(pipeline(store, stt, slowCancel), FakeLeg.farmer(callId));

        session().emitSpeechStart(); // barge-in: the leg is cleared now, the synthesis is cancelled in the background
        assertThat(leg.clearCount()).isEqualTo(1);
        CompletableFuture<Void> canceller = onAnotherThread(executor::runNext);
        gate.awaitArrival(); // the cancel is stuck

        session().emitTurn("ruko");
        executor.runAll(); // the reply is accepted and synthesis starts while the cancel is still stuck
        tts.releaseAll(); // the greeting's frames arrive late, then the reply's

        assertThat(leg.played()).as("no late greeting frame reached the leg").isEqualTo(framesFor(replyTo("ruko")));
        gate.open();
        assertThat(canceller).succeedsWithin(WAIT);
        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(leg.unheardFrames()).isEqualTo(framesFor(replyTo("ruko")).size());
    }

    // ---- finding 2: the greeting is reserved before STT opens; greeting and reply never overlap ----

    @Test
    void aTurnHeardWhileTheSessionOpensReplacesTheGreeting() {
        Gate gate = new Gate();
        SpeechToText slowOpen = (legId, role, language, listener) -> {
            FakeSpeechToText.Session s = stt.open(legId, role, language, listener);
            gate.pass(); // the session is live, but open() has not returned yet
            return s;
        };
        FakeLeg leg = FakeLeg.farmer(callId);
        pipeline(store, slowOpen, tts).register(leg);
        executor.runNext(); // memory
        CompletableFuture<Void> opener = onAnotherThread(executor::runNext);
        gate.awaitArrival();

        session().emitTurn("haan");
        gate.open();
        assertThat(opener).succeedsWithin(WAIT);
        executor.runAll();

        assertThat(brain.greets).as("the greeting was never asked for").isEmpty();
        assertThat(spoken()).containsExactly(replyTo("haan"));
        assertThat(leg.played()).isEqualTo(framesFor(replyTo("haan")));
        assertThat(brain.responds.get(0).history()).isEmpty();
    }

    @Test
    void aTurnHeardWhileTheGreetingIsPreparedDropsTheGreeting() {
        brain.whileGreeting = () -> session().emitTurn("haan");

        FakeLeg leg = join(pipeline(), FakeLeg.farmer(callId));

        assertThat(brain.greets).hasSize(1);
        assertThat(spoken()).containsExactly(replyTo("haan"));
        assertThat(leg.played()).isEqualTo(framesFor(replyTo("haan")));
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).extracting(ConsoleEvent.AssistantSaid::text)
                .containsExactly(replyTo("haan"));
        assertThat(brain.responds.get(0).history()).as("the dropped greeting is not in the transcript").isEmpty();
    }

    // ---- finding 3: the hang-up is serialised with turns and barge-in ----

    @Test
    void aTurnArrivingWhileTheAssistantHangsUpIsIgnored() {
        FakeLeg leg = farmerHearingTheGoodbye();
        Gate gate = new Gate();
        bus.subscribe(e -> {
            if (e instanceof ConsoleEvent.LegEnded) {
                gate.pass();
            }
        });

        CompletableFuture<Void> transport = onAnotherThread(leg::finishPlayback); // the goodbye was heard
        gate.awaitArrival(); // ...and the hang-up is halfway done

        session().emitTurn("ek aur baat");
        gate.open();
        assertThat(transport).succeedsWithin(WAIT);
        executor.runAll();

        assertThat(leg.isEnded()).isTrue();
        assertThat(leg.hungUpByUs()).isTrue();
        assertThat(turnsHeard()).containsExactly("bye");
        assertThat(brain.responds).extracting(c -> c.latest().text()).containsExactly("bye");
        assertThat(endedCalls).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo("assistant_hangup");
            assertThat(c.transcript()).last().isEqualTo(new Line(Speaker.ASSISTANT, replyTo("bye")));
        });
    }

    @Test
    void aTurnAfterTheHangUpDecisionButBeforeTheTransportHangsUpIsIgnored() {
        FakeLeg leg = farmerHearingTheGoodbye();

        leg.finishPlayback();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).hasSize(1);
        assertThat(leg.isEnded()).as("the transport hang-up is still queued").isFalse();
        session().emitTurn("ek aur baat");
        executor.runAll();

        assertThat(leg.hungUpByUs()).isTrue();
        assertThat(turnsHeard()).containsExactly("bye");
        assertThat(brain.responds).extracting(c -> c.latest().text()).containsExactly("bye");
    }

    // ---- finding 4: a bounded wait for the goodbye ----

    @Test
    void aLostPlaybackReportStillHangsUpWhenTheGoodbyeTimesOut() {
        FakeLeg leg = farmerHearingTheGoodbye();
        long goodbyeMs = (long) framesFor(replyTo("bye")).size() * FakeTextToSpeech.FRAME_MS;
        assertThat(delays.pendingDelaysMs()).containsExactly(goodbyeMs + GRACE.toMillis());

        delays.fireAll(); // the leg never reported the goodbye as heard
        executor.runAll();

        assertThat(leg.isEnded()).isTrue();
        assertThat(leg.hungUpByUs()).isTrue();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("assistant_hangup"));
    }

    @Test
    void theGoodbyeTimeoutDoesNothingOnceTheFarmerSpokeOverTheGoodbye() {
        FakeLeg leg = farmerHearingTheGoodbye();

        session().emitSpeechStart();
        delays.fireAll();
        executor.runAll();

        assertThat(leg.isEnded()).isFalse();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).isEmpty();
    }

    @Test
    void anExceptionalPlaybackReportWaitsForTheTimeoutBeforeHangingUp() {
        HookedLeg leg = join(pipeline(), new HookedLeg(FakeLeg.farmer(callId)));
        leg.fake.finishPlayback();
        leg.playbackDone = () -> CompletableFuture.failedFuture(new IllegalStateException("mark lost (test)"));

        session().say("bye");
        executor.runAll();
        assertThat(leg.fake.isEnded()).as("not before the farmer could hear it").isFalse();

        delays.fireAll();
        executor.runAll();
        assertThat(leg.fake.isEnded()).isTrue();
        assertThat(leg.fake.hungUpByUs()).isTrue();
    }

    // ---- finding 5: only PLAYED (or the timeout) hangs up ----

    @Test
    void aGoodbyeClearedBySomeoneElseDoesNotHangUpAndTheCallGoesOn() {
        FakeLeg leg = farmerHearingTheGoodbye();

        leg.clearPlayback(); // another part of the system dropped the goodbye before it was heard
        delays.fireAll();
        executor.runAll();
        assertThat(leg.isEnded()).isFalse();
        assertThat(eventsOf(ConsoleEvent.CallEnded.class)).isEmpty();

        session().say("ek aur baat");
        executor.runAll();
        leg.finishPlayback();
        assertThat(spoken()).containsExactly(GREETING, replyTo("bye"), replyTo("ek aur baat"));
        assertThat(leg.isEnded()).isFalse();
    }

    // ---- review round 2, finding 1: speech start while the brain thinks holds the reply ----

    @Test
    void aSpeechStartWhileTheBrainThinksOnAnotherThreadHoldsTheReplyUntilTheWindowEnds() {
        FakeLeg leg = join(pipeline(), FakeLeg.farmer(callId));
        leg.finishPlayback();
        Gate gate = new Gate();
        brain.whileThinking = text -> gate.pass();
        session().say("pehla");

        CompletableFuture<Void> thinker = onAnotherThread(executor::runNext); // the brain is mid-answer
        gate.awaitArrival();
        session().emitSpeechStart(); // the farmer starts again on the STT thread
        gate.open();
        assertThat(thinker).succeedsWithin(WAIT);

        assertThat(executor.pending()).as("the ready reply is held, not synthesised").isZero();
        assertThat(spoken()).containsExactly(GREETING);
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).hasSize(1);
        assertThat(delays.pendingDelaysMs()).containsExactly(HOLD.toMillis());

        delays.fireAll(); // no final turn followed: a cough
        executor.runAll();
        assertThat(spoken()).containsExactly(GREETING, replyTo("pehla"));
        assertThat(leg.unheardFrames()).isEqualTo(framesFor(replyTo("pehla")).size());
    }

    @Test
    void aTurnRacingTheBrainOnAnotherThreadDiscardsTheHeldReply() {
        FakeLeg leg = join(pipeline(), FakeLeg.farmer(callId));
        leg.finishPlayback();
        Gate gate = new Gate();
        brain.whileThinking = text -> {
            if (text.equals("pehla")) {
                gate.pass();
            }
        };
        session().say("pehla");

        CompletableFuture<Void> thinker = onAnotherThread(executor::runNext);
        gate.awaitArrival();
        session().emitSpeechStart();
        CompletableFuture<Void> farmer = onAnotherThread(() -> session().emitTurn("doosra"));
        assertThat(farmer).succeedsWithin(WAIT);
        gate.open();
        assertThat(thinker).succeedsWithin(WAIT);
        executor.runAll();
        delays.fireAll();
        executor.runAll();

        assertThat(spoken()).containsExactly(GREETING, replyTo("doosra"));
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).extracting(ConsoleEvent.AssistantSaid::text)
                .containsExactly(GREETING, replyTo("doosra"));
        assertThat(leg.clearCount()).isZero();
    }

    // ---- finding 6: the start steps check for the end ----

    @Test
    void aLegThatEndsBeforeMemoryIsNeededNeverTouchesTheStore() {
        CountingStore counting = new CountingStore(null);
        FakeLeg leg = FakeLeg.farmer(callId);
        pipeline(counting, stt, tts).register(leg);

        leg.remoteHangup();
        executor.runAll();

        assertThat(counting.loads).hasValue(0);
        assertThat(events).hasExactlyElementsOfTypes(ConsoleEvent.CallStarted.class, ConsoleEvent.LegJoined.class,
                ConsoleEvent.LegEnded.class, ConsoleEvent.CallEnded.class);
    }

    @Test
    void aLegThatEndsWhileMemoryLoadsShowsNothingAfterTheEnd() {
        Gate gate = new Gate();
        CountingStore slow = new CountingStore(gate);
        FakeLeg leg = FakeLeg.farmer(callId);
        pipeline(slow, stt, tts).register(leg);
        CompletableFuture<Void> loader = onAnotherThread(executor::runNext);
        gate.awaitArrival();

        leg.remoteHangup(); // while the store is still answering
        gate.open();
        assertThat(loader).succeedsWithin(WAIT);
        executor.runAll();

        assertThat(events).as("no memory_loaded after call_ended").hasExactlyElementsOfTypes(
                ConsoleEvent.CallStarted.class, ConsoleEvent.LegJoined.class, ConsoleEvent.LegEnded.class,
                ConsoleEvent.CallEnded.class);
        assertThat(stt.sessions()).isEmpty();
        assertThat(brain.greets).isEmpty();
        assertThat(endedCalls).isEmpty();
    }

    @Test
    void aLegThatEndsWhileTheSessionOpensClosesItAndIsNotGreeted() {
        Gate gate = new Gate();
        SpeechToText slowOpen = (legId, role, language, listener) -> {
            FakeSpeechToText.Session s = stt.open(legId, role, language, listener);
            gate.pass();
            return s;
        };
        FakeLeg leg = FakeLeg.farmer(callId);
        pipeline(store, slowOpen, tts).register(leg);
        executor.runNext(); // memory
        CompletableFuture<Void> opener = onAnotherThread(executor::runNext);
        gate.awaitArrival();

        leg.remoteHangup();
        gate.open();
        assertThat(opener).succeedsWithin(WAIT);
        executor.runAll();

        assertThat(session().isClosed()).isTrue();
        assertThat(brain.greets).isEmpty();
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).isEmpty();
        assertThat(events).last().isInstanceOf(ConsoleEvent.CallEnded.class);
    }

    // ---- helpers ----

    /** Stops the thread that calls {@link #pass} until the test calls {@link #open}. Passing after that is free. */
    private static final class Gate {
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch opened = new CountDownLatch(1);

        void pass() {
            arrived.countDown();
            await(opened);
        }

        void awaitArrival() {
            await(arrived);
        }

        void open() {
            opened.countDown();
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("Timed out waiting at a test gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** An in-memory store that counts loads and can hold each one at a gate. */
    private static final class CountingStore implements MemoryStore {
        private final InMemoryMemoryStore delegate = new InMemoryMemoryStore();
        private final Gate gate;
        final AtomicInteger loads = new AtomicInteger();

        CountingStore(Gate gate) {
            this.gate = gate;
        }

        @Override
        public Optional<FarmerMemory> load(CallerKey key) {
            loads.incrementAndGet();
            if (gate != null) {
                gate.pass();
            }
            return delegate.load(key);
        }

        @Override
        public void saveGist(CallerKey key, CallGist gist) {
            delegate.saveGist(key, gist);
        }

        @Override
        public void upsertFacts(CallerKey key, List<ProfileFact> facts) {
            delegate.upsertFacts(key, facts);
        }

        @Override
        public boolean forget(CallerKey key, CallId duringCall, long atMs) {
            return delegate.forget(key, duringCall, atMs);
        }
    }
}
