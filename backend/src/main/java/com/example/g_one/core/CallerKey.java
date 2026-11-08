package com.example.g_one.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The key a farmer's memory is stored under: HMAC-SHA256 of the caller's E.164 number with a secret salt, as 64
 * lowercase hex characters. Raw numbers are never stored; hash them with {@link #fromPhone} as soon as they arrive.
 * Caller ID is not identity: the same key only means the same number called.
 */
public record CallerKey(@JsonValue String value) {
    /** The shortest accepted HMAC key, from the memory decision: at least 32 random bytes. */
    public static final int MIN_SALT_BYTES = 32;
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{6,14}");

    public CallerKey {
        Objects.requireNonNull(value, "value");
        if (!HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("CallerKey must be 64 lowercase hex characters");
        }
    }

    @JsonCreator
    public static CallerKey of(String value) {
        return new CallerKey(value);
    }

    /**
     * Hashes a phone number. The same number and salt always give the same key; another salt gives another key.
     *
     * @param e164 the number in E.164 form, such as {@code +15005550006}; surrounding spaces are ignored
     * @param salt the secret HMAC key from configuration, at least {@value #MIN_SALT_BYTES} random bytes; never logged
     *     or stored here
     * @throws IllegalArgumentException when the number is not E.164 (for example a withheld "anonymous" caller) or the
     *     salt is missing or shorter than {@value #MIN_SALT_BYTES} bytes. The message never contains the number or the
     *     salt.
     */
    public static CallerKey fromPhone(String e164, byte[] salt) {
        if (salt == null || salt.length < MIN_SALT_BYTES) {
            throw new IllegalArgumentException("A caller key needs a salt of at least " + MIN_SALT_BYTES + " bytes");
        }
        String number = e164 == null ? "" : e164.strip();
        if (!E164.matcher(number).matches()) {
            throw new IllegalArgumentException("Caller number is not in E.164 form");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return new CallerKey(HexFormat.of().formatHex(mac.doFinal(number.getBytes(StandardCharsets.US_ASCII))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
