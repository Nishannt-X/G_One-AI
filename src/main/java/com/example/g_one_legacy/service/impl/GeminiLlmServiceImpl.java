package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.service.GeminiLlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class GeminiLlmServiceImpl implements GeminiLlmService {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmServiceImpl.class);

    private final WebClient webClient;
    private final String geminiApiKey;

    public GeminiLlmServiceImpl(WebClient.Builder webClientBuilder,
                                @Value("${g-one.api.gemini.key:${voxora.api.gemini.key:}}") String geminiApiKey) {
        this.webClient = webClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.geminiApiKey = geminiApiKey;
    }

    @Override
    public CompletableFuture<String> generateTextResponse(String text, String systemPrompt) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Gemini API key is missing"));
        }

        // Construct Gemini request body
        // Note: For simplicity, combining system prompt and user text,
        // as model config handling can vary significantly by Gemini model version.
        String combinedPrompt = String.format("System Rules:\n%s\n\nUser Input:\n%s", systemPrompt, text);

        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> parts = new HashMap<>();
        parts.put("text", combinedPrompt);

        Map<String, Object> contents = new HashMap<>();
        contents.put("parts", List.of(parts));

        requestBody.put("contents", List.of(contents));

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/gemini-pro:generateContent")
                        .queryParam("key", geminiApiKey)
                        .build())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(node -> {
                    try {
                        JsonNode candidates = node.path("candidates");
                        if (candidates.isArray() && !candidates.isEmpty()) {
                            JsonNode partsNode = candidates.get(0).path("content").path("parts");
                            if (partsNode.isArray() && !partsNode.isEmpty()) {
                                return partsNode.get(0).path("text").asText("");
                            }
                        }
                        log.warn("Unexpected Gemini response format: {}", node);
                        return "I am sorry, I encountered an error generating a response.";
                    } catch (Exception e) {
                        log.error("Error parsing Gemini response", e);
                        return "I am sorry, I encountered an error.";
                    }
                })
                .doOnError(error -> log.error("Gemini API request failed", error))
                .toFuture();
    }
}
