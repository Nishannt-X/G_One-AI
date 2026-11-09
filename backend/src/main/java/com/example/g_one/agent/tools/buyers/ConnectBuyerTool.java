package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code connect_buyer(buyer_id)}: calls a suggested buyer after the farmer's explicit spoken yes (docs/call-flow.md
 * step 8). The model's call alone is not enough: {@link BuyerConsent} checks in code that the buyer was suggested on
 * this call and that the farmer's latest turn, heard after the suggestion, is a clear yes. Unclear means no. Only then
 * is the number looked up ({@link BuyerPhoneBook}) and {@link BuyerConnector} called, once. The result tells the model
 * what to say and never contains the number.
 */
@Component
public class ConnectBuyerTool implements Tool {
    public static final String NAME = "connect_buyer";
    private static final Logger log = LoggerFactory.getLogger(ConnectBuyerTool.class);
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {"buyer_id": {"type": "string", "description": "The buyer_id from find_buyers"}},
             "required": ["buyer_id"]}""");

    private final BuyerDirectory directory;
    private final BuyerConsent consent;
    private final BuyerPhoneBook phones;
    private final BuyerConnector connector;

    public ConnectBuyerTool(BuyerDirectory directory, BuyerConsent consent, BuyerPhoneBook phones,
                            BuyerConnector connector) {
        this.directory = directory;
        this.consent = consent;
        this.phones = phones;
        this.connector = connector;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Phone a buyer from find_buyers and join them to this call. Call it only right after the farmer "
                + "clearly said yes to calling that buyer.";
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
        switch (consent.tryConsume(ctx.callId(), buyer.id())) {
            case NOT_SUGGESTED -> {
                return ToolResult.error("That buyer was not suggested on this call. Call find_buyers and suggest "
                        + "them to the farmer first.");
            }
            case NOT_ANSWERED -> {
                return ToolResult.error("The farmer has not answered yet. Ask: shall I call " + buyer.name()
                        + " now? Please say yes or no. Then wait for their answer.");
            }
            case ALREADY_USED -> {
                return ToolResult.error("The farmer's yes was already used for a call. Ask them again before "
                        + "calling anyone else.");
            }
            case NO_CONSENT -> {
                return ToolResult.error("The farmer did not give a clear yes, so do not call. Unclear means no: say "
                        + "sorry, you did not catch that, and ask once more for a plain yes or no. If the answer is "
                        + "still unclear, say you will not call " + buyer.name() + " now.");
            }
            case GRANTED -> {
                // fall through to the dial below
            }
        }
        Optional<String> number = phones.number(buyer.phoneRef());
        if (number.isEmpty()) {
            log.warn("No phone number configured for buyer {} on call {}", buyer.id(), ctx.callId());
            return ToolResult.error(buyer.name() + " cannot be reached by phone right now. Tell the farmer honestly "
                    + "and offer the other buyer or a call back later.");
        }
        BuyerConnector.Outcome outcome;
        try {
            outcome = connector.connect(ctx, new BuyerConnector.Dial(buyer, number.get()));
        } catch (RuntimeException e) {
            log.warn("Buyer connector failed for buyer {} on call {} ({})", buyer.id(), ctx.callId(),
                    e.getClass().getSimpleName());
            outcome = BuyerConnector.Outcome.FAILED;
        }
        return switch (outcome == null ? BuyerConnector.Outcome.FAILED : outcome) {
            case DIALING -> ToolResult.ok("Calling " + buyer.name() + " now. Tell the farmer you are calling them, it "
                    + "may ring for a little while, and to please stay on the line. " + buyer.name()
                    + " will be asked to agree before you start translating.");
            case NOT_WIRED -> ToolResult.error("Calling buyers is not switched on yet. Tell the farmer honestly that "
                    + "you cannot call " + buyer.name() + " right now, and offer to call them back later.");
            case FAILED -> ToolResult.error("The call to " + buyer.name() + " could not be started. Apologise and "
                    + "offer the other buyer or a call back later.");
        };
    }
}
