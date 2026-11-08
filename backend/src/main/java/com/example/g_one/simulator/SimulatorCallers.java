package com.example.g_one.simulator;

import com.example.g_one.core.CallerKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The synthetic phone numbers a simulator caller may pick, so that memory works across calls (same number, same
 * {@link CallerKey}) without ever taking a real number. Any other number is refused. Choosing no number is a withheld
 * caller ID: memory is off for that call.
 */
@Component
public class SimulatorCallers {
    private static final Logger log = LoggerFactory.getLogger(SimulatorCallers.class);

    /** Used only when no salt is configured. It guards nothing: the numbers hashed with it are public and synthetic. */
    static final String DEV_SALT = "g-one-simulator-synthetic-numbers-only";

    public record Caller(String number, String label) {}

    static final List<Caller> ALL = List.of(
            new Caller("+919876543210", "Caller A, 98765 43210"),
            new Caller("+919876543211", "Caller B, 98765 43211"),
            new Caller("+919876543212", "Caller C, 98765 43212"),
            new Caller("+919876543213", "Caller D, 98765 43213"),
            new Caller("+919876543214", "Caller E, 98765 43214"));

    private final byte[] salt;

    /** @param salt the secret for hashing numbers (property {@code g-one.simulator.caller-salt}); blank uses a dev salt */
    public SimulatorCallers(@Value("${g-one.simulator.caller-salt:}") String salt) {
        if (salt.isBlank()) {
            log.warn("No caller salt configured; simulator callers use the built-in dev salt");
            this.salt = DEV_SALT.getBytes(StandardCharsets.UTF_8);
        } else {
            this.salt = salt.getBytes(StandardCharsets.UTF_8);
            if (this.salt.length < CallerKey.MIN_SALT_BYTES) {
                throw new IllegalStateException(
                        "g-one.simulator.caller-salt must be at least " + CallerKey.MIN_SALT_BYTES + " bytes");
            }
        }
    }

    public List<Caller> all() {
        return ALL;
    }

    /**
     * The memory key for a simulated number; empty for a withheld caller ID (null or blank).
     *
     * @throws IllegalArgumentException when the number is not one of the synthetic callers
     */
    public Optional<CallerKey> keyFor(String number) {
        if (number == null || number.isBlank()) {
            return Optional.empty();
        }
        String n = number.strip();
        if (ALL.stream().noneMatch(c -> c.number().equals(n))) {
            throw new IllegalArgumentException("Pick one of the simulator caller numbers.");
        }
        return Optional.of(CallerKey.fromPhone(n, salt));
    }
}
