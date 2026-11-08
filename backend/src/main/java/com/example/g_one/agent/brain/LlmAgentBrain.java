package com.example.g_one.agent.brain;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.ChatException;
import com.example.g_one.agent.llm.ChatMessage;
import com.example.g_one.agent.llm.ChatReply;
import com.example.g_one.agent.llm.ChatRequest;
import com.example.g_one.agent.llm.FallbackLines;
import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.llm.PromptTemplate;
import com.example.g_one.agent.llm.SpokenEnglish;
import com.example.g_one.agent.llm.ToolCall;
import com.example.g_one.agent.llm.ToolSpec;
import com.example.g_one.agent.tools.EndCallTool;
import com.example.g_one.agent.tools.memory.ConfirmNameTool;
import com.example.g_one.agent.tools.memory.ForgetMeTool;
import com.example.g_one.agent.tools.memory.MemoryBlock;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.Language;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.example.g_one.core.Turn;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The agent brain: an OpenAI-compatible model with tool calling behind {@link AgentBrain}.
 *
 * <ul>
 *   <li>{@link #greet} speaks fixed lines (docs/call-flow.md, variant B): a new farmer hears the welcome and the memory
 *       notice, a withheld caller ID hears the welcome and that nothing will be remembered, and a returning farmer
 *       hears only the name check. No model call, so the greeting cannot fail on the model.</li>
 *   <li>{@link #respond} renders the system prompt (language, today, the memory rules for the call's state), adds a
 *       separate memory data message (JSON, never instructions), replays the history, and runs the tool loop: at most
 *       {@value #MAX_MODEL_CALLS} model calls, {@value #MAX_TOOL_CALLS_PER_TURN} tool calls ({@value #MAX_TOOL_CALLS_PER_RESPONSE} from one response) and {@link #TURN_DEADLINE} per turn,
 *       the last model call without tools so the model has to speak. Each tool call is published to the console as
 *       a redacted {@link ConsoleEvent.ToolCalled} ({@link ConsoleView}). A successful {@code forget_me} ends the
 *       turn with the fixed confirmation and no further model call.</li>
 * </ul>
 *
 * <p>The returning farmer's memory stays away from the model until they say yes to the name check: the model records
 * the answer with {@code confirm_name}, offered only while the check waits, and the code's own reading of the farmer's
 * words must agree ({@link com.example.g_one.agent.tools.memory.NameCheckReply}). A turn that ends with the check
 * unanswered counts as someone else. That per-call state lives in {@link MemoryGate}, keyed by call id; call
 * {@link #callEnded} at hang-up (idle entries are swept anyway).
 *
 * <p>The reply language follows the script of the model's answer ({@link ReplyLanguage}), so a farmer who switches to
 * English gets English. Neither method throws: a model failure, a loop that never answers or any other error becomes
 * the spoken apology in the farmer's language. Logs never contain the prompt, the farmer's words or the API key.
 */
@Component
public class LlmAgentBrain implements AgentBrain {
    /** Model calls per farmer turn, the last one without tools. */
    public static final int MAX_MODEL_CALLS = 4;
    /** Tool calls run per farmer turn; once used up, the next model call offers no tools. */
    public static final int MAX_TOOL_CALLS_PER_TURN = 6;
    /** Tool calls run from one model response; the rest are answered "skipped" without running. */
    public static final int MAX_TOOL_CALLS_PER_RESPONSE = 3;
    /** A farmer turn gets a spoken answer within this time, or the apology. */
    public static final Duration TURN_DEADLINE = Duration.ofSeconds(12);
    /** Earlier lines of the call replayed to the model; older ones are left out to bound tokens and latency. */
    public static final int MAX_HISTORY_LINES = 24;
    private static final Logger log = LoggerFactory.getLogger(LlmAgentBrain.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Markdown the model sometimes adds; Timbre would read it out. */
    private static final Pattern MARKUP = Pattern.compile("[*#`]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final ChatClient chat;
    private final Map<String, Tool> tools;
    private final MemoryGate gate;
    private final FixedLines lines;
    private final FallbackLines fallback;
    private final Language.Registry languages;
    private final ConsoleBus bus;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration deadline;
    private final PromptTemplate system = PromptTemplate.load(PromptTemplate.ASSISTANT_SYSTEM);

    @Autowired
    public LlmAgentBrain(ChatClient chat, List<Tool> tools, MemoryGate gate, FixedLines lines, FallbackLines fallback,
                         Language.Registry languages, ConsoleBus bus, ObjectMapper json) {
        this(chat, tools, gate, lines, fallback, languages, bus, json, Clock.system(INDIA));
    }

    public LlmAgentBrain(ChatClient chat, List<Tool> tools, MemoryGate gate, FixedLines lines, FallbackLines fallback,
                         Language.Registry languages, ConsoleBus bus, ObjectMapper json, Clock clock) {
        this(chat, tools, gate, lines, fallback, languages, bus, json, clock, TURN_DEADLINE);
    }

    /** @param deadline how long one {@link #respond} may take before it gives the apology */
    public LlmAgentBrain(ChatClient chat, List<Tool> tools, MemoryGate gate, FixedLines lines, FallbackLines fallback,
                         Language.Registry languages, ConsoleBus bus, ObjectMapper json, Clock clock,
                         Duration deadline) {
        this.chat = chat;
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool t : tools) {
            if (byName.putIfAbsent(t.name(), t) != null) {
                throw new IllegalStateException("Two tools are named " + t.name());
            }
        }
        this.tools = Map.copyOf(byName);
        this.gate = gate;
        this.lines = lines;
        this.fallback = fallback;
        this.languages = languages;
        this.bus = bus;
        this.json = json;
        this.clock = clock;
        this.deadline = deadline;
    }

    @Override
    public Reply greet(CallContext ctx) {
        try {
            MemoryGate.State state = gate.state(ctx);
            FixedLines.Lines l = lines.forLanguage(ctx.language().code());
            Language lang = spokenIn(l.code(), ctx.language());
            String text = switch (state) {
                case WITHHELD -> l.welcome() + " " + l.withheld();
                case NAME_CHECK -> l.nameCheck(MemoryBlock.rememberedName(ctx.memory()).orElseThrow());
                case NEW, UNNAMED -> l.welcome() + " " + l.memoryNotice();
                case CONFIRMED, OTHER_SPEAKER, FORGOTTEN -> l.welcome();
            };
            return new Reply(text, lang, false);
        } catch (RuntimeException e) {
            log.warn("Greeting failed on call {} ({})", ctx.callId(), e.getClass().getSimpleName());
            return apology(ctx.language());
        }
    }

    @Override
    public Reply respond(CallContext ctx, List<Line> history, Turn latest) {
        Progress turn = new Progress(clock.millis() + deadline.toMillis());
        FutureTask<Reply> task = new FutureTask<>(() -> converse(ctx, history, latest, turn));
        try {
            Thread.ofVirtual().name("agent-turn").start(task);
            return task.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Agent brain missed the {} ms turn deadline on call {}", deadline.toMillis(), ctx.callId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ChatException c) {
                log.warn("Language model failed on call {}: {} {}", ctx.callId(), c.kind(), c.status());
            } else if (cause instanceof TurnOver) {
                log.warn("Agent brain ran out of turn time on call {}", ctx.callId());
            } else {
                log.warn("Agent brain failed on call {} ({})", ctx.callId(),
                        cause == null ? "unknown" : cause.getClass().getSimpleName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Agent brain failed on call {} ({})", ctx.callId(), e.getClass().getSimpleName());
        } finally {
            // Whatever happened, the worker stops at its next step, and an unanswered name check counts as no.
            turn.over = true;
            task.cancel(true);
            gate.closeNameCheck(ctx);
        }
        return turn.endCall ? goodbye(ctx.language()) : apology(ctx.language());
    }

    /** Drops this call's per-call state. The pipeline calls it at hang-up, after reading {@link #memoryState}. */
    public void callEnded(CallId callId) {
        gate.end(callId);
    }

    /**
     * What this call did with memory, for whoever writes the gist at hang-up: {@code OTHER_SPEAKER} means the gist
     * must be flagged and the profile left alone; {@code FORGOTTEN} and {@code WITHHELD} mean nothing is written.
     * Empty for an unknown call.
     */
    public Optional<MemoryGate.State> memoryState(CallId callId) {
        return gate.peek(callId);
    }

    /** What happened during one {@link #respond}, shared with the worker so a late failure still ends the call. */
    private final class Progress {
        final long deadlineMs;
        volatile boolean endCall;
        volatile boolean over;

        Progress(long deadlineMs) {
            this.deadlineMs = deadlineMs;
        }

        /** Stops the worker once the turn is answered elsewhere or out of time. */
        void check() {
            if (over || Thread.currentThread().isInterrupted() || clock.millis() > deadlineMs) throw new TurnOver();
        }
    }

    /** The turn ran out of time or was answered elsewhere: stop without another model or tool call. */
    private static final class TurnOver extends RuntimeException {
        TurnOver() {
            super(null, null, false, false);
        }
    }

    private Reply converse(CallContext ctx, List<Line> history, Turn latest, Progress turn) {
        if (latest == null || latest.text().isBlank()) return apology(ctx.language());
        MemoryGate.State state = gate.state(ctx);
        // The code's own reading of the farmer's words; confirm_name cannot unlock memory without it.
        if (state == MemoryGate.State.NAME_CHECK) gate.heardNameCheckReply(ctx, latest.text());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(ctx, state)));
        boolean hasData = addData(messages, state, ctx);
        List<Line> past = history == null ? List.of() : history;
        for (Line line : past.subList(Math.max(0, past.size() - MAX_HISTORY_LINES), past.size())) {
            if (line == null || line.text().isBlank()) continue;
            messages.add(switch (line.speaker()) {
                case FARMER -> ChatMessage.user(line.text());
                case BUYER -> ChatMessage.user("The buyer said: " + line.text());
                case ASSISTANT -> ChatMessage.assistant(line.text());
            });
        }
        messages.add(ChatMessage.user(latest.text()));

        int toolCalls = 0;
        for (int call = 1; call <= MAX_MODEL_CALLS; call++) {
            turn.check();
            boolean toolsLeft = call < MAX_MODEL_CALLS && toolCalls < MAX_TOOL_CALLS_PER_TURN;
            List<ToolSpec> offered = toolsLeft ? specs(gate.state(ctx)) : List.of();
            ChatReply reply = chat.complete(ChatRequest.of(messages, offered));
            turn.check();
            if (!reply.wantsTools()) {
                String text = speakable(reply.content());
                if (text.isEmpty()) break;
                return new Reply(text, ReplyLanguage.of(text, ctx.language(), languages), turn.endCall);
            }
            if (offered.isEmpty()) break; // asked for tools when none were offered: no more rounds
            messages.add(ChatMessage.assistant(reply));
            int inResponse = 0;
            for (ToolCall tc : reply.toolCalls()) {
                if (inResponse >= MAX_TOOL_CALLS_PER_RESPONSE || toolCalls >= MAX_TOOL_CALLS_PER_TURN) {
                    // Every tool call id needs an answer, but a skipped call never runs.
                    messages.add(ChatMessage.tool(tc.id(), toolMessage(ToolResult.error(
                            "Skipped: too many tool calls in this turn. Answer the farmer with what you have."))));
                    continue;
                }
                turn.check();
                inResponse++;
                toolCalls++;
                ToolResult result = run(tc, ctx);
                if (EndCallTool.NAME.equals(tc.name()) && result.ok()) turn.endCall = true;
                if (ForgetMeTool.NAME.equals(tc.name()) && result.ok()) {
                    // The conversation so far may hold remembered content: no further model call sees it.
                    return forgotten(ctx.language(), turn.endCall);
                }
                messages.add(ChatMessage.tool(tc.id(), toolMessage(result)));
            }
            MemoryGate.State after = gate.state(ctx);
            if (after != state) {
                state = after;
                messages.set(0, ChatMessage.system(systemPrompt(ctx, state)));
                if (hasData) messages.remove(1);
                hasData = addData(messages, state, ctx);
            }
        }
        log.warn("Agent brain gave no spoken answer within {} model calls on call {}", MAX_MODEL_CALLS, ctx.callId());
        return turn.endCall ? goodbye(ctx.language()) : apology(ctx.language());
    }

    /** Puts the memory data message for this state right after the system prompt; true when there is one. */
    private static boolean addData(List<ChatMessage> messages, MemoryGate.State state, CallContext ctx) {
        Optional<String> data = MemoryBlock.dataMessage(state, ctx.memory());
        data.ifPresent(d -> messages.add(1, ChatMessage.user(d)));
        return data.isPresent();
    }

    /** A tool result as the model sees it: JSON, so text inside it cannot pose as anything but a value. */
    private String toolMessage(ToolResult result) {
        ObjectNode node = json.createObjectNode().put("ok", result.ok()).put("result", result.content());
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Tool result could not be written as JSON", e);
        }
    }

    private String systemPrompt(CallContext ctx, MemoryGate.State state) {
        return system.render(Map.of(
                "language", ctx.language().label(),
                "today", SpokenEnglish.date(LocalDate.now(clock.withZone(INDIA))),
                "memory", MemoryBlock.forPrompt(state)));
    }

    /** Every tool, except {@code confirm_name} while no name check is waiting. */
    private List<ToolSpec> specs(MemoryGate.State state) {
        List<ToolSpec> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (!offered(t.name(), state)) continue;
            out.add(new ToolSpec(t.name(), t.description(), t.jsonSchema()));
        }
        out.sort(java.util.Comparator.comparing(ToolSpec::name));
        return out;
    }

    private static boolean offered(String tool, MemoryGate.State state) {
        return !ConfirmNameTool.NAME.equals(tool) || state == MemoryGate.State.NAME_CHECK;
    }

    private ToolResult run(ToolCall call, CallContext ctx) {
        long started = clock.millis();
        Tool tool = tools.get(call.name());
        boolean known = tool != null;
        JsonNode args;
        ToolResult result;
        try {
            args = call.arguments(json);
        } catch (IllegalArgumentException e) {
            args = json.createObjectNode();
            result = ToolResult.error("The arguments were not a JSON object.");
            publish(ctx, call, known, args, result, started);
            return result;
        }
        if (tool == null || !offered(call.name(), gate.state(ctx))) {
            result = ToolResult.error("No such tool is available now.");
        } else {
            try {
                result = tool.invoke(args, ctx);
                if (result == null) result = ToolResult.error("The tool gave no result.");
            } catch (RuntimeException e) {
                log.warn("Tool {} failed on call {} ({})", call.name(), ctx.callId(), e.getClass().getSimpleName());
                result = ToolResult.error("The tool failed. Apologise briefly and offer to try again.");
            }
        }
        publish(ctx, call, known, args, result, started);
        return result;
    }

    /** Publishes a redacted copy ({@link ConsoleView}); the model still gets the real result. */
    private void publish(CallContext ctx, ToolCall call, boolean known, JsonNode args, ToolResult result, long started) {
        String name = ConsoleView.tool(call.name(), known);
        bus.publish(new ConsoleEvent.ToolCalled(ctx.callId(), started, name,
                ConsoleView.args(json, args, known, result), result.ok(),
                ConsoleView.result(name, result, gate.peek(ctx.callId()).orElse(null)),
                Math.max(0, clock.millis() - started)));
    }

    private static String speakable(String content) {
        return content == null ? "" : SPACES.matcher(MARKUP.matcher(content).replaceAll(" ")).replaceAll(" ").strip();
    }

    private Reply apology(Language current) {
        String key = FixedLines.bestKey(fallback.apologies().keySet(), current.code(), FallbackLines.DEFAULT_LANGUAGE);
        return new Reply(fallback.apology(key), spokenIn(key, current), false);
    }

    /** The fixed forget-me confirmation from docs/memory.md, spoken without another model call. */
    private Reply forgotten(Language current, boolean endCall) {
        FixedLines.Lines l = lines.forLanguage(current.code());
        return new Reply(l.forgotten(), spokenIn(l.code(), current), endCall);
    }

    private Reply goodbye(Language current) {
        FixedLines.Lines l = lines.forLanguage(current.code());
        return new Reply(l.goodbye(), spokenIn(l.code(), current), true);
    }

    /** The language to voice a fixed line written for {@code lineCode}: the current one when it shares the prefix. */
    private Language spokenIn(String lineCode, Language current) {
        String prefix = FixedLines.prefix(lineCode);
        if (prefix.equals(FixedLines.prefix(current.code()))) return current;
        return ReplyLanguage.byPrefix(prefix, languages).orElse(current);
    }
}
