package com.example.g_one.agent.tools.memory;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code save_profile_fact(field, value)}: saves one stable fact the farmer said about themselves, with provenance
 * "said" and this call's id. Writes nothing (and says so honestly) when memory is off: a withheld caller ID, an
 * unanswered name check, another speaker, or after {@code forget_me}.
 *
 * <p>The memory area decides which {@link MemoryStore} is wired; until one is, this tool says memory is unavailable.
 */
@Component
public class SaveProfileFactTool implements Tool {
    public static final String NAME = "save_profile_fact";
    /** The profile fields from docs/memory.md. */
    public static final List<String> FIELDS =
            List.of("name", "village", "district", "state", "crops", "usualMandi", "language");
    static final int MAX_VALUE_CHARS = 80;
    /** Six or more digits, even with spaces or dashes between them: a phone, account, Aadhaar or OTP. Never saved. */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){5,}");
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "field": {"type": "string", "enum": ["name", "village", "district", "state", "crops", "usualMandi", "language"],
                 "description": "Which fact. crops is the full list the farmer grows, comma separated; usualMandi is the market they usually sell at."},
               "value": {"type": "string", "description": "The fact as the farmer said it, short, for example Nashik or red onion, tomato"}},
             "required": ["field", "value"]}""");

    private final Supplier<MemoryStore> store;
    private final MemoryGate gate;
    private final Clock clock;

    @Autowired
    public SaveProfileFactTool(ObjectProvider<MemoryStore> store, MemoryGate gate) {
        this(store::getIfAvailable, gate, Clock.systemUTC());
    }

    /** @param store gives the store, or null when none is wired */
    public SaveProfileFactTool(Supplier<MemoryStore> store, MemoryGate gate, Clock clock) {
        this.store = store;
        this.gate = gate;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Save one stable fact the farmer told you about themselves (name, village, district, state, crops, "
                + "usual mandi, preferred language) so the next call does not start from zero.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> field = ToolArgs.text(args, "field");
        if (field.isEmpty() || !FIELDS.contains(field.get())) {
            return ToolResult.error("field must be one of " + String.join(", ", FIELDS) + ".");
        }
        String value = MemoryBlock.clean(ToolArgs.text(args, "value").orElse(""), Integer.MAX_VALUE);
        if (value.isEmpty()) return ToolResult.error("value is required.");
        if (value.codePointCount(0, value.length()) > MAX_VALUE_CHARS) {
            return ToolResult.error("value is too long; save a short phrase.");
        }
        if (LONG_NUMBER.matcher(value).find()) {
            return ToolResult.error("Not saved: never save long numbers such as phone, account, Aadhaar or OTP.");
        }
        Optional<CallerKey> key = ctx.callerKey();
        if (key.isEmpty()) {
            return ToolResult.error("Not saved: memory is off for this call because the caller ID is withheld.");
        }
        MemoryGate.State state = gate.state(ctx);
        if (!state.maySave()) return ToolResult.error("Not saved: " + why(state));
        MemoryStore s = store.get();
        if (s == null) return ToolResult.error("Not saved: memory is not available right now.");
        s.upsertFacts(key.get(), List.of(new ProfileFact(field.get(), value,
                Provenance.said(ctx.callId(), clock.millis()))));
        return ToolResult.ok("Saved " + field.get() + ".");
    }

    static String why(MemoryGate.State state) {
        return switch (state) {
            case NAME_CHECK -> "the farmer has not answered the name check yet.";
            case OTHER_SPEAKER -> "this is not the remembered farmer, so memory is off for this call.";
            case FORGOTTEN -> "the farmer asked to be forgotten, so memory is off for the rest of this call.";
            case WITHHELD -> "memory is off for this call because the caller ID is withheld.";
            case NEW, UNNAMED, CONFIRMED -> "memory is off for this call.";
        };
    }
}
