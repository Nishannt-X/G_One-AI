package com.example.g_one_legacy.service;

import java.util.concurrent.CompletableFuture;

public interface GeminiLlmService {
    CompletableFuture<String> generateTextResponse(String text, String systemPrompt);
}

