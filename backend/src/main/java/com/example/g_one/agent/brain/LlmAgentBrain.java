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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   <li>{@link #respond} renders the system prompt (language, today, a memory section for the call's state), replays
 *       the history, and runs the tool loop: at most {@value #MAX_MODEL_CALLS} model calls per turn, the last one
 *       without tools so the model has to speak. Each tool call is published as {@link ConsoleEvent.ToolCalled}.</li>
 * </ul>
 *
 * <p>The returning farmer's memory stays out of the prompt until they say yes to the name check: the model records
 * the answer with {@code confirm_name}, offered only while the check waits. That per-call state lives in
 * {@link MemoryGate}, keyed by call id; call {@link #callEnded} at hang-up (idle entries expire anyway).
 *
 * <p>The reply language follows the script of the model's answer ({@link ReplyLanguage}), so a farmer who switches to
 * English gets English. Neither method throws: a model failure, a loop that never answers or any other error becomes
 * the spoken apology in the farmer's language. Logs never contain the prompt, the farmer's words or the API key.
 */
@Component
public class LlmAgentBrain implements AgentBrain {
    /** Model calls per farmer turn, the last one without tools. */
    public static final int MAX_MODEL_CALLS = 4;
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
    private final PromptTemplate system = PromptTemplate.load(PromptTemplate.ASSISTANT_SYSTEM);

    @Autowired
    public LlmAgentBrain(ChatClient chat, List<Tool> tools, MemoryGate gate, FixedLines lines, FallbackLines fallback,
                         Language.Registry languages, ConsoleBus bus, ObjectMapper json) {
        this(chat, tools, gate, lines, fallback, languages, bus, json, Clock.system(INDIA));
    }

    public LlmAgentBrain(ChatClient chat, List<Tool> tools, MemoryGate gate, FixedLines lines, FallbackLines fallback,
                         Language.Registry languages, ConsoleBus bus, ObjectMapper json, Clock clock) {
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
        Progress turn = new Progress();
        try {
            return converse(ctx, history, latest, turn);
        } catch (ChatException e) {
            log.warn("Language model failed on call {}: {} {}", ctx.callId(), e.kind(), e.status());
        } catch (RuntimeException e) {
            log.warn("Agent brain failed on call {} ({})", ctx.callId(), e.getClass().getSimpleName());
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

    /** What happened during one {@link #respond}, kept outside the try so a late failure still ends the call. */
    private static final class Progress {
        boolean endCall;
    }

    private Reply converse(CallContext ctx, List<Line> history, Turn latest, Progress turn) {
        if (latest == null || latest.text().isBlank()) return apology(ctx.language());
        MemoryGate.State state = gate.state(ctx);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(ctx, state)));
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

        for (int call = 1; call <= MAX_MODEL_CALLS; call++) {
            List<ToolSpec> offered = call < MAX_MODEL_CALLS ? specs(gate.state(ctx)) : List.of();
            ChatReply reply = chat.complete(ChatRequest.of(messages, offered));
            if (!reply.wantsTools()) {
                String text = speakable(reply.content());
                if (text.isEmpty()) break;
                return new Reply(text, ReplyLanguage.of(text, ctx.language(), languages), turn.endCall);
            }
            if (offered.isEmpty()) break; // asked for tools on the last call: no more rounds
            messages.add(ChatMessage.assistant(reply));
            for (ToolCall tc : reply.toolCalls()) {
                ToolResult result = run(tc, ctx);
                if (EndCallTool.NAME.equals(tc.name()) && result.ok()) turn.endCall = true;
                messages.add(ChatMessage.tool(tc.id(), result.content()));
            }
        }
        log.warn("Agent brain gave no spoken answer within {} model calls on call {}", MAX_MODEL_CALLS, ctx.callId());
        return turn.endCall ? goodbye(ctx.language()) : apology(ctx.language());
    }

    private String systemPrompt(CallContext ctx, MemoryGate.State state) {
        return system.render(Map.of(
                "language", ctx.language().label(),
                "today", SpokenEnglish.date(LocalDate.now(clock.withZone(INDIA))),
                "memory", MemoryBlock.forPrompt(state, ctx.memory())));
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
        JsonNode args;
        ToolResult result;
        try {
            args = call.arguments(json);
        } catch (IllegalArgumentException e) {
            args = json.createObjectNode();
            result = ToolResult.error("The arguments were not a JSON object.");
            publish(ctx, call, args, result, started);
            return result;
        }
        Tool tool = tools.get(call.name());
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
        publish(ctx, call, args, result, started);
        return result;
    }

    private void publish(CallContext ctx, ToolCall call, JsonNode args, ToolResult result, long started) {
        bus.publish(new ConsoleEvent.ToolCalled(ctx.callId(), started, call.name(), args, result.ok(),
                result.content(), Math.max(0, clock.millis() - started)));
    }

    private static String speakable(String content) {
        return content == null ? "" : SPACES.matcher(MARKUP.matcher(content).replaceAll(" ")).replaceAll(" ").strip();
    }

    private Reply apology(Language current) {
        String key = FixedLines.bestKey(fallback.apologies().keySet(), current.code(), FallbackLines.DEFAULT_LANGUAGE);
        return new Reply(fallback.apology(key), spokenIn(key, current), false);
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
