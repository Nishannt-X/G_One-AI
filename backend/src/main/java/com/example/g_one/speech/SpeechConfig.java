package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the real Gnani speech adapters when {@code GNANI_API_KEY} is set; without a key there are no speech beans
 * and nothing calls Gnani. {@code g-one.speech.mode} (env {@code G_ONE_SPEECH_MODE}) picks the path:
 * <ul>
 *   <li>{@code rest} (default): our turn detection plus Prisma REST per turn, and Timbre REST. Verified live.</li>
 *   <li>{@code realtime}: Prisma and Timbre WebSockets. Documented, not yet probed with the project's key.</li>
 * </ul>
 */
@Configuration
@ConditionalOnExpression("!'${g-one.gnani.api-key:}'.isEmpty()")
class SpeechConfig {

    @Bean
    SpeechToText speechToText(GOneProperties props, PrismClient prism, ObjectMapper json,
            @Value("${g-one.speech.mode:rest}") String mode) {
        return isRealtime(mode) ? new PrismRealtimeStt(props.gnani(), json) : new PrismRestStt(prism);
    }

    @Bean
    TextToSpeech textToSpeech(GOneProperties props, TimbreClient timbre, ObjectMapper json,
            @Value("${g-one.speech.mode:rest}") String mode) {
        return isRealtime(mode) ? new TimbreRealtimeTts(props.gnani(), json) : new TimbreRestTts(timbre);
    }

    private static boolean isRealtime(String mode) {
        if (mode.equalsIgnoreCase("realtime")) return true;
        if (mode.equalsIgnoreCase("rest")) return false;
        throw new IllegalStateException("g-one.speech.mode must be rest or realtime");
    }
}
