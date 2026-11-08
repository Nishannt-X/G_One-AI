package com.example.g_one.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SerialMailboxTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final SerialMailbox mailbox = new SerialMailbox("test");
    private final List<String> ran = new CopyOnWriteArrayList<>();

    @Test
    void tasksRunInPostOrderAndATaskPostedFromATaskRunsAfterIt() {
        mailbox.post(() -> {
            ran.add("a");
            mailbox.post(() -> ran.add("c"));
            ran.add("a done");
        });
        mailbox.post(() -> ran.add("d"));

        assertThat(ran).containsExactly("a", "a done", "c", "d");
    }

    @Test
    void aPostWhileAnotherThreadRunsATaskReturnsAtOnceAndThatThreadRunsIt() throws Exception {
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> runner = CompletableFuture.runAsync(() -> mailbox.post(() -> {
            threads.add(Thread.currentThread());
            inFirst.countDown();
            await(release);
        }), task -> Thread.ofPlatform().daemon().start(task));
        await(inFirst);

        mailbox.post(() -> {
            threads.add(Thread.currentThread());
            ran.add("second");
        });

        assertThat(ran).as("queued behind the running task, not run here").isEmpty();
        release.countDown();
        assertThat(runner).succeedsWithin(WAIT);
        assertThat(ran).containsExactly("second");
        assertThat(threads).hasSize(2);
        assertThat(threads.get(1)).isSameAs(threads.get(0)).isNotSameAs(Thread.currentThread());
    }

    @Test
    void aFailingTaskDoesNotStopTheNextOne() {
        mailbox.post(() -> {
            mailbox.post(() -> ran.add("after"));
            throw new IllegalStateException("boom (test)");
        });

        assertThat(ran).containsExactly("after");
    }

    @Test
    void tasksPostedFromManyThreadsNeverOverlap() {
        AtomicBoolean inside = new AtomicBoolean();
        AtomicInteger overlaps = new AtomicInteger();
        int[] count = {0}; // touched only inside tasks, so no lock is needed if they never overlap
        List<CompletableFuture<Void>> posters = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            posters.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 2_000; i++) {
                    mailbox.post(() -> {
                        if (!inside.compareAndSet(false, true)) {
                            overlaps.incrementAndGet();
                        }
                        count[0]++;
                        inside.set(false);
                    });
                }
            }, task -> Thread.ofPlatform().daemon().start(task)));
        }

        assertThat(CompletableFuture.allOf(posters.toArray(CompletableFuture[]::new))).succeedsWithin(WAIT);
        mailbox.post(() -> ran.add("count " + count[0]));
        assertThat(overlaps).hasValue(0);
        assertThat(ran).containsExactly("count 16000");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Timed out waiting for the test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
