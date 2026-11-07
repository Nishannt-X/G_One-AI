package com.example.g_one_legacy.service;

import java.util.concurrent.CompletableFuture;

public interface ElevenLabsService {
    /**
     * Converts raw text to synthetic voice bytes asynchronously.
     * @param text string to synthesize
     * @return Future emitting raw byte audio representation
     */
    CompletableFuture<byte[]> synthesizeAudio(String text);
}
