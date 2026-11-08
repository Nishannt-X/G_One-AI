package com.example.g_one.core;

import com.example.g_one.GOneProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A language the demo offers: Gnani locale code, display label and the Timbre voice used to speak it. */
public record Language(String code, String label, String voice) {

    /** Supported languages, parsed once from configuration ("code:label:voice,..."). */
    @Component
    public static class Registry {
        private final Map<String, Language> byCode = new LinkedHashMap<>();

        public Registry(GOneProperties props) {
            for (String entry : props.languages()) {
                String[] p = entry.trim().split(":");
                if (p.length != 3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) {
                    throw new IllegalStateException("G_ONE_LANGUAGES entries must be code:label:voice");
                }
                byCode.put(p[0].trim(), new Language(p[0].trim(), p[1].trim(), p[2].trim()));
            }
            if (byCode.size() < 2) {
                throw new IllegalStateException("G_ONE_LANGUAGES must list at least two languages");
            }
        }

        public List<Language> all() {
            return List.copyOf(byCode.values());
        }

        /** @throws IllegalArgumentException when the code is not an offered language */
        public Language require(String code) {
            Language l = code == null ? null : byCode.get(code);
            if (l == null) {
                throw new IllegalArgumentException("Unsupported language.");
            }
            return l;
        }
    }
}
