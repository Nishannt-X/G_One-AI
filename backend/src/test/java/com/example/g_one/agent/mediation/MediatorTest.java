package com.example.g_one.agent.mediation;

import static com.example.g_one.agent.mediation.MediationFixture.NO_CAUTION;
import static com.example.g_one.agent.mediation.MediationFixture.NO_OFFER;
import static com.example.g_one.agent.mediation.MediationFixture.frames;
import static com.example.g_one.agent.mediation.MediationFixture.translation;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.WhisperKind;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.SttListener;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import com.example.g_one.memory.SecretScrubber;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mediation against fakes: a Hindi farmer and an English buyer unless a test says otherwise. Synthetic data only.
 * {@link #start()} settles the buyer's consent first; the assertions after it only look at what came after consent.
 */
class MediatorTest {
    private static final String OPENING = MediationLines.HINDI_WHISPER_OPENING;
    private static final String HOLD = MediationLines.ENGLISH_HOLD;
    private static final String INTRO = MediationLines.buyerIntro(FakeLeg.ENGLISH, FakeLeg.HINDI);
    private static final String JOINED = MediationLines.buyerJoined(FakeLeg.HINDI, FakeLeg.ENGLISH);

    private final CallId callId = CallId.of("c-mediation");
    private final AtomicInteger ended = new AtomicInteger();
    private MediationFixture f;
    private FakeLeg farmer;
    private FakeLeg buyer;
    private FakeSpeechToText.Session farmerSays;
    private FakeSpeechToText.Session buyerSays;
    private int farmerBase;
    private int buyerBase;
    private int ttsBase;

    @BeforeEach
    void setUp() throws Exception {
        f = new MediationFixture();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    /** Starts mediation without settling consent. */
    private void join(FakeLeg farmerLeg, FakeLeg buyerLeg) {
        farmer = farmerLeg;
        buyer = buyerLeg;
        SttListener farmerListener = f.mediator.start(farmer, buyer, ended::incrementAndGet);
        buyerSays = f.stt.last(); // the mediator opens the buyer's session itself
        // The farmer's session belongs to the call pipeline, which forwards its events; here the test plays that part.
        farmerSays = f.stt.open(farmer.id(), LegRole.FARMER, farmer.language(), farmerListener);
    }

    private void join() {
        join(FakeLeg.farmer(callId), FakeLeg.buyer(callId));
    }

    /** Joins, settles consent with a yes, and forgets everything said so far. */
    private void start(FakeLeg farmerLeg, FakeLeg buyerLeg) {
        join(farmerLeg, buyerLeg);
        f.consent(farmer, buyer);
        farmerBase = farmer.played().size();
        buyerBase = buyer.played().size();
        ttsBase = f.tts.requests().size();
        f.events.clear();
    }

    private void start() {
        start(FakeLeg.farmer(callId), FakeLeg.buyer(callId));
    }

    private List<AudioFrame> farmerPlayed() {
        return farmer.played().subList(farmerBase, farmer.played().size());
    }

    private List<AudioFrame> buyerPlayed() {
        return buyer.played().subList(buyerBase, buyer.played().size());
    }

    private List<FakeTextToSpeech.Request> tts() {
        List<FakeTextToSpeech.Request> all = f.tts.requests();
        return all.subList(ttsBase, all.size());
    }

    // ---- buyer consent ----

    @Test
    void theBuyerHearsTheIntroductionAndAYesStartsMediation() {
        join();
        assertThat(f.events(ConsoleEvent.LegJoined.class)).singleElement()
                .satisfies(e -> assertThat(e.role()).isEqualTo(LegRole.BUYER));
        assertThat(f.tts.requests()).singleElement().satisfies(r -> {
            assertThat(r.text()).isEqualTo(INTRO).contains("Please say yes or no.").contains("Hindi");
            assertThat(r.language()).isEqualTo(FakeLeg.ENGLISH);
        });
        assertThat(buyer.played()).hasSize(frames(INTRO));
        assertThat(farmer.played()).isEmpty(); // only the buyer hears the introduction

        // "Hello?" while the introduction plays neither cuts it nor counts as the answer.
        buyerSays.emitSpeechStart();
        assertThat(buyer.clearCount()).isZero();
        buyer.finishPlayback();
        buyerSays.emitTurn("Hello?");
        assertThat(f.tts.requests()).hasSize(1);

        buyerSays.say("Yes");
        assertThat(f.tts.requests()).hasSize(2);
        assertThat(f.tts.requests().get(1).text()).isEqualTo(JOINED).startsWith("ख़रीदार लाइन पर हैं").contains("अंग्रेज़ी");
        assertThat(farmer.played()).hasSize(frames(JOINED));
        assertThat(f.llm.requests()).isEmpty(); // consent is decided in code, never by the model
        assertThat(ended).hasValue(0);

        // Now turns flow.
        farmer.finishPlayback();
        f.llm.replyText(NO_CAUTION).replyText(translation("नमस्ते", "नमस्ते")).replyText(NO_OFFER);
        buyerSays.say("Hello");
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).hasSize(1);
    }

    @Test
    void aNoEndsTheBuyerLegAndTellsTheFarmer() {
        join();
        buyer.finishPlayback();
        buyerSays.say("No, not now");
        assertBuyerDeclined();
    }

    @Test
    void anUnclearAnswerIsANo() {
        join();
        buyer.finishPlayback();
        buyerSays.say("Yes but call me later");
        assertBuyerDeclined();
    }

    @Test
    void noAnswerWithinTheConsentTimeoutIsANo() {
        join();
        buyer.finishPlayback();
        assertThat(f.tts.requests()).hasSize(1);
        f.fireTimers();
        assertBuyerDeclined();
    }

    private void assertBuyerDeclined() {
        String apology = MediationLines.ENGLISH_BUYER_APOLOGY;
        String told = MediationLines.HINDI_BUYER_DECLINED;
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly(INTRO, apology);
        assertThat(buyer.played()).hasSize(frames(INTRO) + frames(apology));
        assertThat(buyer.isEnded()).isFalse(); // the apology is heard before the leg ends
        buyer.finishPlayback();
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly(INTRO, apology, told);
        assertThat(farmer.played()).hasSize(frames(told));
        farmer.finishPlayback();

        assertThat(buyer.isEnded()).isTrue();
        assertThat(buyer.hungUpByUs()).isTrue();
        assertThat(ended).hasValue(1); // the farmer goes back to the assistant
        assertThat(f.events(ConsoleEvent.LegEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("no_consent"));
        assertThat(f.llm.requests()).isEmpty();
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).isEmpty();
    }

    @Test
    void nothingTheFarmerSaysBeforeTheBuyersYesIsRelayed() {
        join();
        farmerSays.say("हेलो? कौन है?");
        assertThat(f.llm.requests()).isEmpty();
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly(INTRO);
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).isEmpty();
    }

    @Test
    void aTurnThatStartedBeforeTheBuyersYesIsDroppedEvenIfItEndsAfterIt() {
        join();
        buyer.finishPlayback();
        farmerSays.emitSpeechStart(); // the farmer starts talking while consent is still open
        buyerSays.say("Yes");
        farmer.finishPlayback();
        farmerSays.emitTurn("पहले मेरी बात सुनो"); // its final transcript arrives after the yes

        assertThat(f.llm.requests()).isEmpty();
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).isEmpty();
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).extracting(ConsoleEvent.TurnHeard::role)
                .containsExactly(LegRole.BUYER); // only the buyer's yes
        assertThat(buyer.played()).hasSize(frames(INTRO));

        // The farmer's next utterance, started after the yes, is relayed.
        f.llm.replyText(NO_CAUTION).replyText(translation("Hello", "Hello"));
        farmerSays.say("नमस्ते");
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).hasSize(1);
    }

    @Test
    void anIntroductionThatCouldNotBePlayedIsTriedOnceMoreBeforeConsentCounts() {
        f.failingSpeaks.set(1);
        join();
        // The first attempt failed to synthesise; the buyer hears the retry, and nothing before it is heard counts.
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly(INTRO);
        assertThat(buyer.played()).hasSize(frames(INTRO));
        buyerSays.say("Yes"); // still playing: does not count
        assertThat(f.tts.requests()).hasSize(1);

        buyer.finishPlayback();
        buyerSays.say("Yes");
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly(INTRO, JOINED);
        assertThat(ended).hasValue(0);
    }

    @Test
    void anIntroductionThatFailsTwiceIsADeclineAndAYesCannotAcceptIt() {
        f.failingSpeaks.set(2);
        join();
        buyerSays.say("Yes"); // the buyer never heard the disclosure, so this is not consent
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).isEmpty();

        String apology = MediationLines.ENGLISH_BUYER_APOLOGY;
        String told = MediationLines.HINDI_BUYER_DECLINED;
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly(apology);
        buyer.finishPlayback();
        assertThat(f.tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly(apology, told);
        farmer.finishPlayback();

        assertThat(buyer.hungUpByUs()).isTrue();
        assertThat(ended).hasValue(1);
        assertThat(f.events(ConsoleEvent.LegEnded.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("no_consent"));
        assertThat(f.llm.requests()).isEmpty();
    }

    // ---- turns ----

    @Test
    void translatesEachTurnIntoTheListenersLanguageBothWays() {
        start();
        assertThat(buyerSays.role()).isEqualTo(LegRole.BUYER);

        String hindi = "नमस्ते, क्या प्याज़ तैयार है?";
        f.llm.replyText(NO_CAUTION).replyText(translation(hindi, hindi)).replyText(NO_OFFER);
        buyerSays.say("Hello, is the onion ready?");

        assertThat(tts()).singleElement().satisfies(r -> {
            assertThat(r.text()).isEqualTo(hindi);
            assertThat(r.language()).isEqualTo(FakeLeg.HINDI);
            assertThat(r.sampleRate()).isEqualTo(8000);
        });
        assertThat(farmerPlayed()).hasSize(frames(hindi));
        assertThat(buyerPlayed()).isEmpty(); // raw audio never crosses legs, and the buyer hears nothing back
        JsonAssert.translationRequest(f, 1, "en-IN", "hi-IN", "Hello, is the onion ready?");

        farmer.finishPlayback();
        String english = "Yes, twenty quintals are ready.";
        String spoken = "Yes, twenty quintals are ready.";
        f.llm.replyText(NO_CAUTION).replyText(translation(english, spoken));
        farmerSays.say("हाँ, बीस क्विंटल तैयार है।");

        assertThat(tts()).hasSize(2);
        assertThat(tts().get(1).language()).isEqualTo(FakeLeg.ENGLISH);
        assertThat(buyerPlayed()).hasSize(frames(spoken));
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
        assertThat(tts().getLast().text()).startsWith("यह सिर्फ़ आप सुन रहे हैं");
        // The buyer hears one "One moment please" and nothing of the whisper.
        assertThat(buyerPlayed()).hasSize(frames(HOLD));
        assertThat(f.events(ConsoleEvent.AssistantSaid.class)).singleElement().satisfies(e -> {
            assertThat(e.role()).isEqualTo(LegRole.BUYER);
            assertThat(e.text()).isEqualTo(HOLD);
        });
        assertThat(farmerPlayed()).hasSize(frames(tts().get(0).text()) + frames(OPENING + whisper));
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
    void anOfferJustBelowTheReferenceIsBelowEvenWhenItRoundsUpToIt() {
        start();
        f.llm.replyText(NO_CAUTION).replyText(translation("ठीक", "ठीक"))
                .replyText("{\"status\":\"clear\",\"commodity\":\"onion\",\"price\":4499.6,\"unit\":\"quintal\"}")
                .replyText(translation("कम", "कम"));
        buyerSays.say("Four thousand four hundred ninety nine rupees sixty paise a quintal.");

        // 4,499.60 rounds to 4,500 but is below 4,500; the spoken numbers must not contradict "below".
        assertThat(f.sentData(3).path("text").asText()).startsWith(
                "that offer of four thousand four hundred ninety-nine rupees per quintal is below the AGMARKNET listing "
                        + "of four thousand five hundred rupees");
    }

    @Test
    void anOfferPerTonneJustBelowTheReferenceIsComparedWithoutRounding() {
        start();
        f.llm.replyText(NO_CAUTION).replyText(translation("ठीक", "ठीक"))
                .replyText("{\"status\":\"clear\",\"commodity\":\"onion\",\"price\":44999.96,\"unit\":\"tonne\"}")
                .replyText(translation("कम", "कम"));
        buyerSays.say("Forty four thousand nine hundred ninety nine rupees ninety six paise a tonne.");

        // 44,999.96 per tonne is 4,499.996 per quintal: below 4,500, though two-decimal rounding would make it 4,500.
        assertThat(PriceWhisper.inUnit(new BigDecimal("44999.96"), "tonne", "quintal")).hasValueSatisfying(
                v -> assertThat(v).isEqualByComparingTo("4499.996"));
        assertThat(f.sentData(3).path("text").asText()).startsWith(
                "that offer of four thousand four hundred ninety-nine rupees per quintal is below the AGMARKNET listing "
                        + "of four thousand five hundred rupees");
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
        assertThat(buyerPlayed()).isEmpty();
    }

    // ---- cautions and secrets ----

    @Test
    void anOtpRequestEarnsTheFixedFarmerOnlyCautionFirstWithNoModelWording() {
        start();
        String request = "Tell me the OTP on your phone so I can send the money.";
        String hindiRequest = "अपने फ़ोन पर आया OTP बताइए ताकि मैं पैसे भेज सकूँ।";
        String caution = MediationLines.HINDI_CAUTION_REQUEST;
        assertThat(caution).isEqualTo("यह सिर्फ़ आप सुन रहे हैं: पैसे लेने के लिए कभी PIN या OTP नहीं लगता। "
                + "किसी को मत बताइए।"); // docs/call-flow.md, "Caution (fixed)"
        f.llm.replyText("{\"status\":\"ok\",\"secretRequest\":true,\"secretShare\":false}")
                .replyText(translation(hindiRequest, hindiRequest))
                .replyText(NO_OFFER);
        buyerSays.say(request);

        assertThat(f.llm.requests()).hasSize(3); // classifier, translation, offer read-out: the caution is not translated
        assertThat(f.sentData(0).path("text").asText()).isEqualTo(request); // the classifier gets it as a data field
        assertThat(f.systemPrompt(0)).doesNotContain(request);

        ConsoleEvent.WhisperSent sent = f.events(ConsoleEvent.WhisperSent.class).getFirst();
        assertThat(sent.kind()).isEqualTo(WhisperKind.CAUTION);
        assertThat(sent.legId()).isEqualTo(farmer.id());
        assertThat(sent.language()).isEqualTo("hi-IN");
        assertThat(sent.text()).isEqualTo(caution);
        assertThat(tts().get(1).text()).isEqualTo(caution);
        // The farmer hears the caution first; the translated request waits until it was heard.
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).isEmpty();
        assertThat(farmerPlayed()).hasSize(frames(caution));
        assertThat(buyerPlayed()).hasSize(frames(HOLD));

        farmer.finishPlayback();

        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).singleElement()
                .satisfies(t -> assertThat(t.text()).isEqualTo(hindiRequest));
        assertThat(farmerPlayed()).hasSize(frames(caution) + frames(hindiRequest));
        assertThat(buyerPlayed()).hasSize(frames(HOLD)); // one "One moment please", never a whisper
    }

    @Test
    void aFarmerWhoIsNotHindiGetsTheEnglishFixedCaution() {
        Language hinglish = new Language("hi-en", "Hinglish", "Poorvi");
        start(new FakeLeg(callId, LegRole.FARMER, LegSource.TWILIO, hinglish, FakeLeg.FARMER_KEY),
                FakeLeg.buyer(callId));
        f.llm.replyText("{\"status\":\"threat\",\"secretRequest\":false,\"secretShare\":false}")
                .replyText(translation("Paise do warna...", "Paise do warna...")).replyText(NO_OFFER);
        buyerSays.say("Pay up or else.");

        ConsoleEvent.WhisperSent sent = f.events(ConsoleEvent.WhisperSent.class).getFirst();
        assertThat(sent.text()).isEqualTo(MediationLines.ENGLISH_CAUTION_THREAT);
        assertThat(sent.language()).isEqualTo("en-IN");
        assertThat(f.llm.requests()).hasSize(3);
    }

    @Test
    void aSecretTheRedactorRemovesNeverReachesTheConsoleTheModelsOrTheBuyer() {
        start();
        String secret = "482913";
        String redacted = "मेरा OTP " + SecretScrubber.REMOVED + " है।";
        f.llm.replyText(NO_CAUTION)
                .replyText(translation("My OTP is [number removed].", "My OTP is number removed."));
        farmerSays.say("मेरा OTP " + secret + " है।");

        // Neither the console, the classifier nor the translator ever saw the secret.
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).singleElement()
                .satisfies(e -> assertThat(e.text()).isEqualTo(redacted));
        assertThat(f.sentData(0).path("text").asText()).isEqualTo(redacted);
        assertThat(f.sentData(1).path("text").asText()).isEqualTo(redacted);
        assertThat(f.llm.requests()).hasSize(2);
        assertThat(f.llm.requests()).allSatisfy(r -> assertThat(r.toString()).doesNotContain(secret));
        assertThat(f.events).allSatisfy(e -> assertThat(e.toString()).doesNotContain(secret));
        assertThat(tts()).allSatisfy(r -> assertThat(r.text()).doesNotContain(secret));
        assertThat(buyerPlayed()).hasSize(frames("My OTP is number removed."));
    }

    @Test
    void aCodeSpokenAsDigitWordsAfterAPinLabelIsRedacted() {
        start();
        f.llm.replyText(NO_CAUTION)
                .replyText(translation("मेरा PIN [number removed] है।", "मेरा PIN नंबर हटाया गया है।"))
                .replyText(NO_OFFER);
        buyerSays.say("My PIN is four eight two nine.");

        assertThat(f.events(ConsoleEvent.TurnHeard.class)).singleElement()
                .satisfies(e -> assertThat(e.text()).isEqualTo("My PIN is " + SecretScrubber.REMOVED + "."));
        assertThat(f.llm.requests()).allSatisfy(r -> assertThat(r.toString()).doesNotContain("four eight"));
        assertThat(TurnRedactor.redact("mera OTP chaar aath do nau hai").text())
                .isEqualTo("mera OTP " + SecretScrubber.REMOVED + " hai");
        assertThat(TurnRedactor.redact("ओटीपी चार आठ दो नौ").text()).isEqualTo("ओटीपी " + SecretScrubber.REMOVED);
        // Not a secret: a price in words, and a label with too few digit words.
        assertThat(TurnRedactor.redact("twenty quintals at four thousand").changed()).isFalse();
        assertThat(TurnRedactor.redact("what is the pin code, one?").changed()).isFalse();
    }

    @Test
    void aPasswordTheRedactorCannotRemoveNeverReachesTheConsoleOrTheOtherLeg() {
        start();
        String said = "my password is mango";
        assertThat(TurnRedactor.redact(said).changed()).isFalse(); // the redactor cannot recognise it
        f.llm.replyText("{\"status\":\"ok\",\"secretRequest\":false,\"secretShare\":true}");
        farmerSays.say(said);

        assertWithheld("mango");
    }

    @Test
    void whenTheClassifierFailsTheTurnIsWithheld() {
        start();
        f.llm.replyText("not json");
        farmerSays.say("my password is mango");

        assertWithheld("mango");
    }

    @Test
    void aSecretShareIsWithheldEvenWhenTheRedactorRemovedAnUnrelatedNumber() {
        start();
        String said = "मेरा खाता 000111222333 है और पासवर्ड mango है";
        assertThat(TurnRedactor.redact(said).changed()).isTrue(); // the account number was removed, the password not
        f.llm.replyText("{\"status\":\"ok\",\"secretRequest\":false,\"secretShare\":true}");
        farmerSays.say(said);

        assertWithheld("mango");
    }

    /** Only the classifier ran; the console and the buyer got nothing; the farmer was asked to say it again. */
    private void assertWithheld(String secret) {
        assertThat(f.llm.requests()).hasSize(1); // no translation, no read-out
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).isEmpty();
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).isEmpty();
        assertThat(f.events(ConsoleEvent.WhisperSent.class)).isEmpty();
        assertThat(f.events).allSatisfy(e -> assertThat(e.toString()).doesNotContain(secret));
        assertThat(buyerPlayed()).isEmpty();
        assertThat(tts()).extracting(FakeTextToSpeech.Request::text).containsExactly(MediationLines.HINDI_WITHHELD);
        assertThat(farmerPlayed()).hasSize(frames(MediationLines.HINDI_WITHHELD));

        // The floor is free again: the next clean turn goes through.
        farmer.finishPlayback();
        f.llm.replyText(NO_CAUTION).replyText(translation("Twenty quintals.", "Twenty quintals."));
        farmerSays.say("बीस क्विंटल।");
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).hasSize(1);
        assertThat(buyerPlayed()).hasSize(frames("Twenty quintals."));
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
        assertThat(tts()).singleElement().satisfies(r -> assertThat(r.text()).isEqualTo(english));
        assertThat(buyerPlayed()).hasSize(frames(english));
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
        assertThat(tts()).singleElement().satisfies(r -> assertThat(r.text()).isEqualTo(turn));
        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).singleElement().satisfies(t -> {
            assertThat(t.text()).isEqualTo(turn);
            assertThat(t.fromLanguage()).isEqualTo(t.toLanguage());
        });
    }

    // ---- floor, barge-in, the end ----

    @Test
    void turnsHeardWhileTheFloorIsBusyWaitInOrderAndAreNeverDropped() {
        start();
        f.llm.replyText(NO_CAUTION).replyText(translation("एक", "एक")).replyText(NO_OFFER)
                .replyText(NO_CAUTION).replyText(translation("दो", "दो")).replyText(NO_OFFER)
                .replyText(NO_CAUTION).replyText(translation("तीन", "तीन")).replyText(NO_OFFER);
        buyerSays.say("one");
        buyerSays.say("two");
        buyerSays.say("three");

        // Only the first turn is handled while the farmer still hears it; the others are not published until screened.
        assertThat(f.llm.requests()).hasSize(3);
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).hasSize(1);
        assertThat(tts()).extracting(FakeTextToSpeech.Request::text).containsExactly("एक");

        farmer.finishPlayback();
        assertThat(tts()).extracting(FakeTextToSpeech.Request::text).containsExactly("एक", "दो");
        farmer.finishPlayback();

        assertThat(f.llm.requests()).hasSize(9);
        assertThat(f.events(ConsoleEvent.TurnHeard.class)).hasSize(3);
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
        assertThat(buyerPlayed()).hasSize(frames("Wait"));
    }

    @Test
    void anUntranslatableTurnAsksTheSpeakerToRepeat() {
        start();
        f.llm.replyText(NO_CAUTION).replyText("not json at all").replyText(NO_OFFER);
        buyerSays.say("Hello?");

        assertThat(f.events(ConsoleEvent.TurnTranslated.class)).isEmpty();
        assertThat(farmerPlayed()).isEmpty();
        assertThat(tts()).singleElement()
                .satisfies(r -> assertThat(r.text()).isEqualTo(MediationLines.ENGLISH_NOT_TRANSLATED));
        assertThat(buyerPlayed()).hasSize(frames(MediationLines.ENGLISH_NOT_TRANSLATED));
    }

    @Test
    void theBuyerHangingUpEndsMediationAndHandsTheFarmerBack() {
        start();
        buyer.remoteHangup();

        assertThat(ended).hasValue(1);
        assertThat(buyerSays.isClosed()).isTrue();
        assertThat(f.events(ConsoleEvent.LegEnded.class)).singleElement().satisfies(e -> {
            assertThat(e.role()).isEqualTo(LegRole.BUYER);
            assertThat(e.reason()).isEqualTo("hangup");
        });
        farmerSays.say("हेलो?");
        assertThat(f.llm.requests()).isEmpty();
    }

    @Test
    void theBuyerHangingUpClearsAQueuedWhisperSoNothingPlaysOnAfterward() {
        start();
        String whisper = "वह ऑफ़र दर्ज भाव से कम है।";
        f.llm.replyText(NO_CAUTION).replyText(translation("चार हज़ार", "चार हज़ार"))
                .replyText("{\"status\":\"clear\",\"commodity\":\"onion\",\"price\":4000,\"unit\":\"quintal\"}")
                .replyText(translation(whisper, whisper));
        buyerSays.say("Four thousand a quintal.");
        farmer.finishPlayback(); // the translated offer was heard; the whisper is now queued on the farmer leg
        assertThat(f.events(ConsoleEvent.WhisperSent.class)).hasSize(1);
        int clearsBefore = farmer.clearCount();
        assertThat(farmer.unheardFrames()).isPositive();

        buyer.remoteHangup();

        assertThat(farmer.clearCount()).isEqualTo(clearsBefore + 1); // the whisper is dropped, not played over the brain
        assertThat(farmer.unheardFrames()).isZero();
        int ttsAfter = f.tts.requests().size();
        farmer.finishPlayback();
        f.fireTimers();
        assertThat(f.tts.requests()).hasSize(ttsAfter);
    }

    @Test
    void theFarmerHangingUpClearsTheBuyerLegAndHangsItUp() {
        start();
        f.llm.replyText(NO_CAUTION).replyText(translation("A long reply", "A long reply"));
        farmerSays.say("लंबा जवाब");
        assertThat(buyer.unheardFrames()).isPositive();

        farmer.remoteHangup();

        assertThat(buyer.clearCount()).isEqualTo(1);
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
