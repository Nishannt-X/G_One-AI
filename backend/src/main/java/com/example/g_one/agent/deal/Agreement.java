package com.example.g_one.agent.deal;

import com.example.g_one.core.LegRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * One room's agreement state machine: pure and synchronous (no I/O; time only from the {@link Clock}), so every rule
 * below is tested without a network. It decides nothing from audio or text; callers hand it already-classified replies.
 *
 * <pre>
 * IDLE --clear offer--> OFFER_OPEN --farmer yes--> READBACK --both phones played it--> CONFIRMING --both yes--> AGREED
 * any live draft --clear buyer offer with different terms--> new draft (OFFER_OPEN); --no--> closed; --time limit--> closed
 * READBACK or CONFIRMING --new terms spoken in a reply--> closed (only a fresh clear buyer offer opens new terms)
 * </pre>
 *
 * Rules: only an explicit YES counts, only for the speaker's own role, only after both phones reported playing the
 * read-back to its end and only for the exact terms that were read back (any new terms start a new draft with both
 * confirmations cleared). AGREED is final and happens at most once per draft, so a repeated or replayed reply can never
 * produce a second summary.
 *
 * Turn sequence numbers come from the room and rise with each turn. Offers and replies are read asynchronously, so a
 * result can arrive after a newer one: the offer of the newest buyer turn wins whatever the arrival order, and an older
 * reply from the same person is ignored unless it is a no (declining is always safe).
 */
final class Agreement {
    enum State { IDLE, OFFER_OPEN, READBACK, CONFIRMING, AGREED }

    /** What a spoken reply meant. */
    enum Intent { YES, NO, UNCLEAR, NONE, CHANGED }

    /** What the language model is asked about the next reply. */
    enum Phase { ACCEPT, CONFIRM }

    enum Kind {
        OPENED, READBACK, READBACK_FAILED, READBACK_PLAYED, CONFIRMING, CONFIRMED, RETRY, AGREED,
        DECLINED, EXPIRED, VOIDED,
        /** The farmer said no to an open offer, or an unanswered offer went stale: the draft is dropped silently. */
        REJECTED, LAPSED
    }

    /**
     * Something the room must be told. {@code role} is set for CONFIRMED, RETRY and READBACK_PLAYED. {@code seq} rises
     * with every notice in this room, so a phone can order state updates that two threads delivered out of order.
     */
    record Notice(Kind kind, LegRole role, int version, Terms terms, boolean farmerYes, boolean buyerYes,
                  boolean farmerPlayed, boolean buyerPlayed, long seq) {}

    record Ask(Phase phase, int version, Terms terms) {}

    private final Clock clock;
    private final Duration window;
    private final Duration offerTtl;
    private State state = State.IDLE;
    private int version;
    private Terms terms;
    private Instant openedAt;
    private Instant deadline;
    private Instant confirmingSince;
    private final EnumSet<LegRole> yes = EnumSet.noneOf(LegRole.class);
    private final EnumSet<LegRole> played = EnumSet.noneOf(LegRole.class);
    private boolean readbackSent;
    private long lastOfferTurn;
    private final Map<LegRole, Long> lastReplyTurn = new EnumMap<>(LegRole.class);
    private long noticeSeq;

    Agreement(Clock clock, Duration window, Duration offerTtl) {
        this.clock = clock;
        this.window = window;
        this.offerTtl = offerTtl;
    }

    synchronized State state() {
        return state;
    }

    synchronized int version() {
        return version;
    }

    /**
     * A clear buyer offer arrived (from the same read the advisory makes).
     *
     * @param turnSeq the room's sequence number of the buyer turn; an offer older than one already applied is stale
     */
    synchronized List<Notice> offer(Terms t, long turnSeq) {
        List<Notice> out = new ArrayList<>(expireDue());
        if (turnSeq <= lastOfferTurn) return out;
        lastOfferTurn = turnSeq;
        if (state != State.IDLE && t.sameAs(terms)) return out; // same terms: nothing changes, nothing resets
        if (state == State.READBACK || state == State.CONFIRMING) out.add(notice(Kind.VOIDED, null));
        version++;
        state = State.OFFER_OPEN;
        terms = t;
        openedAt = clock.instant();
        deadline = null;
        confirmingSince = null;
        yes.clear();
        clearReadback();
        out.add(notice(Kind.OPENED, null));
        return out;
    }

    /** @return what to ask the model about this speaker's next reply, or null when no reply can matter right now */
    synchronized Ask ask(LegRole role) {
        if (due()) return null;
        return switch (state) {
            case OFFER_OPEN -> role == LegRole.FARMER ? new Ask(Phase.ACCEPT, version, terms) : null;
            case READBACK, CONFIRMING -> new Ask(Phase.CONFIRM, version, terms);
            default -> null;
        };
    }

    /**
     * Applies one classified reply.
     *
     * @param draft the version the reply was classified against; a reply for an older draft is ignored
     * @param turnSeq the room's sequence number of this turn; an older reply than this person's latest is stale
     * @param spokenAt when the recording began (server time minus its length); speech that began before both phones
     *                 finished playing the read-back cannot have been a response to it
     */
    synchronized List<Notice> heard(int draft, LegRole role, Intent intent, long turnSeq, Instant spokenAt) {
        List<Notice> out = new ArrayList<>(expireDue());
        if (draft != version) return out;
        if (turnSeq <= lastReplyTurn.getOrDefault(role, 0L) && intent != Intent.NO) return out;
        lastReplyTurn.merge(role, turnSeq, Math::max);
        switch (state) {
            case OFFER_OPEN -> {
                if (role != LegRole.FARMER) break;
                if (intent == Intent.YES) {
                    state = State.READBACK;
                    deadline = clock.instant().plus(window);
                    clearReadback();
                    out.add(notice(Kind.READBACK, null));
                } else if (intent == Intent.NO) {
                    out.add(notice(Kind.REJECTED, null));
                    reset();
                }
            }
            case READBACK, CONFIRMING -> {
                // Declining or changing the terms is always safe, even when said before the read-back ended.
                if (intent == Intent.NO) {
                    close(out, Kind.DECLINED);
                } else if (intent == Intent.CHANGED) {
                    close(out, Kind.VOIDED);
                } else if (state == State.CONFIRMING && !spokenAt.isBefore(confirmingSince)) {
                    switch (intent) {
                        case YES -> {
                            if (!yes.add(role)) break; // already counted: a repeat changes nothing
                            if (yes.size() == 2) {
                                state = State.AGREED;
                                out.add(notice(Kind.AGREED, null));
                            } else {
                                out.add(notice(Kind.CONFIRMED, role));
                            }
                        }
                        case UNCLEAR -> {
                            if (!yes.contains(role)) out.add(notice(Kind.RETRY, role));
                        }
                        default -> { }
                    }
                }
            }
            default -> { }
        }
        return out;
    }

    /**
     * Sends the read-back only while this draft is still waiting for it. The check and the send share this lock with
     * every state change, so a phone can never receive a read-back after the event that made it obsolete.
     * ponytail: the send runs under the room's agreement lock; bounded by the socket send limits, and translated turns
     * never take this lock on their own path. Queue the events instead if a slow phone ever matters.
     *
     * @return false, having sent nothing, when the draft has moved on
     */
    synchronized boolean sendReadback(int draft, Runnable send) {
        if (due() || draft != version || state != State.READBACK) return false;
        send.run();
        readbackSent = true;
        return true;
    }

    /** Same rule for a spoken retry: it is sent only while that draft is still waiting for a confirmation. */
    synchronized boolean sendWhileConfirming(int draft, Runnable send) {
        if (due() || draft != version || state != State.CONFIRMING) return false;
        send.run();
        return true;
    }

    /**
     * One phone reports that it played the read-back of this draft to its end. Confirmations may count once both have;
     * the time limit keeps running, so a phone that never reports expires the draft instead of confirming anything.
     */
    synchronized List<Notice> readbackPlayed(int draft, LegRole role) {
        List<Notice> out = new ArrayList<>(expireDue());
        if (draft != version || state != State.READBACK || !readbackSent || !played.add(role)) return out;
        if (played.size() == 2) {
            state = State.CONFIRMING;
            confirmingSince = clock.instant();
            out.add(notice(Kind.CONFIRMING, null));
        } else {
            out.add(notice(Kind.READBACK_PLAYED, role));
        }
        return out;
    }

    /** The read-back could not be produced for someone: fail closed back to an open offer, nothing is confirmable. */
    synchronized List<Notice> readbackFailed(int draft) {
        List<Notice> out = new ArrayList<>(expireDue());
        if (draft == version && state == State.READBACK) {
            state = State.OFFER_OPEN;
            deadline = null;
            clearReadback();
            out.add(notice(Kind.READBACK_FAILED, null));
        }
        return out;
    }

    /** Applies the time limits; safe to call at any moment (timer or lazily). */
    synchronized List<Notice> expire() {
        return expireDue();
    }

    private boolean due() {
        Instant now = clock.instant();
        return switch (state) {
            case OFFER_OPEN -> now.isAfter(openedAt.plus(offerTtl));
            case READBACK, CONFIRMING -> !now.isBefore(deadline);
            default -> false;
        };
    }

    private List<Notice> expireDue() {
        if (!due()) return List.of();
        List<Notice> out = new ArrayList<>();
        if (state == State.OFFER_OPEN) {
            out.add(notice(Kind.LAPSED, null));
            reset();
        } else {
            close(out, Kind.EXPIRED);
        }
        return out;
    }

    private void close(List<Notice> out, Kind kind) {
        out.add(notice(kind, null));
        reset();
    }

    private void reset() {
        state = State.IDLE;
        terms = null;
        deadline = null;
        confirmingSince = null;
        yes.clear();
        clearReadback();
    }

    private void clearReadback() {
        played.clear();
        readbackSent = false;
    }

    private Notice notice(Kind kind, LegRole role) {
        return new Notice(kind, role, version, terms, yes.contains(LegRole.FARMER), yes.contains(LegRole.BUYER),
                played.contains(LegRole.FARMER), played.contains(LegRole.BUYER), ++noticeSeq);
    }
}
