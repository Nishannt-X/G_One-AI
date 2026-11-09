package com.example.g_one.agent.mediation;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.example.g_one.agent.tools.mandi.MandiQuote;
import com.example.g_one.agent.tools.mandi.MandiSource;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegRole;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeLlm;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real {@link Mediator} against {@link FakeLlm}, the speech fakes and the bundled dated mandi sample (red onion,
 * Lasalgaon, ₹4,500 per quintal on 4 Nov 2026). Background work runs on the calling thread and playback timeouts never
 * fire, so every test is deterministic. Nothing leaves the machine; all names and numbers are synthetic.
 */
public final class MediationFixture implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    public static final String NO_CAUTION = "{\"status\":\"ok\",\"secretRequest\":false,\"secretShare\":false}";
    public static final String NO_OFFER = "{\"status\":\"none\"}";
    public static final Duration CONSENT_TIMEOUT = Duration.ofSeconds(20);

    public final FakeLlm llm;
    public final FakeSpeechToText stt = new FakeSpeechToText(() -> 1_000L);
    public final FakeTextToSpeech tts = new FakeTextToSpeech();
    public final ConsoleBus bus = new ConsoleBus();
    public final List<ConsoleEvent> events = new CopyOnWriteArrayList<>();
    /** Timers the mediator scheduled; nothing fires until {@link #fireTimers()}. */
    public final List<Runnable> timers = new CopyOnWriteArrayList<>();
    public final Mediator mediator;

    public MediationFixture() throws IOException {
        llm = new FakeLlm();
        GOneProperties props = new GOneProperties(List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri"), "",
                new GOneProperties.Agmarknet("http://127.0.0.1:9", "", "", "", "", "", "", 5, 3, 30), null,
                new GOneProperties.Llm(llm.baseUrl(), FakeLlm.KEY, "fake-model", 5));
        MandiQuote quote = new MandiQuote(new MandiSource(props, JSON), new MandiCatalog(JSON));
        bus.subscribe(events::add);
        mediator = new Mediator(stt, tts, new ChatClient(props, JSON), JSON, quote, bus, Runnable::run, () -> 2_000L,
                Duration.ofSeconds(4), CONSENT_TIMEOUT, (delayMs, task) -> timers.add(task));
    }

    /** Fires every timer scheduled so far (playback and consent timeouts), as if its delay had passed. */
    public void fireTimers() {
        List<Runnable> due = List.copyOf(timers);
        timers.clear();
        due.forEach(Runnable::run);
    }

    /** The buyer session the mediator opened (the newest buyer session of {@link #stt}). */
    public FakeSpeechToText.Session buyerSession() {
        return stt.sessions().stream().filter(s -> s.role() == LegRole.BUYER).reduce((a, b) -> b).orElseThrow();
    }

    /**
     * Settles consent the happy way: the buyer hears the introduction, says yes, and the farmer hears that the buyer
     * is on the line. Makes no model call.
     */
    public void consent(FakeLeg farmer, FakeLeg buyer) {
        buyer.finishPlayback();
        buyerSession().say("Yes");
        farmer.finishPlayback();
    }

    /** Queues a translation answer. */
    public static String translation(String text, String spoken) {
        return JSON.createObjectNode().put("translation", text).put("spoken", spoken).toString();
    }

    /** The JSON data object the mediator sent as the user message of request {@code i}. */
    public JsonNode sentData(int i) {
        try {
            return JSON.readTree(llm.requests().get(i).path("messages").get(1).path("content").asText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String systemPrompt(int i) {
        return llm.requests().get(i).path("messages").get(0).path("content").asText();
    }

    public <T extends ConsoleEvent> List<T> events(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Frames the speech fake makes for {@code text}. */
    public static int frames(String text) {
        return FakeTextToSpeech.framesFor(text, 8000, 50).size();
    }

    @Override
    public void close() {
        llm.close();
    }
}
