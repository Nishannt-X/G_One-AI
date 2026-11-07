package com.example.g_one;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The single place configuration is read. Values come from environment variables (see application.yml). */
@ConfigurationProperties("g-one")
public record GOneProperties(
        List<String> languages,
        String accessCode,
        int maxTurnSeconds,
        int maxTurnsPerRoom,
        int maxRooms,
        int roomIdleMinutes,
        String mandiSnapshotFile,
        Agmarknet agmarknet,
        Gnani gnani,
        Llm llm) {

    /**
     * Optional live AGMARKNET lookup for one commodity/variety/market. The six identifying fields are all required
     * together (see MandiSource); the names are checked against the response, so a wrong ID is rejected, not trusted.
     */
    public record Agmarknet(String baseUrl, String marketId, String stateId, String commodityId, String commodity,
                            String market, String variety, int timeoutSeconds, int maxAgeDays, int cacheMinutes) {}

    public record Gnani(String baseUrl, String apiKey, String sttModel, String ttsModel, int timeoutSeconds) {
        public boolean configured() {
            return !apiKey.isBlank();
        }
    }

    public record Llm(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        public boolean configured() {
            return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
        }
    }
}
