package com.example.g_one.agent.pipeline;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Reply;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.TextToSpeech.Synthesis;
import com.example.g_one.core.Turn;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs each farmer leg end to end: load memory, greet, listen, think, speak, stop for barge-in, and hand the transcript
 * to the {@link CallEndHandler}s at hang-up. Buyer legs are ignored until mediation (Phase 2).
 *
 * <p>How one farmer leg works:
 * <ol>
 *   <li>When the leg is announced, publish {@code call_started} and {@code leg_joined}, then on the executor: load
 *       memory by caller key ({@link FarmerMemory#withheld()} without one, and then no {@code memory_loaded}), open the
 *       speech-to-text session and speak the brain's greeting.</li>
 *   <li>The leg's audio goes to the session. Each finished turn is published ({@code turn_heard}) and answered by the
 *       brain on the executor; the reply is published ({@code assistant_said}), synthesised at 8 kHz and played.</li>
 *   <li>Only the newest turn's reply reaches the farmer. Every new turn, barge-in or hang-up moves the leg to a new
 *       "generation"; a reply, a synthesis frame or a pending hang-up from an older generation is dropped.</li>
 *   <li>Barge-in: speech start while the assistant is speaking (synthesising or still playing) cancels the synthesis
 *       and clears the leg's queued audio.</li>
 *   <li>A reply with {@link Reply#endCall()} hangs up once its audio has been played, unless the farmer speaks
 *       first.</li>
 *   <li>When the leg ends: close the session, cancel speech, publish {@code leg_ended} and {@code call_ended}, and run
 *       the end handlers on the executor.</li>
 * </ol>
 *
 * <p>Threading: leg, speech-to-text and synthesis callbacks only update state and hand slow work (memory, brain, end
 * handlers) to the executor, so they never block. Per-leg state is guarded by one lock that is never held while calling
 * out to the leg, a session, a synthesis, the brain or the console bus. Exceptions from any collaborator are logged and
 * never stop the call. Logs carry ids only, never what was said.
 */
public final class CallPipeline {
    private static final Logger log = LoggerFactory.getLogger(CallPipeline.class);

    /** Sample rate the assistant's speech is synthesised at for telephony legs. */
    public static final int TELEPHONY_SAMPLE_RATE = 8000;

    static final String HINDI_APOLOGY = "माफ़ कीजिए, कुछ गड़बड़ हो गई। कृपया फिर से बोलिए।";
    static final String ENGLISH_APOLOGY = "Sorry, something went wrong. Please say that again.";

    private final AgentBrain brain;
    private final SpeechToText speechToText;
    private final TextToSpeech textToSpeech;
    private final MemoryStore memory;
    private final ConsoleBus bus;
    private final List<CallEndHandler> endHandlers;
    private final Executor executor;
    private final LongSupplier clock;

    /**
     * @param endHandlers run in order at each farmer hang-up; may be empty
     * @param executor runs memory loads, brain calls and end handlers; must not run tasks on the submitting thread
     *     (tests use a manual executor that runs them when told)
     * @param clock epoch milliseconds for console events and call times
     */
    public CallPipeline(AgentBrain brain, SpeechToText speechToText, TextToSpeech textToSpeech, MemoryStore memory,
                        ConsoleBus bus, List<CallEndHandler> endHandlers, Executor executor, LongSupplier clock) {
        this.brain = Objects.requireNonNull(brain, "brain");
        this.speechToText = Objects.requireNonNull(speechToText, "speechToText");
        this.textToSpeech = Objects.requireNonNull(textToSpeech, "textToSpeech");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.endHandlers = List.copyOf(Objects.requireNonNull(endHandlers, "endHandlers"));
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Starts handling every leg the registry announces from now on. */
    public void attach(LegRegistry registry) {
        registry.onLegAdded(this::onLegAdded);
    }

    /** Handles one new leg. Returns quickly; the work runs on the executor. */
    void onLegAdded(Leg leg) {
        if (leg.role() != LegRole.FARMER) {
            log.info("Ignoring {} leg {} of call {}: mediation is not built yet", leg.role().wire(), leg.id(),
                    leg.callId());
            return;
        }
        new FarmerLeg(leg).begin();
    }

    /** The spoken apology used when the brain throws or returns nothing, against its contract. */
    static Reply apology(Language language) {
        boolean english = language.code().toLowerCase(Locale.ROOT).startsWith("en");
        return new Reply(english ? ENGLISH_APOLOGY : HINDI_APOLOGY, language, false);
    }

    private long now() {
        return clock.getAsLong();
    }

    private void publish(ConsoleEvent event) {
        bus.publish(event); // the bus already isolates failing subscribers
    }

    /** One farmer leg's state. All mutable fields are guarded by {@link #lock} unless noted. */
    private final class FarmerLeg implements SttListener {
        private final Leg leg;
        private final CallId callId;
        private final long startedMs;
        private final Object lock = new Object();
        /**
         * Held while frames are queued on the leg and while the leg's playback is cleared, so a frame from a
         * superseded generation can never be queued after the clear that was meant to drop it. Lock order is
         * {@code playLock} then {@link #lock} (a clear can complete a playback future whose callback takes
         * {@code lock}); {@code playLock} is never taken while holding {@code lock}.
         */
        private final Object playLock = new Object();
        /** Read without the lock by synthesis sinks; changed only while holding {@link #lock}. */
        private final AtomicLong generation = new AtomicLong();
        private final List<Line> lines = new ArrayList<>();

        private boolean ended;
        private boolean speaking;
        private boolean hungUpByUs;
        private CallContext context;
        private SttSession session;
        private Synthesis synthesis;
        /** The session audio is fed to. Volatile so the audio thread reads it without the lock. */
        private volatile SttSession feedTarget;
        private volatile boolean feedFailureLogged;

        FarmerLeg(Leg leg) {
            this.leg = leg;
            this.callId = leg.callId();
            this.startedMs = now();
        }

        void begin() {
            publish(new ConsoleEvent.CallStarted(callId, startedMs, leg.language().code()));
            publish(ConsoleEvent.LegJoined.of(leg, startedMs));
            leg.onEnded(this::onEnded);
            leg.onAudio(this::onAudio);
            submit("start", this::start);
        }

        // ---- start: memory, speech-to-text session, greeting (on the executor) ----

        private void start() {
            LoadedMemory loaded = loadMemory();
            CallContext ctx = new CallContext(callId, leg, leg.language(), loaded.memory());
            synchronized (lock) {
                if (ended) {
                    return;
                }
                context = ctx;
            }
            if (loaded.publish()) {
                publish(ConsoleEvent.MemoryLoaded.of(callId, loaded.memory(), now()));
            }

            SttSession opened = null;
            try {
                opened = speechToText.open(leg.id(), LegRole.FARMER, leg.language(), this);
            } catch (RuntimeException e) {
                log.error("Could not open speech-to-text for leg {} of call {}; the farmer will not be heard",
                        leg.id(), callId, e);
            }
            boolean endedMeanwhile;
            long greetingGeneration;
            synchronized (lock) {
                endedMeanwhile = ended;
                if (!ended) {
                    session = opened;
                    feedTarget = opened;
                }
                greetingGeneration = generation.get();
            }
            if (endedMeanwhile) {
                closeQuietly(opened);
                return;
            }

            Reply greeting = askBrain("greet", () -> brain.greet(ctx), ctx.language());
            deliver(greetingGeneration, greeting);
        }

        private LoadedMemory loadMemory() {
            Optional<CallerKey> key = leg.callerKey();
            if (key.isEmpty()) {
                return new LoadedMemory(FarmerMemory.withheld(), false);
            }
            try {
                FarmerMemory m = memory.load(key.get()).orElseGet(() -> FarmerMemory.empty(key.get()));
                if (!m.callerKey().equals(key)) {
                    log.error("The memory store returned another caller's memory for call {}; using none", callId);
                    return new LoadedMemory(FarmerMemory.empty(key.get()), false);
                }
                return new LoadedMemory(m, true);
            } catch (RuntimeException e) {
                log.warn("Could not load memory for call {}; treating the farmer as new", callId, e);
                return new LoadedMemory(FarmerMemory.empty(key.get()), false);
            }
        }

        // ---- audio and speech-to-text events (on transport / STT threads; never block) ----

        private void onAudio(AudioFrame frame) {
            SttSession target = feedTarget;
            if (target == null) {
                return; // not open yet, or the leg ended
            }
            try {
                target.feed(frame);
            } catch (RuntimeException e) {
                if (!feedFailureLogged) {
                    feedFailureLogged = true;
                    log.warn("Feeding audio to speech-to-text failed on leg {} (logged once)", leg.id(), e);
                }
            }
        }

        @Override
        public void onSpeechStart() {
            Synthesis toCancel;
            synchronized (lock) {
                if (ended || !speaking) {
                    return;
                }
                generation.incrementAndGet();
                speaking = false;
                toCancel = synthesis;
                synthesis = null;
            }
            log.debug("Barge-in on leg {}", leg.id());
            stopSpeaking(toCancel);
        }

        @Override
        public void onFinalTurn(Turn turn) {
            List<Line> history;
            CallContext ctx;
            long turnGeneration;
            boolean wasSpeaking;
            Synthesis toCancel;
            synchronized (lock) {
                if (ended || context == null) {
                    return;
                }
                history = List.copyOf(lines);
                lines.add(new Line(Speaker.FARMER, turn.text()));
                turnGeneration = generation.incrementAndGet();
                wasSpeaking = speaking;
                speaking = false;
                toCancel = synthesis;
                synthesis = null;
                ctx = context;
            }
            if (wasSpeaking) {
                stopSpeaking(toCancel); // a turn with no speech start first still interrupts the assistant
            }
            publish(ConsoleEvent.TurnHeard.of(callId, turn, now()));
            submit("respond", () -> {
                if (generation.get() != turnGeneration) {
                    return; // a newer turn already arrived; skip the brain call
                }
                Reply reply = askBrain("respond", () -> brain.respond(ctx, history, turn), ctx.language());
                deliver(turnGeneration, reply);
            });
        }

        @Override
        public void onError(Exception error) {
            log.warn("Speech-to-text reported an error on leg {} of call {}", leg.id(), callId, error);
        }

        // ---- speaking ----

        /** Speaks {@code reply} unless a newer turn, a barge-in or the hang-up superseded {@code replyGeneration}. */
        private void deliver(long replyGeneration, Reply reply) {
            synchronized (lock) {
                if (ended || generation.get() != replyGeneration) {
                    log.debug("Dropping a superseded reply on leg {}", leg.id());
                    return;
                }
                lines.add(new Line(Speaker.ASSISTANT, reply.text()));
                speaking = true;
                if (!reply.language().equals(context.language())) {
                    context = new CallContext(callId, leg, reply.language(), context.memory());
                }
            }
            publish(new ConsoleEvent.AssistantSaid(callId, now(), leg.id(), LegRole.FARMER, reply.language().code(),
                    reply.text()));

            Synthesis started;
            try {
                started = textToSpeech.speak(reply.text(), reply.language(), TELEPHONY_SAMPLE_RATE,
                        frame -> playFrame(replyGeneration, frame));
            } catch (RuntimeException e) {
                log.warn("Speech synthesis failed to start on leg {}", leg.id(), e);
                afterSynthesis(replyGeneration, reply);
                return;
            }
            boolean superseded;
            synchronized (lock) {
                superseded = ended || generation.get() != replyGeneration;
                if (!superseded) {
                    synthesis = started;
                }
            }
            if (superseded) {
                cancelQuietly(started); // whoever superseded it already cleared the leg
                return;
            }
            started.done().whenComplete((ignored, error) -> {
                if (error != null && !isCancellation(error)) {
                    log.warn("Speech synthesis failed on leg {}", leg.id(), error);
                }
                afterSynthesis(replyGeneration, reply);
            });
        }

        private void playFrame(long replyGeneration, AudioFrame frame) {
            synchronized (playLock) {
                if (generation.get() != replyGeneration) {
                    return;
                }
                try {
                    leg.play(frame);
                } catch (RuntimeException e) {
                    log.warn("Playing audio failed on leg {}", leg.id(), e);
                }
            }
        }

        /** Every frame of the reply is queued (or synthesis failed): wait for the farmer to hear it. */
        private void afterSynthesis(long replyGeneration, Reply reply) {
            if (generation.get() != replyGeneration) {
                return;
            }
            leg.playbackDone().thenAccept(outcome -> afterPlayback(replyGeneration, reply, outcome));
        }

        private void afterPlayback(long replyGeneration, Reply reply, PlaybackOutcome outcome) {
            boolean hangUp = false;
            synchronized (lock) {
                if (ended || generation.get() != replyGeneration || outcome == PlaybackOutcome.ENDED) {
                    return;
                }
                speaking = false;
                synthesis = null;
                if (reply.endCall()) {
                    hungUpByUs = true;
                    hangUp = true;
                }
            }
            if (hangUp) {
                log.info("The assistant is ending call {}", callId);
                try {
                    leg.hangup();
                } catch (RuntimeException e) {
                    log.warn("Hanging up leg {} failed", leg.id(), e);
                }
            }
        }

        /** Barge-in: no more frames from {@code toCancel}, then drop what the leg has queued. */
        private void stopSpeaking(Synthesis toCancel) {
            cancelQuietly(toCancel);
            synchronized (playLock) {
                try {
                    leg.clearPlayback();
                } catch (RuntimeException e) {
                    log.warn("Clearing playback failed on leg {}", leg.id(), e);
                }
            }
        }

        // ---- the end ----

        private void onEnded() {
            Synthesis toCancel;
            SttSession toClose;
            CallContext ctx;
            List<Line> transcript;
            String reason;
            synchronized (lock) {
                if (ended) {
                    return;
                }
                ended = true;
                generation.incrementAndGet();
                speaking = false;
                toCancel = synthesis;
                synthesis = null;
                toClose = session;
                session = null;
                feedTarget = null;
                ctx = context;
                transcript = List.copyOf(lines);
                lines.clear(); // transcripts live only for the call
                reason = hungUpByUs ? "assistant_hangup" : "hangup";
            }
            cancelQuietly(toCancel);
            closeQuietly(toClose);
            long endedMs = now();
            publish(new ConsoleEvent.LegEnded(callId, endedMs, leg.id(), LegRole.FARMER, reason));
            publish(new ConsoleEvent.CallEnded(callId, endedMs, endedMs - startedMs, reason));
            if (ctx == null) {
                log.info("Call {} ended before it started; no end handlers run", callId);
                return;
            }
            CallEndHandler.EndedCall endedCall =
                    new CallEndHandler.EndedCall(ctx, transcript, startedMs, endedMs, reason);
            submit("end handlers", () -> {
                for (CallEndHandler h : endHandlers) {
                    try {
                        h.callEnded(endedCall);
                    } catch (RuntimeException e) {
                        log.warn("A call end handler failed for call {}", callId, e);
                    }
                }
            });
        }

        // ---- helpers ----

        private Reply askBrain(String what, Supplier<Reply> call, Language language) {
            try {
                Reply reply = call.get();
                if (reply != null) {
                    return reply;
                }
                log.warn("The agent brain returned no reply ({}) on call {}; apologising", what, callId);
            } catch (RuntimeException e) {
                log.warn("The agent brain failed ({}) on call {}; apologising", what, callId, e);
            }
            return apology(language);
        }

        private void submit(String what, Runnable task) {
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.error("Pipeline step '{}' failed on leg {} of call {}", what, leg.id(), callId, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("Pipeline step '{}' was not run for call {}: the executor is shut down", what, callId);
            }
        }

        private void cancelQuietly(Synthesis s) {
            if (s == null) {
                return;
            }
            try {
                s.cancel();
            } catch (RuntimeException e) {
                log.warn("Cancelling speech synthesis failed on leg {}", leg.id(), e);
            }
        }

        private void closeQuietly(SttSession s) {
            if (s == null) {
                return;
            }
            try {
                s.close();
            } catch (RuntimeException e) {
                log.warn("Closing speech-to-text failed on leg {}", leg.id(), e);
            }
        }
    }

    private static boolean isCancellation(Throwable error) {
        Throwable t = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return t instanceof CancellationException;
    }

    /** @param publish false when nothing should be shown: withheld caller ID, or the load failed */
    private record LoadedMemory(FarmerMemory memory, boolean publish) {}
}
