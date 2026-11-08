package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.pipeline.CallEndHandler.EndedCall;
import com.example.g_one.agent.tools.memory.ForgetMeTool;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.core.ToolResult;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeLlm;
import com.example.g_one.speech.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The call-end handler against the real gist writer, the fake LLM server and the reference store, with the brain's
 * per-call memory state stubbed: what happens when that state is missing (expired, lost, never seen) or says
 * {@code FORGOTTEN}; and, with the real gate and {@code forget_me}, a hang-up that arrives while a forget is still
 * deleting. Nothing leaves the machine.
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
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(START + 30_000), ZoneOffset.UTC);
    private final MemoryGate gate = new MemoryGate(CLOCK);
    private GistCallEndHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        llm = new FakeLlm();
        store = new InMemoryMemoryStore();
        writer = new GistWriter(store, new LlmCallSummarizer(new LlmClient(props(), JSON), JSON));
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

    // ---- hang-up while forget_me is still running ------------------------------------------------------------

    @Test
    void aHangUpWhileAFirstTimeCallersForgetIsDeletingSendsNothingToTheSummarizer() throws Exception {
        // The review's case: a first-time caller's snapshot is empty, so only the gate state can stop the summary.
        hangUpWhileForgetIsDeleting(FarmerMemory.empty(SURESH), ctx -> { });
    }

    @Test
    void aHangUpWhileAConfirmedFarmersForgetIsDeletingSendsNothingToTheSummarizer() throws Exception {
        hangUpWhileForgetIsDeleting(seededFacts(), ctx -> {
            gate.heardNameCheckReply(ctx, "haan ji");
            assertThat(gate.answerNameCheck(ctx, true)).isEqualTo(MemoryGate.State.CONFIRMED);
        });
    }

    @Test
    void aFinishedForgetThenAHangUpWritesNothing() {
        store.upsertFacts(SURESH, List.of(SAID_DISTRICT)); // saved earlier in this first call
        CallContext ctx = ended(FarmerMemory.empty(SURESH), "").context();
        GistCallEndHandler real = new GistCallEndHandler(writer, gate::peek, gate::end);

        ToolResult r = new ForgetMeTool(() -> store, gate, new FixedLines(JSON), CLOCK)
                .invoke(JSON.createObjectNode(), ctx);
        assertThat(r.ok()).isTrue();
        assertThat(r.content()).startsWith("Deleted everything");
        assertThat(gate.peek(CALL)).contains(MemoryGate.State.FORGOTTEN);

        real.callEnded(ended(FarmerMemory.empty(SURESH), "Mujhe bhool jao"));

        assertThat(llm.requests()).as("no summarizer call").isEmpty();
        assertThat(store.load(SURESH)).isEmpty();
        assertThat(gate.peek(CALL)).as("state dropped at hang-up").isEmpty();
    }

    /**
     * Runs {@code forget_me} on its own thread with the store's delete held open, hangs up while it is held, and then
     * lets the delete finish. Latches order the steps, so the interleaving is the same on every run.
     */
    private void hangUpWhileForgetIsDeleting(FarmerMemory atStart, Consumer<CallContext> prepare) throws Exception {
        BlockingForgetStore blocking = new BlockingForgetStore();
        if (!atStart.facts().isEmpty()) blocking.upsertFacts(SURESH, atStart.facts());
        // A fact saved earlier in this call, so the forget has something to delete either way.
        blocking.upsertFacts(SURESH, List.of(new ProfileFact("crops", "onion", Provenance.said(CALL, START + 1_000))));
        CallContext ctx = ended(atStart, "").context();
        prepare.accept(ctx);
        ForgetMeTool tool = new ForgetMeTool(() -> blocking, gate, new FixedLines(JSON), CLOCK);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        GistWriter raceWriter = new GistWriter(blocking, new LlmCallSummarizer(new LlmClient(props(), JSON), JSON));
        try {
            GistCallEndHandler real = new GistCallEndHandler(raceWriter, gate::peek, id -> {
                dropped.add(id);
                gate.end(id);
            });
            Future<ToolResult> forget = pool.submit(() -> tool.invoke(JSON.createObjectNode(), ctx));
            assertThat(blocking.entered.await(5, TimeUnit.SECONDS)).as("forget_me reached the store").isTrue();
            assertThat(gate.peek(CALL)).as("marked before the delete finished").contains(MemoryGate.State.FORGOTTEN);

            real.callEnded(ended(atStart, "Mujhe bhool jao"));

            assertThat(llm.requests()).as("no summarizer call").isEmpty();
            assertThat(blocking.gistWrites.get()).as("no gist written").isZero();
            assertThat(dropped).containsExactly(CALL);

            blocking.release.countDown();
            ToolResult r = forget.get(5, TimeUnit.SECONDS);
            assertThat(r.ok()).isTrue();
            assertThat(r.content()).startsWith("Deleted everything");
            assertThat(blocking.load(SURESH)).isEmpty();
            assertThat(llm.requests()).isEmpty();
        } finally {
            blocking.release.countDown();
            pool.shutdownNow();
            raceWriter.close();
        }
    }

    /** Suresh's stored facts, as the memory loaded at call start. */
    private static FarmerMemory seededFacts() {
        return FarmerMemory.of(SURESH, List.of(SAID_DISTRICT, SAID_NAME), List.of());
    }

    private GOneProperties props() {
        return new GOneProperties(List.of(), "", null, null,
                new GOneProperties.Llm(llm.baseUrl(), FakeLlm.KEY, "fake-model", 5));
    }

    /** Holds {@code forget} open until the test releases it, and counts gist writes. */
    private static final class BlockingForgetStore extends InMemoryMemoryStore {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger gistWrites = new AtomicInteger();

        @Override
        public boolean forget(CallerKey key, CallId duringCall, long atMs) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("forget never released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return super.forget(key, duringCall, atMs);
        }

        @Override
        public void saveGist(CallerKey key, CallGist gist) {
            gistWrites.incrementAndGet();
            super.saveGist(key, gist);
        }
    }
}
