package com.example.g_one.agent.tools.memory;

import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code forget_me()}: "मुझे भूल जाओ / forget me". Deletes the profile, gists and deals stored for this caller key
 * ({@link MemoryStore#forget}, which also keeps the rest of this call from writing), turns memory off for the rest of
 * the call, and hands the model the fixed spoken confirmation from docs/memory.md.
 *
 * <p>Deletes nothing when the caller ID is withheld (nothing is stored), while the name check is unanswered, or when
 * the speaker said they are not the remembered farmer: someone else on a shared phone cannot erase the owner's memory.
 */
@Component
public class ForgetMeTool implements Tool {
    public static final String NAME = "forget_me";
    private static final JsonNode SCHEMA = ToolArgs.schema("{\"type\": \"object\", \"properties\": {}}");

    private final Supplier<MemoryStore> store;
    private final MemoryGate gate;
    private final FixedLines lines;
    private final Clock clock;

    @Autowired
    public ForgetMeTool(ObjectProvider<MemoryStore> store, MemoryGate gate, FixedLines lines) {
        this(store::getIfAvailable, gate, lines, Clock.systemUTC());
    }

    /** @param store gives the store, or null when none is wired */
    public ForgetMeTool(Supplier<MemoryStore> store, MemoryGate gate, FixedLines lines, Clock clock) {
        this.store = store;
        this.gate = gate;
        this.lines = lines;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Delete everything remembered about this farmer, when they ask you to forget them or delete their "
                + "information.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<CallerKey> key = ctx.callerKey();
        if (key.isEmpty()) {
            return ToolResult.error("Nothing to delete: the caller ID is withheld, so nothing about this call is "
                    + "remembered. Tell the farmer that plainly.");
        }
        MemoryGate.State state = gate.state(ctx);
        if (state == MemoryGate.State.NAME_CHECK) {
            return ToolResult.error("Not deleted: first ask the farmer to answer whether they are the person named in "
                    + "the greeting.");
        }
        if (!state.mayForget()) {
            return ToolResult.error("Not deleted: this is not the remembered farmer, so their request cannot delete "
                    + "that farmer's memory. Nothing from this call is saved.");
        }
        MemoryStore s = store.get();
        if (s == null) return ToolResult.error("Not deleted: memory is not available right now. Offer to try later.");
        boolean had = s.forget(key.get(), ctx.callId(), clock.millis());
        gate.forgotten(ctx);
        String line = lines.forLanguage(ctx.language().code()).forgotten();
        return ToolResult.ok((had ? "Deleted everything remembered about this farmer."
                : "Nothing was stored about this farmer.")
                + " Memory is off for the rest of this call. Tell the farmer exactly: " + line);
    }
}
