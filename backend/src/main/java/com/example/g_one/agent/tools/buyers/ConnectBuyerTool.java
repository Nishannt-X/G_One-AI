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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code connect_buyer(buyer_id)}: calls a suggested buyer after the farmer's explicit spoken yes (docs/call-flow.md
 * step 8). The model's call alone is not enough: {@link BuyerConsent} checks in code that {@code ask_connect_buyer}
 * asked about this very buyer, and that the farmer turn this reply answers ({@link LlmAgentBrain#answering}) is the next
 * one after that question, is still the newest, and is a clear yes. Unclear means no. Only then is the number looked up
 * ({@link BuyerPhoneBook}) and {@link BuyerConnector} called, once, inside {@link BuyerConsent#dialWhileLive} so it
 * never runs after hang-up. The result tells the model what to say and never contains the number.
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
    private final Function<CallContext, Optional<AnsweringTurn>> answering;

    @Autowired
    public ConnectBuyerTool(BuyerDirectory directory, BuyerConsent consent, BuyerPhoneBook phones,
                            BuyerConnector connector) {
        this(directory, consent, phones, connector, LlmAgentBrain::answering);
    }

    /** @param answering the farmer turn the brain is answering for a call (tests supply their own) */
    public ConnectBuyerTool(BuyerDirectory directory, BuyerConsent consent, BuyerPhoneBook phones,
                            BuyerConnector connector, Function<CallContext, Optional<AnsweringTurn>> answering) {
        this.directory = directory;
        this.consent = consent;
        this.phones = phones;
        this.connector = connector;
        this.answering = answering;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Phone a buyer and join them to this call. Call it only when the farmer has just clearly said yes to "
                + "the ask_connect_buyer question about that buyer.";
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
        if (turn.isEmpty()) return ToolResult.error("You can only call a buyer while answering the farmer.");
        switch (consent.tryConsume(ctx, turn.get(), buyer.id())) {
            case NOT_SUGGESTED -> {
                return ToolResult.error("That buyer was not suggested on this call. Call find_buyers and suggest "
                        + "them to the farmer first.");
            }
            case NOT_ASKED -> {
                return ToolResult.error("You have not asked the farmer yet. Call ask_connect_buyer for "
                        + buyer.name() + " and wait for their answer.");
            }
            case OTHER_BUYER -> {
                return ToolResult.error("The farmer was asked about a different buyer, so do not call "
                        + buyer.name() + ". If they want " + buyer.name() + ", call ask_connect_buyer for them first.");
            }
            case NOT_ANSWERED -> {
                return ToolResult.error("The farmer has not answered yet. Wait for their answer.");
            }
            case MOVED_ON, STALE -> {
                return ToolResult.error("The turn you are answering is not the farmer's answer to the question, so do "
                        + "not call. Answer what they said; if they still want a buyer, ask again with "
                        + "ask_connect_buyer.");
            }
            case NO_CONSENT -> {
                return ToolResult.error("The farmer did not give a clear yes, so do not call. Unclear means no: say "
                        + "sorry, you did not catch that, and ask once more with ask_connect_buyer. If the answer is "
                        + "still unclear, say you will not call " + buyer.name() + " now.");
            }
            case ENDED -> {
                return ended();
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
        BuyerConnector.Dial dial = new BuyerConnector.Dial(buyer, number.get());
        Optional<BuyerConnector.Outcome> dialled = consent.dialWhileLive(ctx, turn.get(), () -> {
            try {
                BuyerConnector.Outcome o = connector.connect(ctx, dial);
                return o == null ? BuyerConnector.Outcome.FAILED : o;
            } catch (RuntimeException e) {
                log.warn("Buyer connector failed for buyer {} on call {} ({})", buyer.id(), ctx.callId(),
                        e.getClass().getSimpleName());
                return BuyerConnector.Outcome.FAILED;
            }
        });
        if (dialled.isEmpty()) {
            return consent.ended(ctx.callId()) ? ended() : ToolResult.error("The farmer said something new before "
                    + "the call started, so do not call. Answer what they said.");
        }
        return switch (dialled.get()) {
            case DIALING -> ToolResult.ok("Calling " + buyer.name() + " now. Tell the farmer you are calling them, it "
                    + "may ring for a little while, and to please stay on the line. " + buyer.name()
                    + " will be asked to agree before you start translating.");
            case NOT_WIRED -> ToolResult.error("Calling buyers is not switched on yet. Tell the farmer honestly that "
                    + "you cannot call " + buyer.name() + " right now, and offer to call them back later.");
            case FAILED -> ToolResult.error("The call to " + buyer.name() + " could not be started. Apologise and "
                    + "offer the other buyer or a call back later.");
        };
    }

    private static ToolResult ended() {
        return ToolResult.error("The call has ended. Do not call anyone.");
    }
}
