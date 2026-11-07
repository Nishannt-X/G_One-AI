package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.service.ContractMessagingService;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Sends verbal contract confirmations to both parties via Twilio WhatsApp.
 * Uses Twilio WhatsApp sandbox for MVP.
 */
@Service
public class ContractMessagingServiceImpl implements ContractMessagingService {

    private static final Logger log = LoggerFactory.getLogger(ContractMessagingServiceImpl.class);

    // Twilio WhatsApp Sandbox number
    private static final String WHATSAPP_FROM = "whatsapp:+14155238886";

    @Value("${TWILIO_ACCOUNT_SID}")
    private String accountSid;

    @Value("${TWILIO_AUTH_TOKEN}")
    private String authToken;

    @Value("${g-one.twilio.speakerA.phone:${voxora.twilio.speakerA.phone}}")
    private String speakerAPhone;

    @Value("${g-one.twilio.speakerB.phone:${voxora.twilio.speakerB.phone}}")
    private String speakerBPhone;

    @Override
    @Async
    public void sendContractToWhatsApp(String contractText) {
        log.info("[WHATSAPP] Sending contract to both speakers...\n{}", contractText);

        try {
            Twilio.init(accountSid, authToken);

            // ── Send to Speaker A ────────────────────────────────────────────
            Message msgA = Message.creator(
                    new PhoneNumber("whatsapp:" + speakerAPhone),
                    new PhoneNumber(WHATSAPP_FROM),
                    contractText
            ).create();
            log.info("[WHATSAPP] ✅ Sent to Speaker A ({}). SID: {}", speakerAPhone, msgA.getSid());

            // ── Send to Speaker B ────────────────────────────────────────────
            Message msgB = Message.creator(
                    new PhoneNumber("whatsapp:" + speakerBPhone),
                    new PhoneNumber(WHATSAPP_FROM),
                    contractText
            ).create();
            log.info("[WHATSAPP] ✅ Sent to Speaker B ({}). SID: {}", speakerBPhone, msgB.getSid());

            System.out.println("\n📱📱📱 WHATSAPP CONTRACT SENT 📱📱📱");
            System.out.println("   → Speaker A: " + speakerAPhone);
            System.out.println("   → Speaker B: " + speakerBPhone);
            System.out.println("📱📱📱📱📱📱📱📱📱📱📱📱📱📱📱📱📱📱\n");

        } catch (Exception e) {
            log.error("[WHATSAPP] ❌ Failed to send contract: {}", e.getMessage(), e);
        }
    }
}
