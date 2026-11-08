package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.pipeline.CallEndHandler.EndedCall;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The call-end handler against the real gist writer, the fake LLM server and the reference store, with the brain's
 * per-call memory state stubbed: what happens when that state is missing (expired, lost, never seen) or says
 * {@code FORGOTTEN}. Nothing leaves the machine.
 */
class GistCallEndHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CallerKey SURESH = FakeLeg.FARMER_KEY;
    private static final CallId CALL = CallId.of("c-1108");
    private static final long START = 1_793_000_000_000L;
    private static final ProfileFact SAID_DISTRICT =
            new ProfileFact("district", "Nashik", Provenance.said(CallId.of("c-1103"), START - 86_400_000L));
    private static final ProfileFact SAID_NAME =
            new ProfileFact("name", "Suresh", Provenance.said(CallId.of("c-1103"), START - 86_400_000L));

    private FakeLlm llm;
    private MemoryStore store;
    private GistWriter writer;
    private final Map<CallId, MemoryGate.State> states = new ConcurrentHashMap<>();
    private final List<CallId> dropped = new CopyOnWriteArrayList<>();
    private GistCallEndHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        llm = new FakeLlm();
        store = new InMemoryMemoryStore();
        GOneProperties props = new GOneProperties(List.of(), "", null, null,
                new GOneProperties.Llm(llm.baseUrl(), FakeLlm.KEY, "fake-model", 5));
        writer = new GistWriter(store, new LlmCallSummarizer(new LlmClient(props, JSON), JSON));
        handler = new GistCallEndHandler(writer, id -> Optional.ofNullable(states.get(id)), dropped::add);
        llm.replyText(JSON.writeValueAsString(Map.of("gist", "Suresh asked about onion.", "followUps", List.of(),
                "profileUpdates", List.of(Map.of("field", "crops", "value", "garlic")))));
    }

    @AfterEach
    void tearDown() {
        writer.close();
        llm.close();
    }

    private static EndedCall ended(FarmerMemory atStart, String farmerLine) {
        CallContext ctx = new CallContext(CALL, FakeLeg.farmer(CALL), FakeLeg.HINDI, atStart);
        return new EndedCall(ctx, List.of(
                new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, "Namaste, kya main Suresh ji se baat kar raha hoon?"),
                new AgentBrain.Line(AgentBrain.Speaker.FARMER, farmerLine)), START, START + 60_000, "hangup");
    }

    private FarmerMemory seeded() {
        store.upsertFacts(SURESH, List.of(SAID_DISTRICT, SAID_NAME));
        return store.load(SURESH).orElseThrow();
    }

    @Test
    void anExpiredStateAfterAForgetSendsNothingToTheSummarizerAndWritesNothing() {
        FarmerMemory atStart = seeded();
        assertThat(store.forget(SURESH, CALL, START + 5_000)).isTrue();
        // The brain's FORGOTTEN state was swept (or lost) before hang-up: the handler must not rebuild a state from
        // the memory loaded at call start, which would read as a name check and summarize the forgotten call.

        handler.callEnded(ended(atStart, "Mujhe bhool jao"));

        assertThat(llm.requests()).as("no summarizer call").isEmpty();
        assertThat(store.load(SURESH)).isEmpty();
        assertThat(dropped).containsExactly(CALL);
    }

    @Test
    void aMissingStateWritesNothingEvenForAFirstTimeCaller() {
        handler.callEnded(ended(FarmerMemory.empty(SURESH), "Pyaaz ka bhaav?"));

        assertThat(llm.requests()).as("no summarizer call").isEmpty();
        assertThat(store.load(SURESH)).isEmpty();
        assertThat(dropped).containsExactly(CALL);
    }

    @Test
    void aForgottenStateWritesNothing() {
        states.put(CALL, MemoryGate.State.FORGOTTEN);

        handler.callEnded(ended(FarmerMemory.empty(SURESH), "Mujhe bhool jao"));

        assertThat(llm.requests()).as("no summarizer call").isEmpty();
        assertThat(store.load(SURESH)).isEmpty();
        assertThat(dropped).containsExactly(CALL);
    }

    @Test
    void aConfirmedStateWhoseMemoryWasForgottenMeanwhileWritesNothing() {
        FarmerMemory atStart = seeded();
        states.put(CALL, MemoryGate.State.CONFIRMED);
        store.forget(SURESH, CallId.of("c-1107"), START + 5_000);

        handler.callEnded(ended(atStart, "Pyaaz ka bhaav?"));

        assertThat(llm.requests()).as("no summarizer call").isEmpty();
        assertThat(store.load(SURESH)).isEmpty();
    }

    @Test
    void aConfirmedCallIsSummarizedFromTheCallStartSnapshot() {
        FarmerMemory atStart = seeded();
        states.put(CALL, MemoryGate.State.CONFIRMED);

        handler.callEnded(ended(atStart, "Haan ji. Pyaaz ka bhaav?"));

        assertThat(llm.requests()).hasSize(1);
        String user = llm.requests().get(0).path("messages").path(1).path("content").asText();
        assertThat(user).contains("Nashik", "Pyaaz ka bhaav");
        FarmerMemory after = store.load(SURESH).orElseThrow();
        assertThat(after.gists().getFirst().callId()).isEqualTo(CALL);
        assertThat(after.gists().getFirst().summary()).isEqualTo("Suresh asked about onion.");
        assertThat(after.fact("crop:garlic")).isPresent();
        assertThat(dropped).containsExactly(CALL);
    }
}
