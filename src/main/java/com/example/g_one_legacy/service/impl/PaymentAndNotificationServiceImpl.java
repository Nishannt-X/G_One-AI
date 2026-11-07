package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.entity.CallLog;
import com.example.g_one_legacy.repository.CallLogRepository;
import com.example.g_one_legacy.service.PaymentAndNotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentAndNotificationServiceImpl implements PaymentAndNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAndNotificationServiceImpl.class);
    private final ObjectMapper objectMapper;
    private final CallLogRepository callLogRepository;

    public PaymentAndNotificationServiceImpl(ObjectMapper objectMapper, CallLogRepository callLogRepository) {
        this.objectMapper = objectMapper;
        this.callLogRepository = callLogRepository;
    }

    @Override
    @Transactional
    public void processBooking(CallLog callLog, String jsonExtractedFromClaude) {
        try {
            JsonNode root = objectMapper.readTree(jsonExtractedFromClaude);
            String intent = root.path("intent").asText("");
            double amount = root.path("amount").asDouble(0.0);

            if (!"booking".equalsIgnoreCase(intent)) {
                log.info("Not a booking intent for CallLog ID: {}", callLog.getId());
                return;
            }

            log.info("✅ AI successfully extracted booking JSON: {}", jsonExtractedFromClaude);
            log.info("🛑 SKIPPING RAZORPAY & WHATSAPP: Mock payment link generated for MVP testing.");

            // 1. Generate Razorpay UPI Link
            String paymentLink = "https://mock-razorpay.com/test";

            // 2. Send via WhatsApp (Skipped in MVP)

            // 3. Update CallLog entity
            callLog.setBookingConfirmed(true);
            callLog.setPaymentLink(paymentLink);
            callLogRepository.save(callLog);

            log.info("Booking processed successfully for CallLog ID: {}", callLog.getId());
        } catch (Exception e) {
            log.error("Failed to process booking for CallLog ID: {}", callLog.getId(), e);
            throw new RuntimeException("Booking processing failed", e);
        }
    }

    private String generateRazorpayUpiLink(double amount, CallLog callLog) {
        // Stub: In a real app, call Razorpay Payment Links API here.
        log.info("Generating Razorpay UPI link for amount: {} (CallLog: {})", amount, callLog.getId());
        // Simulating the returned payment link
        return "https://rzp.io/i/mockLink" + callLog.getId();
    }

    private void sendWhatsAppNotification(String customerPhone, String paymentLink) {
        // Stub: In a real app, call WhatsApp Business API here.
        log.info("Sending WhatsApp message to {} with link: {}", customerPhone, paymentLink);
    }
}
