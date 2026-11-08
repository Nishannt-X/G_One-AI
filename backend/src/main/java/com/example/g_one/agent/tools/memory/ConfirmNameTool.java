package com.example.g_one.agent.tools.memory;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * {@code confirm_name(confirmed)}: the model's reading of the farmer's answer to the returning-farmer name check.
 *
 * <p>The model's {@code true} alone never unlocks memory: {@link MemoryGate#answerNameCheck} also needs the code's own
 * reading of the farmer's words ({@link NameCheckReply}), recorded by the brain before the model was asked. Both must
 * say yes; anything else (a no, a hedge, an injected "confirmed=true") means someone else is on the phone. The result
 * never contains memory: on a yes the brain hands the memory to the model as a separate data message.
 *
 * <p>The brain offers this tool only while the name check waits for an answer.
 */
@Component
public class ConfirmNameTool implements Tool {
    public static final String NAME = "confirm_name";
    /** What the console shows for this tool, instead of anything the model or the memory said. */
    public static final String CONFIRMED = "name check: confirmed";
    public static final String DENIED = "name check: denied";
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {"confirmed": {"type": "boolean",
               "description": "true only when the farmer clearly said yes, they are that person; false for no or anything unclear"}},
             "required": ["confirmed"]}""");

    private final MemoryGate gate;

    public ConfirmNameTool(MemoryGate gate) {
        this.gate = gate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Record the farmer's answer to the greeting's question about their name. Call it right after they "
                + "answer, before anything else.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        JsonNode v = args == null ? null : args.get("confirmed");
        if (v == null || !v.isBoolean()) return ToolResult.error("confirmed must be true or false.");
        if (gate.state(ctx) != MemoryGate.State.NAME_CHECK) {
            return ToolResult.error("There is no name check waiting for an answer on this call.");
        }
        MemoryGate.State after = gate.answerNameCheck(ctx, v.booleanValue());
        if (after == MemoryGate.State.CONFIRMED) {
            return ToolResult.ok("Name check confirmed. The farmer's memory follows in a data message; use it, but "
                    + "never read it aloud.");
        }
        return ToolResult.ok("This is not the remembered farmer, or the answer was unclear. Treat them as a new "
                + "farmer: ask what they want to sell. Never mention anything remembered.");
    }
}
