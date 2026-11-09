package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.brain.LlmAgentBrain;
import com.example.g_one.agent.brain.LlmAgentBrain.AnsweringTurn;
import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code ask_connect_buyer(buyer_id)}: the consent question for one suggested buyer (docs/call-flow.md step 8). It
 * records the question in {@link BuyerConsent} and hands the model the fixed line to say. Only the farmer's next turn
 * after this one can then say yes to calling that buyer, and only that buyer.
 */
@Component
public class AskConnectBuyerTool implements Tool {
    public static final String NAME = "ask_connect_buyer";
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {"buyer_id": {"type": "string", "description": "The buyer_id from find_buyers"}},
             "required": ["buyer_id"]}""");

    private final BuyerDirectory directory;
    private final BuyerConsent consent;
    private final Function<CallContext, Optional<AnsweringTurn>> answering;

    @Autowired
    public AskConnectBuyerTool(BuyerDirectory directory, BuyerConsent consent) {
        this(directory, consent, LlmAgentBrain::answering);
    }

    /** @param answering the farmer turn the brain is answering for a call (tests supply their own) */
    public AskConnectBuyerTool(BuyerDirectory directory, BuyerConsent consent,
                               Function<CallContext, Optional<AnsweringTurn>> answering) {
        this.directory = directory;
        this.consent = consent;
        this.answering = answering;
    }

    /** The fixed consent question, in English; the model says it in the farmer's language. */
    static String question(Buyer buyer) {
        return "Shall I call " + buyer.name() + " now? Please say yes or no.";
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Ask the farmer whether to phone one buyer from find_buyers. Call it before connect_buyer, for the one "
                + "buyer the farmer wants, then say the line it returns and wait for the answer.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> id = ToolArgs.text(args, "buyer_id");
        if (id.isEmpty()) return ToolResult.error("buyer_id is required.");
        Optional<Buyer> found = directory.byId(id.get());
        if (found.isEmpty()) {
            return ToolResult.error("No buyer with that buyer_id is in the directory. Use one from find_buyers.");
        }
        Buyer buyer = found.get();
        Optional<AnsweringTurn> turn = answering.apply(ctx);
        if (turn.isEmpty()) return ToolResult.error("You can only ask this while answering the farmer.");
        return switch (consent.asked(ctx, turn.get(), buyer.id())) {
            case ASKED -> ToolResult.ok("Say this to the farmer in their language, as the last thing in your reply, "
                    + "then wait for their answer: \"" + question(buyer) + "\" Call connect_buyer for "
                    + buyer.name() + " only after they answer.");
            case NOT_SUGGESTED -> ToolResult.error("That buyer was not suggested on this call. Call find_buyers and "
                    + "suggest them to the farmer first.");
            case STALE -> ToolResult.error("The farmer has said something newer. Answer that first and do not ask "
                    + "about calling anyone in this reply.");
            case ENDED -> ToolResult.error("The call has ended. Do not call anyone.");
        };
    }
}
