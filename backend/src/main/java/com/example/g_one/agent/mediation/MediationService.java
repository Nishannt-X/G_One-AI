package com.example.g_one.agent.mediation;

import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.pipeline.CallPipeline;
import com.example.g_one.agent.tools.mandi.MandiQuote;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.Leg;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.TextToSpeech;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The app's {@link CallPipeline.Mediation}: builds the {@link Mediator} on the first buyer leg, once speech beans exist
 * (the call pipeline is off without them anyway). Model calls and synthesis starts run on virtual threads.
 */
@Component
class MediationService implements CallPipeline.Mediation, DisposableBean {
    private final ObjectProvider<SpeechToText> speechToText;
    private final ObjectProvider<TextToSpeech> textToSpeech;
    private final ChatClient chat;
    private final ObjectMapper json;
    private final MandiQuote mandi;
    private final ConsoleBus bus;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Mediator mediator;

    MediationService(ObjectProvider<SpeechToText> speechToText, ObjectProvider<TextToSpeech> textToSpeech,
                     ChatClient chat, ObjectMapper json, MandiQuote mandi, ConsoleBus bus) {
        this.speechToText = speechToText;
        this.textToSpeech = textToSpeech;
        this.chat = chat;
        this.json = json;
        this.mandi = mandi;
        this.bus = bus;
    }

    @Override
    public SttListener start(Leg farmer, Leg buyer, Runnable onEnded) {
        return mediator().start(farmer, buyer, onEnded);
    }

    private Mediator mediator() {
        Mediator m = mediator;
        if (m == null) {
            synchronized (this) {
                m = mediator;
                if (m == null) {
                    SpeechToText stt = speechToText.getIfUnique();
                    TextToSpeech tts = textToSpeech.getIfUnique();
                    if (stt == null || tts == null) {
                        throw new IllegalStateException("Mediation needs exactly one SpeechToText and TextToSpeech");
                    }
                    m = new Mediator(stt, tts, chat, json, mandi, bus, executor, System::currentTimeMillis,
                            Mediator.DEFAULT_PLAYBACK_GRACE, Mediator.DEFAULT_CONSENT_TIMEOUT,
                            (delayMs, task) -> CompletableFuture
                                    .delayedExecutor(delayMs, TimeUnit.MILLISECONDS, executor).execute(task));
                    mediator = m;
                }
            }
        }
        return m;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
