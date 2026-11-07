package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.stereotype.Component;

/** Gnani Prisma speech-to-text, REST: POST /stt/v3, multipart audio_file (https://docs.gnani.ai/api/STT/speech-to-text). */
@Component
public class PrismClient {
    private final GOneProperties.Gnani cfg;
    private final RestClient client;
    private final ObjectMapper json;

    public PrismClient(GOneProperties props, ObjectMapper json) {
        this.cfg = props.gnani();
        this.client = Http.client(cfg.baseUrl(), cfg.timeoutSeconds());
        this.json = json;
    }

    public boolean configured() {
        return cfg.configured();
    }

    /** @return the transcript; throws UpstreamException("no_speech") when Prisma hears nothing */
    public String transcribe(byte[] wav, String languageCode) {
        AudioClip clip = compressForUpload(wav);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("audio_file", new ByteArrayResource(clip.bytes()) {
            @Override
            public String getFilename() {
                return clip.filename();
            }
        });
        form.add("language_code", languageCode);
        if (!cfg.sttModel().isBlank()) form.add("model", cfg.sttModel());

        Http.Result r = Http.call("stt_failed", client.post().uri("/stt/v3")
                .header("X-API-Key-ID", cfg.apiKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form));
        JsonNode body = parse(r.body());
        if (!r.ok() || !body.path("success").asBoolean(true)) {
            throw new UpstreamException("stt_failed", describe(r.status(), body));
        }
        String transcript = body.path("transcript").asText("").trim();
        if (transcript.isEmpty()) throw new UpstreamException("no_speech", "No speech was detected.");
        return transcript;
    }

    private record AudioClip(byte[] bytes, String filename) {}

    /**
     * Uploads the WAV as-is. TODO(#18): the old build saw uploads over about 48 KB fail with
     * SSLException bad_record_mac and worked around it with macOS afconvert (Mac only, removed here). Telephony turns
     * are 8 kHz and small, but the speech adapter issue must test long turns and pick a portable fix if needed.
     */
    private AudioClip compressForUpload(byte[] wav) {
        return new AudioClip(wav, "turn.wav");
    }

    private JsonNode parse(byte[] bytes) {
        try {
            return json.readTree(bytes);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    /** Only documented error codes/types are surfaced, never raw bodies. */
    static String describe(int status, JsonNode body) {
        String type = body.path("error").path("type").asText(body.path("detail").path("error_code").asText(""));
        return "Speech service returned HTTP " + status + (type.isEmpty() ? "" : " (" + type + ")") + ".";
    }
}
