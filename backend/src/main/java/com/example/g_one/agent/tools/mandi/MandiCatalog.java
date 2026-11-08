package com.example.g_one.agent.tools.mandi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Recognises the crop and market a farmer names ("pyaz", "प्याज", "Bardhaman") and maps them to AGMARKNET's spelling,
 * from {@code /agent/mandi-commodities.json}. It does not hold prices; {@link MandiSource} does.
 */
@Component
public class MandiCatalog {
    public static final String RESOURCE = "/agent/mandi-commodities.json";

    private final List<String> commodities = new ArrayList<>();
    private final List<String> markets = new ArrayList<>();
    private final Map<String, String> commodityByKey = new HashMap<>();
    private final Map<String, String> marketByKey = new HashMap<>();

    @Autowired
    public MandiCatalog(ObjectMapper json) {
        this(json, RESOURCE);
    }

    MandiCatalog(ObjectMapper json, String resource) {
        try (InputStream in = MandiCatalog.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Mandi catalog not found: " + resource);
            JsonNode root = json.readTree(in);
            read(root.path("commodities"), commodities, commodityByKey, resource);
            read(root.path("markets"), markets, marketByKey, resource);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the mandi catalog: " + resource, e);
        }
        if (commodities.isEmpty()) throw new IllegalStateException("Mandi catalog lists no commodities: " + resource);
    }

    private static void read(JsonNode entries, List<String> names, Map<String, String> byKey, String resource) {
        for (JsonNode e : entries) {
            String name = e.path("name").asText("").trim();
            if (name.isEmpty()) throw new IllegalStateException("Mandi catalog entry without a name in " + resource);
            names.add(name);
            put(byKey, name, name, resource);
            for (JsonNode alias : e.path("aliases")) put(byKey, alias.asText(""), name, resource);
        }
    }

    private static void put(Map<String, String> byKey, String spoken, String name, String resource) {
        String k = key(spoken);
        if (k.isEmpty()) return;
        String previous = byKey.putIfAbsent(k, name);
        if (previous != null && !previous.equals(name)) {
            throw new IllegalStateException("Mandi catalog name used twice in " + resource + ": " + spoken);
        }
    }

    /** AGMARKNET commodity name for what the farmer said, if recognised. */
    public Optional<String> commodity(String spoken) {
        return spoken == null ? Optional.empty() : Optional.ofNullable(commodityByKey.get(key(spoken)));
    }

    /** AGMARKNET market name for what the farmer said, if recognised. */
    public Optional<String> market(String spoken) {
        return spoken == null ? Optional.empty() : Optional.ofNullable(marketByKey.get(key(spoken)));
    }

    public List<String> commodities() {
        return List.copyOf(commodities);
    }

    public List<String> markets() {
        return List.copyOf(markets);
    }

    static String key(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("[\\s.\\-'’()]+", "");
    }
}
