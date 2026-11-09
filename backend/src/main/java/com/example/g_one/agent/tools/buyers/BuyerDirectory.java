package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.tools.Redact;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The synthetic buyer directory ({@code /buyers.json}) and its ranking.
 *
 * <p>Ranking for {@link #match}: a buyer must want the crop, and a buyer whose smallest lot is bigger than the
 * farmer's quantity is left out. The rest score {@value #AREA_SCORE} when they collect from the farmer's district or
 * town and {@value #QUANTITY_SCORE} when the quantity fits their range. Ties go to the higher top of the listed range,
 * then the name, so the order is stable.
 *
 * <p>Loading fails when an entry holds a long digit run anywhere in its text, or a {@code phoneRef} that is not a
 * short key, so a phone number cannot slip into the file.
 */
@Component
public class BuyerDirectory {
    public static final String RESOURCE = "/buyers.json";
    /** Call-flow step 7: suggest at most two buyers and recommend one. */
    public static final int MAX_SUGGESTIONS = 2;
    static final int AREA_SCORE = 4;
    static final int QUANTITY_SCORE = 2;
    private static final Pattern ID = Pattern.compile("[a-z][a-z-]{1,40}");
    private static final Pattern PHONE_REF = Pattern.compile("[a-z][a-z_-]{0,40}");
    private static final Pattern KEY_STRIP = Pattern.compile("[\\s.\\-'’()]+");

    private final Map<String, Buyer> byId;
    private final MandiCatalog catalog;

    /** A ranked suggestion and the short reason the model may say. */
    public record Match(Buyer buyer, boolean inArea, boolean quantityFits, String reason) {}

    @Autowired
    public BuyerDirectory(ObjectMapper json, MandiCatalog catalog) {
        this(json, catalog, RESOURCE);
    }

    BuyerDirectory(ObjectMapper json, MandiCatalog catalog, String resource) {
        this.catalog = catalog;
        this.byId = load(json, resource);
    }

    public List<Buyer> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Buyer> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id.strip().toLowerCase(Locale.ROOT)));
    }

    /** The AGMARKNET name for what the farmer said, or the spoken word itself when the catalog does not know it. */
    public String commodityName(String spoken) {
        return catalog.commodity(spoken).orElse(spoken.strip());
    }

    /**
     * Our own name for the crop the farmer said: the AGMARKNET name, or a crop a directory buyer lists. Empty for
     * anything else, so the farmer's (or the model's) words are never repeated back as a crop name.
     */
    public Optional<String> knownCommodity(String spoken) {
        if (spoken == null) return Optional.empty();
        Optional<String> catalogName = catalog.commodity(spoken);
        if (catalogName.isPresent()) return catalogName;
        String k = key(spoken);
        if (k.isEmpty()) return Optional.empty();
        return byId.values().stream().flatMap(b -> b.crops().stream()).filter(c -> key(c).equals(k)).findFirst();
    }

    /**
     * At most {@value #MAX_SUGGESTIONS} buyers for the crop, best first.
     *
     * @param quantity quintals, if the farmer said
     * @param district district, town or mandi the farmer named, if any
     */
    public List<Match> match(String commodity, OptionalDouble quantity, Optional<String> district) {
        String crop = key(commodityName(commodity));
        Optional<String> area = district.map(BuyerDirectory::key).filter(a -> !a.isEmpty());
        List<Match> out = new ArrayList<>();
        for (Buyer b : byId.values()) {
            if (b.crops().stream().map(BuyerDirectory::key).noneMatch(crop::equals)) continue;
            if (quantity.isPresent() && quantity.getAsDouble() < b.minQuintals()) continue;
            Optional<String> inArea = area.flatMap(
                    a -> b.areas().stream().filter(x -> key(x).equals(a)).findFirst());
            boolean fits = quantity.isPresent() && quantity.getAsDouble() <= b.maxQuintals();
            out.add(new Match(b, inArea.isPresent(), fits, reason(b, inArea, fits, quantity)));
        }
        out.sort(Comparator.comparingInt((Match m) -> -score(m))
                .thenComparingInt(m -> -m.buyer().maxPrice())
                .thenComparing(m -> m.buyer().name()));
        return List.copyOf(out.subList(0, Math.min(MAX_SUGGESTIONS, out.size())));
    }

    private static int score(Match m) {
        return (m.inArea() ? AREA_SCORE : 0) + (m.quantityFits() ? QUANTITY_SCORE : 0);
    }

    /** Uses only directory text (the matched area as the directory spells it), never the caller's words. */
    private static String reason(Buyer b, Optional<String> area, boolean fits, OptionalDouble quantity) {
        List<String> parts = new ArrayList<>();
        parts.add(area.map(a -> "collects from " + a).orElse("based in " + b.place()));
        if (quantity.isPresent()) parts.add(fits ? "takes lots of that size" : "takes only part of that quantity");
        return String.join(", ", parts);
    }

    static String key(String s) {
        return KEY_STRIP.matcher(Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static Map<String, Buyer> load(ObjectMapper json, String resource) {
        try (InputStream in = BuyerDirectory.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Buyer directory not found: " + resource);
            JsonNode root = json.readTree(in);
            Map<String, Buyer> out = new LinkedHashMap<>();
            for (JsonNode n : root.path("buyers")) {
                Buyer b = new Buyer(text(n, "id"), text(n, "name"), text(n, "type"), text(n, "place"),
                        list(n, "areas"), list(n, "crops"), n.path("minPrice").asInt(), n.path("maxPrice").asInt(),
                        n.path("minQuintals").asInt(), n.path("maxQuintals").asInt(), text(n, "language"),
                        text(n, "phoneRef"));
                check(b);
                if (out.putIfAbsent(b.id(), b) != null) {
                    throw new IllegalStateException("Buyer id used twice in " + resource + ": " + b.id());
                }
            }
            if (out.isEmpty()) throw new IllegalStateException("Buyer directory is empty: " + resource);
            return Collections.unmodifiableMap(out);
        } catch (IOException e) {
            throw new IllegalStateException("Buyer directory could not be read: " + resource, e);
        }
    }

    /** The directory must never carry a phone number, and ids must be safe to show the model. */
    private static void check(Buyer b) {
        if (!ID.matcher(b.id()).matches()) throw new IllegalStateException("Bad buyer id: " + b.id());
        if (!PHONE_REF.matcher(b.phoneRef()).matches()) {
            throw new IllegalStateException("Bad phoneRef for buyer " + b.id() + " (a key, never a number)");
        }
        if (b.crops().isEmpty() || b.areas().isEmpty()) {
            throw new IllegalStateException("Buyer " + b.id() + " needs crops and areas");
        }
        List<String> texts = new ArrayList<>(List.of(b.name(), b.type(), b.place(), b.language()));
        texts.addAll(b.areas());
        texts.addAll(b.crops());
        for (String t : texts) {
            if (Redact.hasLongNumber(t)) {
                throw new IllegalStateException("Buyer " + b.id() + " has a long number in its text");
            }
        }
    }

    private static String text(JsonNode n, String field) {
        String s = n.path(field).asText("").strip();
        if (s.isEmpty()) throw new IllegalStateException("Buyer field missing: " + field);
        return s;
    }

    private static List<String> list(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : n.path(field)) {
            String s = v.asText("").strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
