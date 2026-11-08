package com.example.g_one.agent.pipeline;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

/** An executor that only queues tasks; the test runs them when and in the order it wants, on its own thread. */
final class ManualExecutor implements Executor {
    private final Deque<Runnable> queue = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable task) {
        queue.addLast(task);
    }

    synchronized int pending() {
        return queue.size();
    }

    /** Runs the oldest queued task. */
    void runNext() {
        take(true).run();
    }

    /** Runs the newest queued task (to let a later step overtake an earlier one). */
    void runLast() {
        take(false).run();
    }

    /** Runs tasks, oldest first, including any they queue, until none are left. */
    void runAll() {
        int guard = 0;
        while (pending() > 0) {
            if (++guard > 1_000) {
                throw new IllegalStateException("Tasks keep queueing more tasks");
            }
            runNext();
        }
    }

    private synchronized Runnable take(boolean oldest) {
        if (queue.isEmpty()) {
            throw new IllegalStateException("No task queued");
        }
        return oldest ? queue.pollFirst() : queue.pollLast();
    }
}
