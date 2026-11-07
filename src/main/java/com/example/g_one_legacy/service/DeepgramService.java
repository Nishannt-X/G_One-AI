package com.example.g_one_legacy.service;

/**
 * Deepgram Speech-to-Text service using real-time streaming WebSocket.
 * Each speaker gets a dedicated persistent WebSocket connection to Deepgram.
 */
public interface DeepgramService {

    /**
     * Opens a persistent WebSocket connection to Deepgram's streaming API for a speaker.
     * Must be called before streamAudio().
     *
     * @param speakerId the speaker identifier (e.g., "A" or "B")
     */
    void openConnection(String speakerId);

    /**
     * Streams raw audio bytes to the speaker's active Deepgram WebSocket.
     * Bytes are sent immediately with NO buffering — Deepgram handles chunking internally.
     *
     * @param audioBytes raw mu-law 8kHz audio bytes (typically 160 bytes from Twilio)
     * @param speakerId  the speaker identifier (e.g., "A" or "B")
     */
    void streamAudio(byte[] audioBytes, String speakerId);

    /**
     * Closes the Deepgram WebSocket connection for a speaker.
     * Should be called when the Twilio stream stops.
     *
     * @param speakerId the speaker identifier (e.g., "A" or "B")
     */
    void closeConnection(String speakerId);
}
