package com.example.g_one.agent.tools;

import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * {@code end_call()}: the farmer said goodbye or wants to stop. The tool itself does nothing; the brain sees the call
 * and sets {@code Reply.endCall}, so the pipeline hangs up after the goodbye has played.
 */
@Component
public class EndCallTool implements Tool {
    public static final String NAME = "end_call";
    private static final JsonNode SCHEMA = ToolArgs.schema("{\"type\": \"object\", \"properties\": {}}");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "End the phone call after your goodbye, when the farmer says goodbye or clearly wants to stop.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        return ToolResult.ok("The call will end after your reply. Now say a short goodbye in the farmer's language.");
    }
}
