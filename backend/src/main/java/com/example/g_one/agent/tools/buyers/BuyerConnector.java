package com.example.g_one.agent.tools.buyers;

import com.example.g_one.core.CallContext;
import java.util.Objects;

/**
 * Starts the buyer leg once the farmer has said yes. {@code connect_buyer} calls it at most once per granted consent.
 * The default bean ({@link NotWiredBuyerConnector}) only logs; the telephony issue (#25) supplies the real one and
 * marks it {@code @Primary}.
 *
 * <p>Implementations must never log, publish or return {@link Dial#number()}.
 */
public interface BuyerConnector {

    enum Outcome {
        /** The buyer leg is ringing (or about to). */
        DIALING,
        /** No telephony behind this build: nothing was dialled. */
        NOT_WIRED,
        /** The dial could not be started. */
        FAILED
    }

    /** Must not throw; a thrown exception counts as {@link Outcome#FAILED}. */
    Outcome connect(CallContext ctx, Dial dial);

    /** Who to call. {@code number} comes from {@code G_ONE_BUYER_PHONES}; {@link #toString()} hides it. */
    record Dial(Buyer buyer, String number) {
        public Dial {
            Objects.requireNonNull(buyer, "buyer");
            Objects.requireNonNull(number, "number");
        }

        @Override
        public String toString() {
            return "Dial[" + buyer.id() + "]";
        }
    }
}
