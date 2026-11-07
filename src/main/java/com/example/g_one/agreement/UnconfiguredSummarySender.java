package com.example.g_one.agreement;

import org.springframework.stereotype.Component;

/** Default until a WhatsApp sender is configured: sends nothing and says so, so the app shows an honest "not sent". */
@Component
class UnconfiguredSummarySender implements SummarySender {
    @Override
    public Delivery send(Summary summary) {
        return Delivery.notSent("not_configured");
    }
}
