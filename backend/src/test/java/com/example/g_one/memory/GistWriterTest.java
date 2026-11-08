package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeLlm;
import com.example.g_one.speech.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The gist writer against the fake LLM server and the reference store. Nothing leaves the machine. */
class GistWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CallerKey SURESH = FakeLeg.FARMER_KEY;
    private static final CallId CALL = CallId.of("c-1108");
    private static final long START = OffsetDateTime.parse("2026-11-08T10:00:00+05:30").toInstant().toEpochMilli();
    private static final long END = START + 9 * 60_000;
    private static final long DAY = 86_400_000L;
    private static final ProfileFact SAID_DISTRICT =
            new ProfileFact("district", "Nashik", Provenance.said(CallId.of("c-1103"), START - DAY));
    private static final ProfileFact SAID_CROP =
            new ProfileFact("crop:red onion", "red onion", Provenance.said(CallId.of("c-1103"), START - DAY));

    private FakeLlm llm;
    private MemoryStore store;
    private GistWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        llm = new FakeLlm();
        store = new InMemoryMemoryStore();
        store.upsertFacts(SURESH, List.of(SAID_DISTRICT, SAID_CROP));
        writer = writerFor(store, llm.baseUrl(), FakeLlm.KEY, "fake-model");
    }

    @AfterEach
    void tearDown() {
        writer.close();
        llm.close();
    }

    private static GistWriter writerFor(MemoryStore store, String baseUrl, String key, String model) {
        GOneProperties props = new GOneProperties(List.of(), "", null, null,
                new GOneProperties.Llm(baseUrl, key, model, 5));
        return new GistWriter(store, new LlmCallSummarizer(new LlmClient(props, JSON), JSON));
    }

    private static CallTranscript transcript(String... farmerLines) {
        List<CallTranscript.Line> lines = new ArrayList<>();
        lines.add(CallTranscript.Line.assistant("Namaste, G-One here. Am I speaking with Suresh ji?", START));
        for (int i = 0; i < farmerLines.length; i++) {
            lines.add(new CallTranscript.Line(CallTranscript.Speaker.FARMER, farmerLines[i], START + 1_000L * (i + 1)));
        }
        return new CallTranscript(CALL, START, END, lines, false, Optional.empty());
    }

    private void llmAnswers(Object answer) throws Exception {
        llm.replyText(JSON.writeValueAsString(answer));
    }

    private FarmerMemory memory() {
        return store.load(SURESH).orElseThrow();
    }

    @Test
    void theLlmGistIsSavedWithOpenFollowUpsAndOnlyWhitelistedInferredFacts() throws Exception {
        llmAnswers(Map.of(
                "gist", "Suresh harvested about 20 quintal red onion and wants a buyer. Connected to Shinde Traders.",
                "followUps", List.of(
                        Map.of("text", "Call back with the pickup time", "dueHint", "2026-11-09", "status", "OPEN"),
                        Map.of("text", "Find a buyer for red onion", "status", "DONE"),
                        "Ask about the tomato crop"),
                "profileUpdates", List.of(
                        Map.of("field", "district", "value", "Pune"),
                        Map.of("field", "crops", "value", "Red  Onion"),
                        Map.of("field", "crops", "value", "garlic"),
                        Map.of("field", "language", "value", "hi_en"),
                        Map.of("field", "phone", "value", "a number"),
                        Map.of("field", "buyerName", "value", "Shinde"))));

        GistWriter.Result r = writer.write(FarmerMemory.empty(SURESH),
                transcript("Haan, main Suresh. Bees quintal pyaaz taiyaar hai."));

        assertThat(r.status()).isEqualTo(GistWriter.Status.WRITTEN);
        assertThat(r.source()).isEqualTo(GistWriter.Source.SUMMARIZER);
        CallGist gist = memory().gists().get(0);
        assertThat(gist.callId()).isEqualTo(CALL);
        assertThat(gist.startedMs()).isEqualTo(START);
        assertThat(gist.endedMs()).isEqualTo(END);
        assertThat(gist.summary()).startsWith("Suresh harvested about 20 quintal red onion");
        assertThat(gist.followUps())
                .containsExactly("Call back with the pickup time (due ~2026-11-09)", "Ask about the tomato crop");

        FarmerMemory m = memory();
        assertThat(m.fact("district")).as("inferred never replaces said").contains(SAID_DISTRICT);
        assertThat(m.fact("crop:red onion")).as("said crop kept").contains(SAID_CROP);
        assertThat(m.fact("crop:garlic").orElseThrow().provenance()).isEqualTo(Provenance.inferred(CALL, END));
        assertThat(m.fact("language").orElseThrow().value()).isEqualTo("HI_EN");
        assertThat(m.facts()).extracting(ProfileFact::field)
                .containsExactly("district", "crop:red onion", "crop:garlic", "language");
        assertThat(r.facts()).allMatch(f -> f.provenance().saidOrInferred() == Provenance.Origin.INFERRED);

        String user = llm.requests().get(0).path("messages").path(1).path("content").asText();
        assertThat(JSON.readTree(user).path("profile").toString()).contains("Nashik", "said");
        assertThat(user).contains("Bees quintal pyaaz");
    }

    @Test
    void secretLookingNumbersNeverReachTheLlmOrTheStore() throws Exception {
        llmAnswers(Map.of(
                "gist", "He read out 4829 13 and account 123456789012 by mistake. Agreed 20 quintal at Rs 120000, "
                        + "pickup on 20261109.",
                "followUps", List.of(Map.of("text", "Remind him never to share 482913", "status", "OPEN")),
                "profileUpdates", List.of(
                        Map.of("field", "village", "value", "Plot 4567891 Lasalgaon"),
                        Map.of("field", "name", "value", "Suresh"))));

        writer.write(FarmerMemory.empty(SURESH),
                transcript("Mera OTP 482913 hai", "Number +91 98765 43210 pe bhejo", "Rate 1,200 per quintal"));

        String sent = llm.requests().get(0).toString();
        assertThat(sent).doesNotContain("482913").doesNotContain("98765").contains("1,200 per quintal");

        CallGist gist = memory().gists().get(0);
        assertThat(gist.summary())
                .doesNotContain("4829 13").doesNotContain("123456789012")
                .contains(SecretScrubber.REMOVED, "Rs 120000", "20261109");
        assertThat(gist.followUps()).containsExactly("Remind him never to share " + SecretScrubber.REMOVED);
        assertThat(memory().fact("village")).as("a fact with a secret-looking number is dropped").isEmpty();
        assertThat(memory().fact("name").orElseThrow().value()).isEqualTo("Suresh");
    }

    @Test
    void theGistIsCappedAtEightyWords() throws Exception {
        llmAnswers(Map.of("gist", String.join(" ", Collections.nCopies(120, "word")), "followUps", List.of(),
                "profileUpdates", List.of()));

        writer.write(FarmerMemory.empty(SURESH), transcript("hello"));

        String summary = memory().gists().get(0).summary();
        assertThat(summary.split("\\s+")).hasSize(GistWriter.MAX_GIST_WORDS);
        assertThat(summary).endsWith("word…");
    }

    @Test
    void aWithheldCallerIdWritesNothingAndNeverCallsTheLlm() {
        MemoryStore untouchable = new InMemoryMemoryStore() {
            @Override
            public Optional<FarmerMemory> load(CallerKey key) {
                throw new AssertionError("no read when withheld");
            }

            @Override
            public void saveGist(CallerKey key, CallGist gist) {
                throw new AssertionError("no write when withheld");
            }

            @Override
            public void upsertFacts(CallerKey key, List<ProfileFact> facts) {
                throw new AssertionError("no write when withheld");
            }
        };
        try (GistWriter w = writerFor(untouchable, llm.baseUrl(), FakeLlm.KEY, "fake-model")) {
            GistWriter.Result r = w.write(FarmerMemory.withheld(), transcript("Mera naam Suresh hai"));

            assertThat(r.status()).isEqualTo(GistWriter.Status.SKIPPED_WITHHELD);
            assertThat(r.gist()).isEmpty();
        }
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void afterForgetMeTheHangUpGistAndFactsAreDropped() throws Exception {
        store.saveGist(SURESH, new CallGist(CallId.of("c-1103"), START - DAY, START - DAY + 60_000, "Earlier call.",
                List.of()));
        assertThat(store.forget(SURESH, CALL, START + 5_000)).isTrue();
        llmAnswers(Map.of("gist", "Suresh asked to be forgotten.", "followUps", List.of(),
                "profileUpdates", List.of(Map.of("field", "name", "value", "Suresh"))));

        writer.write(FarmerMemory.of(SURESH, List.of(SAID_DISTRICT), List.of()), transcript("Mujhe bhool jao"));

        assertThat(store.load(SURESH)).isEmpty();
    }

    @Test
    void whenTheLlmFailsTwiceATemplateGistIsWritten() {
        llm.failures.set(2);

        GistWriter.Result r = writer.write(FarmerMemory.empty(SURESH),
                transcript("Red onion bechna hai").withOutcome(CallTranscript.Outcome.DEAL_CONFIRMED));

        assertThat(llm.requests()).hasSize(2);
        assertThat(r.source()).isEqualTo(GistWriter.Source.TEMPLATE);
        assertThat(memory().gists().get(0).summary()).isEqualTo("Call 2026-11-08: red onion, outcome deal confirmed.");
        assertThat(memory().gists().get(0).followUps()).isEmpty();
        assertThat(memory().facts()).containsExactly(SAID_DISTRICT, SAID_CROP);
    }

    @Test
    void oneLlmFailureIsRetried() throws Exception {
        llm.failures.set(1);
        llmAnswers(Map.of("gist", "Asked the tomato price.", "followUps", List.of(), "profileUpdates", List.of()));

        GistWriter.Result r = writer.write(FarmerMemory.empty(SURESH), transcript("Tamatar ka bhaav?"));

        assertThat(llm.requests()).hasSize(2);
        assertThat(r.source()).isEqualTo(GistWriter.Source.SUMMARIZER);
        assertThat(memory().gists().get(0).summary()).isEqualTo("Asked the tomato price.");
    }

    @Test
    void anUnconfiguredLlmGivesTheTemplateWithoutAnyRequest() {
        try (GistWriter w = writerFor(store, "", "", "")) {
            GistWriter.Result r = w.write(FarmerMemory.empty(SURESH), transcript("Kuch nahin"));

            assertThat(r.source()).isEqualTo(GistWriter.Source.TEMPLATE);
        }
        assertThat(memory().gists().get(0).summary()).isEqualTo("Call 2026-11-08: outcome not recorded.");
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void anEmptyLlmGistFallsBackToTheTemplateButKeepsFollowUps() throws Exception {
        llmAnswers(Map.of("gist", "  ", "followUps", List.of("Call back"), "profileUpdates", List.of()));

        GistWriter.Result r = writer.write(FarmerMemory.empty(SURESH), transcript("hello"));

        assertThat(r.source()).isEqualTo(GistWriter.Source.TEMPLATE);
        assertThat(memory().gists().get(0).followUps()).containsExactly("Call back");
    }

    @Test
    void anotherSpeakerGetsAFlaggedGistAndNoProfileChange() throws Exception {
        llmAnswers(Map.of("gist", "Someone asked about onion prices.", "followUps", List.of(),
                "profileUpdates", List.of(Map.of("field", "name", "value", "Ramesh"))));

        GistWriter.Result r = writer.write(FarmerMemory.empty(SURESH),
                transcript("Nahin, main Ramesh hoon").withOtherSpeaker(true));

        assertThat(r.facts()).isEmpty();
        assertThat(memory().facts()).containsExactly(SAID_DISTRICT, SAID_CROP);
        assertThat(memory().gists().get(0).summary())
                .isEqualTo(GistWriter.OTHER_SPEAKER_PREFIX + "Someone asked about onion prices.");
    }

    @Test
    void writeAsyncRunsInTheBackground() throws Exception {
        llmAnswers(Map.of("gist", "Async gist.", "followUps", List.of(), "profileUpdates", List.of()));

        GistWriter.Result r = writer.writeAsync(FarmerMemory.empty(SURESH), transcript("hi"))
                .get(10, TimeUnit.SECONDS);

        assertThat(r.gist().orElseThrow().summary()).isEqualTo("Async gist.");
        assertThat(memory().gists()).hasSize(1);
    }
}
