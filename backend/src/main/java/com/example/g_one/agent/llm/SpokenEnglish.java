package com.example.g_one.agent.llm;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Numbers and dates written as English words, for text that goes to the model or to Timbre. Timbre reads numbers
 * better as words, and a model that rephrases "four thousand five hundred" into Hindi words does not misread digits.
 * Indian grouping is used (lakh, crore), because that is how farmers say amounts.
 */
public final class SpokenEnglish {
    private static final String[] SMALL = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
    private static final String[] ORDINAL_SMALL = {
            "zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
            "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth",
            "nineteenth"};

    private SpokenEnglish() {}

    /** Whole number in words, e.g. 4500 → "four thousand five hundred", 150000 → "one lakh fifty thousand". */
    public static String number(long n) {
        if (n < 0) return "minus " + number(-n);
        if (n < 20) return SMALL[(int) n];
        if (n < 100) return TENS[(int) (n / 10)] + (n % 10 == 0 ? "" : "-" + SMALL[(int) (n % 10)]);
        if (n < 1_000) return part(n / 100, "hundred", n % 100);
        if (n < 1_00_000) return part(n / 1_000, "thousand", n % 1_000);
        if (n < 1_00_00_000) return part(n / 1_00_000, "lakh", n % 1_00_000);
        return part(n / 1_00_00_000, "crore", n % 1_00_00_000);
    }

    private static String part(long head, String unit, long rest) {
        return number(head) + " " + unit + (rest == 0 ? "" : " " + number(rest));
    }

    /** Day of month as an ordinal word, 1 → "first", 22 → "twenty-second". */
    public static String ordinal(int n) {
        if (n < 1 || n > 31) throw new IllegalArgumentException("Day of month out of range.");
        if (n < 20) return ORDINAL_SMALL[n];
        String tens = TENS[n / 10];
        if (n % 10 == 0) return tens.substring(0, tens.length() - 1) + "ieth"; // twenty → twentieth
        return tens + "-" + ORDINAL_SMALL[n % 10];
    }

    /** A date as said aloud, e.g. "the fourth of November, twenty twenty-six". */
    public static String date(LocalDate d) {
        String month = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return "the " + ordinal(d.getDayOfMonth()) + " of " + month + ", " + year(d.getYear());
    }

    /** A year as people say it: 2026 → "twenty twenty-six", 2000 → "two thousand", 2005 → "two thousand five". */
    static String year(int y) {
        if (y >= 2000 && y < 2010) return number(y);
        if (y >= 1100 && y < 10_000 && y % 100 != 0) return number(y / 100) + " " + (y % 100 < 10 ? "oh " : "") + number(y % 100);
        return number(y);
    }
}
