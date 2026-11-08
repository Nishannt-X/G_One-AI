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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
 *   <li>When the leg is announced, publish {@code call_started} and {@code leg_joined}, then in the background: load
 *       memory by caller key ({@link FarmerMemory#withheld()} without one, and then no {@code memory_loaded}), open the
 *       speech-to-text session and speak the brain's greeting.</li>
 *   <li>The leg's audio goes to the session. Each finished turn is published ({@code turn_heard}) and answered by the
 *       brain in the background; the reply is published ({@code assistant_said}), synthesised at 8 kHz and played.</li>
 *   <li>Only the newest turn's reply reaches the farmer. Every new turn, barge-in or hang-up moves the leg to a new
 *       "generation"; a greeting, reply or synthesis frame from an older generation is dropped. The greeting reserves
 *       its generation before the session opens, so a turn heard first replaces it.</li>
 *   <li>Barge-in: speech start while the assistant is speaking (synthesising or still playing) clears the leg's queued
 *       audio and cancels the synthesis.</li>
 *   <li>Speech start while the brain is still preparing a reply (or the greeting) holds that reply: it does not start
 *       playing over the farmer. A final turn that follows discards it, as any newer turn does. If no final turn
 *       arrives within the hold window (a cough, a noise), the held reply plays once it is ready, so the farmer is
 *       never left in silence.</li>
 *   <li>A reply with {@link Reply#endCall()} hangs up once the leg reports it {@link PlaybackOutcome#PLAYED played}, or
 *       when that report has not come within the goodbye's length plus a grace period. A barge-in, a newer turn or a
 *       clear by anyone else cancels the hang-up and the conversation goes on.</li>
 *   <li>A goodbye whose audio is missing or incomplete (synthesis failed to start, failed or was cancelled midway, or
 *       produced no frames, or a frame could not be played) is never counted as heard. The pipeline says the fixed
 *       goodbye for the language once instead. If that fails too, it waits for the audio already queued plus the grace
 *       period and then hangs up with reason {@code goodbye_failed}, unless the farmer speaks first.</li>
 *   <li>When the leg ends: close the session, cancel speech, publish {@code leg_ended} and {@code call_ended}, and run
 *       the end handlers in the background.</li>
 * </ol>
 *
 * <p>Threading: each farmer leg is an actor. Every state change (a new turn, a barge-in, a reply being accepted, a
 * frame being played, playback finishing, the hang-up, the end) runs as a task in the leg's {@link SerialMailbox}, one
 * at a time, so no two of them can interleave. Leg, speech-to-text, synthesis and timer callbacks only post to the
 * mailbox and return. Anything that may block (memory, the brain, opening speech-to-text, starting synthesis,
 * cancelling it, closing the session, hanging up, end handlers) runs on the executor, and its result comes back as a
 * mailbox task tagged with the generation it was started for. Exceptions from any collaborator are logged and never
 * stop the call. Logs carry ids only, never what was said.
 */
public final class CallPipeline {
    private static final Logger log = LoggerFactory.getLogger(CallPipeline.class);

    /** Sample rate the assistant's speech is synthesised at for telephony legs. */
    public static final int TELEPHONY_SAMPLE_RATE = 8000;

    /**
     * How long, beyond the goodbye's own length, the assistant waits for the leg to confirm the farmer heard it before
     * hanging up anyway (a lost Twilio mark must not keep the call open).
     */
    public static final Duration DEFAULT_GOODBYE_GRACE = Duration.ofSeconds(8);

    /**
     * How long a reply held by the farmer's speech start waits for a final turn before it is released and played (a
     * cough must not leave the farmer in silence).
     */
    public static final Duration DEFAULT_HELD_REPLY_RELEASE = Duration.ofMillis(1500);

    /**
     * The fixed goodbye when no per-language one is configured, or looking it up fails: a short English word, used for
     * every language.
     */
    static final String FALLBACK_GOODBYE = "Goodbye.";

    /** Fixed goodbyes when the agent's fixed lines are not available: {@link #FALLBACK_GOODBYE} for every language. */
    public static final Function<Language, String> DEFAULT_FIXED_GOODBYES = language -> FALLBACK_GOODBYE;

    /** Call end reason after a goodbye the farmer heard, or one whose "played" report never came. */
    static final String ASSISTANT_HANGUP = "assistant_hangup";
    /** Call end reason when neither the goodbye nor the fixed goodbye after it could be spoken. */
    static final String GOODBYE_FAILED = "goodbye_failed";

    static final String HINDI_APOLOGY = "माफ़ कीजिए, कुछ गड़बड़ हो गई। कृपया फिर से बोलिए।";
    static final String ENGLISH_APOLOGY = "Sorry, something went wrong. Please say that again.";

    /** Runs a task once after a delay. Tests fire the tasks by hand. */
    @FunctionalInterface
    public interface Delays {
        void schedule(long delayMs, Runnable task);
    }

    private final AgentBrain brain;
    private final SpeechToText speechToText;
    private final TextToSpeech textToSpeech;
    private final MemoryStore memory;
    private final ConsoleBus bus;
    private final List<CallEndHandler> endHandlers;
    private final Executor executor;
    private final LongSupplier clock;
    private final long goodbyeGraceMs;
    private final long heldReplyReleaseMs;
    private final Function<Language, String> fixedGoodbyes;
    private final Delays delays;

    /**
     * A pipeline with the {@link #DEFAULT_GOODBYE_GRACE}, the {@link #DEFAULT_HELD_REPLY_RELEASE} and the
     * {@link #DEFAULT_FIXED_GOODBYES}, whose timers run on {@code executor}.
     *
     * @see #CallPipeline(AgentBrain, SpeechToText, TextToSpeech, MemoryStore, ConsoleBus, List, Executor,
     *     LongSupplier, Duration, Duration, Function, Delays)
     */
    public CallPipeline(AgentBrain brain, SpeechToText speechToText, TextToSpeech textToSpeech, MemoryStore memory,
                        ConsoleBus bus, List<CallEndHandler> endHandlers, Executor executor, LongSupplier clock) {
        this(brain, speechToText, textToSpeech, memory, bus, endHandlers, executor, clock, DEFAULT_GOODBYE_GRACE,
                DEFAULT_HELD_REPLY_RELEASE, DEFAULT_FIXED_GOODBYES, executorDelays(executor));
    }

    /**
     * @param endHandlers run in order at each farmer hang-up; may be empty
     * @param executor runs memory loads, brain calls, speech set-up and tear-down, hang-ups and end handlers; must not
     *     run tasks on the submitting thread (tests use a manual executor that runs them when told)
     * @param clock epoch milliseconds for console events and call times
     * @param goodbyeGrace how long past the goodbye's length to wait for the leg to confirm it was heard
     * @param heldReplyRelease how long a reply held by the farmer's speech start waits for a final turn before it plays
     * @param fixedGoodbyes the fixed goodbye to say, in a language, when the brain's goodbye could not be spoken
     * @param delays runs the goodbye timeouts and the held-reply release
     */
    public CallPipeline(AgentBrain brain, SpeechToText speechToText, TextToSpeech textToSpeech, MemoryStore memory,
                        ConsoleBus bus, List<CallEndHandler> endHandlers, Executor executor, LongSupplier clock,
                        Duration goodbyeGrace, Duration heldReplyRelease, Function<Language, String> fixedGoodbyes,
                        Delays delays) {
        this.brain = Objects.requireNonNull(brain, "brain");
        this.speechToText = Objects.requireNonNull(speechToText, "speechToText");
        this.textToSpeech = Objects.requireNonNull(textToSpeech, "textToSpeech");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.endHandlers = List.copyOf(Objects.requireNonNull(endHandlers, "endHandlers"));
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (goodbyeGrace.isNegative()) {
            throw new IllegalArgumentException("goodbyeGrace must not be negative");
        }
        this.goodbyeGraceMs = goodbyeGrace.toMillis();
        if (heldReplyRelease.isNegative()) {
            throw new IllegalArgumentException("heldReplyRelease must not be negative");
        }
        this.heldReplyReleaseMs = heldReplyRelease.toMillis();
        this.fixedGoodbyes = Objects.requireNonNull(fixedGoodbyes, "fixedGoodbyes");
        this.delays = Objects.requireNonNull(delays, "delays");
    }

    /** Timers that hand their task to {@code executor} when they fire. */
    static Delays executorDelays(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return (delayMs, task) -> CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, executor)
                .execute(task);
    }

    /** Starts handling every leg the registry announces from now on. */
    public void attach(LegRegistry registry) {
        registry.onLegAdded(this::onLegAdded);
    }

    /** Handles one new leg. Returns quickly; the slow work runs on the executor. */
    void onLegAdded(Leg leg) {
        if (leg.role() != LegRole.FARMER) {
            log.info("Ignoring {} leg {} of call {}: mediation is not built yet", leg.role().wire(), leg.id(),
                    leg.callId());
            return;
        }
        new FarmerLeg(leg).begin();
    }

    /** The fixed goodbye for {@code language}; {@link #FALLBACK_GOODBYE} when there is none or the lookup fails. */
    private String fixedGoodbye(Language language) {
        try {
            String line = fixedGoodbyes.apply(language);
            if (line != null && !line.isBlank()) {
                return line;
            }
            log.warn("No fixed goodbye for {}; using the English one", language.code());
        } catch (RuntimeException e) {
            log.warn("Could not look up the fixed goodbye for {}; using the English one", language.code(), e);
        }
        return FALLBACK_GOODBYE;
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

    /**
     * One farmer leg. Methods marked "in the mailbox" run only as {@link #mailbox} tasks and are the only code that
     * reads or writes the plain fields. Methods marked "background" run on the executor.
     */
    private final class FarmerLeg implements SttListener {
        private final Leg leg;
        private final CallId callId;
        private final long startedMs;
        private final SerialMailbox mailbox;
        private final List<Line> lines = new ArrayList<>();

        /**
         * Changed only in the mailbox (so {@code ++} is safe); volatile so background steps can skip work that is
         * already stale. Every new turn, barge-in and the end move it on.
         */
        private volatile long generation;
        /** Set only in the mailbox; volatile for the same reason. */
        private volatile boolean ended;
        /** The session audio is fed to. Volatile so the audio thread reads it without the mailbox. */
        private volatile SttSession feedTarget;
        private volatile boolean feedFailureLogged;

        private long greetingGeneration;
        /** The generation whose reply (or greeting) the brain is preparing; 0 when none is on the way. */
        private long thinkingFor;
        /** A reply the farmer started speaking over before it was ready; null when nothing is held. */
        private Hold hold;
        private CallContext context;
        private SttSession session;
        /** What the assistant is saying right now; null while it is silent. Never set while {@link #hold} is. */
        private Utterance speaking;

        FarmerLeg(Leg leg) {
            this.leg = leg;
            this.callId = leg.callId();
            this.startedMs = now();
            this.mailbox = new SerialMailbox("leg " + leg.id());
        }

        void begin() {
            mailbox.post(this::joined);
            leg.onEnded(() -> mailbox.post(() -> finish("hangup")));
            leg.onAudio(this::onAudio);
        }

        // ---- start: memory, speech-to-text session, greeting ----

        /** In the mailbox. */
        private void joined() {
            publish(new ConsoleEvent.CallStarted(callId, startedMs, leg.language().code()));
            publish(ConsoleEvent.LegJoined.of(leg, startedMs));
            // Reserved now: a turn heard before the greeting is ready moves the generation on and replaces it.
            greetingGeneration = ++generation;
            thinkingFor = greetingGeneration;
            background("load memory", this::loadMemory);
        }

        /** Background. */
        private void loadMemory() {
            if (ended) {
                return; // the farmer hung up before memory was needed
            }
            LoadedMemory loaded = readMemory();
            mailbox.post(() -> memoryLoaded(loaded));
        }

        /** In the mailbox. */
        private void memoryLoaded(LoadedMemory loaded) {
            if (ended) {
                return; // too late: call_ended is out, and nothing about this call may follow it
            }
            context = new CallContext(callId, leg, leg.language(), loaded.memory());
            if (loaded.publish()) {
                publish(ConsoleEvent.MemoryLoaded.of(callId, loaded.memory(), now()));
            }
            background("open speech-to-text", this::openSession);
        }

        /** Background. */
        private void openSession() {
            if (ended) {
                return;
            }
            SttSession opened = null;
            try {
                opened = speechToText.open(leg.id(), LegRole.FARMER, leg.language(), this);
            } catch (RuntimeException e) {
                log.error("Could not open speech-to-text for leg {} of call {}; the farmer will not be heard",
                        leg.id(), callId, e);
            }
            SttSession s = opened;
            mailbox.post(() -> sessionOpened(s));
        }

        /** In the mailbox. {@code opened} is null when opening failed. */
        private void sessionOpened(SttSession opened) {
            if (ended) {
                cleanUp("close speech-to-text", () -> closeQuietly(opened));
                return;
            }
            session = opened;
            feedTarget = opened;
            if (generation != greetingGeneration) {
                log.debug("The farmer spoke first on leg {}; no greeting", leg.id());
                return;
            }
            CallContext ctx = context;
            long g = greetingGeneration;
            background("greet", () -> think(g, "greet", () -> brain.greet(ctx), ctx.language()));
        }

        private LoadedMemory readMemory() {
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
            mailbox.post(this::speechStarted);
        }

        @Override
        public void onFinalTurn(Turn turn) {
            mailbox.post(() -> turnHeard(turn));
        }

        @Override
        public void onError(Exception error) {
            log.warn("Speech-to-text reported an error on leg {} of call {}", leg.id(), callId, error);
        }

        /** In the mailbox. */
        private void speechStarted() {
            if (ended) {
                return;
            }
            if (speaking != null) {
                log.debug("Barge-in on leg {}", leg.id());
                generation++;
                stopSpeaking();
                return;
            }
            if (thinkingFor == generation && hold == null) {
                holdReply();
            }
            // Otherwise the assistant is silent with nothing on the way, or a reply is already held: nothing to do.
        }

        /**
         * In the mailbox, on speech start while the brain prepares the current reply: hold that reply so it does not
         * start over the farmer. A final turn discards it ({@link #turnHeard}); otherwise {@link #holdExpired} lets it
         * play after the hold window. Another speech start during the hold does not extend the window.
         */
        private void holdReply() {
            Hold h = new Hold(generation);
            hold = h;
            log.debug("The farmer started speaking while the assistant was thinking on leg {}; holding the reply",
                    leg.id());
            try {
                delays.schedule(heldReplyReleaseMs, () -> mailbox.post(() -> holdExpired(h)));
            } catch (RuntimeException e) {
                hold = null; // without its release timer a held reply could wait forever, so do not hold
                log.warn("Could not start the held-reply timer on leg {}; not holding the reply", leg.id(), e);
            }
        }

        /** In the mailbox: no final turn followed the speech start in time, so the held reply may play. */
        private void holdExpired(Hold h) {
            if (hold != h) {
                return; // a final turn or the end came first
            }
            hold = null;
            if (ended || generation != h.generation) {
                return; // cannot happen while the hold is current; kept so a held reply never outlives its turn
            }
            if (h.reply == null) {
                log.debug("No turn followed the speech start on leg {}; the reply plays when it is ready", leg.id());
                return;
            }
            log.debug("No turn followed the speech start on leg {}; playing the held reply", leg.id());
            speak(new Utterance(h.reply, false, 0));
        }

        /** In the mailbox. */
        private void turnHeard(Turn turn) {
            if (ended || context == null) {
                return;
            }
            List<Line> history = List.copyOf(lines);
            lines.add(new Line(Speaker.FARMER, turn.text()));
            long g = ++generation;
            thinkingFor = g;
            if (hold != null) {
                log.debug("A turn followed the speech start on leg {}; discarding the held reply", leg.id());
                hold = null;
            }
            if (speaking != null) {
                stopSpeaking(); // a turn with no speech start first still interrupts the assistant
            }
            publish(ConsoleEvent.TurnHeard.of(callId, turn, now()));
            CallContext ctx = context;
            background("respond", () -> think(g, "respond", () -> brain.respond(ctx, history, turn), ctx.language()));
        }

        // ---- speaking ----

        /** Background: asks the brain unless {@code g} is already stale, and hands the reply to the mailbox. */
        private void think(long g, String what, Supplier<Reply> call, Language language) {
            if (ended || generation != g) {
                return; // a newer turn, a barge-in or the hang-up came first; skip the brain call
            }
            Reply reply = askBrain(what, call, language);
            mailbox.post(() -> replyReady(g, reply));
        }

        /**
         * In the mailbox: speaks {@code reply} unless a newer turn, a barge-in or the end superseded {@code g}. While
         * the farmer may still be speaking ({@link #hold}), the reply waits in the hold instead.
         */
        private void replyReady(long g, Reply reply) {
            if (ended || generation != g) {
                log.debug("Dropping a superseded reply on leg {}", leg.id());
                return;
            }
            thinkingFor = 0;
            if (hold != null && hold.generation == g) {
                hold.reply = reply; // played by holdExpired, or discarded by the next final turn
                log.debug("A reply is ready on leg {} but held: the farmer started speaking", leg.id());
                return;
            }
            speak(new Utterance(reply, false, 0));
        }

        /**
         * In the mailbox, for the current generation: records and publishes {@code u}, then starts its synthesis.
         * Every generation change stops the current utterance, so nothing else is being said now.
         */
        private void speak(Utterance u) {
            Reply reply = u.reply;
            lines.add(new Line(Speaker.ASSISTANT, reply.text()));
            if (!reply.language().equals(context.language())) {
                context = new CallContext(callId, leg, reply.language(), context.memory());
            }
            speaking = u;
            publish(new ConsoleEvent.AssistantSaid(callId, now(), leg.id(), LegRole.FARMER, reply.language().code(),
                    reply.text()));
            long g = generation;
            background("speak", () -> synthesise(g, u));
        }

        /** Background: starts the synthesis; its frames and its end come back to the mailbox. */
        private void synthesise(long g, Utterance u) {
            if (ended || generation != g) {
                return; // stopped before synthesis started
            }
            Synthesis started;
            try {
                started = textToSpeech.speak(u.reply.text(), u.reply.language(), TELEPHONY_SAMPLE_RATE,
                        frame -> mailbox.post(() -> framePlayed(u, frame)));
            } catch (RuntimeException e) {
                log.warn("Speech synthesis failed to start on leg {}", leg.id(), e);
                mailbox.post(() -> allQueued(u, false));
                return;
            }
            mailbox.post(() -> synthesisStarted(u, started));
        }

        /** In the mailbox. */
        private void synthesisStarted(Utterance u, Synthesis started) {
            if (speaking != u) {
                cancelLater(started); // stopped meanwhile; whoever stopped it already cleared the leg
                return;
            }
            u.synthesis = started;
            started.done().whenComplete((ignored, error) -> {
                // The pipeline cancels only utterances it already stopped, and allQueued ignores those. So a current
                // utterance that sees a cancellation was cancelled elsewhere, and its audio is incomplete.
                if (error != null) {
                    if (isCancellation(error)) {
                        log.warn("Speech synthesis was cancelled by someone else on leg {}", leg.id());
                    } else {
                        log.warn("Speech synthesis failed on leg {}", leg.id(), error);
                    }
                }
                mailbox.post(() -> allQueued(u, error == null));
            });
        }

        /** In the mailbox. A frame of a stopped utterance is dropped, so it can never follow the clear meant for it. */
        private void framePlayed(Utterance u, AudioFrame frame) {
            if (speaking != u) {
                return;
            }
            try {
                leg.play(frame);
                u.queuedMs += frame.durationMs();
                u.framesQueued++;
            } catch (RuntimeException e) {
                u.playFailed = true;
                log.warn("Playing audio failed on leg {}", leg.id(), e);
            }
        }

        /**
         * In the mailbox. Every frame of {@code u} is queued, or synthesis failed: wait for the farmer to hear it, and
         * for a goodbye start the timeout. A goodbye whose audio is incomplete is never waited on as if it could be
         * heard ({@link #goodbyeNotSpoken}). Frames reach the mailbox before the synthesis completes, so this always
         * runs after the last {@link #framePlayed}.
         *
         * @param synthesised true when the synthesis completed normally; false when it failed to start, failed, or was
         *     cancelled by someone else
         */
        private void allQueued(Utterance u, boolean synthesised) {
            if (speaking != u) {
                return;
            }
            u.synthesis = null;
            boolean complete = synthesised && u.framesQueued > 0 && !u.playFailed;
            if (u.reply.endCall() && !complete) {
                goodbyeNotSpoken(u);
                return;
            }
            CompletableFuture<PlaybackOutcome> heard;
            try {
                heard = leg.playbackDone();
            } catch (RuntimeException e) {
                heard = CompletableFuture.failedFuture(e);
            }
            heard.whenComplete((outcome, error) -> mailbox.post(() -> playbackFinished(u, outcome, error)));
            if (u.reply.endCall()) {
                long waitMs = u.queuedMs + goodbyeGraceMs;
                try {
                    delays.schedule(waitMs, () -> mailbox.post(() -> goodbyeTimedOut(u, waitMs)));
                } catch (RuntimeException e) {
                    log.warn("Could not start the goodbye timeout on leg {}", leg.id(), e);
                }
            }
        }

        /**
         * In the mailbox, for a current goodbye whose audio is missing or incomplete. The first time, say the fixed
         * goodbye for its language instead, after whatever part of the goodbye was queued. It is voiced in the
         * goodbye's language even when the fixed line is the English one. If that fails too, there is no goodbye to
         * wait for: hang up with reason {@link #GOODBYE_FAILED} once the audio already queued and the grace period
         * have passed. Until then the assistant counts as speaking, so the farmer speaking still interrupts it and the
         * call goes on.
         */
        private void goodbyeNotSpoken(Utterance u) {
            if (!u.fallback) {
                log.info("The goodbye on leg {} could not be synthesised in full; saying the fixed goodbye instead",
                        leg.id());
                Language language = u.reply.language();
                speak(new Utterance(new Reply(fixedGoodbye(language), language, true), true, u.queuedMs));
                return;
            }
            long waitMs = u.queuedMs + goodbyeGraceMs;
            log.info("The fixed goodbye on leg {} could not be synthesised in full either; ending call {} in {} ms "
                    + "without a goodbye", leg.id(), callId, waitMs);
            try {
                delays.schedule(waitMs, () -> mailbox.post(() -> unspokenGoodbyeTimedOut(u)));
            } catch (RuntimeException e) {
                log.warn("Could not start the goodbye timeout on leg {}; ending call {} now", leg.id(), callId, e);
                speaking = null;
                hangUp(GOODBYE_FAILED);
            }
        }

        /** In the mailbox. */
        private void unspokenGoodbyeTimedOut(Utterance u) {
            if (speaking != u) {
                return; // the farmer spoke, or the leg ended, in the meantime
            }
            log.info("Ending call {} without a goodbye: none could be spoken on leg {}", callId, leg.id());
            speaking = null;
            hangUp(GOODBYE_FAILED);
        }

        /** In the mailbox. */
        private void playbackFinished(Utterance u, PlaybackOutcome outcome, Throwable error) {
            if (speaking != u) {
                return; // stopped, replaced or ended meanwhile
            }
            if (error != null || outcome == null) {
                // Against the Leg contract. Keep treating it as playing: a barge-in still clears it, and a goodbye
                // still hangs up when its timeout fires.
                log.warn("Leg {} could not report whether the assistant was heard", leg.id(), error);
                return;
            }
            switch (outcome) {
                case PLAYED -> {
                    speaking = null;
                    if (u.reply.endCall()) {
                        log.info("The farmer heard the {}goodbye; the assistant is ending call {}",
                                u.fallback ? "fixed " : "", callId);
                        hangUp(ASSISTANT_HANGUP);
                    }
                }
                case CLEARED -> {
                    // Someone else dropped the audio before it was heard. Never hang up on an unheard goodbye: the
                    // conversation goes on.
                    speaking = null;
                    log.info("Playback on leg {} was cleared before it was heard{}", leg.id(),
                            u.reply.endCall() ? "; not hanging up" : "");
                }
                case ENDED -> { } // the leg's end listener finishes the call
            }
        }

        /** In the mailbox. */
        private void goodbyeTimedOut(Utterance u, long waitedMs) {
            if (speaking != u) {
                return; // heard, cleared, interrupted or ended in time
            }
            log.warn("Leg {} did not confirm the goodbye within {} ms; the assistant is ending call {} anyway",
                    leg.id(), waitedMs, callId);
            speaking = null;
            hangUp(ASSISTANT_HANGUP);
        }

        /**
         * In the mailbox, right after checking that the goodbye still owns the current generation. Ending the call
         * here, in the same task, means no turn or barge-in can slip in between that check and the hang-up: anything
         * that arrives later sees an ended leg. The transport hang-up itself may block, so it runs in the background.
         */
        private void hangUp(String reason) {
            finish(reason);
            cleanUp("hang up", () -> {
                try {
                    leg.hangup();
                } catch (RuntimeException e) {
                    log.warn("Hanging up leg {} failed", leg.id(), e);
                }
            });
        }

        /**
         * In the mailbox, after the generation moved on: drop what the leg has queued, then cancel the synthesis in the
         * background. The clear runs here, before any newer frame can be played, so it can never drop a newer reply.
         * Frames the old synthesis still sends are dropped by {@link #framePlayed}, so cancelling late is safe.
         */
        private void stopSpeaking() {
            Utterance u = speaking;
            speaking = null;
            try {
                leg.clearPlayback();
            } catch (RuntimeException e) {
                log.warn("Clearing playback failed on leg {}", leg.id(), e);
            }
            cancelLater(u.synthesis);
        }

        // ---- the end ----

        /** In the mailbox. Runs once, for the far side's hang-up or ours. */
        private void finish(String reason) {
            if (ended) {
                return;
            }
            ended = true;
            generation++;
            hold = null;
            thinkingFor = 0;
            Utterance u = speaking;
            speaking = null;
            SttSession toClose = session;
            session = null;
            feedTarget = null;
            CallContext ctx = context;
            List<Line> transcript = List.copyOf(lines);
            lines.clear(); // transcripts live only for the call
            if (u != null) {
                cancelLater(u.synthesis);
            }
            cleanUp("close speech-to-text", () -> closeQuietly(toClose));
            long endedMs = now();
            publish(new ConsoleEvent.LegEnded(callId, endedMs, leg.id(), LegRole.FARMER, reason));
            publish(new ConsoleEvent.CallEnded(callId, endedMs, endedMs - startedMs, reason));
            if (ctx == null) {
                log.info("Call {} ended before it started; no end handlers run", callId);
                return;
            }
            CallEndHandler.EndedCall endedCall =
                    new CallEndHandler.EndedCall(ctx, transcript, startedMs, endedMs, reason);
            background("end handlers", () -> {
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

        /** Runs a slow step on the executor; skipped (and logged) when the executor is shut down. */
        private void background(String what, Runnable task) {
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

        /** Releases something on the executor, or right here when the executor is shut down. */
        private void cleanUp(String what, Runnable task) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException e) {
                log.debug("Executor shut down; running '{}' inline on leg {}", what, leg.id());
                task.run();
            }
        }

        private void cancelLater(Synthesis s) {
            if (s == null) {
                return;
            }
            cleanUp("cancel speech", () -> {
                try {
                    s.cancel();
                } catch (RuntimeException e) {
                    log.warn("Cancelling speech synthesis failed on leg {}", leg.id(), e);
                }
            });
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

    /**
     * One reply the assistant is saying, from the moment it is accepted until it was heard, stopped or the leg ended.
     * Fields are touched only in the leg's mailbox; identity tells a current utterance from a stopped one.
     */
    private static final class Utterance {
        final Reply reply;
        /** True for the fixed goodbye said after a goodbye that could not be spoken. */
        final boolean fallback;
        /** Set while the synthesis runs. */
        Synthesis synthesis;
        /** Length of the audio queued on the leg so far, including a failed goodbye's part before its fallback. */
        long queuedMs;
        /** Frames of this utterance queued on the leg. */
        int framesQueued;
        /** True once a frame of this utterance could not be queued. */
        boolean playFailed;

        Utterance(Reply reply, boolean fallback, long queuedBeforeMs) {
            this.reply = reply;
            this.fallback = fallback;
            this.queuedMs = queuedBeforeMs;
        }
    }

    /**
     * A reply held because the farmer started speaking while it was being prepared. Fields are touched only in the
     * leg's mailbox; identity tells the current hold from an expired or discarded one.
     */
    private static final class Hold {
        final long generation;
        /** The reply once the brain has it; null while it is still being prepared. */
        Reply reply;

        Hold(long generation) {
            this.generation = generation;
        }
    }

    private static boolean isCancellation(Throwable error) {
        Throwable t = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return t instanceof CancellationException;
    }

    /** @param publish false when nothing should be shown: withheld caller ID, or the load failed */
    private record LoadedMemory(FarmerMemory memory, boolean publish) {}
}
