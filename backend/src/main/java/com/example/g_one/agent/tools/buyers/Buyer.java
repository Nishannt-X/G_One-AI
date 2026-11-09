package com.example.g_one.agent.tools.buyers;

import java.util.List;
import java.util.Objects;

/**
 * One synthetic buyer from {@code buyers.json}. {@code crops} are AGMARKNET commodity names (as in the mandi catalog),
 * {@code areas} the districts and towns the buyer collects from, and the price range is the indicative range the
 * directory lists, in rupees per quintal. {@code phoneRef} is only a key into {@code G_ONE_BUYER_PHONES}; it never
 * reaches the model, the console or a log.
 */
public record Buyer(String id, String name, String type, String place, List<String> areas, List<String> crops,
                    int minPrice, int maxPrice, int minQuintals, int maxQuintals, String language, String phoneRef) {

    public Buyer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(phoneRef, "phoneRef");
        areas = List.copyOf(areas);
        crops = List.copyOf(crops);
        if (minPrice <= 0 || maxPrice < minPrice) throw new IllegalArgumentException("Bad price range for " + id);
        if (minQuintals <= 0 || maxQuintals < minQuintals) {
            throw new IllegalArgumentException("Bad quantity range for " + id);
        }
    }

    /** Never prints {@code phoneRef}. */
    @Override
    public String toString() {
        return "Buyer[" + id + "]";
    }
}
