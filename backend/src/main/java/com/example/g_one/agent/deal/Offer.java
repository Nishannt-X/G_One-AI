package com.example.g_one.agent.deal;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * A price offer as the LLM read it out of a spoken buyer turn. {@code status} is none, clear or unclear. Only a clear
 * offer in rupees can become {@link Terms}.
 */
public record Offer(String status, String commodity, String variety, String market, BigDecimal price,
                    String currency, String unit) {

    /** Lower-case letters only, singular: "Onions" and "onion" compare equal. */
    public static String norm(String s) {
        if (s == null) return null;
        String t = s.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z]", "");
        return t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
    }

    /** Ignores only the generic words AGMARKNET adds to a market name, so "Burdwan mandi" matches "Burdwan APMC". */
    public static String marketKey(String s) {
        return norm(s.toLowerCase(Locale.ENGLISH).replaceAll("\\b(apmc|mandi|market)\\b", " "));
    }

    /** "qtl" and "q" mean quintal; other units are only normalised. */
    public static String unit(String s) {
        String n = norm(s);
        return "qtl".equals(n) || "q".equals(n) ? "quintal" : n;
    }
}
