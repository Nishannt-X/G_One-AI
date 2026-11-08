package com.example.g_one.agent.brain;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.FallbackLines;
import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.tools.EndCallTool;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.example.g_one.agent.tools.mandi.MandiPriceTool;
import com.example.g_one.agent.tools.mandi.MandiQuote;
import com.example.g_one.agent.tools.mandi.MandiSource;
import com.example.g_one.agent.tools.memory.ConfirmNameTool;
import com.example.g_one.agent.tools.memory.ForgetMeTool;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.agent.tools.memory.SaveProfileFactTool;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.core.Tool;
import com.example.g_one.core.Turn;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeLlm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the real brain and real tools against {@link FakeLlm}, an in-memory store and the bundled dated mandi sample.
 * Nothing leaves the machine. All names and numbers are synthetic (seed farmer Suresh from docs/memory.md).
 */
final class BrainFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 8 Nov 2026, 10 am IST. */
    static final Clock CLOCK = Clock.fixed(LocalDate.of(2026, 11, 8).atTime(10, 0).atZone(IST).toInstant(), IST);
    static final Language HINDI = FakeLeg.HINDI;
    static final Language ENGLISH = FakeLeg.ENGLISH;
    static final CallId PAST_CALL = CallId.of("c-1103");
    static final long PAST_MS = LocalDate.of(2026, 11, 3).atTime(9, 40).atZone(IST).toInstant().toEpochMilli();

    final FakeLlm llm;
    final CountingStore store = new CountingStore();
    final ConsoleBus bus = new ConsoleBus();
    final List<ConsoleEvent.ToolCalled> toolEvents = new CopyOnWriteArrayList<>();
    final MemoryGate gate = new MemoryGate(CLOCK);
    final FixedLines lines = new FixedLines(JSON);
    final FallbackLines fallback = new FallbackLines(JSON);
    final Language.Registry registry;
    final LlmAgentBrain brain;

    BrainFixture() throws Exception {
        this(true);
    }

    BrainFixture(boolean configured) throws Exception {
        llm = new FakeLlm();
        GOneProperties props = new GOneProperties(List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri"), "",
                new GOneProperties.Agmarknet("http://127.0.0.1:9", "", "", "", "", "", "", 5, 3, 30), null,
                new GOneProperties.Llm(configured ? llm.baseUrl() : "", FakeLlm.KEY, "fake-model", 5));
        registry = new Language.Registry(props);
        MandiQuote quote = new MandiQuote(new MandiSource(props, JSON), new MandiCatalog(JSON));
        List<Tool> tools = List.of(
                new MandiPriceTool(quote),
                new SaveProfileFactTool(() -> store, gate, CLOCK),
                new ForgetMeTool(() -> store, gate, lines, CLOCK),
                new ConfirmNameTool(gate),
                new EndCallTool());
        bus.subscribe(e -> {
            if (e instanceof ConsoleEvent.ToolCalled t) toolEvents.add(t);
        });
        brain = new LlmAgentBrain(new ChatClient(props, JSON), tools, gate, lines, fallback, registry, bus, JSON, CLOCK);
    }

    /** The seed farmer's remembered profile and last gist, also saved in the store. */
    FarmerMemory seedSuresh() {
        List<ProfileFact> facts = List.of(
                new ProfileFact("name", "Suresh", Provenance.said(PAST_CALL, PAST_MS)),
                new ProfileFact("district", "Nashik", Provenance.said(PAST_CALL, PAST_MS)),
                new ProfileFact("usualMandi", "Lasalgaon APMC", Provenance.said(PAST_CALL, PAST_MS)));
        CallGist gist = new CallGist(PAST_CALL, PAST_MS, PAST_MS + 720_000,
                "Suresh asked the red onion price at Lasalgaon and heard the dated AGMARKNET reference.",
                List.of("Find a buyer for about twenty quintal red onion after harvest"));
        store.upsertFacts(FakeLeg.FARMER_KEY, facts);
        store.saveGist(FakeLeg.FARMER_KEY, gist);
        store.calls.set(0);
        return store.load(FakeLeg.FARMER_KEY).orElseThrow();
    }

    static CallContext newFarmer(Language lang) {
        return ctx(lang, FakeLeg.FARMER_KEY, FarmerMemory.empty(FakeLeg.FARMER_KEY));
    }

    static CallContext withheld(Language lang) {
        return ctx(lang, null, FarmerMemory.withheld());
    }

    static CallContext returning(Language lang, FarmerMemory memory) {
        return ctx(lang, FakeLeg.FARMER_KEY, memory);
    }

    static CallContext ctx(Language lang, CallerKey key, FarmerMemory memory) {
        CallId id = CallId.random();
        FakeLeg leg = new FakeLeg(id, LegRole.FARMER, LegSource.SIMULATOR, lang, key);
        return new CallContext(id, leg, lang, memory);
    }

    static Turn said(CallContext ctx, String text) {
        long now = CLOCK.millis();
        return new Turn(ctx.farmerLeg().id(), LegRole.FARMER, ctx.language(), text, now - 2000, now);
    }

    @Override
    public void close() {
        llm.close();
    }

    /** Counts every write and forget, so a test can prove memory was not touched. */
    static final class CountingStore extends InMemoryMemoryStore {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void saveGist(CallerKey key, CallGist gist) {
            calls.incrementAndGet();
            super.saveGist(key, gist);
        }

        @Override
        public void upsertFacts(CallerKey key, List<ProfileFact> facts) {
            calls.incrementAndGet();
            super.upsertFacts(key, facts);
        }

        @Override
        public boolean forget(CallerKey key, CallId duringCall, long atMs) {
            calls.incrementAndGet();
            return super.forget(key, duringCall, atMs);
        }
    }
}
