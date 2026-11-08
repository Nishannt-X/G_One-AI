package com.example.g_one.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The Spring wiring: the pipeline stays off until its beans exist, then answers legs and runs end handler beans. */
class CallPipelineStarterTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(LegRegistry.class)
            .withBean(ConsoleBus.class)
            .withBean(CallPipelineStarter.class);

    @Test
    void staysOffWithoutBrainOrSpeechBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(CallPipelineStarter.class).pipeline()).isNull();
            // A leg registered now is simply not answered.
            ctx.getBean(LegRegistry.class).register(FakeLeg.farmer(CallId.random()));
        });
    }

    @Test
    void greetsALegAndRunsEndHandlerBeansWhenEverythingIsThere() {
        FakeSpeechToText stt = new FakeSpeechToText();
        FakeTextToSpeech tts = new FakeTextToSpeech();
        CompletableFuture<CallEndHandler.EndedCall> ended = new CompletableFuture<>();
        runner.withBean(AgentBrain.class, ScriptedBrain::new)
                .withBean(SpeechToText.class, () -> stt)
                .withBean(TextToSpeech.class, () -> tts)
                .withBean(CallEndHandler.class, () -> ended::complete)
                .run(ctx -> {
                    assertThat(ctx.getBean(CallPipelineStarter.class).pipeline()).isNotNull();
                    CompletableFuture<ConsoleEvent.AssistantSaid> greeted = new CompletableFuture<>();
                    ctx.getBean(ConsoleBus.class).subscribe(e -> {
                        if (e instanceof ConsoleEvent.AssistantSaid said) {
                            greeted.complete(said);
                        }
                    });
                    FakeLeg leg = FakeLeg.farmer(CallId.random());

                    ctx.getBean(LegRegistry.class).register(leg);

                    assertThat(greeted.get(5, TimeUnit.SECONDS).text()).isEqualTo(ScriptedBrain.GREETING);
                    leg.remoteHangup();
                    assertThat(ended.get(5, TimeUnit.SECONDS).callId()).isEqualTo(leg.callId());
                });
    }
}
