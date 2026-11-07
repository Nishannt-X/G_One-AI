package com.example.g_one_legacy.service;

/**
 * Redis-backed conversation context service.
 * Maintains a sliding window of the last 5 turns per speaker.
 */
public interface ConversationContextService {

    /**
     * Adds a conversation turn for a speaker. Trims to keep only the last 5.
     */
    void addTurn(String speakerId, String text);

    /**
     * Returns formatted context block for a single speaker.
     * e.g., "A_1: ...\nA_2: ...\nA_3: ..."
     */
    String getContextBlock(String speakerId);

    /**
     * Returns the combined context for both speakers, formatted for Groq injection.
     */
    String getFullContextForGroq();

    /**
     * Clears all conversation context (e.g., on call end).
     */
    void clearAll();
}
