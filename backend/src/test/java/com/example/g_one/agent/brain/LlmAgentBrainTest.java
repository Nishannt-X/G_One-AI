package com.example.g_one.agent.brain;

import static com.example.g_one.agent.brain.BrainFixture.ENGLISH;
import static com.example.g_one.agent.brain.BrainFixture.HINDI;
import static com.example.g_one.agent.brain.BrainFixture.said;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Reply;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The agent brain end to end against a fake OpenAI-compatible server: prompts, the tool loop, memory and failures. */
class LlmAgentBrainTest {
    private BrainFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new BrainFixture();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    // ---- greeting ---------------------------------------------------------------------------------------------

    @Test
    void newFarmerHearsTheWelcomeAndTheMemoryNoticeWithoutAModelCall() {
        Reply hi = f.brain.greet(BrainFixture.newFarmer(HINDI));
        assertThat(hi.text()).startsWith("नमस्ते, जी-वन में आपका स्वागत है।").contains("मुझे भूल जाओ");
        assertThat(hi.language()).isEqualTo(HINDI);
        assertThat(hi.endCall()).isFalse();

        Reply en = f.brain.greet(BrainFixture.newFarmer(ENGLISH));
        assertThat(en.text()).startsWith("Welcome to G-One.").contains("forget me");
        assertThat(en.language()).isEqualTo(ENGLISH);
        assertThat(f.llm.requests()).isEmpty();
    }

    @Test
    void withheldCallerHearsThatNothingWillBeRemembered() {
        Reply r = f.brain.greet(BrainFixture.withheld(HINDI));
        assertThat(r.text()).contains("आपका नंबर नहीं दिख रहा").doesNotContain("नोट रखूँगी");
    }

    @Test
    void returningFarmerHearsOnlyTheNameCheckAndNoRememberedFacts() {
        CallContext ctx = BrainFixture.returning(HINDI, f.seedSuresh());
        Reply r = f.brain.greet(ctx);
        assertThat(r.text()).isEqualTo("नमस्ते, जी-वन बोल रही हूँ। क्या मैं Suresh जी से बात कर रही हूँ?");
        assertThat(r.text()).doesNotContain("Lasalgaon").doesNotContain("Nashik");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.NAME_CHECK);

        Reply en = f.brain.greet(BrainFixture.returning(ENGLISH, f.seedSuresh()));
        assertThat(en.text()).isEqualTo("Hello, this is G-One. Am I speaking with Suresh ji?");
    }

    @Test
    void returningMemoryWithoutANameIsNotUsed() {
        FarmerMemory noName = FarmerMemory.of(FakeLeg.FARMER_KEY, List.of(new ProfileFact("district", "Nashik",
                Provenance.said(BrainFixture.PAST_CALL, BrainFixture.PAST_MS))), List.of());
        CallContext ctx = BrainFixture.returning(ENGLISH, noName);
        assertThat(f.brain.greet(ctx).text()).startsWith("Welcome to G-One.");
        f.llm.replyText("Which crop do you want to sell?");
        f.brain.respond(ctx, List.of(), said(ctx, "hello"));
        assertThat(systemPrompt(0)).doesNotContain("Nashik").contains("Nothing remembered may be used");
    }

    // ---- tool loop --------------------------------------------------------------------------------------------

    @Test
    void mandiPriceRoundTripGivesADatedReferenceAndNeverTodaysPrice() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyToolCall("get_mandi_price", "{\"commodity\":\"pyaz\",\"market\":\"Lasalgaon\"}");
        f.llm.replyText("AGMARKNET ने चार नवंबर को लासलगाँव में लाल प्याज़ का भाव चार हज़ार पाँच सौ रुपये प्रति क्विंटल दर्ज किया था।");

        Reply r = f.brain.respond(ctx, List.of(new Line(Speaker.ASSISTANT, "नमस्ते")), said(ctx, "प्याज का भाव क्या है?"));

        assertThat(r.text()).startsWith("AGMARKNET ने चार नवंबर");
        assertThat(r.language()).isEqualTo(HINDI);
        assertThat(r.endCall()).isFalse();
        assertThat(f.llm.requests()).hasSize(2);

        JsonNode first = f.llm.requests().get(0);
        assertThat(toolNames(first)).containsExactly("end_call", "forget_me", "get_mandi_price", "save_profile_fact");
        JsonNode toolMessage = last(f.llm.requests().get(1).path("messages"));
        assertThat(toolMessage.path("role").asText()).isEqualTo("tool");
        String result = toolMessage.path("content").asText();
        assertThat(result).isEqualTo("AGMARKNET listed four thousand five hundred rupees per quintal for Red onion at "
                + "Lasalgaon on the fourth of November, twenty twenty-six, a dated reference.");
        assertThat(result.toLowerCase(Locale.ROOT)).doesNotContain("today").doesNotContain("live").doesNotContain("fair");

        assertThat(f.toolEvents).singleElement().satisfies(e -> {
            assertThat(e.tool()).isEqualTo("get_mandi_price");
            assertThat(e.ok()).isTrue();
            assertThat(e.args().path("commodity").asText()).isEqualTo("pyaz");
            assertThat(e.result()).isEqualTo(result);
            assertThat(e.callId()).isEqualTo(ctx.callId());
        });
    }

    @Test
    void systemPromptCarriesLanguageRulesTodayAndHistoryInOrder() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyText("ठीक है।");
        f.brain.respond(ctx, List.of(
                new Line(Speaker.ASSISTANT, "नमस्ते, जी-वन में आपका स्वागत है।"),
                new Line(Speaker.FARMER, "मुझे प्याज बेचना है")), said(ctx, "बीस क्विंटल"));

        JsonNode messages = f.llm.requests().get(0).path("messages");
        assertThat(systemPrompt(0)).contains("The farmer's language on this call is Hindi.")
                .contains("Hinglish").contains("Never say \"today's price\"")
                .contains("Today is the eighth of November, twenty twenty-six.")
                .contains("Nothing is remembered about this caller yet");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("मुझे प्याज बेचना है");
        assertThat(messages.get(3).path("content").asText()).isEqualTo("बीस क्विंटल");
    }

    @Test
    void replyLanguageFollowsAFarmerWhoSwitchesToEnglish() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyText("Sure, I will speak English. Which crop do you want to sell?");
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "Can you speak English please?"));
        assertThat(r.language()).isEqualTo(ENGLISH);
    }

    @Test
    void endCallToolSetsTheEndCallFlag() {
        CallContext ctx = BrainFixture.newFarmer(ENGLISH);
        f.llm.replyToolCall("end_call", "{}");
        f.llm.replyText("Thank you, goodbye.");
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "That's all, bye."));
        assertThat(r.endCall()).isTrue();
        assertThat(r.text()).isEqualTo("Thank you, goodbye.");
    }

    @Test
    void endCallStillHangsUpWithAGoodbyeWhenTheModelFailsAfterIt() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyToolCall("end_call", "{}");
        // The request after end_call fails: arm the failure as soon as the tool has run.
        f.bus.subscribe(e -> f.llm.failures.set(1));
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "बस, धन्यवाद"));
        assertThat(f.llm.requests()).hasSize(2);
        assertThat(r.endCall()).isTrue();
        assertThat(r.text()).isEqualTo("धन्यवाद। नमस्ते।");
        assertThat(r.language()).isEqualTo(HINDI);
    }

    @Test
    void endCallStillHangsUpWithAGoodbyeWhenTheModelNeverSpeaks() {
        CallContext ctx = BrainFixture.newFarmer(ENGLISH);
        for (int i = 0; i < 4; i++) f.llm.replyToolCall("end_call", "{}");
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "bye"));
        assertThat(r.endCall()).isTrue();
        assertThat(r.text()).isEqualTo("Thank you. Goodbye.");
    }

    @Test
    void toolLoopStopsAfterFourModelCallsAndApologises() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        for (int i = 0; i < 6; i++) f.llm.replyToolCall("get_mandi_price", "{\"commodity\":\"onion\"}");

        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "भाव?"));

        assertThat(f.llm.requests()).hasSize(LlmAgentBrain.MAX_MODEL_CALLS);
        assertThat(f.llm.requests().get(3).has("tools")).as("the last call offers no tools").isFalse();
        assertThat(f.llm.requests().get(2).has("tools")).isTrue();
        assertThat(r.text()).isEqualTo(f.fallback.apology("hi-IN"));
        assertThat(r.language()).isEqualTo(HINDI);
        assertThat(f.toolEvents).hasSize(3);
    }

    @Test
    void unknownToolAndBadArgumentsGoBackToTheModelAsErrors() {
        CallContext ctx = BrainFixture.newFarmer(ENGLISH);
        f.llm.replyToolCall("transfer_money", "{}");
        f.llm.replyToolCall("get_mandi_price", "not json");
        f.llm.replyToolCall("get_mandi_price", "{}");
        f.llm.replyText("Sorry, I could not find that. Which crop?");
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "price"));
        assertThat(r.text()).startsWith("Sorry, I could not find that");
        assertThat(f.toolEvents).extracting(e -> e.ok()).containsExactly(false, false, false);
        assertThat(f.toolEvents.get(2).result()).isEqualTo("commodity is required.");
    }

    // ---- failures ---------------------------------------------------------------------------------------------

    @Test
    void modelFailureGivesTheApologyInHindiAndEnglish() {
        CallContext hi = BrainFixture.newFarmer(HINDI);
        f.llm.failures.set(1);
        Reply r = f.brain.respond(hi, List.of(), said(hi, "नमस्ते"));
        assertThat(r.text()).isEqualTo("माफ़ कीजिए, अभी थोड़ी दिक्कत आ रही है। कृपया एक बार फिर से बोलिए।");
        assertThat(r.language()).isEqualTo(HINDI);
        assertThat(r.endCall()).isFalse();

        CallContext en = BrainFixture.newFarmer(ENGLISH);
        f.llm.failures.set(1);
        Reply e = f.brain.respond(en, List.of(), said(en, "hello"));
        assertThat(e.text()).isEqualTo("Sorry, I am having a little trouble right now. Please say that once more.");
        assertThat(e.language()).isEqualTo(ENGLISH);
    }

    @Test
    void unconfiguredModelAndBlankTurnAlsoGiveTheApology() throws Exception {
        try (BrainFixture off = new BrainFixture(false)) {
            CallContext ctx = BrainFixture.newFarmer(HINDI);
            assertThat(off.brain.respond(ctx, List.of(), said(ctx, "नमस्ते")).text())
                    .isEqualTo(off.fallback.apology("hi-IN"));
            assertThat(off.brain.respond(ctx, null, said(ctx, "   ")).text()).isEqualTo(off.fallback.apology("hi-IN"));
        }
    }

    // ---- memory -----------------------------------------------------------------------------------------------

    @Test
    void returningMemoryIsHiddenUntilTheNameCheckIsAnsweredYes() {
        CallContext ctx = BrainFixture.returning(HINDI, f.seedSuresh());
        f.brain.greet(ctx);
        f.llm.replyToolCall("confirm_name", "{\"confirmed\":true}");
        f.llm.replyText("आपने कहा था कि कटाई के बाद प्याज़ के लिए ख़रीदार ढूँढूँ।");

        f.brain.respond(ctx, List.of(new Line(Speaker.ASSISTANT, "क्या मैं Suresh जी से बात कर रही हूँ?")),
                said(ctx, "हाँ, मैं सुरेश हूँ"));

        assertThat(systemPrompt(0)).doesNotContain("Lasalgaon APMC").doesNotContain("Nashik")
                .contains("call confirm_name");
        assertThat(toolNames(f.llm.requests().get(0))).contains("confirm_name");
        String toolResult = last(f.llm.requests().get(1).path("messages")).path("content").asText();
        assertThat(toolResult).contains("Lasalgaon APMC").contains("NOT verified identity")
                .contains("Find a buyer for about twenty quintal red onion");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.CONFIRMED);

        f.llm.replyText("ठीक है।");
        f.brain.respond(ctx, List.of(), said(ctx, "हाँ, बीस क्विंटल"));
        assertThat(systemPrompt(2)).contains("usualMandi Lasalgaon APMC (said, 2026-11-03)");
        assertThat(toolNames(f.llm.requests().get(2))).doesNotContain("confirm_name");
    }

    @Test
    void aNoToTheNameCheckKeepsMemoryHiddenAndWritesNothing() {
        CallContext ctx = BrainFixture.returning(HINDI, f.seedSuresh());
        f.brain.greet(ctx);
        f.llm.replyToolCall("confirm_name", "{\"confirmed\":false}");
        f.llm.replyToolCall("save_profile_fact", "{\"field\":\"name\",\"value\":\"Ramesh\"}");
        f.llm.replyToolCall("forget_me", "{}");
        f.llm.replyText("ठीक है, आप कौन सी फ़सल बेचना चाहते हैं?");

        f.brain.respond(ctx, List.of(), said(ctx, "नहीं, मैं रमेश हूँ, मुझे भूल जाओ"));

        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.OTHER_SPEAKER);
        assertThat(f.toolEvents).extracting(e -> e.ok()).containsExactly(true, false, false);
        assertThat(f.store.calls).hasValue(0);
        assertThat(f.store.load(FakeLeg.FARMER_KEY).orElseThrow().fact("name").orElseThrow().value()).isEqualTo("Suresh");
        assertThat(String.join("\n", f.toolEvents.stream().map(e -> e.result()).toList())).doesNotContain("Lasalgaon");
    }

    @Test
    void saveProfileFactWritesWithSaidProvenanceForThisCall() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyToolCall("save_profile_fact", "{\"field\":\"district\",\"value\":\"Nashik\"}");
        f.llm.replyText("ठीक है, नासिक।");
        f.brain.respond(ctx, List.of(), said(ctx, "मैं नासिक से हूँ"));

        ProfileFact fact = f.store.load(FakeLeg.FARMER_KEY).orElseThrow().fact("district").orElseThrow();
        assertThat(fact.value()).isEqualTo("Nashik");
        assertThat(fact.provenance().saidOrInferred()).isEqualTo(Provenance.Origin.SAID);
        assertThat(fact.provenance().callId()).isEqualTo(ctx.callId());
        assertThat(fact.provenance().atMs()).isEqualTo(BrainFixture.CLOCK.millis());
    }

    @Test
    void withheldCallerWritesNothingAndForgetsNothing() {
        CallContext ctx = BrainFixture.withheld(HINDI);
        f.llm.replyToolCall("save_profile_fact", "{\"field\":\"name\",\"value\":\"Suresh\"}");
        f.llm.replyToolCall("forget_me", "{}");
        f.llm.replyText("आपका नंबर नहीं दिख रहा, इसलिए कुछ याद नहीं रखा गया।");
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "मेरा नाम सुरेश है, मुझे भूल जाओ"));

        assertThat(r.text()).isNotBlank();
        assertThat(f.store.calls).hasValue(0);
        assertThat(f.toolEvents).extracting(e -> e.ok()).containsExactly(false, false);
        assertThat(systemPrompt(0)).contains("caller ID is withheld");
    }

    @Test
    void forgetMeDeletesTheFarmerAndTurnsMemoryOffForTheCall() {
        CallContext ctx = BrainFixture.returning(HINDI, f.seedSuresh());
        f.gate.answerNameCheck(ctx, true);
        f.llm.replyToolCall("forget_me", "{}");
        f.llm.replyText("ठीक है, मैंने आपकी जानकारी मिटा दी है।");
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "मुझे भूल जाओ"));

        assertThat(r.text()).isEqualTo("ठीक है, मैंने आपकी जानकारी मिटा दी है।");
        assertThat(f.store.load(FakeLeg.FARMER_KEY)).isEmpty();
        assertThat(f.toolEvents.get(0).result()).contains("Tell the farmer exactly: ठीक है, मैंने आपकी जानकारी मिटा दी है।");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.FORGOTTEN);

        f.llm.replyText("ठीक है।");
        f.brain.respond(ctx, List.of(), said(ctx, "और कुछ नहीं"));
        assertThat(systemPrompt(2)).doesNotContain("Lasalgaon APMC").contains("asked to be forgotten");
    }

    @Test
    void callEndedDropsThePerCallState() {
        CallContext ctx = BrainFixture.returning(HINDI, f.seedSuresh());
        f.brain.greet(ctx);
        assertThat(f.brain.memoryState(ctx.callId())).isPresent();
        f.brain.callEnded(ctx.callId());
        assertThat(f.brain.memoryState(ctx.callId())).isEmpty();
    }

    @Test
    void theBrainIsAnAgentBrain() {
        AgentBrain brain = f.brain;
        assertThat(brain).isInstanceOf(LlmAgentBrain.class);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private String systemPrompt(int request) {
        JsonNode first = f.llm.requests().get(request).path("messages").get(0);
        assertThat(first.path("role").asText()).isEqualTo("system");
        return first.path("content").asText();
    }

    private static List<String> toolNames(JsonNode request) {
        List<String> names = new ArrayList<>();
        request.path("tools").forEach(t -> names.add(t.path("function").path("name").asText()));
        return names;
    }

    private static JsonNode last(JsonNode array) {
        return array.get(array.size() - 1);
    }
}
