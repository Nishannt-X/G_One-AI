package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.Language;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Gnani Timbre text-to-speech, REST: POST /api/v1/tts/inference returns binary WAV (https://docs.gnani.ai/api/TTS/tts-inference). */
@Component
public class TimbreClient {
    private final GOneProperties.Gnani cfg;
    private final RestClient client;

    public TimbreClient(GOneProperties props) {
        this.cfg = props.gnani();
        this.client = Http.client(cfg.baseUrl(), cfg.timeoutSeconds());
    }

    public byte[] synthesize(String text, Language language) {
        Map<String, Object> body = Map.of(
                "text", text,
                "model", cfg.ttsModel(),
                "voice", language.voice(),
                "language", language.code(),
                "audio_config", Map.of(
                        "sample_rate", 24000,
                        "num_channels", 1,
                        "sample_width", 2,
                        "encoding", "linear_pcm",
                        "container", "wav"));
        Http.Result r = Http.call("tts_failed", client.post().uri("/api/v1/tts/inference")
                .header("X-API-Key-ID", cfg.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
        if (!r.ok()) {
            throw new UpstreamException("tts_failed", "Voice service returned HTTP " + r.status() + ".");
        }
        byte[] wav = r.body();
        if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            throw new UpstreamException("tts_failed", "Voice service did not return WAV audio.");
        }
        return wav;
    }
}
