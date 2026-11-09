package com.example.g_one.agent.mediation;

import com.example.g_one.agent.caution.Caution;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.mediation.Translator.Translation;
import com.example.g_one.agent.pipeline.CallPipeline;
import com.example.g_one.agent.pipeline.SerialMailbox;
import com.example.g_one.agent.tools.mandi.MandiQuote;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.WhisperKind;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.TextToSpeech.Synthesis;
import com.example.g_one.core.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mediation (#26): once a buyer leg joins a call, every finished turn on either leg is screened, translated and spoken
 * on the other leg, and the farmer may get a private whisper. See docs/call-flow.md ("Mediation", "Mediation rules").
 *
 * <p>Per turn, in this order:
 * <ol>
 *   <li>Publish {@code turn_heard}.</li>
 *   <li>Background: one caution classifier call ({@link CautionCheck}); the translation into the listener's language
 *       ({@link Translator}; same language means the transcript is re-voiced verbatim, with no model call); for a buyer
 *       turn, the offer read-out and the price comparison ({@link PriceWhisper}); then each whisper's fixed English text
 *       is translated for the farmer. Nothing said is ever copied into a whisper, so a secret is never echoed.</li>
 *   <li>Speak, one step at a time. A threat or secret request from the buyer: the buyer hears "One moment please" once
 *       and the farmer hears the caution first, then the translated turn. Otherwise the translated turn goes to the
 *       listener first, and a price whisper (after a buyer turn) or a caution about a secret the farmer said aloud
 *       follows on the farmer leg, with the buyer's one "One moment please".</li>
 * </ol>
 *
 * <p>Rules this class keeps:
 * <ul>
 *   <li>Raw audio never crosses legs: each leg hears only speech synthesised here.</li>
 *   <li>One floor at a time: turns are handled strictly in the order they were heard, one at a time. A turn heard while
 *       an earlier one is still being prepared or played waits in a queue; it is never dropped.</li>
 *   <li>A step waits until its leg reports the audio heard ({@link Leg#playbackDone}), cleared or ended, or until its
 *       length plus a grace period has passed (a lost Twilio mark must not stall the call).</li>
 *   <li>Barge-in stops playback only on the speaker's own leg.</li>
 *   <li>Whispers go to the farmer leg only. A caution meant for the buyer ({@link Caution#recipient}) is not spoken in
 *       this version; it is logged by kind only.</li>
 *   <li>Nothing said during mediation reaches the agent brain or its tools: the model calls here offer no tools.</li>
 * </ul>
 *
 * <p>Threading: each mediated call is an actor. Every state change runs as a task in the call's {@link SerialMailbox};
 * model calls, opening speech-to-text and starting synthesis run on the executor and post their result back. Logs carry
 * ids and caution kinds only, never what was said.
 */
public final class Mediator implements CallPipeline.Mediation {
    private static final Logger log = LoggerFactory.getLogger(Mediator.class);

    /** How long past a step's audio length to wait for the leg to confirm it was heard before moving on. */
    public static final Duration DEFAULT_PLAYBACK_GRACE = Duration.ofSeconds(4);

    /** The language of our fixed caution and price wording before it is translated for the farmer. */
    static final Language ENGLISH = new Language("en-IN", "English", "Kaveri");

    private final SpeechToText speechToText;
    private final TextToSpeech textToSpeech;
    private final CautionCheck cautions;
    private final Translator translator;
    private final PriceWhisper prices;
    private final ConsoleBus bus;
    private final Executor executor;
    private final LongSupplier clock;
    private final long graceMs;
    private final CallPipeline.Delays delays;

    /**
     * @param executor runs model calls, speech set-up and synthesis starts; may run them on the calling thread (tests)
     * @param clock epoch milliseconds for console events
     * @param playbackGrace how long past a step's length to wait for the leg's "heard" report
     * @param delays runs the playback timeouts
     */
    public Mediator(SpeechToText speechToText, TextToSpeech textToSpeech, ChatClient chat, ObjectMapper json,
                    MandiQuote mandi, ConsoleBus bus, Executor executor, LongSupplier clock, Duration playbackGrace,
                    CallPipeline.Delays delays) {
        this.speechToText = Objects.requireNonNull(speechToText, "speechToText");
        this.textToSpeech = Objects.requireNonNull(textToSpeech, "textToSpeech");
        MediationLlm llm = new MediationLlm(chat, json);
        this.cautions = new CautionCheck(llm);
        this.translator = new Translator(llm);
        this.prices = new PriceWhisper(llm, Objects.requireNonNull(mandi, "mandi"));
        this.bus = Objects.requireNonNull(bus, "bus");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (playbackGrace.isNegative()) {
            throw new IllegalArgumentException("playbackGrace must not be negative");
        }
        this.graceMs = playbackGrace.toMillis();
        this.delays = Objects.requireNonNull(delays, "delays");
    }

    @Override
    public SttListener start(Leg farmer, Leg buyer, Runnable onEnded) {
        if (farmer.role() != LegRole.FARMER || buyer.role() != LegRole.BUYER || !farmer.callId().equals(buyer.callId())) {
            throw new IllegalArgumentException("Mediation needs the farmer and buyer legs of one call");
        }
        MediatedCall call = new MediatedCall(farmer, buyer, Objects.requireNonNull(onEnded, "onEnded"));
        call.begin();
        return call.farmerListener;
    }

    private void publish(ConsoleEvent event) {
        bus.publish(event);
    }

    private long now() {
        return clock.getAsLong();
    }

    /** One mediated call. Plain fields are touched only by {@link #mailbox} tasks. */
    private final class MediatedCall {
        private final Leg farmer;
        private final Leg buyer;
        private final CallId callId;
        private final Runnable onEnded;
        private final SerialMailbox mailbox;
        final SttListener farmerListener;
        private final SttListener buyerListener;

        /** Turns heard and not yet handled, oldest first. */
        private final Deque<Turn> waiting = new ArrayDeque<>();
        /** Steps of the current turn not yet started. */
        private final Deque<Step> steps = new ArrayDeque<>();
        /** The newest utterance on each leg, for barge-in. */
        private final Map<LegRole, Utterance> speaking = new EnumMap<>(LegRole.class);
        /** True from the moment a turn is taken off {@link #waiting} until its last step finished. */
        private boolean busy;
        /** The step being waited on; null between steps. */
        private Utterance current;
        private SttSession buyerSession;

        private volatile boolean ended;
        private volatile SttSession buyerFeed;
        private volatile boolean feedFailureLogged;

        MediatedCall(Leg farmer, Leg buyer, Runnable onEnded) {
            this.farmer = farmer;
            this.buyer = buyer;
            this.callId = farmer.callId();
            this.onEnded = onEnded;
            this.mailbox = new SerialMailbox("mediation " + callId);
            this.farmerListener = new Listener(LegRole.FARMER);
            this.buyerListener = new Listener(LegRole.BUYER);
        }

        void begin() {
            mailbox.post(this::joined);
            buyer.onEnded(() -> mailbox.post(() -> end(LegRole.BUYER)));
            farmer.onEnded(() -> mailbox.post(() -> end(LegRole.FARMER)));
            buyer.onAudio(this::onBuyerAudio);
        }

        private Leg leg(LegRole role) {
            return role == LegRole.FARMER ? farmer : buyer;
        }

        // ---- start ----

        /** In the mailbox. */
        private void joined() {
            if (ended) {
                return;
            }
            log.info("Mediation started on call {} (farmer leg {}, buyer leg {})", callId, farmer.id(), buyer.id());
            publish(ConsoleEvent.LegJoined.of(buyer, now()));
            background("open buyer speech-to-text", () -> {
                SttSession opened = null;
                try {
                    opened = speechToText.open(buyer.id(), LegRole.BUYER, buyer.language(), buyerListener);
                } catch (RuntimeException e) {
                    log.error("Could not open speech-to-text for buyer leg {} of call {}", buyer.id(), callId, e);
                }
                SttSession s = opened;
                mailbox.post(() -> buyerSessionOpened(s));
            });
        }

        /** In the mailbox. */
        private void buyerSessionOpened(SttSession opened) {
            if (ended) {
                closeQuietly(opened);
                return;
            }
            buyerSession = opened;
            buyerFeed = opened;
        }

        private void onBuyerAudio(AudioFrame frame) {
            SttSession target = buyerFeed;
            if (target == null) {
                return;
            }
            try {
                target.feed(frame);
            } catch (RuntimeException e) {
                if (!feedFailureLogged) {
                    feedFailureLogged = true;
                    log.warn("Feeding buyer audio to speech-to-text failed on leg {} (logged once)", buyer.id(), e);
                }
            }
        }

        // ---- turns ----

        /** In the mailbox. Barge-in: stop what this person is hearing, and nothing else. */
        private void speechStarted(LegRole role) {
            if (ended) {
                return;
            }
            Utterance u = speaking.get(role);
            if (u != null && !u.finished) {
                log.debug("Barge-in on {} leg of call {}", role.wire(), callId);
                try {
                    leg(role).clearPlayback();
                } catch (RuntimeException e) {
                    log.warn("Clearing playback failed on {} leg of call {}", role.wire(), callId, e);
                }
                finish(u);
            }
        }

        /** In the mailbox. */
        private void turnHeard(Turn turn) {
            if (ended || turn.text().isBlank()) {
                return;
            }
            publish(ConsoleEvent.TurnHeard.of(callId, turn, now()));
            waiting.addLast(turn);
            next();
        }

        /** In the mailbox: takes the oldest waiting turn when the floor is free. */
        private void next() {
            if (ended || busy || waiting.isEmpty()) {
                return;
            }
            busy = true;
            Turn turn = waiting.pollFirst();
            boolean started = background("prepare turn", () -> {
                List<Step> planned = List.of();
                try {
                    planned = prepare(turn);
                } finally {
                    List<Step> p = planned;
                    mailbox.post(() -> stepsReady(p));
                }
            });
            if (!started) {
                busy = false;
            }
        }

        /**
         * Background: everything the model has to say about one turn, turned into steps. Model trouble never throws:
         * no caution, no price whisper, and an apology to the speaker instead of a guessed translation.
         */
        private List<Step> prepare(Turn turn) {
            LegRole speaker = turn.role();
            LegRole listener = speaker.other();
            Language from = turn.language();
            Language to = leg(listener).language();
            Language farmerLanguage = farmer.language();

            Set<Caution> found = cautions.check(turn.text(), speaker, from.code());
            Optional<Translation> translation = translator.translate(turn.text(), from, to);
            Optional<String> priceBody = speaker == LegRole.BUYER
                    ? prices.whisperFor(turn.text(), from.code()) : Optional.empty();

            List<Step> before = new ArrayList<>();
            List<Step> after = new ArrayList<>();
            for (Caution c : found) {
                if (c.recipient(speaker) != LegRole.FARMER) {
                    log.info("A {} caution for the buyer on call {} is not spoken: whispers are farmer-only",
                            c.wire(), callId);
                    continue;
                }
                Step whisper = whisper(WhisperKind.CAUTION, c.text(speaker), farmerLanguage);
                // A threat or request from the buyer: the farmer hears the caution before the buyer's words.
                (speaker == LegRole.BUYER ? before : after).add(whisper);
            }
            priceBody.ifPresent(body -> after.add(whisper(WhisperKind.PRICE_REFERENCE, body, farmerLanguage)));

            List<Step> plan = new ArrayList<>();
            if (!before.isEmpty()) {
                plan.add(hold());
                plan.addAll(before);
            }
            if (translation.isPresent()) {
                plan.add(translated(turn, listener, to, translation.get()));
            } else {
                log.warn("Could not translate a {} turn on call {}; asking the speaker to repeat", speaker.wire(), callId);
                Language own = leg(speaker).language();
                plan.add(said(speaker, MediationLines.notTranslated(own), own));
            }
            if (!after.isEmpty()) {
                if (before.isEmpty()) {
                    plan.add(hold());
                }
                plan.addAll(after);
            }
            return plan;
        }

        /** Background: a whisper for the farmer from our own English wording, translated, with the fixed opening. */
        private Step whisper(WhisperKind kind, String englishBody, Language language) {
            Optional<Translation> t = translator.translate(englishBody, ENGLISH, language);
            Language spokenIn = t.isPresent() ? language : ENGLISH;
            String opening = MediationLines.whisperOpening(spokenIn);
            String text = opening + t.map(Translation::text).orElse(englishBody);
            String spoken = opening + t.map(Translation::spoken).orElse(englishBody);
            return new Step(LegRole.FARMER, spoken, spokenIn, true, () -> publish(
                    new ConsoleEvent.WhisperSent(callId, now(), farmer.id(), kind, spokenIn.code(), text)));
        }

        /** The buyer's "One moment please" while the farmer hears a whisper; not waited on. */
        private Step hold() {
            Language language = buyer.language();
            String line = MediationLines.hold(language);
            return new Step(LegRole.BUYER, line, language, false, () -> publish(new ConsoleEvent.AssistantSaid(
                    callId, now(), buyer.id(), LegRole.BUYER, language.code(), line)));
        }

        private Step translated(Turn turn, LegRole listener, Language to, Translation t) {
            return new Step(listener, t.spoken(), to, true, () -> publish(new ConsoleEvent.TurnTranslated(callId,
                    now(), turn.legId(), turn.role(), listener, turn.language().code(), to.code(), t.text(),
                    turn.startedMs())));
        }

        private Step said(LegRole role, String line, Language language) {
            return new Step(role, line, language, true, () -> publish(new ConsoleEvent.AssistantSaid(callId, now(),
                    leg(role).id(), role, language.code(), line)));
        }

        // ---- speaking ----

        /** In the mailbox. */
        private void stepsReady(List<Step> planned) {
            if (ended) {
                return;
            }
            steps.addAll(planned);
            nextStep();
        }

        /** In the mailbox: starts steps until one must be waited on; frees the floor after the last. */
        private void nextStep() {
            while (!ended && current == null) {
                Step s = steps.pollFirst();
                if (s == null) {
                    busy = false;
                    next();
                    return;
                }
                Utterance u = new Utterance(s);
                s.announce().run();
                speaking.put(s.to(), u);
                background("speak", () -> synthesise(u));
                if (s.waitForIt()) {
                    current = u;
                }
            }
        }

        /** Background. */
        private void synthesise(Utterance u) {
            if (ended || u.finished) {
                return;
            }
            Synthesis started;
            try {
                started = textToSpeech.speak(u.step.text(), u.step.language(), CallPipeline.TELEPHONY_SAMPLE_RATE,
                        frame -> mailbox.post(() -> framePlayed(u, frame)));
            } catch (RuntimeException e) {
                log.warn("Speech synthesis failed to start on {} leg of call {}", u.step.to().wire(), callId, e);
                mailbox.post(() -> finish(u));
                return;
            }
            mailbox.post(() -> synthesisStarted(u, started));
        }

        /** In the mailbox. */
        private void synthesisStarted(Utterance u, Synthesis started) {
            if (u.finished) {
                cancelLater(started);
                return;
            }
            u.synthesis = started;
            started.done().whenComplete((ignored, error) -> mailbox.post(() -> allQueued(u, error)));
        }

        /** In the mailbox. */
        private void framePlayed(Utterance u, AudioFrame frame) {
            if (u.finished || ended || frame.samples() == 0) {
                return;
            }
            try {
                leg(u.step.to()).play(frame);
                u.queuedMs += frame.durationMs();
            } catch (RuntimeException e) {
                log.warn("Playing audio failed on {} leg of call {}", u.step.to().wire(), callId, e);
            }
        }

        /** In the mailbox: synthesis is over; wait for the leg to play it, with a timeout. */
        private void allQueued(Utterance u, Throwable error) {
            if (u.finished) {
                return;
            }
            u.synthesis = null;
            if (error != null) {
                log.warn("Speech synthesis failed on {} leg of call {}", u.step.to().wire(), callId, error);
            }
            CompletableFuture<PlaybackOutcome> heard;
            try {
                heard = leg(u.step.to()).playbackDone();
            } catch (RuntimeException e) {
                heard = CompletableFuture.failedFuture(e);
            }
            heard.whenComplete((outcome, failure) -> mailbox.post(() -> finish(u)));
            long waitMs = u.queuedMs + graceMs;
            try {
                delays.schedule(waitMs, () -> mailbox.post(() -> {
                    if (!u.finished) {
                        log.warn("The {} leg of call {} did not confirm playback within {} ms; moving on",
                                u.step.to().wire(), callId, waitMs);
                        finish(u);
                    }
                }));
            } catch (RuntimeException e) {
                log.warn("Could not start the playback timeout on call {}", callId, e);
            }
        }

        /** In the mailbox: {@code u} was heard, cleared, interrupted, timed out or failed. Idempotent. */
        private void finish(Utterance u) {
            if (u.finished) {
                return;
            }
            u.finished = true;
            speaking.remove(u.step.to(), u);
            cancelLater(u.synthesis);
            u.synthesis = null;
            if (current == u) {
                current = null;
                nextStep();
            }
        }

        // ---- the end ----

        /** In the mailbox. Runs once, when either leg ends. */
        private void end(LegRole whoEnded) {
            if (ended) {
                return;
            }
            ended = true;
            waiting.clear();
            steps.clear();
            current = null;
            for (Utterance u : List.copyOf(speaking.values())) {
                u.finished = true;
                cancelLater(u.synthesis);
            }
            speaking.clear();
            SttSession toClose = buyerSession;
            buyerSession = null;
            buyerFeed = null;
            cleanUp(() -> closeQuietly(toClose));
            log.info("Mediation ended on call {}: the {} leg ended", callId, whoEnded.wire());
            if (whoEnded == LegRole.BUYER) {
                // The farmer is still on the line: the call goes back to the assistant.
                publish(new ConsoleEvent.LegEnded(callId, now(), buyer.id(), LegRole.BUYER, "hangup"));
                onEnded.run();
            } else {
                // call_ended is already out for the farmer leg, so nothing more is published for this call.
                cleanUp(() -> {
                    try {
                        buyer.hangup();
                    } catch (RuntimeException e) {
                        log.warn("Hanging up buyer leg {} failed", buyer.id(), e);
                    }
                });
                onEnded.run();
            }
        }

        // ---- helpers ----

        private boolean background(String what, Runnable task) {
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.error("Mediation step '{}' failed on call {}", what, callId, e);
                    }
                });
                return true;
            } catch (RejectedExecutionException e) {
                log.warn("Mediation step '{}' was not run for call {}: the executor is shut down", what, callId);
                return false;
            }
        }

        private void cleanUp(Runnable task) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException e) {
                task.run();
            }
        }

        private void cancelLater(Synthesis s) {
            if (s == null) {
                return;
            }
            cleanUp(() -> {
                try {
                    s.cancel();
                } catch (RuntimeException e) {
                    log.warn("Cancelling speech synthesis failed on call {}", callId, e);
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
                log.warn("Closing buyer speech-to-text failed on call {}", callId, e);
            }
        }

        /** Speech-to-text events for one leg; they only post to the mailbox. */
        private final class Listener implements SttListener {
            private final LegRole role;

            Listener(LegRole role) {
                this.role = role;
            }

            @Override
            public void onSpeechStart() {
                mailbox.post(() -> speechStarted(role));
            }

            @Override
            public void onFinalTurn(Turn turn) {
                if (turn.role() != role) {
                    log.warn("Dropping a {} turn reported on the {} leg of call {}", turn.role().wire(), role.wire(),
                            callId);
                    return;
                }
                mailbox.post(() -> turnHeard(turn));
            }

            @Override
            public void onError(Exception error) {
                log.warn("Speech-to-text reported an error on the {} leg of call {}", role.wire(), callId, error);
            }
        }
    }

    /**
     * One thing to say on one leg.
     *
     * @param waitForIt true when the next step waits until this one was heard
     * @param announce publishes the console event as the step starts
     */
    private record Step(LegRole to, String text, Language language, boolean waitForIt, Runnable announce) {}

    /** One step being spoken. Fields are touched only in the call's mailbox. */
    private static final class Utterance {
        final Step step;
        Synthesis synthesis;
        long queuedMs;
        boolean finished;

        Utterance(Step step) {
            this.step = step;
        }
    }
}
