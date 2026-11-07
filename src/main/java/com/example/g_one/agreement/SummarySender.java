package com.example.g_one.agreement;

import java.time.Instant;

/**
 * Where a confirmed summary goes after both people said yes. The only implementation shipped is
 * {@link UnconfiguredSummarySender}; a Twilio WhatsApp Sandbox sender is a later, human-approved addition behind this
 * same interface (recipients and credentials are its configuration, never part of {@link Summary}).
 */
public interface SummarySender {

    /**
     * The nonbinding summary. {@code id} is stable for one confirmed draft ({@code roomId:version}), so a sender can use
     * it as an idempotency key. {@code text} is the English message and contains only the spoken terms and the
     * disclaimer: nothing the farmer alone was told (price reference, caution) ever reaches a sender.
     */
    record Summary(String id, Terms terms, Instant confirmedAt, String text) {}

    /** @param reason a short fixed code (for example "not_configured", "send_failed"), never provider error text */
    record Delivery(boolean sent, String reason) {
        public static Delivery delivered() {
            return new Delivery(true, "sent");
        }

        public static Delivery notSent(String reason) {
            return new Delivery(false, reason);
        }
    }

    /** Called at most once per confirmed draft. May throw: the caller records "not sent" and carries on. */
    Delivery send(Summary summary);
}
