package com.example.g_one.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.g_one.GOneProperties;
import com.example.g_one.Language;
import com.example.g_one.advisory.AdvisoryService;
import com.example.g_one.agreement.AgreementTest.MutableClock;
import com.example.g_one.agreement.SummarySender.Delivery;
import com.example.g_one.agreement.SummarySender.Summary;
import com.example.g_one.room.Role;
import com.example.g_one.room.Room;
import com.example.g_one.room.RoomEvent;
import com.example.g_one.room.RoomHub;
import com.example.g_one.room.RoomService;
import com.example.g_one.speech.LlmClient;
import com.example.g_one.speech.TimbreClient;
import com.example.g_one.speech.UpstreamException;
import com.example.g_one.turn.Translation;
import com.example.g_one.turn.TranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The agreement end to end without a network: a real room with two participants, a fake model that answers per
 * transcript, a fake translator ("hi-IN|..." / "en-IN|..."), voice and sender. Steps run synchronously.
 */
class AgreementServiceTest {
    private static final String YES = "{\"intent\":\"yes\",\"newTerms\":false}";
    private static final String NO = "{\"intent\":\"no\",\"newTerms\":false}";
    private static final String NONE = "{\"intent\":\"none\",\"newTerms\":false}";
    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MutableClock clock = new MutableClock(T0);
    private final Map<String, String> replies = new HashMap<>();
    private final List<Summary> sent = new ArrayList<>();
    private Supplier<Delivery> senderResult = () -> Delivery.notSent("not_configured");
    private boolean modelFails;
    private boolean translateFails;
    private volatile boolean voiceFails;
    private volatile Role voiceFailsFor;
    private volatile Runnable beforeReadbackVoice = () -> { };
    private int turns;

    private LlmClient llm;
    private Language.Registry languages;
    private Room room;
    private AgreementService svc;

    @BeforeEach
    void setUp() throws Exception {
        GOneProperties props = new GOneProperties(List.of("hi-IN:Hindi:v", "en-IN:English:v"), "", 30, 40, 5, 60, "",
                null, null, null);
        languages = new Language.Registry(props);
        RoomService rooms = new RoomService(props);
        RoomService.Session created = rooms.create(Role.FARMER, "hi-IN", "");
        rooms.join(created.room().id, "en-IN");
        room = created.room();

        llm = mock(LlmClient.class);
        when(llm.askJson(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            if (modelFails) throw new UpstreamException("agreement_reply_failed", "boom");
            return JSON.readTree(replies.getOrDefault(inv.<String>getArgument(2), NONE));
        });
        TranslationService translator = mock(TranslationService.class);
        when(translator.translate(anyString(), anyString(), any())).thenAnswer(inv -> {
            if (translateFails) throw new UpstreamException("translation_failed", "boom");
            String text = inv.getArgument(0);
            return new Translation(inv.<Language>getArgument(2).code() + "|" + text, "spoken|" + text);
        });
        TimbreClient tts = mock(TimbreClient.class);
        when(tts.synthesize(anyString(), any())).thenAnswer(inv -> {
            if (voiceFails || (voiceFailsFor != null
                    && inv.<Language>getArgument(1).code().equals(room.participant(voiceFailsFor).language()))) {
                throw new UpstreamException("tts_failed", "boom");
            }
            if (inv.<String>getArgument(0).contains("Please check these terms")) beforeReadbackVoice.run();
            return new byte[] {1, 2, 3};
        });
        svc = new AgreementService(null, llm, translator, tts, mock(RoomHub.class), languages, s -> {
            sent.add(s);
            return senderResult.get();
        }, clock, Duration.ofSeconds(120));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private String turnId() {
        return "turn" + (++turns);
    }

    private void buyerOffers(String price) {
        String id = turnId();
        svc.handleOffer(room, id, turns, offer(price));
    }

    private static AdvisoryService.Offer offer(String price) {
        return new AdvisoryService.Offer("clear", "rice", "Common", "Burdwan APMC", new BigDecimal(price), "INR", "quintal");
    }

    private void say(Role who, String transcript) {
        String id = turnId();
        svc.handleTurn(room, id, turns, who, transcript, languages.require(room.participant(who).language()), clock.instant());
    }

    /** Both phones report playing the read-back of the current draft to its end. */
    private void bothPlayIt() {
        int version = (Integer) lastState().get("version");
        assertThat(svc.readbackPlayed(room, Role.FARMER, version)).isTrue();
        assertThat(lastState()).containsEntry("state", "readback");
        assertThat(svc.readbackPlayed(room, Role.BUYER, version)).isTrue();
        assertThat(lastState()).containsEntry("state", "confirming");
    }

    private void accepts(String farmerSays, String modelAnswer) {
        replies.put(farmerSays, modelAnswer);
        say(Role.FARMER, farmerSays);
    }

    /** Offer 3400, farmer accepts, read-back played on both phones: both people may now confirm. */
    private void confirming() {
        buyerOffers("3400");
        accepts("Yes I accept", YES);
        assertThat(lastState()).containsEntry("state", "readback");
        bothPlayIt();
    }

    private List<RoomEvent> events(String type) {
        return room.history().stream().filter(e -> e.type().equals(type)).toList();
    }

    private List<Map<String, Object>> viewsFor(Role r, String type) {
        return room.history().stream().filter(e -> e.type().equals(type)).map(e -> e.viewFor(room.id, r))
                .filter(v -> v != null).toList();
    }

    private Map<String, Object> lastState() {
        List<RoomEvent> states = events("agreement");
        return states.get(states.size() - 1).data();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    // ---- the flow ---------------------------------------------------------------------------------------------

    @Test
    void fullVoiceFlowReadsBackToBothThenAgreesOnceWithANonbindingSummary() {
        senderResult = Delivery::delivered;
        buyerOffers("3400");
        assertThat(lastState()).containsEntry("state", "offer_open");

        accepts("Yes I accept", YES);
        String terms = "rice, variety Common, market Burdwan APMC, 3,400 rupees per quintal";
        List<Map<String, Object>> farmerReadback = viewsFor(Role.FARMER, "agreement_readback");
        List<Map<String, Object>> buyerReadback = viewsFor(Role.BUYER, "agreement_readback");
        assertThat(farmerReadback).hasSize(1);
        assertThat(buyerReadback).hasSize(1);
        assertThat(farmerReadback.get(0).get("text").toString()).startsWith("hi-IN|Please check these terms: " + terms);
        assertThat(buyerReadback.get(0).get("text").toString()).startsWith("en-IN|Please check these terms: " + terms);
        // Each person can fetch only their own read-back voice.
        assertThat(farmerReadback.get(0)).containsKey("audioUrl");
        assertThat(buyerReadback.get(0)).containsKey("audioUrl");
        assertThat(farmerReadback.get(0).get("audioUrl")).isNotEqualTo(buyerReadback.get(0).get("audioUrl"));
        assertThat(room.clip(farmerReadback.get(0).get("audioUrl").toString().replaceAll(".*/", "")).owner()).isEqualTo(Role.FARMER);
        assertThat(room.clip(buyerReadback.get(0).get("audioUrl").toString().replaceAll(".*/", "")).owner()).isEqualTo(Role.BUYER);
        assertThat(farmerReadback.get(0)).containsEntry("version", 1);
        assertThat(lastState()).containsEntry("state", "readback");
        assertThat(sent).isEmpty();
        bothPlayIt();

        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        assertThat(map(lastState().get("confirmed"))).containsEntry("farmer", true).containsEntry("buyer", false);
        assertThat(sent).isEmpty();
        say(Role.BUYER, "Yes, I agree");

        assertThat(lastState()).containsEntry("state", "agreed");
        assertThat(map(lastState().get("confirmed"))).containsEntry("farmer", true).containsEntry("buyer", true);
        assertThat(sent).hasSize(1);
        Summary summary = sent.get(0);
        assertThat(summary.id()).isEqualTo(room.id + ":1");
        assertThat(summary.text()).contains("Crop: rice", "Variety: Common", "Market: Burdwan APMC",
                "Price: ₹3,400 per quintal", "10 November 2026, 13:30 IST", "Nonbinding summary", "Not a contract",
                "identities were not verified");
        assertThat(summary.text().toLowerCase()).doesNotContain("smart contract", "legally binding", "locked", "secure", "verified identity");

        for (Role r : Role.values()) {
            List<Map<String, Object>> finals = viewsFor(r, "agreement_final");
            assertThat(finals).hasSize(1);
            assertThat(finals.get(0).get("text").toString()).startsWith(languages.require(room.participant(r).language()).code() + "|")
                    .contains("Nonbinding summary");
            assertThat(finals.get(0)).containsEntry("delivery", "sent").containsEntry("summaryId", room.id + ":1").containsKey("audioUrl");
            assertThat(map(finals.get(0).get("terms"))).containsEntry("price", "3400").containsEntry("unit", "quintal");
        }
    }

    @Test
    void repeatsReplaysSameRecordingAndReconnectsNeverProduceASecondSummary() {
        confirming();
        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        say(Role.BUYER, "Yes, I agree");
        assertThat(sent).hasSize(1);

        // The same recording sent again, a retried upload, more yes replies, and the buyer repeating the same offer.
        for (int i = 0; i < 3; i++) {
            say(Role.FARMER, "Yes, I agree");
            say(Role.BUYER, "Yes, I agree");
        }
        svc.handleTurn(room, "turn-dup", 99, Role.BUYER, "Yes, I agree", languages.require("en-IN"), clock.instant());
        svc.handleTurn(room, "turn-dup", 99, Role.BUYER, "Yes, I agree", languages.require("en-IN"), clock.instant());
        buyerOffers("3400");
        clock.advance(Duration.ofHours(2));
        svc.expire(room);

        assertThat(sent).hasSize(1);
        assertThat(events("agreement_final")).hasSize(2); // one per person, ever
        // A reconnecting phone replays history: each person sees exactly one summary and it is still the agreed one.
        for (Role r : Role.values()) assertThat(viewsFor(r, "agreement_final")).hasSize(1);
        assertThat(lastState()).containsEntry("state", "agreed");
    }

    @Test
    void noModelCallIsMadeWhenNoDraftIsWaitingOnThisSpeaker() {
        say(Role.FARMER, "Yes I accept");   // no offer yet
        say(Role.BUYER, "Yes I accept");
        buyerOffers("3400");
        say(Role.BUYER, "Yes, deal");        // only the farmer's reply can accept an open offer
        verifyNoInteractions(llm);
        assertThat(events("agreement_readback")).isEmpty();
    }

    // ---- negation and conditionals ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" is not a yes even if the model says so")
    @ValueSource(strings = {
            "I do not agree", "I don't agree", "I dont agree", "I didn’t say yes", "Yes, but only if you pay for transport",
            "Yes if the price is higher", "yes unless it rains", "Maybe yes", "Yes?", "Wait, yes", "yes however", "Never",
            "haan lekin agar transport doge", "haan par nahi", "nahi nahi haan", "हाँ लेकिन अगर आप ढुलाई दें", "हाँ, नहीं", "शायद हाँ"})
    void negatedOrConditionalRepliesAreNeverCountedEvenWhenTheModelSaysYes(String transcript) {
        confirming();
        replies.put(transcript, YES); // a model mistake: the code must still refuse
        say(Role.FARMER, transcript);

        assertThat(map(lastState().get("confirmed"))).containsEntry("farmer", false);
        assertThat(lastState()).containsEntry("state", "confirming");
        assertThat(viewsFor(Role.FARMER, "agreement_retry")).hasSize(1); // spoken retry for the speaker
        assertThat(viewsFor(Role.BUYER, "agreement_retry")).isEmpty();
        // The buyer's own clear yes still counts for the buyer alone; the summary waits for the farmer.
        replies.put("Yes, I agree", YES);
        say(Role.BUYER, "Yes, I agree");
        assertThat(map(lastState().get("confirmed"))).containsEntry("buyer", true).containsEntry("farmer", false);
        assertThat(sent).isEmpty();
    }

    @Test
    void aPlainYesInEnglishAndHindiIsAccepted() {
        for (String ok : new String[] {"Yes, I agree", "yes", "Yeah okay", "haan theek hai", "हाँ, मैं सहमत हूँ", "Yes I accept the terms."}) {
            assertThat(AgreementService.plainReply(ok)).as(ok).isTrue();
        }
        assertThat(AgreementService.plainReply("")).isFalse();
        assertThat(AgreementService.plainReply(null)).isFalse();
    }

    @Test
    void onlyAnExplicitYesFromTheModelCountsEverythingElseIsUnclearOrNone() throws Exception {
        String say = "Yes, I agree";
        assertThat(interpret("{\"intent\":\"yes\"}", say)).isEqualTo(Agreement.Intent.YES);
        assertThat(interpret("{\"intent\":\" YES \"}", say)).isEqualTo(Agreement.Intent.YES);
        assertThat(interpret("{\"intent\":\"no\"}", "No")).isEqualTo(Agreement.Intent.NO);
        assertThat(interpret("{\"intent\":\"none\"}", say)).isEqualTo(Agreement.Intent.NONE);
        for (String unknown : new String[] {"{\"intent\":\"maybe\"}", "{}", "{\"intent\":null}", "{\"intent\":7}", "{\"intent\":\"agree\"}"}) {
            assertThat(interpret(unknown, say)).as(unknown).isEqualTo(Agreement.Intent.UNCLEAR);
        }
        // Contradictory output never confirms: a yes that also claims new terms is unclear; a non-yes that claims them voids.
        assertThat(interpret("{\"intent\":\"yes\",\"newTerms\":true}", say)).isEqualTo(Agreement.Intent.UNCLEAR);
        assertThat(interpret("{\"intent\":\"none\",\"newTerms\":true}", "Rice is 3600 these days")).isEqualTo(Agreement.Intent.CHANGED);
        assertThat(interpret("{\"intent\":\"unclear\",\"newTerms\":true}", "make it 3600")).isEqualTo(Agreement.Intent.CHANGED);
        assertThat(interpret("{\"intent\":\"no\",\"newTerms\":true}", "No, 3600")).isEqualTo(Agreement.Intent.NO);
    }

    private static Agreement.Intent interpret(String json, String transcript) throws Exception {
        return AgreementService.interpret(JSON.readTree(json), transcript);
    }

    // ---- spoken retry ------------------------------------------------------------------------------------------------

    @Test
    void anUnclearConfirmationGetsASpokenRetryForTheSpeakerOnlyRestatingTheSameTerms() {
        confirming();
        replies.put("hmm okay maybe", "{\"intent\":\"unclear\",\"newTerms\":false}");
        say(Role.BUYER, "hmm okay maybe");

        List<Map<String, Object>> retry = viewsFor(Role.BUYER, "agreement_retry");
        assertThat(retry).hasSize(1);
        assertThat(retry.get(0).get("text").toString()).startsWith("en-IN|I could not tell whether you agree to these terms: "
                + "rice, variety Common, market Burdwan APMC, 3,400 rupees per quintal").contains("Yes, I agree");
        assertThat(retry.get(0)).containsKey("audioUrl");
        assertThat(room.clip(retry.get(0).get("audioUrl").toString().replaceAll(".*/", "")).owner()).isEqualTo(Role.BUYER);
        assertThat(viewsFor(Role.FARMER, "agreement_retry")).isEmpty();
        assertThat(lastState()).containsEntry("state", "confirming");

        // Nothing was counted; a clear answer after the prompt is.
        replies.put("Yes, I agree", YES);
        say(Role.BUYER, "Yes, I agree");
        assertThat(map(lastState().get("confirmed"))).containsEntry("buyer", true).containsEntry("farmer", false);
    }

    @Test
    void unrelatedTalkDuringConfirmationChangesNothingAndPromptsNobody() {
        confirming();
        say(Role.FARMER, "How is the weather there?"); // model answer defaults to none
        assertThat(events("agreement_retry")).isEmpty();
        assertThat(map(lastState().get("confirmed"))).containsEntry("farmer", false).containsEntry("buyer", false);
    }

    // ---- changed terms, decline, expiry -------------------------------------------------------------------------

    @Test
    void aChangedTermCancelsBothConfirmationsTellsBothAndNeedsAFreshAccept() {
        confirming();
        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");

        buyerOffers("3500");

        for (Role r : Role.values()) {
            List<Map<String, Object>> notices = viewsFor(r, "agreement_notice");
            assertThat(notices).hasSize(1);
            assertThat(notices.get(0)).containsEntry("outcome", "voided");
            assertThat(notices.get(0).get("text").toString()).contains("earlier confirmations are cancelled");
        }
        assertThat(lastState()).containsEntry("state", "offer_open").containsEntry("version", 2);
        assertThat(map(lastState().get("terms"))).containsEntry("price", "3500");
        assertThat(map(lastState().get("confirmed"))).containsEntry("farmer", false).containsEntry("buyer", false);

        say(Role.BUYER, "Yes, I agree");            // nobody can confirm until the new terms are read back
        say(Role.FARMER, "Yes, I agree");           // this is the farmer accepting the new offer: read-back again
        assertThat(viewsFor(Role.FARMER, "agreement_readback")).hasSize(2);
        assertThat(viewsFor(Role.FARMER, "agreement_readback").get(1).get("text").toString()).contains("3,500 rupees");
        assertThat(sent).isEmpty();
        bothPlayIt();
        say(Role.BUYER, "Yes, I agree");
        assertThat(sent).isEmpty();                 // the old yes was not carried over
        say(Role.FARMER, "Yes, I agree");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).text()).contains("₹3,500").doesNotContain("3,400");
    }

    @Test
    void newTermsSpokenInAReplyVoidTheDraftAndOnlyAFreshBuyerOfferOpensThem() {
        confirming();
        replies.put("Make it thirty six hundred", "{\"intent\":\"none\",\"newTerms\":true}");
        say(Role.FARMER, "Make it thirty six hundred");
        assertThat(lastState()).containsEntry("state", "closed").containsEntry("outcome", "voided");
        for (Role r : Role.values()) {
            assertThat(viewsFor(r, "agreement_notice").get(0).get("text").toString())
                    .contains("only when the buyer states them clearly as a new offer");
        }

        // The farmer's spoken number opened nothing: a yes now confirms nothing and costs no model call.
        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        say(Role.BUYER, "Yes, I agree");
        assertThat(lastState()).containsEntry("state", "closed");
        assertThat(sent).isEmpty();

        // The buyer's clear new offer does open them, as a new draft that needs the whole confirmation again.
        buyerOffers("3600");
        assertThat(lastState()).containsEntry("state", "offer_open").containsEntry("version", 2);
        assertThat(map(lastState().get("terms"))).containsEntry("price", "3600");
        say(Role.FARMER, "Yes, I agree");
        bothPlayIt();
        say(Role.FARMER, "Yes, I agree");
        say(Role.BUYER, "Yes, I agree");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).text()).contains("₹3,600");
    }

    @Test
    void aNoDeclinesTellsBothAndBlocksTheSummary() {
        confirming();
        replies.put("No", NO);
        say(Role.BUYER, "No");
        assertThat(lastState()).containsEntry("state", "closed").containsEntry("outcome", "declined");
        for (Role r : Role.values()) {
            assertThat(viewsFor(r, "agreement_notice")).hasSize(1);
            assertThat(viewsFor(r, "agreement_notice").get(0).get("text").toString()).contains("nothing was agreed");
        }
        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        say(Role.BUYER, "Yes, I agree");
        assertThat(sent).isEmpty();
        assertThat(events("agreement_final")).isEmpty();
    }

    @Test
    void theTimeLimitExpiresTheDraftTellsBothAndALateYesCountsForNothing() {
        confirming();
        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        clock.advance(Duration.ofSeconds(121));
        svc.expire(room);

        assertThat(lastState()).containsEntry("state", "closed").containsEntry("outcome", "expired");
        for (Role r : Role.values()) assertThat(viewsFor(r, "agreement_notice")).hasSize(1);
        say(Role.BUYER, "Yes, I agree");
        assertThat(sent).isEmpty();
        svc.expire(room); // the notice is given once
        assertThat(events("agreement_notice")).hasSize(2);
    }

    @Test
    void aLateReplyAloneAlsoExpiresFirstWithoutTheTimer() {
        confirming();
        clock.advance(Duration.ofSeconds(121));
        replies.put("Yes, I agree", YES);
        say(Role.BUYER, "Yes, I agree");
        assertThat(lastState()).containsEntry("outcome", "expired");
        assertThat(sent).isEmpty();
    }

    // ---- role isolation -------------------------------------------------------------------------------------------------

    private static final String CAUTION = "Caution: the buyer's last words sounded like a threat";
    private static final String REFERENCE = "AGMARKNET listed ₹4,500 per quintal for Red onion at Lasalgaon. That is a dated reference";

    @Test
    void nothingTheFarmerWasToldPrivatelyReachesTheBuyerTheSharedSummaryOrTheSender() {
        room.record(new RoomEvent("warning", Role.FARMER, Map.of("turnId", "x", "text", CAUTION), Role.FARMER, null));
        room.record(new RoomEvent("advisory", Role.FARMER, Map.of("turnId", "x", "text", REFERENCE), Role.FARMER, null));
        confirming();
        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        say(Role.BUYER, "Yes, I agree");
        assertThat(sent).hasSize(1);

        String everythingTheBuyerCanSee = String.valueOf(room.history().stream().map(e -> e.viewFor(room.id, Role.BUYER))
                .filter(v -> v != null).toList());
        assertThat(everythingTheBuyerCanSee).doesNotContain("Caution", "threat", "AGMARKNET", "dated reference", "4,500", "Lasalgaon");
        assertThat(everythingTheBuyerCanSee).contains("agreement_final");
        for (String secret : new String[] {"Caution", "threat", "AGMARKNET", "dated reference", "4,500", "Lasalgaon"}) {
            assertThat(sent.get(0).text()).doesNotContain(secret);
            for (Role r : Role.values()) assertThat(String.valueOf(viewsFor(r, "agreement_final"))).doesNotContain(secret);
            assertThat(String.valueOf(events("agreement").stream().map(RoomEvent::data).toList())).doesNotContain(secret);
        }
        // The buyer's read-back, retry and notices are never visible to the farmer, and the other way round.
        for (String type : new String[] {"agreement_readback", "agreement_final"}) {
            for (RoomEvent e : events(type)) {
                Role other = e.audience().other();
                assertThat(e.viewFor(room.id, other)).as(type).isNull();
            }
        }
    }

    @Test
    void aBuyersYesNeverConfirmsForTheFarmer() {
        confirming();
        replies.put("Yes, I agree", YES);
        say(Role.BUYER, "Yes, I agree");
        say(Role.BUYER, "Yes, I agree");
        say(Role.BUYER, "The farmer agrees too");
        assertThat(map(lastState().get("confirmed"))).containsEntry("buyer", true).containsEntry("farmer", false);
        assertThat(sent).isEmpty();
    }

    // ---- failure paths -----------------------------------------------------------------------------------------------------

    @Test
    void aFailingModelCountsNothingAndPromptsTheSpeakerToRepeat() {
        confirming();
        modelFails = true;
        say(Role.FARMER, "Yes, I agree");
        assertThat(map(lastState().get("confirmed"))).containsEntry("farmer", false);
        assertThat(viewsFor(Role.FARMER, "agreement_retry")).hasSize(1);
        assertThat(viewsFor(Role.BUYER, "agreement_retry")).isEmpty();

        modelFails = false;
        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        assertThat(map(lastState().get("confirmed"))).containsEntry("farmer", true);
    }

    @Test
    void aFailingModelWhileAnOfferIsOpenDoesNotAcceptIt() {
        buyerOffers("3400");
        modelFails = true;
        say(Role.FARMER, "Yes I accept");
        assertThat(events("agreement_readback")).isEmpty();
        assertThat(lastState()).containsEntry("state", "offer_open");
    }

    @Test
    void aReadbackThatCannotBeTranslatedFallsBackToAnOpenOfferAndConfirmsNothing() {
        buyerOffers("3400");
        translateFails = true;
        accepts("Yes I accept", YES);
        assertThat(events("agreement_readback")).isEmpty();
        assertThat(lastState()).containsEntry("state", "offer_open").containsEntry("outcome", "readback_failed");

        replies.put("Yes, I agree", YES);
        say(Role.BUYER, "Yes, I agree");
        say(Role.FARMER, "Yes, I agree");
        assertThat(sent).isEmpty();
        assertThat(events("agreement_readback")).isEmpty();

        translateFails = false;                       // the farmer simply tries again
        say(Role.FARMER, "Yes I accept");
        assertThat(events("agreement_readback")).hasSize(2);
        assertThat(lastState()).containsEntry("state", "readback");
        bothPlayIt();
    }

    @Test
    void aVoiceFailureForEitherPersonSendsNoReadbackAndConfirmsNothing() {
        for (Role whoseVoiceFails : Role.values()) {
            buyerOffers(whoseVoiceFails == Role.FARMER ? "3400" : "3500");
            voiceFailsFor = whoseVoiceFails;
            accepts("Yes I accept", YES);

            assertThat(events("agreement_readback")).as("text alone never opens a voice confirmation").isEmpty();
            assertThat(lastState()).containsEntry("state", "offer_open").containsEntry("outcome", "readback_failed");
            int version = (Integer) lastState().get("version");
            assertThat(svc.readbackPlayed(room, Role.FARMER, version)).isFalse();
            assertThat(svc.readbackPlayed(room, Role.BUYER, version)).isFalse();
            replies.put("Yes, I agree", YES);
            say(Role.BUYER, "Yes, I agree");
            assertThat(sent).isEmpty();
            voiceFailsFor = null;
        }

        accepts("Yes I accept", YES);                 // the voice is back: the farmer simply tries again
        assertThat(lastState()).containsEntry("state", "readback");
        bothPlayIt();
    }

    @Test
    void aPhoneThatNeverReportsPlayingTheReadbackMeansNobodyConfirmsAndTheDraftExpires() {
        buyerOffers("3400");
        accepts("Yes I accept", YES);
        assertThat(svc.readbackPlayed(room, Role.FARMER, 1)).isTrue();
        assertThat(svc.readbackPlayed(room, Role.FARMER, 1)).isFalse(); // a repeated report does not count twice
        assertThat(map(lastState().get("readbackPlayed"))).containsEntry("farmer", true).containsEntry("buyer", false);

        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        say(Role.BUYER, "Yes, I agree");
        assertThat(map(lastState().get("confirmed"))).containsEntry("farmer", false).containsEntry("buyer", false);
        assertThat(sent).isEmpty();

        clock.advance(Duration.ofSeconds(121));
        svc.expire(room);
        assertThat(lastState()).containsEntry("state", "closed").containsEntry("outcome", "expired");
        assertThat(svc.readbackPlayed(room, Role.BUYER, 1)).isFalse();
        assertThat(sent).isEmpty();
    }

    /** The race the version check alone could not close: new terms arrive while the read-back voice is being made. */
    @Test
    void aReadbackOvertakenByNewTermsWhileBeingVoicedIsNeverSentAndCannotBeReported() throws Exception {
        buyerOffers("3400");
        CountDownLatch voicing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        beforeReadbackVoice = () -> {
            voicing.countDown();
            try {
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        replies.put("Yes I accept", YES);
        Thread accept = Thread.ofVirtual().start(() -> say(Role.FARMER, "Yes I accept"));
        assertThat(voicing.await(5, TimeUnit.SECONDS)).isTrue();

        beforeReadbackVoice = () -> { };
        svc.handleOffer(room, "newer", 50, offer("3500"));
        finish.countDown();
        accept.join();

        assertThat(events("agreement_readback")).isEmpty();
        assertThat(lastState()).containsEntry("state", "offer_open").containsEntry("version", 2);
        assertThat(svc.readbackPlayed(room, Role.FARMER, 1)).isFalse();
        assertThat(svc.readbackPlayed(room, Role.BUYER, 1)).isFalse();
    }

    @Test
    void anOlderOfferReadSlowlyDoesNotReplaceANewerOneOrVoidItsConfirmations() {
        svc.handleOffer(room, "newer", 2, offer("3600"));
        svc.handleOffer(room, "older", 1, offer("3400"));
        assertThat(events("agreement")).hasSize(1);
        assertThat(map(lastState().get("terms"))).containsEntry("price", "3600");
        assertThat(lastState()).containsEntry("version", 1);
        assertThat(events("agreement_notice")).isEmpty();
    }

    @Test
    void stateUpdatesCarryARisingSequenceSoPhonesCanOrderThem() {
        confirming();
        List<Long> seqs = events("agreement").stream().map(e -> ((Number) e.data().get("seq")).longValue()).toList();
        assertThat(seqs).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void aSenderThatFailsOrRefusesLeavesTheAgreementStandingAndSaysNotSentExactlyOnce() throws Exception {
        for (Supplier<Delivery> failing : List.<Supplier<Delivery>>of(
                () -> { throw new IllegalStateException("provider said: +91 98xxxx secret"); },
                () -> null,
                () -> Delivery.notSent("not_configured"))) {
            sent.clear();
            setUp(); // fresh room and service per iteration
            senderResult = failing;
            confirming();
            replies.put("Yes, I agree", YES);
            say(Role.FARMER, "Yes, I agree");
            say(Role.BUYER, "Yes, I agree");
            say(Role.BUYER, "Yes, I agree");

            assertThat(lastState()).containsEntry("state", "agreed");
            assertThat(sent).hasSize(1);
            for (Role r : Role.values()) {
                List<Map<String, Object>> finals = viewsFor(r, "agreement_final");
                assertThat(finals).hasSize(1);
                assertThat(finals.get(0)).containsEntry("delivery", "not_sent");
                assertThat(finals.get(0).get("reason").toString()).matches("send_failed|not_configured");
                assertThat(finals.get(0).toString()).doesNotContain("provider said", "98xxxx");
            }
        }
    }

    @Test
    void ifTheSummaryCannotBeTranslatedBothStillGetTheEnglishSummaryOnce() {
        confirming();
        translateFails = true;
        replies.put("Yes, I agree", YES);
        say(Role.FARMER, "Yes, I agree");
        say(Role.BUYER, "Yes, I agree");
        assertThat(sent).hasSize(1);
        for (Role r : Role.values()) {
            List<Map<String, Object>> finals = viewsFor(r, "agreement_final");
            assertThat(finals).hasSize(1);
            assertThat(finals.get(0).get("text").toString()).startsWith("G-One demo summary (nonbinding)");
            assertThat(finals.get(0)).doesNotContainKey("audioUrl");
        }
    }

    @Test
    void theSummaryTextIsFixedFromTheSpokenTermsOnly() {
        Terms t = AgreementTest.terms("onion", null, null, "4000", "qtl");
        Summary s = AgreementService.summary("r:3", t, T0);
        assertThat(s.text()).isEqualTo("""
                G-One demo summary (nonbinding)
                Crop: onion
                Variety: not stated
                Market: not stated
                Price: ₹4,000 per quintal
                The farmer and the buyer each said yes by voice to these terms on 10 November 2026, 13:30 IST.
                Nonbinding summary produced by a G-One demo from two spoken confirmations. Not a contract or a payment \
                instruction. The speakers' identities were not verified.""");
    }
}
