package com.example.g_one.agreement;

import com.example.g_one.advisory.AdvisoryService;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The terms the buyer stated aloud, exactly as spoken. Nothing is filled in from the market reference, so a variety or
 * market the buyer did not say stays null and is read back as "not stated". Always rupees. Compare with
 * {@link #sameAs}, not {@code equals}: "Onions" and "onion", "qtl" and "quintal", 3400 and 3400.00 are the same term.
 */
public record Terms(String commodity, String variety, String market, BigDecimal price, String unit) {

    /** The words are model-extracted from speech and then read aloud and sent on: keep them short and plain. */
    private static final Pattern PLAIN = Pattern.compile("[\\p{L}\\p{N} .,'&/()-]{1,40}");

    /** @return the terms of a complete, clear offer in rupees, or null when the offer cannot be agreed on */
    static Terms from(AdvisoryService.Offer o) {
        if (o == null || !"clear".equals(o.status()) || o.price() == null || o.price().signum() <= 0
                || !"INR".equalsIgnoreCase(o.currency())) {
            return null;
        }
        try {
            String commodity = shown(o.commodity());
            String unit = shown(AdvisoryService.unit(o.unit()));
            if (commodity == null || unit == null) return null;
            return new Terms(commodity, shown(o.variety()), shown(o.market()), o.price().stripTrailingZeros(), unit);
        } catch (IllegalArgumentException unsafe) {
            return null; // fail closed: odd text means no draft, never a cleaned-up guess
        }
    }

    /** @return the tidied text, null when there is none, or throws when it is not short plain text */
    private static String shown(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty() || AdvisoryService.norm(t).isEmpty()) return null;
        if (!PLAIN.matcher(t).matches()) throw new IllegalArgumentException("not plain text");
        return t;
    }

    boolean sameAs(Terms o) {
        return o != null
                && Objects.equals(AdvisoryService.norm(commodity), AdvisoryService.norm(o.commodity))
                && Objects.equals(key(variety), key(o.variety))
                && Objects.equals(market == null ? null : AdvisoryService.marketKey(market),
                        o.market == null ? null : AdvisoryService.marketKey(o.market))
                && price.compareTo(o.price) == 0
                && Objects.equals(unit, o.unit);
    }

    private static String key(String s) {
        return s == null ? null : AdvisoryService.norm(s);
    }

    /** The price as a plain number with thousands separators and no invented decimals, e.g. "3,400" or "3,400.5". */
    String priceText() {
        DecimalFormat f = new DecimalFormat("#,##0.##########", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return f.format(price);
    }

    /** One English sentence fragment used for the read-back and the retry prompt. */
    String say() {
        return commodity + ", variety " + (variety == null ? "not stated" : variety)
                + ", market " + (market == null ? "not stated" : market)
                + ", " + priceText() + " rupees per " + unit;
    }

    /** JSON-ready view for the phones. Price is a plain string so it is never rounded on the way. */
    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commodity", commodity);
        m.put("variety", variety);
        m.put("market", market);
        m.put("price", price.toPlainString());
        m.put("currency", "INR");
        m.put("unit", unit);
        return m;
    }
}
