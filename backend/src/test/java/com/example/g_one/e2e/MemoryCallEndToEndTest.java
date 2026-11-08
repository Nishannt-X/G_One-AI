package com.example.g_one.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.brain.LlmAgentBrain;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeLlm;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import com.example.g_one.memory.MemorySeeder;
import com.example.g_one.memory.SqliteMemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Whole calls through the real app (#51): the call pipeline (#23) answers a farmer leg registered in the
 * {@link LegRegistry}, the real agent brain (#19) runs its tool loop against {@link FakeLlm}, speech is faked, and at
 * hang-up the gist handler writes the SQLite store (#20) in a temp dir as the brain's name check allows. Nothing leaves
 * the machine: the LLM is a local fake, mandi prices come from the bundled dated snapshot, and every phone number is a
 * Twilio magic test number.
 *
 * <p>The fake LLM answers requests in the order they arrive, so each test queues exactly the replies its call needs,
 * brain turns first and the gist summary last, and checks the number of requests at the end.
 */
@SpringBootTest
@DirtiesContext // close the SQLite file before the temp dir is deleted (Windows cannot delete an open file)
@Import(MemoryCallEndToEndTest.FakeSpeech.class)
class MemoryCallEndToEndTest {
    /** Twilio magic test numbers: synthetic, never a real person. The first one is the seeded Suresh. */
    private static final String SURESH_PHONE = "+15005550006";
    private static final String FORGET_PHONE = "+15005550007";
    private static final String SHARED_PHONE = "+15005550008";
    private static final long WAIT_MS = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FakeLlm LLM = startLlm();

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("g-one.memory.store", () -> "sqlite");
        r.add("g-one.memory.data-dir", () -> dataDir.toString());
        r.add("g-one.memory.seed-file", () -> "classpath:memory/seed-suresh.json");
        r.add("g-one.memory.hmac-key", () -> Base64.getEncoder().encodeToString(FakeLeg.SALT));
        r.add("G_ONE_DEMO_FARMER_PHONE", () -> SURESH_PHONE);
        r.add("g-one.llm.base-url", LLM::baseUrl);
        r.add("g-one.llm.api-key", () -> FakeLlm.KEY);
        r.add("g-one.llm.model", () -> "fake-model");
        // Short waits so a lost "played" report cannot slow the suite; the tests confirm playback themselves.
        r.add("g-one.pipeline.goodbye-grace-ms", () -> "500");
        r.add("g-one.pipeline.held-reply-release-ms", () -> "200");
    }

    @TestConfiguration
    static class FakeSpeech {
        @Bean
        FakeSpeechToText speechToText() {
            return new FakeSpeechToText();
        }

        @Bean
        FakeTextToSpeech textToSpeech() {
            return new FakeTextToSpeech().msPerChar(1);
        }
    }

    @Autowired
    LegRegistry registry;
    @Autowired
    ConsoleBus bus;
    @Autowired
    MemoryStore store;
    @Autowired
    LlmAgentBrain brain;
    @Autowired
    FakeSpeechToText stt;

    private final List<ConsoleEvent> events = new CopyOnWriteArrayList<>();
    private ConsoleBus.Subscription subscription;

    @BeforeEach
    void listen() {
        subscription = bus.subscribe(events::add);
    }

    @AfterEach
    void stopListening() throws Exception {
        subscription.close();
    }

    @AfterAll
    static void stopLlm() {
        LLM.close();
    }

    @Test
    void returningFarmerConfirmsAsksAPriceAndTheNextCallRemembersIt() {
        CallerKey key = CallerKey.fromPhone(SURESH_PHONE, FakeLeg.SALT);
        assertThat(store).isInstanceOf(SqliteMemoryStore.class);
        List<ProfileFact> factsBefore = store.load(key).orElseThrow().facts();
        int start = LLM.requests().size();
        LLM.replyToolCall("confirm_name", "{\"confirmed\": true}")
                .replyText("जी सुरेश जी, बताइए क्या मदद करूँ?")
                .replyToolCall("get_mandi_price", "{\"commodity\": \"pyaz\", \"market\": \"Lasalgaon\"}")
                .replyText("लासलगाँव में लाल प्याज़ का पिछला दर्ज भाव पैंतालीस सौ रुपये क्विंटल था।")
                .replyToolCall("end_call", "{}")
                .replyText("धन्यवाद सुरेश जी, नमस्ते।")
                .replyText(summary("Suresh confirmed his name and asked the red onion price at Lasalgaon. He heard "
                        + "the dated AGMARKNET reference and said he would call back after harvest."));

        Call first = new Call(key);
        first.awaitGreeting();
        assertThat(first.saidTexts().getFirst()).contains("Suresh"); // the name check, no model call
        first.say("haan ji");
        first.say("Lasalgaon mein pyaaz ka bhav kya hai?");
        first.say("bas, dhanyavaad");
        first.awaitAssistantHangup();
        first.awaitMemoryWritten();

        assertThat(LLM.requests()).hasSize(start + 7);
        // Memory stays away from the model until the farmer passes the name check, and is used after it.
        assertThat(LLM.requests().get(start).toString()).doesNotContain("Lasalgaon APMC");
        assertThat(LLM.requests().get(start + 1).toString()).contains("Lasalgaon APMC");
        assertThat(events).filteredOn(ConsoleEvent.ToolCalled.class::isInstance)
                .map(ConsoleEvent.ToolCalled.class::cast)
                .filteredOn(t -> t.callId().equals(first.id))
                .extracting(ConsoleEvent.ToolCalled::tool, ConsoleEvent.ToolCalled::ok)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("confirm_name", true),
                        org.assertj.core.groups.Tuple.tuple("get_mandi_price", true),
                        org.assertj.core.groups.Tuple.tuple("end_call", true));
        assertThat(events).filteredOn(e -> e instanceof ConsoleEvent.CallEnded c && c.callId().equals(first.id))
                .hasSize(1);
        // The summarizer saw the whole call, both sides, in order.
        String gistRequest = summarizerInput(start + 6);
        assertThat(gistRequest).contains("haan ji", "pyaaz", "पैंतालीस सौ", "Lasalgaon APMC");

        FarmerMemory after = store.load(key).orElseThrow();
        CallGist saved = after.gists().getFirst();
        assertThat(saved.callId()).isEqualTo(first.id);
        assertThat(saved.summary()).startsWith("Suresh confirmed his name");
        assertThat(after.facts()).containsExactlyInAnyOrderElementsOf(factsBefore);

        // The next call from the same number loads the new gist (shown on the console as memory_loaded).
        LLM.replyText(summary("The caller hung up during the name check."));
        Call second = new Call(key);
        second.awaitGreeting();
        ConsoleEvent.MemoryLoaded loaded = second.memoryLoaded().orElseThrow();
        assertThat(loaded.returning()).isTrue();
        assertThat(loaded.gists().getFirst().callId()).isEqualTo(first.id);
        assertThat(loaded.gists().getFirst().summary()).isEqualTo(saved.summary());

        // Hanging up before answering the name check counts as someone else: a flagged gist, profile untouched.
        second.leg.remoteHangup();
        second.awaitMemoryWritten();
        assertThat(LLM.requests()).hasSize(start + 8);
        assertThat(summarizerInput(start + 7)).contains("\"profile\":[]").doesNotContain("Lasalgaon APMC");
        FarmerMemory afterSecond = store.load(key).orElseThrow();
        assertThat(afterSecond.gists().getFirst().callId()).isEqualTo(second.id);
        assertThat(afterSecond.gists().getFirst().summary()).startsWith("Other speaker (the name check failed): ");
        assertThat(afterSecond.facts()).containsExactlyInAnyOrderElementsOf(factsBefore);
    }

    @Test
    void withheldCallerIdWritesNothing() {
        int start = LLM.requests().size();
        LLM.replyToolCall("get_mandi_price", "{\"commodity\": \"onion\", \"market\": \"Lasalgaon\"}")
                .replyText("लासलगाँव में लाल प्याज़ का पिछला दर्ज भाव पैंतालीस सौ रुपये क्विंटल था।");

        Call call = new Call(null);
        call.awaitGreeting();
        call.say("pyaaz ka bhav kya hai?");
        call.leg.remoteHangup();
        call.awaitMemoryWritten();

        assertThat(LLM.requests()).as("no gist summarizer call").hasSize(start + 2);
        assertThat(call.memoryLoaded()).isEmpty();
        assertThat(events).filteredOn(e -> e instanceof ConsoleEvent.CallEnded c && c.callId().equals(call.id))
                .hasSize(1);
    }

    @Test
    void forgetMeLeavesNothingForTheNextCall() {
        CallerKey key = seed(FORGET_PHONE);
        int start = LLM.requests().size();
        LLM.replyToolCall("confirm_name", "{\"confirmed\": true}")
                .replyText("जी सुरेश जी, बताइए?")
                .replyToolCall("forget_me", "{}"); // the brain then says the fixed confirmation, no model call

        Call call = new Call(key);
        call.awaitGreeting();
        call.say("haan ji");
        call.say("mujhe bhool jao");
        call.leg.remoteHangup();
        call.awaitMemoryWritten();

        assertThat(LLM.requests()).as("no gist summarizer call after forget_me").hasSize(start + 3);
        assertThat(store.load(key).map(FarmerMemory::isEmpty).orElse(true)).isTrue();

        // The next call from the number starts as a new farmer: nothing remembered comes back.
        LLM.replyText(summary("A new caller hung up after the greeting."));
        Call next = new Call(key);
        next.awaitGreeting();
        ConsoleEvent.MemoryLoaded loaded = next.memoryLoaded().orElseThrow();
        assertThat(loaded.returning()).isFalse();
        assertThat(loaded.facts()).isEmpty();
        assertThat(loaded.gists()).isEmpty();
        assertThat(next.saidTexts().getFirst()).doesNotContain("Suresh");
        next.leg.remoteHangup();
        next.awaitMemoryWritten();
        // Calls after the forget are noted again (the new-farmer notice was given), with nothing from before it.
        assertThat(store.load(key).orElseThrow().gists()).extracting(CallGist::callId).containsExactly(next.id);
    }

    @Test
    void failedNameCheckLeavesTheOwnersProfileUntouched() {
        CallerKey key = seed(SHARED_PHONE);
        List<ProfileFact> factsBefore = store.load(key).orElseThrow().facts();
        int gistsBefore = store.load(key).orElseThrow().gists().size();
        int start = LLM.requests().size();
        LLM.replyToolCall("confirm_name", "{\"confirmed\": false}")
                .replyText("ठीक है, बताइए मैं आपकी क्या मदद करूँ?")
                .replyText(summary("Ramesh called about tomato prices.",
                        "{\"field\": \"name\", \"value\": \"Ramesh\"}", "{\"field\": \"crop\", \"value\": \"tomato\"}"));

        Call call = new Call(key);
        call.awaitGreeting();
        call.say("nahin, main Ramesh hoon");
        call.leg.remoteHangup();
        call.awaitMemoryWritten();

        assertThat(LLM.requests()).hasSize(start + 3);
        assertThat(LLM.requests().get(start + 1).toString()).doesNotContain("Lasalgaon APMC");
        // The summarizer never sees the owner's profile or follow-ups (only the name the greeting said aloud).
        assertThat(summarizerInput(start + 2)).contains("\"profile\":[]", "\"openFollowUps\":[]")
                .doesNotContain("Lasalgaon").doesNotContain("Nashik");
        FarmerMemory after = store.load(key).orElseThrow();
        assertThat(after.facts()).containsExactlyInAnyOrderElementsOf(factsBefore);
        assertThat(after.fact("name").orElseThrow().value()).isEqualTo("Suresh");
        assertThat(after.gists()).hasSize(gistsBefore + 1);
        assertThat(after.gists().getFirst().callId()).isEqualTo(call.id);
        assertThat(after.gists().getFirst().summary())
                .isEqualTo("Other speaker (the name check failed): Ramesh called about tomato prices.");
    }

    // ---- helpers ----

    /** One farmer call on a fake leg registered like the simulator or telephony would. */
    private final class Call {
        final CallId id = CallId.random();
        final FakeLeg leg;

        /** @param key the caller key, or null for a withheld caller ID */
        Call(CallerKey key) {
            leg = new FakeLeg(id, LegRole.FARMER, LegSource.TWILIO, FakeLeg.HINDI, key);
            registry.register(leg);
        }

        void awaitGreeting() {
            await("greeting", () -> saidTexts().size() == 1 && session().isPresent());
        }

        /** The farmer says one turn; returns once the assistant has answered it. */
        void say(String text) {
            int before = saidTexts().size();
            heard();
            session().orElseThrow().say(text);
            await("reply to \"" + text + "\"", () -> saidTexts().size() > before);
        }

        /** The assistant's goodbye is heard and the assistant hangs up. */
        void awaitAssistantHangup() {
            await("assistant hang-up", () -> {
                heard();
                return leg.isEnded();
            });
            assertThat(leg.hungUpByUs()).isTrue();
        }

        /** The end handlers are done: the gist handler drops the brain's call state as its last step. */
        void awaitMemoryWritten() {
            await("call_ended", () -> events.stream()
                    .anyMatch(e -> e instanceof ConsoleEvent.CallEnded c && c.callId().equals(id)));
            await("end handlers", () -> brain.memoryState(id).isEmpty());
        }

        /** The person has heard everything played so far. */
        private void heard() {
            if (leg.unheardFrames() > 0) {
                leg.finishPlayback();
            }
        }

        List<String> saidTexts() {
            return events.stream()
                    .filter(e -> e instanceof ConsoleEvent.AssistantSaid a && a.callId().equals(id))
                    .map(e -> ((ConsoleEvent.AssistantSaid) e).text())
                    .toList();
        }

        Optional<ConsoleEvent.MemoryLoaded> memoryLoaded() {
            return events.stream()
                    .filter(e -> e instanceof ConsoleEvent.MemoryLoaded m && m.callId().equals(id))
                    .map(ConsoleEvent.MemoryLoaded.class::cast)
                    .findFirst();
        }

        private Optional<FakeSpeechToText.Session> session() {
            return stt.sessions().stream().filter(s -> s.legId().equals(leg.id())).findFirst();
        }
    }

    /** Seeds a copy of the bundled synthetic farmer Suresh under another synthetic number. */
    private CallerKey seed(String phone) {
        JsonNode seed;
        try (InputStream in = new ClassPathResource("memory/seed-suresh.json").getInputStream()) {
            seed = JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        MemorySeeder.Result r = MemorySeeder.seed(store, seed, name -> phone, Optional.of(FakeLeg.SALT));
        assertThat(r.status()).isEqualTo(MemorySeeder.Status.SEEDED);
        return CallerKey.fromPhone(phone, FakeLeg.SALT);
    }

    /** A gist summarizer answer in the JSON shape docs/memory.md "Write timing" gives. */
    private static String summary(String gist, String... profileUpdates) {
        try {
            return "{\"gist\": " + JSON.writeValueAsString(gist) + ", \"followUps\": [], \"profileUpdates\": ["
                    + String.join(", ", profileUpdates) + "]}";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The user message (the JSON input) of the summarizer request at {@code index}. */
    private static String summarizerInput(int index) {
        JsonNode request = LLM.requests().get(index);
        assertThat(request.has("response_format")).as("a gist summarizer request (JSON mode)").isTrue();
        return request.path("messages").path(1).path("content").asText();
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static FakeLlm startLlm() {
        try {
            return new FakeLlm();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
