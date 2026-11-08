package com.example.g_one.agent.pipeline;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Wires the {@link CallPipeline} into the app once every bean exists. The brain (#19), speech-to-text and
 * text-to-speech are built in other issues, so until each has exactly one bean the pipeline stays off and says so in
 * the log; the app still starts. Without a {@link MemoryStore} bean it uses an {@link InMemoryMemoryStore} (lost on
 * restart). Every {@link CallEndHandler} bean (such as the gist writer, #20) runs at hang-up, in {@code @Order}.
 *
 * <p>Brain calls, memory loads and end handlers run on virtual threads, one per task.
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
    private ExecutorService executor;
    private CallPipeline pipeline;

    CallPipelineStarter(LegRegistry registry, ConsoleBus bus, ObjectProvider<AgentBrain> brain,
                        ObjectProvider<SpeechToText> speechToText, ObjectProvider<TextToSpeech> textToSpeech,
                        ObjectProvider<MemoryStore> memory, ObjectProvider<CallEndHandler> endHandlers) {
        this.registry = registry;
        this.bus = bus;
        this.brain = brain;
        this.speechToText = speechToText;
        this.textToSpeech = textToSpeech;
        this.memory = memory;
        this.endHandlers = endHandlers;
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
        executor = Executors.newVirtualThreadPerTaskExecutor();
        pipeline = new CallPipeline(b, stt, tts, store, bus, endHandlers.orderedStream().toList(), executor,
                System::currentTimeMillis);
        pipeline.attach(registry);
        log.info("The call pipeline is on");
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
