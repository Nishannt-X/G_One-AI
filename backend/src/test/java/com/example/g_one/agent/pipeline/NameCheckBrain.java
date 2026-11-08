package com.example.g_one.agent.pipeline;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Turn;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * An {@link AgentBrain} with per-call state, like the name check {@code LlmAgentBrain} keeps in its memory gate. The
 * greeting asks a returning farmer to confirm their name. The next reply reads the check when it starts and settles
 * it when it returns: "haan ji" confirms it, anything else closes it. So two replies that overlap can undo each
 * other's answer. It records the order of every call and how many ran at once.
 */
final class NameCheckBrain implements AgentBrain {
    static final String YES = "haan ji";
    static final String NAME_QUESTION = "Kya aap Ramesh ji hain?";

    enum Check { NONE, ASKED, CONFIRMED, CLOSED }

    /** Each call's start and end, as "start greet", "end respond haan ji" and so on. */
    final List<String> calls = new CopyOnWriteArrayList<>();
    /** The name check each reply saw when it started, keyed by turn text in call order. */
    final List<String> seen = new CopyOnWriteArrayList<>();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger mostInFlight = new AtomicInteger();
    /** Runs inside {@link #respond}, after reading the check and before settling it, with the turn text. */
    volatile Consumer<String> whileThinking = text -> {};
    private volatile Check check = Check.NONE;

    Check check() {
        return check;
    }

    static String replyTo(String turn) {
        return "reply to " + turn;
    }

    @Override
    public Reply greet(CallContext ctx) {
        enter("greet");
        try {
            if (ctx.memory().isEmpty()) {
                return new Reply("Namaste.", ctx.language(), false);
            }
            check = Check.ASKED;
            return new Reply(NAME_QUESTION, ctx.language(), false);
        } finally {
            leave("greet");
        }
    }

    @Override
    public Reply respond(CallContext ctx, List<Line> history, Turn latest) {
        String text = latest.text();
        enter("respond " + text);
        try {
            Check before = check;
            seen.add(text + ": " + before);
            whileThinking.accept(text);
            if (before == Check.ASKED) {
                check = text.equals(YES) ? Check.CONFIRMED : Check.CLOSED;
            }
            return new Reply(replyTo(text), ctx.language(), false);
        } finally {
            leave("respond " + text);
        }
    }

    private void enter(String what) {
        calls.add("start " + what);
        mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
    }

    private void leave(String what) {
        inFlight.decrementAndGet();
        calls.add("end " + what);
    }
}
