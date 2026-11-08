package com.example.g_one.speech;

import com.example.g_one.core.SttListener;
import com.example.g_one.core.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a listener so a session honours the {@link SttListener} contract: callbacks never overlap, and none arrive
 * once the session is closed. A listener that throws does not break the session.
 */
final class GuardedListener implements SttListener {
    private static final Logger log = LoggerFactory.getLogger(GuardedListener.class);

    private final SttListener delegate;
    private boolean closed;

    GuardedListener(SttListener delegate) {
        this.delegate = delegate;
    }

    /** After this returns no further callback runs. Safe to call from inside a callback. */
    synchronized void close() {
        closed = true;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void onSpeechStart() {
        if (closed) return;
        try {
            delegate.onSpeechStart();
        } catch (RuntimeException e) {
            log.warn("STT listener failed in onSpeechStart: {}", e.toString());
        }
    }

    @Override
    public synchronized void onPartial(String text) {
        if (closed) return;
        try {
            delegate.onPartial(text);
        } catch (RuntimeException e) {
            log.warn("STT listener failed in onPartial: {}", e.toString());
        }
    }

    @Override
    public synchronized void onFinalTurn(Turn turn) {
        if (closed) return;
        try {
            delegate.onFinalTurn(turn);
        } catch (RuntimeException e) {
            log.warn("STT listener failed in onFinalTurn: {}", e.toString());
        }
    }

    @Override
    public synchronized void onError(Exception error) {
        if (closed) return;
        try {
            delegate.onError(error);
        } catch (RuntimeException e) {
            log.warn("STT listener failed in onError: {}", e.toString());
        }
    }
}
