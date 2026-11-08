package com.example.g_one.speech;

import java.util.function.BooleanSupplier;

/** Polls until a condition holds, so tests wait for another thread without fixed sleeps. */
final class Wait {
    private Wait() {}

    static void until(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    /** Lets a moment pass so a test can assert that something did NOT happen. */
    static void settle() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
