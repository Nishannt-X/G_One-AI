package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.service.GroqLlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Groq LLM service with full agentic capabilities:
 * 1. Bidirectional translation (Hindi ↔ English)
 * 2. Sentiment Shield — detects aggressive/manipulative negotiation tactics
 * 3. AI Whisperer — generates tactical advice for the listener (in listener's
 * language)
 * 4. Deal Detection — detects "Deal confirm" / "डील कन्फर्म"
 * 5. Ledger Lock — extracts agreed price/quantity/item from conversation
 *
 * Now accepts conversation context (Redis sliding window of last 5 turns).
 */
@Service
public class GroqLlmServiceImpl implements GroqLlmService {

    private static final Logger log = LoggerFactory.getLogger(GroqLlmServiceImpl.class);

    private static final String SYSTEM_PROMPT = """
            You are G-One, an intelligent, real-time bilingual translator and negotiation assistant \
            for Indian farmers and BFSI buyers.

            Your primary task is to translate the input accurately between Hindi and English.

            You will receive CONVERSATION CONTEXT (previous turns from both speakers) and the CURRENT INPUT. \
            Use the context to produce better translations and more accurate analysis.

            Simultaneously, you must analyze the input to execute these core functions:

            1. SENTIMENT SHIELD: Detect if the speaker is using aggressive language, intimidation, \
            predatory pricing, or manipulative negotiation tactics.

            2. AI WHISPERER: If manipulative tactics or requesting of personal details such as OTP, PIN, Password, Bank Details, etc. are detected, or if the user needs guidance, \
            generate a short, tactical piece of advice to protect the listener. \
            CRITICAL: The advice MUST be written in the SAME language as the translated_text \
            (i.e., the listener's language).

            3. LEDGER LOCK: If the conversation contains discussion regarding a specific price and \
            quantity of a commodity, extract those exact values. Include the item name in English.

            4. DEAL DETECTION: If the speaker says "Deal confirm", "Deal done", "Deal pakka", "डील कन्फर्म", \
            "डील पक्का", "सौदा पक्का" or any equivalent phrase indicating both parties want to finalize, \
            set deal_confirmed to true.

            You MUST respond STRICTLY in valid JSON format. Do not include markdown code blocks. \
            Use this exact structure:
            {
              "translated_text": "<translated text here>",
              "sentiment_shield_flag": <true or false>,
              "ai_whisperer_advice": "<tactical advice in listener's language, or empty string>",
              "deal_confirmed": <true if deal confirmation phrase detected, otherwise false>,
              "ledger_lock": {
                "price": <number or null>,
                "quantity": <number or null>,
                "currency": "<INR or other currency code, or null>",
                "item": "<commodity name in English, e.g. wheat, rice, cotton, or null>"
              }
            }
            """;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public GroqLlmServiceImpl(WebClient.Builder webClientBuilder,
            @Value("${g-one.api.groq.key:${voxora.api.groq.key:}}") String apiKey,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        log.info("[GROQ] Initialized with API Key (starts with: {})",
                apiKey != null && apiKey.length() > 4 ? apiKey.substring(0, 4) + "..." : "INVALID_KEY");

        this.webClient = webClientBuilder
                .baseUrl("https://api.groq.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public CompletableFuture<String> processTranslation(String text) {
        return processTranslation(text, "");
    }

    @Override
    public CompletableFuture<String> processTranslation(String text, String conversationContext) {
        log.info("[GROQ] Sending text for agentic processing. Input length: {}, Context length: {}",
                text.length(), conversationContext != null ? conversationContext.length() : 0);

        // Build user message with context
        String userMessage;
        if (conversationContext != null && !conversationContext.isBlank()) {
            userMessage = "CONVERSATION CONTEXT:\n" + conversationContext +
                    "\n\nCURRENT INPUT TO TRANSLATE:\n" + text;
        } else {
            userMessage = text;
        }

        // Build OpenAI-compatible request body
        Map<String, Object> requestBody = Map.of(
                "model", "llama-3.3-70b-versatile",
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userMessage)),
                "temperature", 0.3,
                "max_tokens", 1024,
                "response_format", Map.of("type", "json_object"));

        return webClient.post()
                .uri("/openai/v1/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .toFuture()
                .thenApply(responseJson -> {
                    try {
                        JsonNode root = objectMapper.readTree(responseJson);
                        JsonNode choices = root.path("choices");

                        if (choices.isArray() && !choices.isEmpty()) {
                            String content = choices.get(0)
                                    .path("message")
                                    .path("content")
                                    .asText("");

                            log.info("[GROQ] Raw response content: {}", content);
                            return content;
                        }

                        log.warn("[GROQ] Unexpected response format: {}", responseJson);
                        return "";
                    } catch (Exception e) {
                        log.error("[GROQ] Failed to parse response: {}", e.getMessage(), e);
                        return "";
                    }
                })
                .exceptionally(ex -> {
                    log.error("[GROQ] API request failed: {}", ex.getMessage(), ex);
                    return "";
                });
    }
}
