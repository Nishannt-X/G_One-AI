package com.example.g_one.memory;

import com.example.g_one.core.CallerKey;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Farmer memory configuration ({@code g-one.memory.*} in application.yml). The HMAC key and access code are secrets:
 * this record never prints them.
 *
 * @param store {@code sqlite} (default) or {@code in-memory}
 * @param dataDir directory that holds {@code g-one.db}
 * @param hmacKey base64 of the HMAC key used to turn a phone number into a {@link CallerKey}
 * @param seedFile optional synthetic seed file: a path, or {@code classpath:...}
 * @param accessCode required in the {@code X-Access-Code} header for {@code /api/memory/**}; blank turns the check off
 */
@ConfigurationProperties("g-one.memory")
public record MemoryProperties(String store, String dataDir, String hmacKey, String seedFile, String accessCode) {

    public MemoryProperties {
        store = blankTo(store, "sqlite").toLowerCase(Locale.ROOT);
        dataDir = blankTo(dataDir, "./data");
        hmacKey = hmacKey == null ? "" : hmacKey.strip();
        seedFile = seedFile == null ? "" : seedFile.strip();
        accessCode = accessCode == null ? "" : accessCode;
        if (!store.equals("sqlite") && !store.equals("in-memory")) {
            throw new IllegalStateException("G_ONE_MEMORY_STORE must be sqlite or in-memory");
        }
    }

    /**
     * The decoded HMAC key, or empty when unset.
     *
     * @throws IllegalStateException when set but not base64 or shorter than {@value CallerKey#MIN_SALT_BYTES} bytes
     */
    public Optional<byte[]> salt() {
        if (hmacKey.isEmpty()) {
            return Optional.empty();
        }
        byte[] salt;
        try {
            salt = Base64.getDecoder().decode(hmacKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("G_ONE_MEMORY_HMAC_KEY must be base64");
        }
        if (salt.length < CallerKey.MIN_SALT_BYTES) {
            throw new IllegalStateException(
                    "G_ONE_MEMORY_HMAC_KEY must decode to at least " + CallerKey.MIN_SALT_BYTES + " bytes");
        }
        return Optional.of(salt);
    }

    public boolean accessCheckEnabled() {
        return !accessCode.isEmpty();
    }

    @Override
    public String toString() {
        return "MemoryProperties[store=" + store + ", dataDir=" + dataDir + ", seedFile=" + seedFile + "]";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
