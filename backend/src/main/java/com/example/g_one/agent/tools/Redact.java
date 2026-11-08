package com.example.g_one.agent.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides long digit runs (phone, account, Aadhaar or OTP numbers) in text the agent stores or shows on the console.
 * Six or more digits count, even with spaces, dashes, dots or brackets between them. ISO dates such as 2026-11-04 are
 * kept, since dated references need them.
 */
public final class Redact {
    /** Six or more digits, even with separators between them. */
    public static final Pattern LONG_NUMBER = Pattern.compile("\\+?\\d(?:[\\s().-]?\\d){5,}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    public static final String NUMBER = "[number]";

    private Redact() {}

    /** True when {@code text} holds a long digit run other than an ISO date. */
    public static boolean hasLongNumber(String text) {
        return text != null && !digits(text).equals(text);
    }

    /** {@code text} with every long digit run (other than an ISO date) replaced by {@value #NUMBER}. */
    public static String digits(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        Matcher m = LONG_NUMBER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String hit = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(ISO_DATE.matcher(hit).matches() ? hit : NUMBER));
        }
        m.appendTail(out);
        return out.toString();
    }
}
