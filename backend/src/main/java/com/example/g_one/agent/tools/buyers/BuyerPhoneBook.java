package com.example.g_one.agent.tools.buyers;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Buyer phone numbers, from the env var {@value #ENV} only ({@code ref=+91...;ref2=+1...}, keyed by a buyer's
 * {@code phoneRef}). Only the {@code connect_buyer} dial path reads it. Numbers are never logged: a malformed entry is
 * reported by its key alone, and {@link #toString()} prints only a count.
 */
@Component
public class BuyerPhoneBook {
    public static final String ENV = "G_ONE_BUYER_PHONES";
    private static final Logger log = LoggerFactory.getLogger(BuyerPhoneBook.class);
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");

    private final Map<String, String> numbers;

    @Autowired
    public BuyerPhoneBook(Environment env) {
        this(env.getProperty(ENV, ""));
    }

    /** @param raw the env value, {@code ref=+number} pairs separated by semicolons */
    public BuyerPhoneBook(String raw) {
        Map<String, String> out = new HashMap<>();
        for (String entry : (raw == null ? "" : raw).split(";")) {
            if (entry.isBlank()) continue;
            int eq = entry.indexOf('=');
            String ref = eq < 0 ? "" : entry.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String number = eq < 0 ? "" : entry.substring(eq + 1).replaceAll("[\\s-]", "");
            if (ref.isEmpty() || !E164.matcher(number).matches()) {
                log.warn("Skipping a malformed {} entry{}", ENV, ref.isEmpty() ? "" : " for key " + safeKey(ref));
                continue;
            }
            out.put(ref, number);
        }
        this.numbers = Map.copyOf(out);
    }

    /** The number for a buyer's {@code phoneRef}, if the env var lists one. */
    public Optional<String> number(String phoneRef) {
        return phoneRef == null ? Optional.empty()
                : Optional.ofNullable(numbers.get(phoneRef.strip().toLowerCase(Locale.ROOT)));
    }

    @Override
    public String toString() {
        return "BuyerPhoneBook[" + numbers.size() + " numbers]";
    }

    /** A key is printed only when it cannot itself be a number. */
    private static String safeKey(String ref) {
        return ref.chars().anyMatch(Character::isDigit) ? "[hidden]" : ref;
    }
}
