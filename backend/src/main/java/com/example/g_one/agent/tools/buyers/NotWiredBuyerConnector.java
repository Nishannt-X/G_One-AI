package com.example.g_one.agent.tools.buyers;

import com.example.g_one.core.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The default {@link BuyerConnector} until telephony (#25) lands: logs the request (never the number) and dials nothing. */
@Component
public class NotWiredBuyerConnector implements BuyerConnector {
    private static final Logger log = LoggerFactory.getLogger(NotWiredBuyerConnector.class);

    @Override
    public Outcome connect(CallContext ctx, Dial dial) {
        log.info("Buyer connect requested on call {} for buyer {}: telephony not wired", ctx.callId(),
                dial.buyer().id());
        return Outcome.NOT_WIRED;
    }
}
