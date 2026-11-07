package com.example.g_one_legacy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe state machine for the Ledger Lock (verbal smart contract) feature.
 *
 * States: IDLE → AWAITING_CONFIRMATION → CONFIRMED
 *
 * Flow:
 * 1. Groq detects "Deal confirm" → proposeDeal() → AWAITING_CONFIRMATION
 * 2. Both speakers say "Yes"/"Haan" → registerConfirmation() → CONFIRMED
 * 3. WhatsApp contract sent → reset() → IDLE
 */
@Service
public class LedgerLockStateMachine {

    private static final Logger log = LoggerFactory.getLogger(LedgerLockStateMachine.class);

    public enum State { IDLE, AWAITING_CONFIRMATION, CONFIRMED }

    private final AtomicReference<State> currentState = new AtomicReference<>(State.IDLE);

    // Deal details
    private volatile double price;
    private volatile double quantity;
    private volatile String currency;
    private volatile String item;

    // Confirmation tracking
    private volatile boolean speakerAConfirmed = false;
    private volatile boolean speakerBConfirmed = false;

    /**
     * Proposes a deal and transitions to AWAITING_CONFIRMATION.
     */
    public synchronized void proposeDeal(double price, double quantity, String currency, String item) {
        this.price = price;
        this.quantity = quantity;
        this.currency = currency != null ? currency : "INR";
        this.item = item != null ? item : "commodity";
        this.speakerAConfirmed = false;
        this.speakerBConfirmed = false;
        this.currentState.set(State.AWAITING_CONFIRMATION);

        log.info("[LEDGER_LOCK] 📋 Deal proposed: {} {} of {} at {} {}",
                quantity, getUnit(), item, price, this.currency);
    }

    /**
     * Registers a speaker's "Yes" confirmation.
     * @return true if BOTH speakers have now confirmed (deal is finalized)
     */
    public synchronized boolean registerConfirmation(String speakerId) {
        if (currentState.get() != State.AWAITING_CONFIRMATION) {
            log.warn("[LEDGER_LOCK] Confirmation received but state is {}. Ignoring.", currentState.get());
            return false;
        }

        if ("A".equals(speakerId)) {
            speakerAConfirmed = true;
            log.info("[LEDGER_LOCK] ✅ Speaker A confirmed the deal");
        } else if ("B".equals(speakerId)) {
            speakerBConfirmed = true;
            log.info("[LEDGER_LOCK] ✅ Speaker B confirmed the deal");
        }

        if (speakerAConfirmed && speakerBConfirmed) {
            currentState.set(State.CONFIRMED);
            log.info("[LEDGER_LOCK] 🤝 BOTH speakers confirmed! Deal is FINAL.");
            return true;
        }

        log.info("[LEDGER_LOCK] Waiting for other speaker. A={}, B={}",
                speakerAConfirmed ? "YES" : "PENDING",
                speakerBConfirmed ? "YES" : "PENDING");
        return false;
    }

    public boolean isAwaitingConfirmation() {
        return currentState.get() == State.AWAITING_CONFIRMATION;
    }

    public State getState() {
        return currentState.get();
    }

    /**
     * Returns the formatted WhatsApp contract text.
     */
    public String getContractSummary() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss"));
        return "🤝 *G-ONE VERBAL CONTRACT CONFIRMED*\n" +
               "━━━━━━━━━━━━━━━━━━━━━━\n" +
               "📦 Item: " + capitalize(item) + "\n" +
               "⚖️ Quantity: " + formatQuantity() + "\n" +
               "💰 Price: " + currency + " " + String.format("%.0f", price) + "\n" +
               "━━━━━━━━━━━━━━━━━━━━━━\n" +
               "🕐 Timestamp: " + timestamp + "\n" +
               "✅ Both parties verbally confirmed.\n" +
               "🔒 Powered by G-One";
    }

    /**
     * Returns confirmation prompt in English (for Speaker B).
     */
    public String getConfirmationPromptEnglish() {
        return "This is G-One. A deal of " + formatQuantity() + " " + item +
               " at " + currency + " " + String.format("%.0f", price) +
               " is proposed. Do you agree? Say Yes.";
    }

    /**
     * Returns confirmation prompt in Hindi (for Speaker A).
     */
    public String getConfirmationPromptHindi() {
        return "यह G-One है। " + formatQuantity() + " " + item +
               " " + currency + " " + String.format("%.0f", price) +
               " में डील प्रस्तावित है। क्या आप सहमत हैं? हाँ बोलें।";
    }

    /**
     * Resets back to IDLE state.
     */
    public synchronized void reset() {
        currentState.set(State.IDLE);
        speakerAConfirmed = false;
        speakerBConfirmed = false;
        price = 0;
        quantity = 0;
        currency = "INR";
        item = "commodity";
        log.info("[LEDGER_LOCK] State machine reset to IDLE");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private String formatQuantity() {
        if (quantity == (long) quantity) {
            return String.format("%.0f kg", quantity);
        }
        return String.format("%.1f kg", quantity);
    }

    private String getUnit() {
        return "kg";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // ── Getters for external access ──────────────────────────────────────────────

    public double getPrice() { return price; }
    public double getQuantity() { return quantity; }
    public String getCurrency() { return currency; }
    public String getItem() { return item; }
}
