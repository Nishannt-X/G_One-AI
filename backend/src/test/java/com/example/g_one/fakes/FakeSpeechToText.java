package com.example.g_one.fakes;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.Turn;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * A {@link SpeechToText} for tests that "hears" scripted turns. Either drive a session by hand ({@link Session#say},
 * {@link Session#emitSpeechStart}, ...) or call {@link #turnEvery} so that feeding audio produces the scripted turns:
 * the first frame of a turn fires {@code onSpeechStart} and the n-th fires {@code onFinalTurn} with the next script
 * line. Events run on the feeding (or calling) thread, in order.
 */
public final class FakeSpeechToText implements SpeechToText {
    private final ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<>();
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final LongSupplier clock;
    private volatile int framesPerTurn;

    public FakeSpeechToText() {
        this(System::currentTimeMillis);
    }

    /** @param clock epoch milliseconds used for turn start and end times */
    public FakeSpeechToText(LongSupplier clock) {
        this.clock = clock;
    }

    /** Queues the next turns to be heard, shared by all sessions in order. */
    public FakeSpeechToText script(String... turns) {
        script.addAll(List.of(turns));
        return this;
    }

    /** Auto mode: every {@code frames} fed frames make one scripted turn. 0 turns auto mode off. */
    public FakeSpeechToText turnEvery(int frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("frames must not be negative");
        }
        this.framesPerTurn = frames;
        return this;
    }

    @Override
    public Session open(LegId legId, LegRole role, Language language, SttListener listener) {
        Session s = new Session(legId, role, language, listener);
        sessions.add(s);
        return s;
    }

    public List<Session> sessions() {
        return List.copyOf(sessions);
    }

    /** The most recently opened session. */
    public Session last() {
        if (sessions.isEmpty()) {
            throw new NoSuchElementException("No STT session was opened");
        }
        return sessions.get(sessions.size() - 1);
    }

    /** Script lines not heard yet. */
    public int remainingScript() {
        return script.size();
    }

    public final class Session implements SttSession {
        private final LegId legId;
        private final LegRole role;
        private final Language language;
        private final SttListener listener;
        private final List<AudioFrame> fed = new CopyOnWriteArrayList<>();
        private volatile boolean closed;
        private int framesInTurn;
        private long speechStartedMs = -1;

        private Session(LegId legId, LegRole role, Language language, SttListener listener) {
            this.legId = legId;
            this.role = role;
            this.language = language;
            this.listener = listener;
        }

        @Override
        public synchronized void feed(AudioFrame frame) {
            if (closed) {
                return;
            }
            fed.add(frame);
            int n = framesPerTurn;
            if (n == 0 || script.isEmpty()) {
                return;
            }
            framesInTurn++;
            if (framesInTurn == 1) {
                emitSpeechStart();
            }
            if (framesInTurn >= n) {
                framesInTurn = 0;
                String text = script.poll();
                if (text != null) {
                    emitTurn(text);
                }
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        public synchronized void emitSpeechStart() {
            if (closed) {
                return;
            }
            speechStartedMs = clock.getAsLong();
            listener.onSpeechStart();
        }

        public synchronized void emitPartial(String text) {
            if (!closed) {
                listener.onPartial(text);
            }
        }

        /** Finishes the current turn with {@code text} (started at the last speech start, or now). */
        public synchronized Turn emitTurn(String text) {
            long now = clock.getAsLong();
            Turn turn = new Turn(legId, role, language, text, speechStartedMs < 0 ? now : speechStartedMs, now);
            speechStartedMs = -1;
            if (!closed) {
                listener.onFinalTurn(turn);
            }
            return turn;
        }

        /** Speech start, then the finished turn. */
        public synchronized Turn say(String text) {
            emitSpeechStart();
            return emitTurn(text);
        }

        /** Says the next script line. */
        public Turn sayNext() {
            String text = script.poll();
            if (text == null) {
                throw new NoSuchElementException("The STT script is empty");
            }
            return say(text);
        }

        public synchronized void emitError(Exception error) {
            if (!closed) {
                listener.onError(error);
            }
        }

        public LegId legId() { return legId; }
        public LegRole role() { return role; }
        public Language language() { return language; }
        public boolean isClosed() { return closed; }

        public List<AudioFrame> fed() {
            return List.copyOf(fed);
        }
    }
}
