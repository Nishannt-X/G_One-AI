package com.example.g_one_legacy.service;

import java.util.concurrent.CompletableFuture;

/**
 * Groq LLM service for agentic translation, sentiment analysis, and ledger extraction.
 */
public interface GroqLlmService {

    /**
     * Sends transcribed text to Groq for agentic processing (without conversation context).
     */
    CompletableFuture<String> processTranslation(String text);

    /**
     * Sends transcribed text to Groq with conversation context for richer agentic processing.
     * Returns a raw JSON string containing:
     *   - translated_text: the translated text (Hindi↔English)
     *   - sentiment_shield_flag: boolean indicating manipulative tactics
     *   - ai_whisperer_advice: tactical advice in the LISTENER's language
     *   - deal_confirmed: boolean if "Deal confirm" / "डील कन्फर्म" detected
     *   - ledger_lock: { price, quantity, currency, item }
     *
     * @param text the transcribed text from Deepgram
     * @param conversationContext formatted previous turns from Redis
     * @return CompletableFuture resolving to the raw JSON response content from Groq
     */
    CompletableFuture<String> processTranslation(String text, String conversationContext);
}
