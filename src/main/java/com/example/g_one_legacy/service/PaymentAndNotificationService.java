package com.example.g_one_legacy.service;

import com.example.g_one_legacy.entity.CallLog;

public interface PaymentAndNotificationService {
    void processBooking(CallLog callLog, String jsonExtractedFromClaude);
}

