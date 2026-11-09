package com.example.g_one.agent.mediation;

import static com.example.g_one.agent.mediation.MediationFixture.NO_CAUTION;
import static com.example.g_one.agent.mediation.MediationFixture.NO_OFFER;
import static com.example.g_one.agent.mediation.MediationFixture.frames;
import static com.example.g_one.agent.mediation.MediationFixture.translation;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.caution.Caution;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.WhisperKind;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.SttListener;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Mediation against fakes: a Hindi farmer and an English buyer unless a test says otherwise. Synthetic data only. */
class MediatorTest {
    private static final String OPENING = MediationLines.HINDI_WHISPER_OPENING;
    private static final String HOLD = MediationLines.ENGLISH_HOLD;

    private final CallId callId = CallId.of("c-mediation");
    private final AtomicInteger ended = new AtomicInteger();
    private MediationFixture f;
    private FakeLeg farmer;
    private FakeLeg buyer;
    private FakeSpeechToText.Session farmerSays;
    private FakeSpeechToText.Session buyerSays;

    @BeforeEach
    void setUp() throws Exception {
        f = new MediationFixture();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private void start(FakeLeg farmerLeg, FakeLeg buyerLeg) {
        farmer = farmerLeg;
        buyer = buyerLeg;
        SttListener farmerListener = f.mediator.start(farmer, buyer, ended::incrementAndGet);
        buyerSays = f.stt.last(); // the mediator opens the buyer's session itself
        // The farmer's session belongs to the call pipeline, which forwards its events; here the test plays that part.
        farmerSays = f.stt.open(farmer.id(), LegRole.FARMER, farmer.language(), farmerListener);
    }

    private void start() {
        start(FakeLeg.farmer(callId), FakeLeg.buyer(callId));
    }

    @Test
    void translatesEachTurnIntoTheListenersLanguageBothWays() {
        start();
        assertThat(buyerSays.role()).isEqualTo(LegRole.BUYER);
        assertThat(f.events(ConsoleEvent.LegJoined.class)).singleElement()
                .satisfies(e -> assertThat(e.role()).isEqualTo(LegRole.BUYER));

        String hindi = "नमस्ते, क्या प्याज़ तैयार है?";
        f.llm.replyText(NO_CAUTION).replyText(translation(hindi, hindi)).replyText(NO_OFFER);
        buyerSays.say("Hello, is the onion ready?");

        assertThat(f.tts.requests()).singleElement().satisfies(r -> {
            assertThat(r.text()).isEqualTo(hindi);
            assertThat(r.language()).isEqualTo(FakeLeg.HINDI);
            assertThat(r.sampleRate()).isEqualTo(8000);
        });
        assertThat(farmer.played()).hasSize(frames(hindi));
        assertThat(buyer.played()).isEmpty(); // raw audio never crosses legs, and the buyer hears nothing back
        JsonAssert.translationRequest(f, 1, "en-IN", "hi-IN", "Hello, is the onion ready?");

        farmer.finishPlayback();
        String english = "Yes, twenty quintals are ready.";
        String spoken = "Yes, twenty quintals are ready.";
        f.llm.replyText(NO_CAUTION).replyText(translation(english, spoken));
        farmerSays.say("हाँ, बीस क्विंटल तैयार है।");

        assertThat(f.tts.requests()).hasSize(2);
        assertThat(f.tts.requests().get(1).language()).isEqualTo(FakeLeg.ENGLISH);
        assertThat(buyer.played()).hasSize(frames(spoken));
        assertThat(f.llm.requests()).hasSize(5); // a farmer turn needs no offer read-out

        List<ConsoleEvent.TurnTranslated> translated = f.events(ConsoleEvent.TurnTranslated.class);
        assertThat(translated).hasSize(2);
        assertThat(translated.get(0).role()).isEqualTo(LegRole.BUYER);
        assertThat(translated.get(0).toRole()).isEqualTo(LegRole.FARMER);
        assertThat(translated.get(0).text()).isEqualTo(hindi);
        assertThat(translated.get(1).role()).isEqualTo(LegRole.FARMER);
        assertThat(translated.get(1).toRole()).isEqualTo(LegRole.BUYER);
        assertThat(translated.get(1).fromLanguage()).isEqualTo("hi-IN");
        assertThat(translated.get(1).toLanguage()).isEqualTo("en-IN");
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).hasSize(2);
        assertThat(f.events(ConsoleEvent.WhisperSent.class)).isEmpty();
        // No model call during mediation offers tools.
        assertThat(f.llm.requests()).allSatisfy(r -> assertThat(r.has("tools")).isFalse());
    }

    @Test
    void aBuyerOfferEarnsAFarmerOnlyPriceWhisperAfterTheTranslatedTurn() {
        start();
        String hindi = "मैं लाल प्याज़ के लिए 4000 रुपये प्रति क्विंटल दूँगा।";
        String whisper = "वह ऑफ़र दर्ज भाव से कम है।";
        f.llm.replyText(NO_CAUTION)
                .replyText(translation(hindi, "मैं लाल प्याज़ के लिए चार हज़ार रुपये प्रति क्विंटल दूँगा।"))
                .replyText("{\"status\":\"clear\",\"commodity\":\"onion\",\"price\":4000,\"currency\":\"INR\","
                        + "\"unit\":\"quintal\"}")
                .replyText(translation(whisper, whisper));
        buyerSays.say("I will pay 4000 rupees per quintal for red onion.");

        // Code wrote the comparison from the dated reference; the model only translated it.
        String body = f.sentData(3).path("text").asText();
        assertThat(body).isEqualTo("that offer of four thousand rupees per quintal is below the AGMARKNET listing of "
                + "four thousand five hundred rupees per quintal for Red onion at Lasalgaon on the fourth of November, "
                + "twenty twenty-six, a dated reference.");
        assertThat(f.sentData(3).path("from").asText()).isEqualTo("en-IN");
        assertThat(f.events(ConsoleEvent.WhisperSent.class)).isEmpty(); // not before the translated turn was heard

        farmer.finishPlayback();

        ConsoleEvent.WhisperSent sent = f.events(ConsoleEvent.WhisperSent.class).getFirst();
        assertThat(sent.kind()).isEqualTo(WhisperKind.PRICE_REFERENCE);
        assertThat(sent.legId()).isEqualTo(farmer.id());
        assertThat(sent.text()).isEqualTo(OPENING + whisper);
        assertThat(f.tts.requests().getLast().text()).startsWith("यह सिर्फ़ आप सुन रहे हैं");
        // The buyer hears one "One moment please" and nothing of the whisper.
        assertThat(buyer.played()).hasSize(frames(HOLD));
        assertThat(f.events(ConsoleEvent.AssistantSaid.class)).singleElement().satisfies(e -> {
            assertThat(e.role()).isEqualTo(LegRole.BUYER);
            assertThat(e.text()).isEqualTo(HOLD);
        });
        assertThat(farmer.played()).hasSize(frames(f.tts.requests().get(0).text()) + frames(OPENING + whisper));
        assertThat(f.events).filteredOn(e -> e instanceof ConsoleEvent.TurnTranslated
                        || e instanceof ConsoleEvent.WhisperSent)
                .extracting(e -> e.getClass().getSimpleName())
                .containsExactly("TurnTranslated", "WhisperSent");
    }

    @Test
    void anOfferAtOrAboveTheReferenceSaysSo() {
        start();
        f.llm.replyText(NO_CAUTION).replyText(translation("५ हज़ार", "पाँच हज़ार"))
                .replyText("{\"status\":\"clear\",\"commodity\":\"Onions\",\"price\":50,\"unit\":\"kg\"}")
                .replyText(translation("ठीक", "ठीक"));
        buyerSays.say("Fifty rupees a kilo for onions.");

        assertThat(f.sentData(3).path("text").asText()).startsWith(
                "that offer of five thousand rupees per quintal is at or above the AGMARKNET listing");
    }

    @Test
    void anUnclearOfferOrAnotherCropGetsNoWhisper() {
        start();
        f.llm.replyText(NO_CAUTION).replyText(translation("गेहूँ?", "गेहूँ?"))
                .replyText("{\"status\":\"clear\",\"commodity\":\"wheat\",\"price\":2000,\"unit\":\"quintal\"}");
        buyerSays.say("Two thousand for wheat?");
        farmer.finishPlayback();
        f.llm.replyText(NO_CAUTION).replyText(translation("कुछ कम?", "कुछ कम?"))
                .replyText("{\"status\":\"unclear\",\"commodity\":\"onion\"}");
        buyerSays.say("Maybe a bit less?");
        farmer.finishPlayback();

        assertThat(f.llm.requests()).hasSize(6);
        assertThat(f.events(ConsoleEvent.WhisperSent.class)).isEmpty();
        assertThat(buyer.played()).isEmpty();
    }

    @Test
    void anOtpRequestEarnsAFarmerOnlyCautionFirstThatNeverEchoesTheTurn() {
        start();
        String request = "Tell me the OTP on your phone so I can send the money.";
        String hindiRequest = "अपने फ़ोन पर आया OTP बताइए ताकि मैं पैसे भेज सकूँ।";
        String caution = "सावधान: पैसे लेने के लिए कभी PIN या OTP नहीं लगता।";
        f.llm.replyText("{\"status\":\"ok\",\"secretRequest\":true,\"secretShare\":false}")
                .replyText(translation(hindiRequest, hindiRequest))
                .replyText(NO_OFFER)
                .replyText(translation(caution, caution));
        buyerSays.say(request);

        // The caution is our fixed wording, not anything the buyer said.
        assertThat(f.sentData(3).path("text").asText()).isEqualTo(Caution.SECRET_REQUEST.text(LegRole.BUYER));
        assertThat(f.sentData(0).path("text").asText()).isEqualTo(request); // the classifier gets it as a data field
        assertThat(f.systemPrompt(0)).doesNotContain(request);

        ConsoleEvent.WhisperSent sent = f.events(ConsoleEvent.WhisperSent.class).getFirst();
        assertThat(sent.kind()).isEqualTo(WhisperKind.CAUTION);
        assertThat(sent.legId()).isEqualTo(farmer.id());
        assertThat(sent.text()).isEqualTo(OPENING + caution);
        // The farmer hears the caution first; the translated request waits until it was heard.
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).isEmpty();
        assertThat(farmer.played()).hasSize(frames(OPENING + caution));
        assertThat(buyer.played()).hasSize(frames(HOLD));

        farmer.finishPlayback();

        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).singleElement()
                .satisfies(t -> assertThat(t.text()).isEqualTo(hindiRequest));
        assertThat(farmer.played()).hasSize(frames(OPENING + caution) + frames(hindiRequest));
        assertThat(buyer.played()).hasSize(frames(HOLD)); // one "One moment please", never a whisper
    }

    @Test
    void aSecretTheFarmerSaysAloudEarnsACautionThatNeverRepeatsIt() {
        start();
        String secret = "482913";
        String caution = "सावधान: आपकी बात में कोई निजी सुरक्षा जानकारी लगी।";
        f.llm.replyText("{\"status\":\"ok\",\"secretRequest\":false,\"secretShare\":true}")
                .replyText(translation("My OTP is " + secret + ".", "My OTP is four eight two nine one three."))
                .replyText(translation(caution, caution));
        farmerSays.say("मेरा OTP " + secret + " है।");

        assertThat(f.sentData(2).path("text").asText()).isEqualTo(Caution.SECRET_SHARE.text(LegRole.FARMER));
        assertThat(f.events(ConsoleEvent.WhisperSent.class)).isEmpty(); // after the turn reached the buyer
        buyer.finishPlayback();

        ConsoleEvent.WhisperSent sent = f.events(ConsoleEvent.WhisperSent.class).getFirst();
        assertThat(sent.kind()).isEqualTo(WhisperKind.CAUTION);
        assertThat(sent.text()).doesNotContain(secret);
        assertThat(f.tts.requests()).filteredOn(r -> r.language().equals(FakeLeg.HINDI))
                .allSatisfy(r -> assertThat(r.text()).doesNotContain(secret).doesNotContain("चार आठ"));
        assertThat(f.events(ConsoleEvent.AssistantSaid.class)).singleElement()
                .satisfies(e -> assertThat(e.role()).isEqualTo(LegRole.BUYER));
    }

    @Test
    void aCautionMeantForTheBuyerIsNeverSpokenOnTheBuyerLeg() {
        start();
        String english = "Pay more or you will regret it.";
        f.llm.replyText("{\"status\":\"threat\",\"secretRequest\":false,\"secretShare\":false}")
                .replyText(translation(english, english));
        farmerSays.say("ज़्यादा दो वरना पछताओगे।");

        assertThat(f.llm.requests()).hasSize(2); // no caution wording was even prepared for the buyer
        assertThat(f.events(ConsoleEvent.WhisperSent.class)).isEmpty();
        assertThat(f.tts.requests()).singleElement().satisfies(r -> assertThat(r.text()).isEqualTo(english));
        assertThat(buyer.played()).hasSize(frames(english));
    }

    @Test
    void sameLanguageIsReVoicedVerbatimWithoutATranslationCall() {
        start(FakeLeg.farmer(callId),
                new FakeLeg(callId, LegRole.BUYER, LegSource.TWILIO, FakeLeg.HINDI, FakeLeg.BUYER_KEY));
        String turn = "प्याज़ कितने का दोगे?";
        f.llm.replyText(NO_CAUTION).replyText(NO_OFFER);
        buyerSays.say(turn);

        assertThat(f.llm.requests()).hasSize(2);
        assertThat(f.llm.requests()).noneSatisfy(r ->
                assertThat(r.path("messages").get(0).path("content").asText()).isEqualTo(Translator.INSTRUCTIONS));
        assertThat(f.tts.requests()).singleElement().satisfies(r -> assertThat(r.text()).isEqualTo(turn));
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).singleElement().satisfies(t -> {
            assertThat(t.text()).isEqualTo(turn);
            assertThat(t.fromLanguage()).isEqualTo(t.toLanguage());
        });
    }

    @Test
    void turnsHeardWhileTheFloorIsBusyWaitInOrderAndAreNeverDropped() {
        start();
        f.llm.replyText(NO_CAUTION).replyText(translation("एक", "एक")).replyText(NO_OFFER)
                .replyText(NO_CAUTION).replyText(translation("दो", "दो")).replyText(NO_OFFER)
                .replyText(NO_CAUTION).replyText(translation("तीन", "तीन")).replyText(NO_OFFER);
        buyerSays.say("one");
        buyerSays.say("two");
        buyerSays.say("three");

        // Only the first turn is handled while the farmer still hears it.
        assertThat(f.llm.requests()).hasSize(3);
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).hasSize(3);
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly("एक");

        farmer.finishPlayback();
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly("एक", "दो");
        farmer.finishPlayback();

        assertThat(f.llm.requests()).hasSize(9);
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).extracting(ConsoleEvent.TurnTranslated::text)
                .containsExactly("एक", "दो", "तीन");
        assertThat(List.of(f.sentData(1), f.sentData(4), f.sentData(7))).extracting(d -> d.path("text").asText())
                .containsExactly("one", "two", "three");
    }

    @Test
    void bargeInStopsPlaybackOnlyOnTheSpeakersOwnLeg() {
        start();
        f.llm.replyText(NO_CAUTION).replyText(translation("लंबा जवाब", "लंबा जवाब")).replyText(NO_OFFER)
                .replyText(NO_CAUTION).replyText(translation("Wait", "Wait"));
        buyerSays.say("A long answer.");

        farmerSays.emitSpeechStart();
        assertThat(farmer.clearCount()).isEqualTo(1);
        assertThat(buyer.clearCount()).isZero();
        farmerSays.emitTurn("रुकिए");

        // The floor was free again, so the farmer's turn went straight through.
        assertThat(buyer.played()).hasSize(frames("Wait"));
    }

    @Test
    void anUntranslatableTurnAsksTheSpeakerToRepeat() {
        start();
        f.llm.replyText(NO_CAUTION).replyText("not json at all").replyText(NO_OFFER);
        buyerSays.say("Hello?");

        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).isEmpty();
        assertThat(farmer.played()).isEmpty();
        assertThat(f.tts.requests()).singleElement()
                .satisfies(r -> assertThat(r.text()).isEqualTo(MediationLines.ENGLISH_NOT_TRANSLATED));
        assertThat(buyer.played()).hasSize(frames(MediationLines.ENGLISH_NOT_TRANSLATED));
    }

    @Test
    void theBuyerHangingUpEndsMediationAndHandsTheFarmerBack() {
        start();
        buyer.remoteHangup();

        assertThat(ended).hasValue(1);
        assertThat(buyerSays.isClosed()).isTrue();
        assertThat(f.events(ConsoleEvent.LegEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.role()).isEqualTo(LegRole.BUYER));
        farmerSays.say("हेलो?");
        assertThat(f.llm.requests()).isEmpty();
    }

    @Test
    void theFarmerHangingUpHangsUpTheBuyer() {
        start();
        farmer.remoteHangup();

        assertThat(buyer.isEnded()).isTrue();
        assertThat(buyer.hungUpByUs()).isTrue();
        assertThat(ended).hasValue(1);
        assertThat(f.events(ConsoleEvent.LegEnded.class)).isEmpty(); // call_ended is already out for this call
    }

    /** Checks on what was sent to the model. */
    private static final class JsonAssert {
        static void translationRequest(MediationFixture f, int i, String from, String to, String text) {
            assertThat(f.systemPrompt(i)).isEqualTo(Translator.INSTRUCTIONS);
            assertThat(f.sentData(i).path("from").asText()).isEqualTo(from);
            assertThat(f.sentData(i).path("to").asText()).isEqualTo(to);
            assertThat(f.sentData(i).path("text").asText()).isEqualTo(text);
        }
    }
}
