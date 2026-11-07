package com.example.g_one.agent.deal;

import static com.example.g_one.agent.deal.Agreement.Intent.CHANGED;
import static com.example.g_one.agent.deal.Agreement.Intent.NO;
import static com.example.g_one.agent.deal.Agreement.Intent.NONE;
import static com.example.g_one.agent.deal.Agreement.Intent.UNCLEAR;
import static com.example.g_one.agent.deal.Agreement.Intent.YES;
import static com.example.g_one.agent.deal.Agreement.Kind.AGREED;
import static com.example.g_one.agent.deal.Agreement.Kind.CONFIRMED;
import static com.example.g_one.agent.deal.Agreement.Kind.CONFIRMING;
import static com.example.g_one.agent.deal.Agreement.Kind.DECLINED;
import static com.example.g_one.agent.deal.Agreement.Kind.EXPIRED;
import static com.example.g_one.agent.deal.Agreement.Kind.LAPSED;
import static com.example.g_one.agent.deal.Agreement.Kind.OPENED;
import static com.example.g_one.agent.deal.Agreement.Kind.READBACK;
import static com.example.g_one.agent.deal.Agreement.Kind.READBACK_FAILED;
import static com.example.g_one.agent.deal.Agreement.Kind.READBACK_PLAYED;
import static com.example.g_one.agent.deal.Agreement.Kind.REJECTED;
import static com.example.g_one.agent.deal.Agreement.Kind.RETRY;
import static com.example.g_one.agent.deal.Agreement.Kind.VOIDED;
import static com.example.g_one.core.LegRole.BUYER;
import static com.example.g_one.core.LegRole.FARMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.deal.Agreement.Intent;
import com.example.g_one.agent.deal.Agreement.Kind;
import com.example.g_one.agent.deal.Agreement.Notice;
import com.example.g_one.agent.deal.Agreement.State;
import com.example.g_one.core.LegRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every rule of the agreement, with a fake clock and no I/O. */
class AgreementTest {
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(120);
    private static final Terms RICE = terms("rice", "Common", "Burdwan APMC", "3400", "quintal");

    private final MutableClock clock = new MutableClock(T0);
    private final Agreement a = new Agreement(clock, WINDOW, Duration.ofMinutes(5));
    private int n;

    static Terms terms(String commodity, String variety, String market, String price, String unit) {
        return Terms.from(new Offer("clear", commodity, variety, market, new BigDecimal(price), "INR", unit));
    }

    private static List<Kind> kinds(List<Notice> notices) {
        return notices.stream().map(Notice::kind).toList();
    }

    private long turn() {
        return ++n;
    }

    private List<Notice> say(int draft, LegRole who, Intent intent) {
        return a.heard(draft, who, intent, turn(), clock.instant());
    }

    /** The read-back of this draft was sent and both phones reported playing it to the end. */
    private void playedOnBoth(int draft) {
        assertThat(a.sendReadback(draft, () -> { })).isTrue();
        assertThat(kinds(a.readbackPlayed(draft, FARMER))).containsExactly(READBACK_PLAYED);
        assertThat(kinds(a.readbackPlayed(draft, BUYER))).containsExactly(CONFIRMING);
    }

    /** Draft 1 with the read-back played on both phones: both people may now confirm. */
    private void confirming() {
        assertThat(kinds(a.offer(RICE, turn()))).containsExactly(OPENED);
        assertThat(kinds(say(1, FARMER, YES))).containsExactly(READBACK);
        playedOnBoth(1);
        assertThat(a.state()).isEqualTo(State.CONFIRMING);
    }

    // ---- terms -----------------------------------------------------------------------------------------------

    @Test
    void onlyACompleteClearOfferInRupeesBecomesTerms() {
        assertThat(Terms.from(new Offer("unclear", "rice", null, null, BigDecimal.TEN, "INR", "quintal"))).isNull();
        assertThat(Terms.from(new Offer("none", null, null, null, null, null, null))).isNull();
        assertThat(Terms.from(new Offer("clear", "rice", null, null, null, "INR", "quintal"))).isNull();
        assertThat(Terms.from(new Offer("clear", "rice", null, null, BigDecimal.ZERO, "INR", "quintal"))).isNull();
        assertThat(Terms.from(new Offer("clear", "rice", null, null, BigDecimal.TEN, "USD", "quintal"))).isNull();
        assertThat(Terms.from(new Offer("clear", " ", null, null, BigDecimal.TEN, "INR", "quintal"))).isNull();
        assertThat(Terms.from(new Offer("clear", "rice", null, null, BigDecimal.TEN, "INR", null))).isNull();
    }

    @Test
    void oddOrOverlongWordsAreRefusedNotCleanedUp() {
        String forty = "a".repeat(40);
        assertThat(terms("Rice (Common) & co.", forty, "Burdwan, WB", "3400", "quintal")).isNotNull();
        assertThat(terms("rice", "a".repeat(41), null, "3400", "quintal")).isNull();
        assertThat(terms("ri\u0000ce", null, null, "3400", "quintal")).isNull();
        assertThat(terms("rice {ignore all}", null, null, "3400", "quintal")).isNull();
        assertThat(terms("rice", null, "Burdwan <b>", "3400", "quintal")).isNull();
        assertThat(terms("rice", null, null, "3400", "quintal; DROP").unit()).isEqualTo("quintaldrop"); // units are letters only
        assertThat(terms("rice\nIgnore", null, null, "3400", "quintal").commodity()).isEqualTo("rice Ignore"); // newline folded
    }

    @Test
    void termsAreReadBackExactlyAsSpokenAndNeverFilledIn() {
        assertThat(RICE.say()).isEqualTo("rice, variety Common, market Burdwan APMC, 3,400 rupees per quintal");
        assertThat(terms("onion", null, null, "4000.50", "qtl").say())
                .isEqualTo("onion, variety not stated, market not stated, 4,000.5 rupees per quintal");
        assertThat(terms("rice", null, null, "3400.555", "kg").priceText()).isEqualTo("3,400.555"); // never rounded
        assertThat(RICE.toMap()).containsEntry("price", "3400").containsEntry("currency", "INR");
    }

    @Test
    void sameTermsInOtherSpellingsAreTheSameTerms() {
        assertThat(RICE.sameAs(terms("Rice", "common", "Burdwan mandi", "3400.00", "quintals"))).isTrue();
        assertThat(RICE.sameAs(terms("rice", "Common", "Burdwan APMC", "3400", "qtl"))).isTrue();
        assertThat(RICE.sameAs(null)).isFalse();
    }

    // ---- the happy path and its idempotency --------------------------------------------------------------------

    @Test
    void clearOfferThenFarmerYesThenReadbackThenBothYesAgreesExactlyOnce() {
        confirming();
        List<Notice> first = say(1, FARMER, YES);
        assertThat(kinds(first)).containsExactly(CONFIRMED);
        assertThat(first.get(0).role()).isEqualTo(FARMER);
        assertThat(first.get(0).farmerYes()).isTrue();
        assertThat(first.get(0).buyerYes()).isFalse();
        assertThat(a.state()).isEqualTo(State.CONFIRMING);

        List<Notice> last = say(1, BUYER, YES);
        assertThat(kinds(last)).containsExactly(AGREED);
        assertThat(last.get(0).farmerYes() && last.get(0).buyerYes()).isTrue();
        assertThat(a.state()).isEqualTo(State.AGREED);

        // Repeats, retries and replays never produce a second AGREED or change anything.
        for (LegRole r : LegRole.values()) assertThat(say(1, r, YES)).isEmpty();
        assertThat(a.expire()).isEmpty();
        clock.advance(Duration.ofHours(1));
        assertThat(say(1, FARMER, YES)).isEmpty();
        assertThat(a.expire()).isEmpty();
        assertThat(a.state()).isEqualTo(State.AGREED);
    }

    @Test
    void theSameTurnProcessedTwiceCountsOnce() {
        confirming();
        long dup = turn();
        assertThat(kinds(a.heard(1, FARMER, YES, dup, clock.instant()))).containsExactly(CONFIRMED);
        assertThat(a.heard(1, FARMER, YES, dup, clock.instant())).isEmpty();
        assertThat(kinds(a.heard(1, BUYER, YES, dup, clock.instant()))).containsExactly(AGREED); // other role, same turn
    }

    @Test
    void aSecondYesFromTheSameRoleNeverStandsInForTheOtherRole() {
        confirming();
        say(1, BUYER, YES);
        assertThat(say(1, BUYER, YES)).isEmpty();
        assertThat(say(1, BUYER, YES)).isEmpty();
        assertThat(a.state()).isEqualTo(State.CONFIRMING);
        assertThat(kinds(say(1, FARMER, YES))).containsExactly(AGREED);
    }

    @Test
    void theSameOfferAgainChangesNothingAndDoesNotResetConfirmations() {
        confirming();
        say(1, FARMER, YES);
        assertThat(a.offer(terms("Rice", "common", "burdwan mandi", "3400.0", "qtl"), turn())).isEmpty();
        assertThat(a.version()).isEqualTo(1);
        assertThat(kinds(say(1, BUYER, YES))).containsExactly(AGREED);
    }

    // ---- invalidation on any changed term ----------------------------------------------------------------------

    static Stream<Arguments> changedTerms() {
        return Stream.of(
                Arguments.of("price", terms("rice", "Common", "Burdwan APMC", "3500", "quintal")),
                Arguments.of("unit", terms("rice", "Common", "Burdwan APMC", "3400", "kg")),
                Arguments.of("variety", terms("rice", "Fine", "Burdwan APMC", "3400", "quintal")),
                Arguments.of("variety dropped", terms("rice", null, "Burdwan APMC", "3400", "quintal")),
                Arguments.of("market", terms("rice", "Common", "Bardhaman", "3400", "quintal")),
                Arguments.of("market added", terms("rice", "Common", null, "3400", "quintal")),
                Arguments.of("commodity", terms("wheat", "Common", "Burdwan APMC", "3400", "quintal")));
    }

    @ParameterizedTest(name = "changed {0}")
    @MethodSource("changedTerms")
    void anyChangedTermClearsBothConfirmationsAndStartsOver(String what, Terms changed) {
        confirming();
        say(1, FARMER, YES); // one confirmation already counted

        List<Notice> out = a.offer(changed, turn());
        assertThat(kinds(out)).containsExactly(VOIDED, OPENED);
        assertThat(out.get(0).terms()).isSameAs(RICE);        // the old terms are what was voided
        assertThat(out.get(0).farmerYes()).isTrue();
        assertThat(out.get(1).terms()).isSameAs(changed);      // the new draft starts with nobody confirmed
        assertThat(out.get(1).farmerYes() || out.get(1).buyerYes()).isFalse();
        assertThat(out.get(1).version()).isEqualTo(2);
        assertThat(a.state()).isEqualTo(State.OFFER_OPEN);

        // A reply classified against the old terms is ignored; the old yes does not carry over.
        assertThat(say(1, BUYER, YES)).isEmpty();
        assertThat(say(1, FARMER, YES)).isEmpty();
        assertThat(a.ask(BUYER)).isNull();
        // The new terms need a fresh accept, a fresh read-back and two fresh yes replies.
        assertThat(kinds(say(2, FARMER, YES))).containsExactly(READBACK);
        playedOnBoth(2);
        assertThat(kinds(say(2, BUYER, YES))).containsExactly(CONFIRMED);
        assertThat(a.state()).isEqualTo(State.CONFIRMING);
        assertThat(kinds(say(2, FARMER, YES))).containsExactly(AGREED);
    }

    @Test
    void newTermsStatedInAReplyVoidTheDraftToo() {
        confirming();
        say(1, BUYER, YES);
        List<Notice> out = say(1, FARMER, CHANGED);
        assertThat(kinds(out)).containsExactly(VOIDED);
        assertThat(a.state()).isEqualTo(State.IDLE);
        assertThat(say(1, FARMER, YES)).isEmpty();
    }

    @Test
    void anOfferWhileOnlyOpenIsReplacedWithoutAVoid() {
        a.offer(RICE, turn());
        List<Notice> out = a.offer(terms("rice", "Common", "Burdwan APMC", "3300", "quintal"), turn());
        assertThat(kinds(out)).containsExactly(OPENED);
        assertThat(out.get(0).version()).isEqualTo(2);
    }

    @Test
    void aNewOfferAfterAgreementStartsANewDraftButTheSameTermsDoNot() {
        confirming();
        say(1, FARMER, YES);
        say(1, BUYER, YES);
        assertThat(a.offer(RICE, turn())).isEmpty();           // repeating the agreed terms is not a new deal
        assertThat(a.state()).isEqualTo(State.AGREED);
        assertThat(kinds(a.offer(terms("rice", "Common", "Burdwan APMC", "3600", "quintal"), turn()))).containsExactly(OPENED);
        assertThat(a.state()).isEqualTo(State.OFFER_OPEN);
        assertThat(a.version()).isEqualTo(2);
    }

    // ---- who may say what, and when -------------------------------------------------------------------------------

    @Test
    void onlyTheFarmerCanAcceptAnOpenOfferAndOnlyAYesDoes() {
        a.offer(RICE, turn());
        assertThat(a.ask(BUYER)).isNull();                       // no model call is needed for the buyer
        assertThat(say(1, BUYER, YES)).isEmpty();
        for (Intent i : new Intent[] {UNCLEAR, NONE, CHANGED}) assertThat(say(1, FARMER, i)).isEmpty();
        assertThat(a.state()).isEqualTo(State.OFFER_OPEN);
        assertThat(a.ask(FARMER).phase()).isEqualTo(Agreement.Phase.ACCEPT);
        assertThat(kinds(say(1, FARMER, YES))).containsExactly(READBACK);
    }

    @Test
    void aFarmerNoDropsAnOpenOffer() {
        a.offer(RICE, turn());
        assertThat(kinds(say(1, FARMER, NO))).containsExactly(REJECTED);
        assertThat(a.state()).isEqualTo(State.IDLE);
        assertThat(say(1, FARMER, YES)).isEmpty();
    }

    @Test
    void aYesBeforeTheReadbackIsDeliveredCountsForNothing() {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        assertThat(a.state()).isEqualTo(State.READBACK);
        assertThat(say(1, BUYER, YES)).isEmpty();                // read-back not delivered yet
        assertThat(say(1, FARMER, YES)).isEmpty();
        clock.advance(Duration.ofSeconds(5));
        playedOnBoth(1);
        // Speech that began before the read-back finished cannot have been an answer to it.
        assertThat(a.heard(1, BUYER, YES, turn(), clock.instant().minusSeconds(1))).isEmpty();
        assertThat(kinds(a.heard(1, BUYER, YES, turn(), clock.instant()))).containsExactly(CONFIRMED);
    }

    @Test
    void aNoCanDeclineEvenBeforeTheReadbackEnds() {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        assertThat(kinds(say(1, BUYER, NO))).containsExactly(DECLINED);
        assertThat(a.sendReadback(1, () -> { })).isFalse();      // the late read-back is never sent
        assertThat(a.readbackPlayed(1, FARMER)).isEmpty();       // and a report for it opens nothing
        assertThat(a.state()).isEqualTo(State.IDLE);
    }

    @Test
    void anyNoDeclinesAndNothingCountsAfterwards() {
        confirming();
        say(1, FARMER, YES);
        assertThat(kinds(say(1, BUYER, NO))).containsExactly(DECLINED);
        assertThat(a.state()).isEqualTo(State.IDLE);
        assertThat(say(1, BUYER, YES)).isEmpty();
        assertThat(say(1, FARMER, YES)).isEmpty();
        // A fresh offer can start again, with nobody confirmed.
        assertThat(kinds(a.offer(RICE, turn()))).containsExactly(OPENED);
        assertThat(a.version()).isEqualTo(2);
    }

    @Test
    void aPersonWhoAlreadySaidYesCanStillChangeTheirMind() {
        confirming();
        say(1, FARMER, YES);
        assertThat(kinds(say(1, FARMER, NO))).containsExactly(DECLINED);
    }

    @Test
    void anUnclearReplyAsksOnlyTheUnconfirmedSpeakerToRepeat() {
        confirming();
        List<Notice> retry = say(1, FARMER, UNCLEAR);
        assertThat(kinds(retry)).containsExactly(RETRY);
        assertThat(retry.get(0).role()).isEqualTo(FARMER);
        assertThat(say(1, BUYER, NONE)).isEmpty();               // unrelated talk: no prompt, no change
        say(1, FARMER, YES);
        assertThat(say(1, FARMER, UNCLEAR)).isEmpty();           // already confirmed: nothing to repeat
        assertThat(a.state()).isEqualTo(State.CONFIRMING);
    }

    // ---- time -----------------------------------------------------------------------------------------------------

    @Test
    void theConfirmationWindowExpiresTheDraftAndLateRepliesCountForNothing() {
        confirming();
        say(1, FARMER, YES);
        clock.advance(WINDOW.minusSeconds(1));
        assertThat(a.expire()).isEmpty();
        assertThat(a.ask(BUYER)).isNotNull();
        clock.advance(Duration.ofSeconds(1));
        assertThat(a.ask(BUYER)).isNull();
        List<Notice> out = a.expire();
        assertThat(kinds(out)).containsExactly(EXPIRED);
        assertThat(out.get(0).farmerYes()).isTrue();
        assertThat(a.expire()).isEmpty();                        // reported once
        assertThat(say(1, BUYER, YES)).isEmpty();
        assertThat(a.state()).isEqualTo(State.IDLE);
    }

    @Test
    void aReplyArrivingAfterTheDeadlineIsExpiredFirstNotCounted() {
        confirming();
        say(1, FARMER, YES);
        clock.advance(WINDOW.plusSeconds(1));
        assertThat(kinds(say(1, BUYER, YES))).containsExactly(EXPIRED);
        assertThat(a.state()).isEqualTo(State.IDLE);
    }

    @Test
    void anUnansweredOfferLapsesAfterItsLimit() {
        a.offer(RICE, turn());
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(a.ask(FARMER)).isNull();
        assertThat(kinds(a.expire())).containsExactly(LAPSED);
        assertThat(say(1, FARMER, YES)).isEmpty();
        assertThat(a.state()).isEqualTo(State.IDLE);
    }

    @Test
    void anExpiredDraftDoesNotBlockTheNextOffer() {
        confirming();
        clock.advance(WINDOW.plusSeconds(1));
        assertThat(kinds(a.offer(RICE, turn()))).containsExactly(EXPIRED, OPENED);
        assertThat(a.version()).isEqualTo(2);
    }

    @Test
    void aReadbackThatCouldNotBeProducedFallsBackToAnOpenOfferWithNothingConfirmable() {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        assertThat(kinds(a.readbackFailed(1))).containsExactly(READBACK_FAILED);
        assertThat(a.state()).isEqualTo(State.OFFER_OPEN);
        assertThat(a.sendReadback(1, () -> { })).isFalse();
        assertThat(a.readbackPlayed(1, BUYER)).isEmpty();
        assertThat(say(1, BUYER, YES)).isEmpty();
        assertThat(kinds(say(1, FARMER, YES))).containsExactly(READBACK); // the farmer simply tries again
    }

    // ---- both phones must play the read-back ------------------------------------------------------------------

    @Test
    void oneOrNoPhonesPlayingTheReadbackNeverOpensConfirmationAndTheTimeLimitStillRuns() {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        a.sendReadback(1, () -> { });
        assertThat(a.state()).isEqualTo(State.READBACK);
        assertThat(say(1, BUYER, YES)).isEmpty();                // nobody has played it yet

        assertThat(kinds(a.readbackPlayed(1, FARMER))).containsExactly(READBACK_PLAYED);
        assertThat(a.readbackPlayed(1, FARMER)).isEmpty();       // a repeat report never stands in for the other phone
        assertThat(a.state()).isEqualTo(State.READBACK);
        assertThat(say(1, FARMER, YES)).isEmpty();               // one phone is not enough, even for its own person
        assertThat(say(1, BUYER, YES)).isEmpty();

        clock.advance(WINDOW);                                   // the buyer's phone never reports
        assertThat(kinds(a.expire())).containsExactly(EXPIRED);
        assertThat(a.readbackPlayed(1, BUYER)).isEmpty();
        assertThat(a.state()).isEqualTo(State.IDLE);
    }

    @Test
    void aPlaybackReportCountsOnlyForTheCurrentDraftAfterItsReadbackWasSent() {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        assertThat(a.readbackPlayed(1, FARMER)).isEmpty();       // nothing was sent yet: this report cannot be real
        a.sendReadback(1, () -> { });
        assertThat(a.readbackPlayed(0, FARMER)).isEmpty();
        assertThat(a.readbackPlayed(2, FARMER)).isEmpty();

        a.offer(terms("rice", "Common", "Burdwan APMC", "3500", "quintal"), turn()); // draft 2 replaces it
        assertThat(a.readbackPlayed(1, FARMER)).isEmpty();
        assertThat(a.readbackPlayed(1, BUYER)).isEmpty();
        assertThat(a.state()).isEqualTo(State.OFFER_OPEN);
    }

    @Test
    void reportsFromAnEarlierReadbackDoNotCarryIntoTheNextDraft() {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        a.sendReadback(1, () -> { });
        a.readbackPlayed(1, FARMER);
        a.offer(terms("rice", "Common", "Burdwan APMC", "3500", "quintal"), turn());
        say(2, FARMER, YES);
        a.sendReadback(2, () -> { });
        // Only the buyer has played draft 2, so draft 2 must still be waiting for the farmer.
        assertThat(kinds(a.readbackPlayed(2, BUYER))).containsExactly(READBACK_PLAYED);
        assertThat(a.state()).isEqualTo(State.READBACK);
    }

    @Test
    void aReplyThatStartedBeforeBothPhonesFinishedPlayingNeverCounts() {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        a.sendReadback(1, () -> { });
        a.readbackPlayed(1, FARMER);
        Instant buyerStartedSpeaking = clock.instant();
        clock.advance(Duration.ofSeconds(2));
        a.readbackPlayed(1, BUYER);
        assertThat(a.heard(1, BUYER, YES, turn(), buyerStartedSpeaking)).isEmpty();
        assertThat(a.state()).isEqualTo(State.CONFIRMING);
    }

    @Test
    void aNoOrNewTermsSaidDuringTheReadbackCloseTheDraftEvenIfReadAfterItEnded() {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        a.sendReadback(1, () -> { });
        Instant spoken = clock.instant();
        clock.advance(Duration.ofSeconds(2));
        a.readbackPlayed(1, FARMER);
        a.readbackPlayed(1, BUYER);
        assertThat(kinds(a.heard(1, BUYER, NO, turn(), spoken))).containsExactly(DECLINED); // the model answered late

        a.offer(RICE, turn());
        say(2, FARMER, YES);
        assertThat(kinds(say(2, BUYER, CHANGED))).containsExactly(VOIDED);                  // before it was even sent
        assertThat(a.sendReadback(2, () -> { })).isFalse();
    }

    // ---- races ----------------------------------------------------------------------------------------------------

    @Test
    void anOlderBuyerOfferArrivingAfterANewerOneIsDiscarded() {
        Terms newer = terms("rice", "Common", "Burdwan APMC", "3600", "quintal");
        assertThat(kinds(a.offer(newer, 2))).containsExactly(OPENED);
        assertThat(a.offer(RICE, 1)).isEmpty();                  // the slower read of the earlier sentence
        assertThat(a.offer(RICE, 2)).isEmpty();                  // the same turn again
        assertThat(a.version()).isEqualTo(1);
        assertThat(a.ask(FARMER).terms()).isSameAs(newer);

        // Still stale once the newer draft has been closed: it must not reopen as if it were new.
        say(1, FARMER, NO);
        assertThat(a.offer(RICE, 1)).isEmpty();
        assertThat(a.state()).isEqualTo(State.IDLE);
    }

    @Test
    void offersFinishingInAnyOrderLeaveTheNewestTurnsTermsOpen() throws Exception {
        for (int run = 0; run < 25; run++) {
            Agreement agreement = new Agreement(clock, WINDOW, Duration.ofMinutes(5));
            List<Long> turns = new ArrayList<>(LongStream.rangeClosed(1, 8).boxed().toList());
            Collections.shuffle(turns);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = turns.stream().map(t -> Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                agreement.offer(terms("rice", null, null, String.valueOf(3000 + t), "quintal"), t);
            })).toList();
            go.countDown();
            for (Thread t : threads) t.join();
            assertThat(agreement.ask(FARMER).terms().price()).isEqualByComparingTo("3008");
        }
    }

    @Test
    void anOlderReplyIsDiscardedUnlessItIsANo() {
        confirming();
        long older = turn();
        long newer = turn();
        assertThat(kinds(a.heard(1, FARMER, UNCLEAR, newer, clock.instant()))).containsExactly(RETRY);
        // The older yes must not overrule what this person said after it.
        assertThat(a.heard(1, FARMER, YES, older, clock.instant())).isEmpty();
        assertThat(a.state()).isEqualTo(State.CONFIRMING);
        // A no is honored whenever it arrives.
        assertThat(kinds(a.heard(1, FARMER, NO, older, clock.instant()))).containsExactly(DECLINED);
    }

    @Test
    void aNewerOfferCannotSlipInBetweenTheVersionCheckAndTheReadbackBeingSent() throws Exception {
        a.offer(RICE, turn());
        say(1, FARMER, YES);
        Thread[] offering = new Thread[1];
        boolean sent = a.sendReadback(1, () -> {
            offering[0] = Thread.ofPlatform().start(
                    () -> a.offer(terms("rice", "Common", "Burdwan APMC", "3500", "quintal"), turn()));
            long end = System.currentTimeMillis() + 3000;
            while (offering[0].getState() != Thread.State.BLOCKED && System.currentTimeMillis() < end) Thread.onSpinWait();
            assertThat(offering[0].getState()).isEqualTo(Thread.State.BLOCKED); // it waits for the send to finish
            assertThat(a.version()).isEqualTo(1);
        });
        assertThat(sent).isTrue();
        offering[0].join();
        assertThat(a.version()).isEqualTo(2);
        assertThat(a.readbackPlayed(1, FARMER)).isEmpty();
        assertThat(a.sendReadback(1, () -> { })).isFalse();
    }
}
