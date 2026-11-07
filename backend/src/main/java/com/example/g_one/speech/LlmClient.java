package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Minimal OpenAI-compatible /chat/completions caller that returns the model's JSON answer. */
@Component
public class LlmClient {
    private final GOneProperties.Llm cfg;
    private final RestClient client;
    private final ObjectMapper json;

    public LlmClient(GOneProperties props, ObjectMapper json) {
        this.cfg = props.llm();
        this.client = cfg.configured() ? Http.client(cfg.baseUrl(), cfg.timeoutSeconds()) : null;
        this.json = json;
    }

    public boolean configured() {
        return cfg.configured();
    }

    /** @param stage error code to raise on failure (e.g. "translation_failed") */
    public JsonNode askJson(String stage, String system, String user) {
        if (client == null) throw new UpstreamException(stage, "Translation service is not configured.");
        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
        Http.Result r = Http.call(stage, client.post().uri("/chat/completions")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
        if (!r.ok()) throw new UpstreamException(stage, "Translation service returned HTTP " + r.status() + ".");
        try {
            String content = json.readTree(r.body()).path("choices").path(0).path("message").path("content").asText("");
            JsonNode answer = json.readTree(content);
            if (!answer.isObject()) throw new IOException("not an object");
            return answer;
        } catch (IOException e) {
            throw new UpstreamException(stage, "Translation service returned an unreadable answer.");
        }
    }
}
