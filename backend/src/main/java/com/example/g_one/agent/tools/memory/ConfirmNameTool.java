package com.example.g_one.agent.tools.memory;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * {@code confirm_name(confirmed)}: the farmer's answer to the returning-farmer name check. Only a yes unlocks memory,
 * and the result hands the model the memory block so it can go on with the follow-up in the same turn. The brain
 * offers this tool only while the name check is waiting for an answer.
 */
@Component
public class ConfirmNameTool implements Tool {
    public static final String NAME = "confirm_name";
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
            return ToolResult.ok("The farmer confirmed the name. Use this memory from now on, but never read it "
                    + "aloud:\n" + MemoryBlock.render(ctx.memory()));
        }
        return ToolResult.ok("This is not the remembered farmer. Treat them as a new farmer: ask what they want "
                + "to sell. Never mention anything remembered.");
    }
}
