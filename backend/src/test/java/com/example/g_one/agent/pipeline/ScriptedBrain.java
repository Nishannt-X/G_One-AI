package com.example.g_one.agent.pipeline;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Language;
import com.example.g_one.core.Turn;
import com.example.g_one.fakes.FakeLeg;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An {@link AgentBrain} for pipeline tests. It greets with {@link #GREETING} and answers a turn "x" with "reply to x",
 * ending the call when the turn is "bye" and switching to English for {@link #SWITCH_TO_ENGLISH}. It records every
 * call, can throw on purpose, and can run a hook while "thinking" so a test can make another turn arrive mid-reply.
 */
final class ScriptedBrain implements AgentBrain {
    static final String GREETING = "Namaste, G-One mein aapka swaagat hai.";
    /** A turn the brain answers in English, as if the farmer switched language. */
    static final String SWITCH_TO_ENGLISH = "english please";

    record RespondCall(CallContext ctx, List<Line> history, Turn latest) {}

    final List<CallContext> greets = new CopyOnWriteArrayList<>();
    final List<RespondCall> responds = new CopyOnWriteArrayList<>();
    volatile boolean failGreet;
    volatile boolean failRespond;
    /** Runs inside {@link #respond} before it returns, with the turn text. */
    volatile Consumer<String> whileThinking = text -> {};

    static String replyTo(String turn) {
        return "reply to " + turn;
    }

    @Override
    public Reply greet(CallContext ctx) {
        greets.add(ctx);
        if (failGreet) {
            throw new IllegalStateException("brain down (test)");
        }
        String text = ctx.memory().isEmpty() ? GREETING : GREETING + " Welcome back.";
        return new Reply(text, ctx.language(), false);
    }

    @Override
    public Reply respond(CallContext ctx, List<Line> history, Turn latest) {
        responds.add(new RespondCall(ctx, List.copyOf(history), latest));
        whileThinking.accept(latest.text());
        if (failRespond) {
            throw new IllegalStateException("brain down (test)");
        }
        Language language = latest.text().equals(SWITCH_TO_ENGLISH) ? FakeLeg.ENGLISH : ctx.language();
        return new Reply(replyTo(latest.text()), language, latest.text().equals("bye"));
    }
}
