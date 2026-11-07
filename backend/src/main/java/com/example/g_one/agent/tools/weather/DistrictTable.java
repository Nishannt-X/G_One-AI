package com.example.g_one.agent.tools.weather;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * District name → coordinates, from {@code /agent/weather-districts.json}. Lookup ignores case, spaces, dots,
 * hyphens and a trailing "district"/"zila", and accepts the listed aliases (Burdwan = Bardhaman, Devanagari spellings).
 */
@Component
public class DistrictTable {
    public static final String RESOURCE = "/agent/weather-districts.json";

    private final List<District> all;
    private final Map<String, District> byKey = new HashMap<>();

    @Autowired
    public DistrictTable(ObjectMapper json) {
        this(json, RESOURCE);
    }

    DistrictTable(ObjectMapper json, String resource) {
        try (InputStream in = DistrictTable.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("District table not found: " + resource);
            JsonNode root = json.readTree(in);
            all = List.copyOf(json.convertValue(root.path("districts"), new TypeReference<List<District>>() {}));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot read the district table: " + resource, e);
        }
        if (all.isEmpty()) throw new IllegalStateException("District table is empty: " + resource);
        for (District d : all) {
            if (d.name() == null || d.name().isBlank() || Math.abs(d.lat()) > 90 || Math.abs(d.lon()) > 180) {
                throw new IllegalStateException("Bad district entry in " + resource + ": " + d.name());
            }
            add(d.name(), d, resource);
            for (String alias : d.aliases()) add(alias, d, resource);
        }
    }

    private void add(String name, District d, String resource) {
        District previous = byKey.putIfAbsent(key(name), d);
        if (previous != null && previous != d) {
            throw new IllegalStateException("District name used twice in " + resource + ": " + name);
        }
    }

    public Optional<District> find(String spoken) {
        if (spoken == null || spoken.isBlank()) return Optional.empty();
        return Optional.ofNullable(byKey.get(key(spoken)));
    }

    public List<District> all() {
        return all;
    }

    static String key(String s) {
        String k = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).trim();
        k = k.replaceAll("\\s+(district|zila|zilla|jila|जिला|ज़िला|জেলা)$", "");
        return k.replaceAll("[\\s.\\-'’]+", "");
    }
}
