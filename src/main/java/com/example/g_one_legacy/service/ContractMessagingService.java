package com.example.g_one_legacy.service;

/**
 * Sends verbal contract confirmations via Twilio WhatsApp.
 */
public interface ContractMessagingService {

    /**
     * Sends formatted contract text to both speaker phone numbers via WhatsApp.
     * @param contractText the formatted contract summary
     */
    void sendContractToWhatsApp(String contractText);
}
