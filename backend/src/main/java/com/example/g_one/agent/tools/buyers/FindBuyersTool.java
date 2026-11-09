package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.llm.SpokenEnglish;
import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/**
 * {@code find_buyers(commodity, quantity?, district?)}: at most two directory buyers for the farmer's crop, best first,
 * each with name, place, listed range (numbers as words) and language, plus a short reason (docs/call-flow.md step 7).
 * The result carries each buyer's {@code buyer_id} for {@code ask_connect_buyer} and {@code connect_buyer}, never a
 * phone number or phone key. The suggestions are recorded in {@link BuyerConsent}, so only a suggested buyer can be
 * connected. The commodity argument is never echoed: an unknown crop gets a generic answer, a known one our own name.
 */
@Component
public class FindBuyersTool implements Tool {
    public static final String NAME = "find_buyers";
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "commodity": {"type": "string", "description": "The crop the farmer wants to sell, as they said it, for example onion, pyaz or प्याज"},
               "quantity": {"type": "number", "description": "How much, in quintals, if the farmer said"},
               "district": {"type": "string", "description": "The farmer's district, town or mandi, if they said, for example Nashik or Lasalgaon"}},
             "required": ["commodity"]}""");

    private final BuyerDirectory directory;
    private final BuyerConsent consent;

    public FindBuyersTool(BuyerDirectory directory, BuyerConsent consent) {
        this.directory = directory;
        this.consent = consent;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Find up to two buyers from our directory for the farmer's crop. Call it once you know the crop, and "
                + "the quantity and district if the farmer gave them, after the dated mandi reference.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> commodity = ToolArgs.text(args, "commodity");
        if (commodity.isEmpty()) return ToolResult.error("commodity is required.");
        OptionalDouble quantity;
        try {
            quantity = quantity(args);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("quantity must be a positive number of quintals.");
        }
        // The commodity text is untrusted (the model's copy of the farmer's words): only our own crop names go back.
        Optional<String> known = directory.knownCommodity(commodity.get());
        List<BuyerDirectory.Match> matches = known.isEmpty() ? List.of()
                : directory.match(known.get(), quantity, ToolArgs.text(args, "district"));
        consent.suggested(ctx, matches.stream().map(m -> m.buyer().id()).toList());
        if (known.isEmpty()) {
            return ToolResult.ok("No buyer in our directory buys that crop yet. Tell the farmer honestly and offer to "
                    + "call back when one is listed. Do not make up a buyer.");
        }
        String crop = known.get();
        if (matches.isEmpty()) {
            return ToolResult.ok("No buyer in our directory buys " + crop + " in that quantity yet. Tell the farmer "
                    + "honestly and offer to call back when one is listed. Do not make up a buyer.");
        }
        StringBuilder out = new StringBuilder("Directory buyers for ").append(crop)
                .append(". The ranges are what our directory lists, not today's price.");
        String[] labels = {"First", "Second"};
        for (int i = 0; i < matches.size(); i++) {
            BuyerDirectory.Match m = matches.get(i);
            Buyer b = m.buyer();
            out.append(' ').append(labels[i]).append(": buyer_id ").append(b.id()).append(": ").append(b.name())
                    .append(", ").append(b.type()).append(" in ").append(b.place())
                    .append("; listed range ").append(SpokenEnglish.number(b.minPrice())).append(" to ")
                    .append(SpokenEnglish.number(b.maxPrice())).append(" rupees per quintal; speaks ")
                    .append(languageName(b.language())).append("; why: ").append(m.reason()).append('.');
        }
        // Spelled-out ordinals and prices: the model's text goes to Timbre, which reads digits badly.
        out.append(" Recommend the first. For each, tell the farmer the name, place, listed range and language "
                + "(never the buyer_id). Then call ask_connect_buyer for the one they want, ")
                .append(matches.get(0).buyer().name()).append(" unless they say otherwise, and say its question. "
                        + "Call connect_buyer only after the farmer answers that question.");
        return ToolResult.ok(out.toString());
    }

    private static OptionalDouble quantity(JsonNode args) {
        JsonNode v = args == null ? null : args.get("quantity");
        if (v == null || v.isNull()) return OptionalDouble.empty();
        double q;
        if (v.isNumber()) {
            q = v.asDouble();
        } else if (v.isTextual() && !v.asText().isBlank()) {
            q = Double.parseDouble(v.asText().strip()); // NumberFormatException is an IllegalArgumentException
        } else {
            throw new IllegalArgumentException("quantity");
        }
        if (!(q > 0) || Double.isInfinite(q)) throw new IllegalArgumentException("quantity");
        return OptionalDouble.of(q);
    }

    static String languageName(String code) {
        return switch (code.length() >= 2 ? code.substring(0, 2) : code) {
            case "hi" -> "Hindi";
            case "en" -> "English";
            case "bn" -> "Bengali";
            default -> code;
        };
    }
}
