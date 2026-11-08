package com.example.g_one.agent.tools.mandi;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Answers "what is the price of X (at Y)?" from the one reference {@link MandiSource} holds. A different crop gets
 * {@link MandiAnswer.NoReference}, never another crop's number; a different market still gets the reference, labelled
 * with its own market.
 */
@Component
public class MandiQuote {
    private final MandiSource source;
    private final MandiCatalog catalog;

    public MandiQuote(MandiSource source, MandiCatalog catalog) {
        this.source = source;
        this.catalog = catalog;
    }

    /** @param market may be null or blank when the farmer named none */
    public MandiAnswer quote(String commodity, String market) {
        String asked = commodity == null ? "" : commodity.trim();
        String crop = catalog.commodity(asked).orElse(asked);
        Optional<MandiSnapshot> current = source.current();
        if (current.isEmpty()) return new MandiAnswer.NoReference(crop, null);
        MandiSnapshot s = current.get();
        if (!same(crop, s.commodity())) return new MandiAnswer.NoReference(crop, s.commodity());
        String otherMarket = null;
        if (market != null && !market.isBlank()) {
            String m = catalog.market(market).orElse(market.trim());
            if (!same(m, s.market())) otherMarket = m;
        }
        return new MandiAnswer.Found(s, otherMarket);
    }

    private static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
