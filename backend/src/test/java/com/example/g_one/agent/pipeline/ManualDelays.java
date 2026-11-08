package com.example.g_one.agent.pipeline;

import java.util.ArrayList;
import java.util.List;

/** Timers for tests: nothing fires until the test calls {@link #fireAll()}, on the test's thread. */
final class ManualDelays implements CallPipeline.Delays {
    private record Timer(long delayMs, Runnable task) {}

    private final List<Timer> timers = new ArrayList<>();

    @Override
    public synchronized void schedule(long delayMs, Runnable task) {
        timers.add(new Timer(delayMs, task));
    }

    /** The delays of the timers that have not fired yet, oldest first. */
    synchronized List<Long> pendingDelaysMs() {
        return timers.stream().map(Timer::delayMs).toList();
    }

    /** Fires every timer scheduled so far, oldest first, as if each delay had passed. */
    void fireAll() {
        List<Timer> due;
        synchronized (this) {
            due = List.copyOf(timers);
            timers.clear();
        }
        due.forEach(t -> t.task().run());
    }
}
