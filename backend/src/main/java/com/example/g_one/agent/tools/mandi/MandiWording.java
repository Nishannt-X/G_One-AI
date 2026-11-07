package com.example.g_one.agent.tools.mandi;

import com.example.g_one.agent.llm.SpokenEnglish;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The "dated reference" wording from CONTEXT.md: "AGMARKNET listed ₹X per quintal for &lt;crop, variety, market&gt; on
 * &lt;date&gt;, a dated reference". Never "today's price", "live rate", "fair price" or "modal": the source does not say
 * whether its figure is a minimum, modal or maximum, and the date is the market's report date, not today.
 *
 * <p>{@link #spoken} writes numbers and the date as words (for the model and Timbre); {@link #display} keeps digits
 * and the rupee sign (for the console).
 */
public final class MandiWording {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private MandiWording() {}

    public static String spoken(MandiAnswer answer) {
        return switch (answer) {
            case MandiAnswer.Found f -> {
                MandiSnapshot s = f.snapshot();
                String text = s.sourceName() + " listed " + SpokenEnglish.number(rupees(s.price())) + " " + currencyWords(s)
                        + " per " + s.unit() + " for " + crop(s) + " at " + s.market() + " on "
                        + SpokenEnglish.date(s.priceDate()) + ", a dated reference.";
                yield f.askedMarket() == null ? text
                        : text + " There is no dated reference for " + f.askedMarket() + ".";
            }
            case MandiAnswer.NoReference n -> noReference(n);
        };
    }

    public static String display(MandiAnswer answer) {
        return switch (answer) {
            case MandiAnswer.Found f -> {
                MandiSnapshot s = f.snapshot();
                String money = "INR".equals(s.currency()) ? "₹" + indianGrouping(rupees(s.price()))
                        : indianGrouping(rupees(s.price())) + " " + s.currency();
                String text = s.sourceName() + " listed " + money + " per " + s.unit() + " for " + crop(s) + " at "
                        + s.market() + " on " + s.priceDate().format(DISPLAY_DATE) + ", a dated reference.";
                yield f.askedMarket() == null ? text
                        : text + " There is no dated reference for " + f.askedMarket() + ".";
            }
            case MandiAnswer.NoReference n -> noReference(n);
        };
    }

    private static String noReference(MandiAnswer.NoReference n) {
        String crop = n.crop() == null || n.crop().isBlank() ? "that crop" : n.crop().toLowerCase(Locale.ROOT);
        String text = "There is no recent dated AGMARKNET reference for " + crop + ".";
        return n.available() == null ? text
                : text + " A dated reference is available only for " + n.available().toLowerCase(Locale.ROOT) + ".";
    }

    /** "Red onion", "Common rice": variety as listed, commodity in lower case. */
    private static String crop(MandiSnapshot s) {
        String commodity = s.commodity().toLowerCase(Locale.ROOT);
        return s.variety() == null || s.variety().isBlank() ? commodity : s.variety() + " " + commodity;
    }

    private static String currencyWords(MandiSnapshot s) {
        return "INR".equals(s.currency()) ? "rupees" : s.currency();
    }

    private static long rupees(BigDecimal price) {
        return price.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 4500 → "4,500"; 150000 → "1,50,000". */
    static String indianGrouping(long n) {
        String digits = Long.toString(Math.abs(n));
        if (digits.length() <= 3) return (n < 0 ? "-" : "") + digits;
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder out = new StringBuilder();
        int first = rest.length() % 2;
        if (first == 1) out.append(rest, 0, 1);
        for (int i = first; i < rest.length(); i += 2) {
            if (!out.isEmpty()) out.append(',');
            out.append(rest, i, i + 2);
        }
        return (n < 0 ? "-" : "") + out + "," + last3;
    }
}
