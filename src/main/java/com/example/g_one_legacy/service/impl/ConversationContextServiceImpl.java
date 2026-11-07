package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.service.ConversationContextService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis LIST-backed sliding window of the last 5 conversation turns per speaker.
 *
 * Keys:   convo:A:turns → LIST of strings
 *         convo:B:turns → LIST of strings
 * Pattern: RPUSH + LTRIM keeps only the last 5 entries.
 *
 * Output format:
 *   A_1: कितने का गेहूं है?
 *   A_2: ज्यादा है, कम करो
 */
@Service
public class ConversationContextServiceImpl implements ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextServiceImpl.class);
    private static final int MAX_TURNS = 5;

    private final StringRedisTemplate redisTemplate;
    private volatile boolean redisAvailable = false;

    public ConversationContextServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void checkRedisConnectivity() {
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                redisAvailable = true;
                log.info("[REDIS] ✅ Connection successful! Redis is ONLINE.");
            } else {
                log.warn("[REDIS] ⚠️ Unexpected PING response: {}", pong);
            }
        } catch (Exception e) {
            redisAvailable = false;
            log.error("[REDIS] ❌ Connection FAILED! Context window will be empty. Error: {}", e.getMessage());
            log.error("[REDIS] Check your REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, and SSL settings in .env / application.properties");
        }
    }

    private String redisKey(String speakerId) {
        return "convo:" + speakerId + ":turns";
    }

    @Override
    public void addTurn(String speakerId, String text) {
        if (!redisAvailable) {
            log.warn("[REDIS] Skipping addTurn — Redis not available");
            return;
        }

        String key = redisKey(speakerId);
        try {
            redisTemplate.opsForList().rightPush(key, text);
            redisTemplate.opsForList().trim(key, -MAX_TURNS, -1); // keep only last 5
            log.info("[REDIS] ✅ Stored turn for speaker {}: '{}'", speakerId,
                    text.length() > 60 ? text.substring(0, 60) + "..." : text);
        } catch (Exception e) {
            log.error("[REDIS] ❌ Failed to store turn for speaker {}: {}", speakerId, e.getMessage());
            redisAvailable = false; // mark as unavailable to avoid repeated failures
        }
    }

    @Override
    public String getContextBlock(String speakerId) {
        if (!redisAvailable) {
            return "Speaker " + speakerId + ": (Redis unavailable — no context)";
        }

        String key = redisKey(speakerId);
        try {
            List<String> turns = redisTemplate.opsForList().range(key, 0, -1);
            if (turns == null || turns.isEmpty()) {
                return "Speaker " + speakerId + ": (no previous turns)";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < turns.size(); i++) {
                sb.append(speakerId).append("_").append(i + 1).append(": ").append(turns.get(i));
                if (i < turns.size() - 1) sb.append("\n");
            }
            log.info("[REDIS] Retrieved {} turns for speaker {}", turns.size(), speakerId);
            return sb.toString();
        } catch (Exception e) {
            log.error("[REDIS] ❌ Failed to read context for speaker {}: {}", speakerId, e.getMessage());
            return "Speaker " + speakerId + ": (context unavailable)";
        }
    }

    @Override
    public String getFullContextForGroq() {
        String contextA = getContextBlock("A");
        String contextB = getContextBlock("B");

        String fullContext = "=== SPEAKER A (Hindi) PREVIOUS TURNS ===\n" + contextA +
                             "\n\n=== SPEAKER B (English) PREVIOUS TURNS ===\n" + contextB;

        log.info("[REDIS] Full context for Groq:\n{}", fullContext);
        return fullContext;
    }

    @Override
    public void clearAll() {
        if (!redisAvailable) return;
        try {
            redisTemplate.delete(redisKey("A"));
            redisTemplate.delete(redisKey("B"));
            log.info("[REDIS] Cleared all conversation context");
        } catch (Exception e) {
            log.error("[REDIS] Failed to clear context: {}", e.getMessage());
        }
    }
}
