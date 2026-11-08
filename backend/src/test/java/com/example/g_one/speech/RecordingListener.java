package com.example.g_one.speech;

import com.example.g_one.core.SttListener;
import com.example.g_one.core.Turn;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records everything a session reports, in order. */
final class RecordingListener implements SttListener {
    final List<String> events = new CopyOnWriteArrayList<>();
    final List<Turn> turns = new CopyOnWriteArrayList<>();
    final List<Exception> errors = new CopyOnWriteArrayList<>();
    final List<String> partials = new CopyOnWriteArrayList<>();

    @Override
    public void onSpeechStart() {
        events.add("speechStart");
    }

    @Override
    public void onPartial(String text) {
        partials.add(text);
        events.add("partial");
    }

    @Override
    public void onFinalTurn(Turn turn) {
        turns.add(turn);
        events.add("final");
    }

    @Override
    public void onError(Exception error) {
        errors.add(error);
        events.add("error");
    }
}
