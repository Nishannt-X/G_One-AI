package com.example.g_one.memory;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secret-looking numbers before memory text is stored or sent to the LLM (docs/memory.md: "the gist writer
 * drops any 6+ digit run that is not a quantity, price or date"). OTPs, PINs, phone, account and Aadhaar numbers are
 * all long digit runs, often spoken in groups, so digits joined by single spaces or hyphens count as one run
 * ({@code 98765 43210} is ten digits).
 *
 * <p>A run of six or more digits is kept only when it is
 * <ul>
 *   <li>a price: right after {@code Rs}, {@code ₹}, {@code INR} or {@code rupees}, or right before {@code rupees},
 *       {@code Rs}, {@code /-} or {@code per ...};</li>
 *   <li>a quantity: right before a unit ({@code quintal}, {@code kg}, {@code tonne}, {@code bags}, ...); or</li>
 *   <li>a date: {@code yyyy-MM-dd}, {@code dd-MM-yyyy}, {@code yyyyMMdd} or {@code ddMMyyyy} (spaces work as
 *       separators too) that is a real date between 2000 and 2100.</li>
 * </ul>
 * Everything else becomes {@value #REMOVED}. Amounts written with Indian commas ({@code 1,20,000}) and decimals are
 * never one run, since nobody reads out an OTP that way.
 */
public final class SecretScrubber {
    public static final String REMOVED = "[number removed]";

    /** Digit groups joined by at most one space or hyphen, not part of a comma-grouped or decimal number. */
    private static final Pattern RUN = Pattern.compile("(?<![\\d.,])\\d+(?:[ \\-]\\d+)*(?!\\d|[.,]\\d)");
    private static final Pattern PRICE_BEFORE = Pattern.compile(
            "(?:₹|\\brs\\.?|\\binr|\\brupees?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern UNIT_AFTER = Pattern.compile(
            "^\\s*(?:/-|/\\s*(?:quintal|qtl|kg)|(?:rupees?|rs|inr|per|quintals?|qtls?|kgs?|kilos?|kilograms?"
                    + "|tonnes?|tons?|bags?|boxes|crates?|acres?)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern YEAR_FIRST = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern DAY_FIRST = Pattern.compile("(\\d{2})-(\\d{2})-(\\d{4})");
    private static final Pattern EIGHT = Pattern.compile("\\d{8}");

    private SecretScrubber() {}

    public static String scrub(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = RUN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String run = m.group();
            boolean keep = run.replaceAll("[ \\-]", "").length() < 6
                    || isDate(run.replace(' ', '-'))
                    || PRICE_BEFORE.matcher(text.substring(0, m.start())).find()
                    || UNIT_AFTER.matcher(text.substring(m.end())).find();
            m.appendReplacement(out, Matcher.quoteReplacement(keep ? run : REMOVED));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** True when the text holds a 6+ digit run that {@link #scrub} would remove. */
    public static boolean hasSecret(String text) {
        return text != null && !scrub(text).equals(text);
    }

    private static boolean isDate(String run) {
        Matcher m;
        if ((m = YEAR_FIRST.matcher(run)).matches()) {
            return validDate(m.group(1), m.group(2), m.group(3));
        }
        if ((m = DAY_FIRST.matcher(run)).matches()) {
            return validDate(m.group(3), m.group(2), m.group(1));
        }
        if (EIGHT.matcher(run).matches()) {
            return validDate(run.substring(0, 4), run.substring(4, 6), run.substring(6, 8))
                    || validDate(run.substring(4, 8), run.substring(2, 4), run.substring(0, 2));
        }
        return false;
    }

    private static boolean validDate(String year, String month, String day) {
        int y = Integer.parseInt(year);
        if (y < 2000 || y > 2100) {
            return false;
        }
        try {
            LocalDate.of(y, Integer.parseInt(month), Integer.parseInt(day));
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
