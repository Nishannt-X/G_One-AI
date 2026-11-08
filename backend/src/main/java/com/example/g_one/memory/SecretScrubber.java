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
 *   <li><b>Account labels.</b> A number of 6+ digits right after an account, bank, IFSC or UPI label
 *       ({@code account}, {@code a/c}, {@code acct}, {@code khata}, {@code khaata}, {@code खाता}, {@code bank},
 *       {@code IFSC}, {@code UPI}, ...), with the same connector words between, is removed. This runs before the
 *       date and amount exemptions, so {@code account number 20261108} is removed although it reads as a date.</li>
 *   <li><b>Phone numbers.</b> Ten digits starting with 6 to 9 in any grouping ({@code 98765    43210},
 *       {@code 9876 5 43210}, {@code 98765 , 43210}), optionally after {@code +91}, {@code 91} or {@code 0}. Groups
 *       may be joined by any run of whitespace, or by one comma, dot or hyphen with optional whitespace around it.</li>
 *   <li><b>Numbers of 6+ digits.</b> Every number token is taken whole: digit groups joined by any run of whitespace
 *       or by one comma, dot, hyphen or slash, with an optional leading {@code +} ({@code 123456,78}, {@code 4829 13},
 *       {@code 1234.5678.9012} and {@code 98/76/54} are one token each). A token with 6+ digits in total is removed
 *       unless that exact token is
 *       <ul>
 *         <li>an amount of 6 to 9 digits written the way amounts are written (plain digits, Indian or Western comma
 *             grouping such as {@code 1,23,456} or {@code 123,456}, an optional {@code .5} or {@code .75}, or a
 *             range of two such amounts joined by {@code -} or {@code /}) <i>and</i> right after a price word
 *             ({@code Rs}, {@code ₹}, {@code INR}, {@code rupees}, {@code price}, {@code rate}, {@code bhav},
 *             {@code daam}, {@code भाव}, ...) or right before {@code rupees}, {@code Rs}, {@code /-}, {@code per ...}
 *             or a unit ({@code quintal}, {@code kg}, {@code tonne}, {@code bags}, {@code क्विंटल}, ...); or</li>
 *         <li>a date: {@code yyyy-MM-dd}, {@code dd-MM-yyyy} (separator {@code -}, {@code /}, {@code .} or a space,
 *             the same one twice), {@code yyyyMMdd} or {@code ddMMyyyy}, that is a real date between 2000 and
 *             2100.</li>
 *       </ul>
 *       So {@code 123456,78}, {@code 12345.75} with no price word, and grouped card, Aadhaar, phone or account
 *       numbers are always removed. Ten or more digits are never an amount a farmer means (that is over ₹99 crore),
 *       so a phone, card or Aadhaar number next to {@code Rs} or {@code kg} is removed too.</li>
 * </ol>
 * Short numbers (under six digits, such as {@code 2,450} or {@code 10.30}) stay unless a sensitive label is next to
 * them.
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
    /** Account, bank and payment labels: a 6+ digit number after them is removed before any exemption. */
    private static final String ACCOUNT_LABELS = "(?:account|acount|acct|acc|a/c|bank|khaa?t[ae]|ifsc"
            + "|upi(?:[ \\-]?id)?|vpa|खाता|खाते|बैंक|अकाउंट|आईएफएससी|यूपीआई)";
    /** Any run of whitespace, including no-break and other Unicode spaces. */
    private static final String WS = "[\\s\\p{Z}]+";
    /** Digit groups joined by any whitespace run or one hyphen, comma or dot, with an optional leading {@code +}. */
    private static final String GROUPED = "\\+?\\d+(?:(?:[,.\\-]|" + WS + ")\\d+)*";
    /** A whole number token for the 6+ digit rule: like {@link #GROUPED}, and slashes join groups too. */
    private static final String TOKEN = "\\+?\\d+(?:(?:[,./\\-]|" + WS + ")\\d+)*";
    /** Between phone digits: a whitespace run, or one comma, dot or hyphen with optional whitespace around it. */
    private static final String PHONE_SEP = "(?:[\\s\\p{Z}]*[,.\\-][\\s\\p{Z}]*|" + WS + ")";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern AFTER_LABEL = Pattern.compile(
            WORD_START + "((?:" + SECRET_LABELS + "|" + ID_LABELS + ")" + WORD_END + FILLER + ")"
                    + "(" + GROUPED + ")(?!\\d)", FLAGS);
    private static final Pattern BEFORE_LABEL = Pattern.compile(
            "(?<![\\d.,+])(" + GROUPED + ")(?!\\d)(" + FILLER + SECRET_LABELS + WORD_END + ")", FLAGS);
    private static final Pattern AFTER_ACCOUNT_LABEL = Pattern.compile(
            WORD_START + "(" + ACCOUNT_LABELS + WORD_END + FILLER + ")(" + GROUPED + ")(?!\\d)", FLAGS);
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\d+])(?:(?:\\+[\\s\\p{Z}]*)?91" + PHONE_SEP + "?|0" + PHONE_SEP + "?)?"
                    + "[6-9](?:" + PHONE_SEP + "?\\d){9}(?!" + PHONE_SEP + "?\\d)");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d+])" + TOKEN + "(?!\\d)");

    /** One amount: plain digits, Western (123,456) or Indian (1,23,456) comma grouping, optional paise. */
    private static final String ONE_AMOUNT = "(?:\\d{1,3}(?:,\\d{3})+|\\d{1,2}(?:,\\d{2})*,\\d{3}|\\d+)(?:\\.\\d{1,2})?";
    private static final Pattern AMOUNT = Pattern.compile(ONE_AMOUNT + "(?:[-/]" + ONE_AMOUNT + ")?");
    private static final Pattern PRICE_BEFORE = Pattern.compile(
            "(?:₹|\\brs\\.?|\\binr|\\brupees?|\\brupaye|\\bprice|\\brate|\\bbhaa?v|\\bdaam|\\bk[ei]e?mat"
                    + "|रु\\.?|रुपये|रुपए|भाव|दाम|कीमत)"
                    + "(?:\\s*(?:is|hai|of|:|=|है)" + WORD_END + ")?\\s*$", FLAGS);
    private static final Pattern UNIT_AFTER = Pattern.compile(
            "^\\s*(?:/-|/\\s*(?:quintal|qtl|kg)|(?:rupees?|rupaye|rupaiye|rs|inr|per|quintals?|qtls?|kgs?|kilos?"
                    + "|kilograms?|tonnes?|tons?|bags?|boxes|crates?|acres?|रुपये|रुपए|रु|क्विंटल|किलो|टन|बोरी|बोरे)"
                    + WORD_END + ")", FLAGS);
    private static final Pattern YEAR_FIRST = Pattern.compile("(\\d{4})([-/. ])(\\d{1,2})\\2(\\d{1,2})");
    private static final Pattern DAY_FIRST = Pattern.compile("(\\d{1,2})([-/. ])(\\d{1,2})\\2(\\d{4})");
    private static final Pattern EIGHT = Pattern.compile("\\d{8}");

    static final int MIN_LABELLED_DIGITS = 3;
    static final int MIN_RUN_DIGITS = 6;
    static final int MAX_AMOUNT_DIGITS = 9;

    private SecretScrubber() {}

    public static String scrub(String text) {
        if (text == null) {
            return "";
        }
        String out = afterLabels(text);
        out = beforeLabels(out);
        out = afterAccountLabels(out);
        out = PHONE.matcher(out).replaceAll(Matcher.quoteReplacement(REMOVED));
        return numbers(out);
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

    private static String afterAccountLabels(String text) {
        Matcher m = AFTER_ACCOUNT_LABEL.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String number = digits(m.group(2)) >= MIN_RUN_DIGITS ? REMOVED : m.group(2);
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

    /** Step 3: every number token of 6+ digits goes unless that exact token is an amount in context or a date. */
    private static String numbers(String text) {
        Matcher m = NUMBER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String token = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(keep(text, token, m.start(), m.end()) ? token : REMOVED));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean keep(String text, String token, int start, int end) {
        int digits = digits(token);
        if (digits < MIN_RUN_DIGITS) {
            return true;
        }
        if (digits > MAX_AMOUNT_DIGITS) {
            return false;
        }
        if (isDate(token)) {
            return true;
        }
        return AMOUNT.matcher(token).matches()
                && (PRICE_BEFORE.matcher(text.substring(0, start)).find()
                        || UNIT_AFTER.matcher(text.substring(end)).find());
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
            return validDate(m.group(1), m.group(3), m.group(4));
        }
        if ((m = DAY_FIRST.matcher(run)).matches()) {
            return validDate(m.group(4), m.group(3), m.group(1));
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
