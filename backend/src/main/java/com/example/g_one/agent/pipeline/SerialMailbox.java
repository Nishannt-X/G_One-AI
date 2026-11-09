package com.example.g_one.agent.pipeline;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs posted tasks one at a time, in the order they were posted. It has no thread of its own: the thread that posts
 * while no task is running runs the queue until it is empty. A post never waits. If another thread is already running
 * tasks, the new task is queued and that thread runs it next; a task posted from inside a running task runs after the
 * current one, never inside it.
 *
 * <p>Everything one task writes is visible to every later task, whichever thread runs it, so state that only tasks touch
 * needs no lock. Tasks must not block: slow work belongs on an executor, and its result comes back as a new task.
 *
 * <p>Public so mediation ({@code agent.mediation}) can run each mediated call as the same kind of actor.
 */
public final class SerialMailbox {
    private static final Logger log = LoggerFactory.getLogger(SerialMailbox.class);

    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final String owner;

    /** @param owner names the mailbox in logs, for example a leg id */
    public SerialMailbox(String owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public void post(Runnable task) {
        queue.add(Objects.requireNonNull(task, "task"));
        // Either this thread becomes the runner, or the current runner is guaranteed to see the task: it re-checks the
        // queue after it stops running.
        while (!queue.isEmpty() && running.compareAndSet(false, true)) {
            try {
                Runnable next;
                while ((next = queue.poll()) != null) {
                    try {
                        next.run();
                    } catch (RuntimeException e) {
                        log.error("A pipeline step failed on {}; the call goes on", owner, e);
                    }
                }
            } finally {
                running.set(false);
            }
        }
    }
}
