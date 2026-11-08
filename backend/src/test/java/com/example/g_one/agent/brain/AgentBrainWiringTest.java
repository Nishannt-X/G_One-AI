package com.example.g_one.agent.brain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The whole app on defaults only (no LLM configured, no memory store wired yet): the brain bean exists, offers every
 * tool, greets without the model and apologises instead of failing when the model is not configured.
 */
@SpringBootTest
class AgentBrainWiringTest {
    @Autowired
    AgentBrain brain;

    @Autowired
    List<Tool> tools;

    @Test
    void brainIsWiredWithEveryToolAndNeverThrowsUnconfigured() {
        assertThat(brain).isInstanceOf(LlmAgentBrain.class);
        assertThat(tools).extracting(Tool::name).contains("get_mandi_price", "get_weather", "save_profile_fact",
                "forget_me", "confirm_name", "end_call");

        CallContext ctx = BrainFixture.newFarmer(BrainFixture.HINDI);
        assertThat(brain.greet(ctx).text()).startsWith("नमस्ते");
        AgentBrain.Reply r = brain.respond(ctx, List.of(), BrainFixture.said(ctx, "नमस्ते"));
        assertThat(r.text()).startsWith("माफ़ कीजिए");
        assertThat(r.endCall()).isFalse();
    }
}
