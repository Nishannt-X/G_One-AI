package com.example.g_one.memory;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secret-looking numbers before memory text is stored or sent to the LLM (docs/memory.md: "the gist writer
 * drops any 6+ digit run that is not a quantity, price or date"). The scrub runs in this order, and an earlier step is
 * never undone by a later exemption:
 * <ol>
 *   <li><b>Sensitive labels.</b> A number of 3+ digits right after an OTP, PIN, UPI PIN, password, CVV, card or
 *       Aadhaar label (English, Hinglish or Devanagari: {@code OTP}, {@code pin}, {@code आधार}, {@code ओटीपी}, ...),
 *       with at most a few connector words between ({@code is}, {@code hai}, {@code number}, {@code मेरा}, ...), is
 *       removed. So is a 3+ digit number right before an OTP, PIN, password or CVV label ({@code 4829 mera OTP hai}).
 *       No price, unit or date context saves it.</li>
 *   <li><b>Phone numbers split in two.</b> Ten digits as {@code 98765, 43210} (separator up to three of space, comma,
 *       dot or hyphen), optionally after {@code +91} or {@code 0}.</li>
 *   <li><b>Grouped identifiers.</b> Digit groups joined by single spaces, hyphens, commas or dots that hold 10+ digits
 *       in total (phone, card, Aadhaar and account numbers, however they are grouped, with an optional {@code +}).</li>
 *   <li><b>Long runs.</b> Any other run of 6+ digits (groups joined by single spaces or hyphens count as one run, so
 *       {@code 4829 13} is six digits) is removed unless it is
 *       <ul>
 *         <li>a price of 6 to 9 digits: right after {@code Rs}, {@code ₹}, {@code INR} or {@code rupees}, or right
 *             before {@code rupees}, {@code Rs}, {@code /-} or {@code per ...};</li>
 *         <li>a quantity of 6 to 9 digits: right before a unit ({@code quintal}, {@code kg}, {@code tonne},
 *             {@code bags}, ...); or</li>
 *         <li>a date: {@code yyyy-MM-dd}, {@code dd-MM-yyyy}, {@code yyyyMMdd} or {@code ddMMyyyy} (spaces work as
 *             separators too) that is a real date between 2000 and 2100.</li>
 *       </ul>
 *       Ten or more digits are never a price or quantity a farmer means (that is over ₹99 crore), so a phone, card or
 *       Aadhaar number next to {@code Rs} or {@code kg} is still removed.</li>
 * </ol>
 * Amounts written with Indian commas ({@code 1,20,000}) and decimals ({@code 12345.75}) stay, as long as they are
 * under ten digits and not after a sensitive label.
 */
public final class SecretScrubber {
    public static final String REMOVED = "[number removed]";

    /** Not inside a word: Devanagari vowel signs are marks ({@code \p{M}}), not letters. */
    private static final String WORD_START = "(?<![\\p{L}\\p{M}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{M}])";

    /** Labels whose number is a secret even when short (a 4-digit PIN, a 3-digit CVV). */
    private static final String SECRET_LABELS = "(?:upi[ \\-]?pin|atm[ \\-]?pin|m[ \\-]?pin|pin|"
            + "o\\.?t\\.?p\\.?|one[ \\-]?time[ \\-]?(?:password|passcode|code|pin)|pass[ \\-]?(?:word|code)|pasword|"
            + "cvv|cvc|"
            + "यूपीआई\\s?पिन|एटीएम\\s?पिन|पिन|ओ\\s?टी\\s?पी|ओटीपी|पासवर्ड|सीवीवी)";
    /** Labels for identifiers: the number after them is removed, however it is grouped. */
    private static final String ID_LABELS = "(?:(?:debit|credit|atm)[ \\-]?card|card|kaard|"
            + "aa?dh?aa?r(?:[ \\-]?card)?|uid|uidai|कार्ड|आधार(?:\\s?कार्ड)?)";
    /** Connector words that may sit between a label and its number. */
    private static final String FILLER = "(?:[\\s:=#\\-]*(?:is|was|hai|he|h|ka|ki|ke|ko|number|nambar|numbar|no\\.?"
            + "|num|code|wala|vala|digit|digits|mera|meri|my|your|tera|aapka|apka|apna|the"
            + "|नंबर|नम्बर|है|का|की|के|को|मेरा|मेरी|कोड|वाला|आपका|अपना)" + WORD_END + ")*[\\s:=#\\-]*";
    /** Digit groups joined by single spaces, hyphens, commas or dots, with an optional leading {@code +}. */
    private static final String GROUPED = "\\+?\\d+(?:[ ,.\\-]\\d+)*";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern AFTER_LABEL = Pattern.compile(
            WORD_START + "((?:" + SECRET_LABELS + "|" + ID_LABELS + ")" + WORD_END + FILLER + ")"
                    + "(" + GROUPED + ")(?!\\d)", FLAGS);
    private static final Pattern BEFORE_LABEL = Pattern.compile(
            "(?<![\\d.,+])(" + GROUPED + ")(?!\\d)(" + FILLER + SECRET_LABELS + WORD_END + ")", FLAGS);
    private static final Pattern SPLIT_PHONE = Pattern.compile(
            "(?<![\\d+])(?:(?:\\+?91|0)[ \\-]{0,2})?[6-9]\\d{4}[ ,.\\-]{1,3}\\d{5}(?!\\d)");
    private static final Pattern LONG_GROUPED = Pattern.compile("(?<![\\d+])" + GROUPED + "(?!\\d)");

    /** Digit groups joined by at most one space or hyphen, not part of a comma-grouped or decimal number. */
    private static final Pattern RUN = Pattern.compile("(?<![\\d.,])\\d+(?:[ \\-]\\d+)*(?!\\d|[.,]\\d)");
    private static final Pattern PRICE_BEFORE = Pattern.compile("(?:₹|\\brs\\.?|\\binr|\\brupees?)\\s*$", FLAGS);
    private static final Pattern UNIT_AFTER = Pattern.compile(
            "^\\s*(?:/-|/\\s*(?:quintal|qtl|kg)|(?:rupees?|rs|inr|per|quintals?|qtls?|kgs?|kilos?|kilograms?"
                    + "|tonnes?|tons?|bags?|boxes|crates?|acres?)\\b)", FLAGS);
    private static final Pattern YEAR_FIRST = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern DAY_FIRST = Pattern.compile("(\\d{2})-(\\d{2})-(\\d{4})");
    private static final Pattern EIGHT = Pattern.compile("\\d{8}");

    static final int MIN_LABELLED_DIGITS = 3;
    static final int MIN_RUN_DIGITS = 6;
    static final int MAX_AMOUNT_DIGITS = 9;
    static final int MIN_ID_DIGITS = 10;

    private SecretScrubber() {}

    public static String scrub(String text) {
        if (text == null) {
            return "";
        }
        String out = afterLabels(text);
        out = beforeLabels(out);
        out = SPLIT_PHONE.matcher(out).replaceAll(Matcher.quoteReplacement(REMOVED));
        out = longGrouped(out);
        return longRuns(out);
    }

    /** True when {@link #scrub} would change the text. */
    public static boolean hasSecret(String text) {
        return text != null && !scrub(text).equals(text);
    }

    private static String afterLabels(String text) {
        Matcher m = AFTER_LABEL.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String number = digits(m.group(2)) >= MIN_LABELLED_DIGITS ? REMOVED : m.group(2);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + number));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String beforeLabels(String text) {
        Matcher m = BEFORE_LABEL.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String number = digits(m.group(1)) >= MIN_LABELLED_DIGITS ? REMOVED : m.group(1);
            m.appendReplacement(out, Matcher.quoteReplacement(number + m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String longGrouped(String text) {
        Matcher m = LONG_GROUPED.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(digits(m.group()) >= MIN_ID_DIGITS ? REMOVED : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String longRuns(String text) {
        Matcher m = RUN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String run = m.group();
            int digits = digits(run);
            boolean amount = digits <= MAX_AMOUNT_DIGITS
                    && (PRICE_BEFORE.matcher(text.substring(0, m.start())).find()
                            || UNIT_AFTER.matcher(text.substring(m.end())).find());
            boolean keep = digits < MIN_RUN_DIGITS || isDate(run.replace(' ', '-')) || amount;
            m.appendReplacement(out, Matcher.quoteReplacement(keep ? run : REMOVED));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int digits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                n++;
            }
        }
        return n;
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
