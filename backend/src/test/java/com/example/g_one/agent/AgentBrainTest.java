package com.example.g_one.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.g_one.core.Language;
import org.junit.jupiter.api.Test;

class AgentBrainTest {
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");

    @Test
    void replyKeepsItsFields() {
        AgentBrain.Reply reply = new AgentBrain.Reply("namaste", HINDI, true);
        assertEquals("namaste", reply.text());
        assertEquals(HINDI, reply.language());
        assertEquals(true, reply.endCall());
    }

    @Test
    void replyRejectsBlankOrMissingText() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBrain.Reply("  ", HINDI, false));
        assertThrows(NullPointerException.class, () -> new AgentBrain.Reply(null, HINDI, false));
        assertThrows(NullPointerException.class, () -> new AgentBrain.Reply("hello", null, false));
    }

    @Test
    void lineRequiresSpeakerAndText() {
        assertThrows(NullPointerException.class, () -> new AgentBrain.Line(null, "hi"));
        assertThrows(NullPointerException.class, () -> new AgentBrain.Line(AgentBrain.Speaker.FARMER, null));
        assertEquals(AgentBrain.Speaker.BUYER, new AgentBrain.Line(AgentBrain.Speaker.BUYER, "ok").speaker());
    }
}
