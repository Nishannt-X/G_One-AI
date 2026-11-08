package com.example.g_one.agent.pipeline;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wires the {@link CallPipeline} into the app once every bean exists. The brain (#19), speech-to-text and
 * text-to-speech are built in other issues, so until each has exactly one bean the pipeline stays off and says so in
 * the log; the app still starts. Without a {@link MemoryStore} bean it uses an {@link InMemoryMemoryStore} (lost on
 * restart). Every {@link CallEndHandler} bean (such as the gist writer, #20) runs at hang-up, in {@code @Order}. When
 * the agent's {@link FixedLines} bean exists, its per-language goodbye is the one said after a goodbye that could not
 * be spoken; without it the pipeline says {@link CallPipeline#DEFAULT_FIXED_GOODBYES}.
 *
 * <p>Brain calls, memory loads and end handlers run on virtual threads, one per task.
 *
 * <p>Settings (not in {@code application.yml}; the defaults apply unless set):
 * <ul>
 *   <li>{@code g-one.pipeline.goodbye-grace-ms} (default 8000): how long past the goodbye's length the assistant waits
 *       for the leg to confirm the farmer heard it before hanging up anyway.</li>
 *   <li>{@code g-one.pipeline.held-reply-release-ms} (default 1500): how long a reply held because the farmer started
 *       speaking while it was prepared waits for a final turn before it plays.</li>
 * </ul>
 */
@Component
class CallPipelineStarter implements SmartInitializingSingleton, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(CallPipelineStarter.class);

    private final LegRegistry registry;
    private final ConsoleBus bus;
    private final ObjectProvider<AgentBrain> brain;
    private final ObjectProvider<SpeechToText> speechToText;
    private final ObjectProvider<TextToSpeech> textToSpeech;
    private final ObjectProvider<MemoryStore> memory;
    private final ObjectProvider<CallEndHandler> endHandlers;
    private final ObjectProvider<FixedLines> fixedLines;
    private final Duration goodbyeGrace;
    private final Duration heldReplyRelease;
    private ExecutorService executor;
    private CallPipeline pipeline;

    CallPipelineStarter(LegRegistry registry, ConsoleBus bus, ObjectProvider<AgentBrain> brain,
                        ObjectProvider<SpeechToText> speechToText, ObjectProvider<TextToSpeech> textToSpeech,
                        ObjectProvider<MemoryStore> memory, ObjectProvider<CallEndHandler> endHandlers,
                        ObjectProvider<FixedLines> fixedLines,
                        @Value("${g-one.pipeline.goodbye-grace-ms:8000}") long goodbyeGraceMs,
                        @Value("${g-one.pipeline.held-reply-release-ms:1500}") long heldReplyReleaseMs) {
        this.registry = registry;
        this.bus = bus;
        this.brain = brain;
        this.speechToText = speechToText;
        this.textToSpeech = textToSpeech;
        this.memory = memory;
        this.endHandlers = endHandlers;
        this.fixedLines = fixedLines;
        this.goodbyeGrace = Duration.ofMillis(goodbyeGraceMs);
        this.heldReplyRelease = Duration.ofMillis(heldReplyReleaseMs);
    }

    @Override
    public void afterSingletonsInstantiated() {
        AgentBrain b = brain.getIfUnique();
        SpeechToText stt = speechToText.getIfUnique();
        TextToSpeech tts = textToSpeech.getIfUnique();
        List<String> missing = new ArrayList<>();
        if (b == null) {
            missing.add("AgentBrain");
        }
        if (stt == null) {
            missing.add("SpeechToText");
        }
        if (tts == null) {
            missing.add("TextToSpeech");
        }
        if (!missing.isEmpty()) {
            log.warn("The call pipeline is off: no single {} bean. Legs will not be answered.", missing);
            return;
        }
        MemoryStore store = memory.getIfUnique(() -> {
            log.info("No MemoryStore bean; the call pipeline keeps memory in RAM until restart");
            return new InMemoryMemoryStore();
        });
        FixedLines lines = fixedLines.getIfUnique();
        Function<Language, String> goodbyes =
                lines != null ? fixedGoodbyes(lines) : CallPipeline.DEFAULT_FIXED_GOODBYES;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        pipeline = new CallPipeline(b, stt, tts, store, bus, endHandlers.orderedStream().toList(), executor,
                System::currentTimeMillis, goodbyeGrace, heldReplyRelease, goodbyes,
                CallPipeline.executorDelays(executor));
        pipeline.attach(registry);
        log.info("The call pipeline is on");
    }

    /** The agent's fixed goodbye for a language (its own entry, else one with the same prefix, else English). */
    static Function<Language, String> fixedGoodbyes(FixedLines lines) {
        return language -> lines.forLanguage(language.code()).goodbye();
    }

    /** The running pipeline, or null when it is off. */
    CallPipeline pipeline() {
        return pipeline;
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
