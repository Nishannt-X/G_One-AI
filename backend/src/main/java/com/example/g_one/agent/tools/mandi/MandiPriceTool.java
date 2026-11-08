package com.example.g_one.agent.tools.mandi;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code get_mandi_price(commodity, market?)}: the dated AGMARKNET reference for a crop, in the "dated reference"
 * wording with numbers and the date as words ({@link MandiWording#spoken}). A crop with no reference gets an honest "no
 * dated reference", never another crop's number.
 */
@Component
public class MandiPriceTool implements Tool {
    public static final String NAME = "get_mandi_price";
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "commodity": {"type": "string", "description": "The crop as the farmer said it, for example onion, pyaz or प्याज"},
               "market": {"type": "string", "description": "The mandi the farmer named, if any, for example Lasalgaon"}},
             "required": ["commodity"]}""");

    private final MandiQuote quote;

    public MandiPriceTool(MandiQuote quote) {
        this.quote = quote;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Look up the dated AGMARKNET reference price for a crop, optionally at a named mandi. Call it whenever "
                + "the farmer asks about a price, rate or bhav.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> commodity = ToolArgs.text(args, "commodity");
        if (commodity.isEmpty()) return ToolResult.error("commodity is required.");
        return ToolResult.ok(MandiWording.spoken(quote.quote(commodity.get(), ToolArgs.text(args, "market").orElse(null))));
    }
}
