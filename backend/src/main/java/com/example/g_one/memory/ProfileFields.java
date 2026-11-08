package com.example.g_one.memory;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The farmer profile fields from docs/memory.md, as {@link com.example.g_one.core.ProfileFact#field()} names. A profile
 * holds one fact per field, so each crop is its own field, {@code crop:<name>} (for example {@code crop:red onion}),
 * which keeps a provenance per crop.
 */
public final class ProfileFields {
    public static final String NAME = "name";
    public static final String VILLAGE = "village";
    public static final String DISTRICT = "district";
    public static final String STATE = "state";
    public static final String USUAL_MANDI = "usualMandi";
    public static final String LANGUAGE = "language";
    public static final String CROP_PREFIX = "crop:";

    /** Single-valued fields; crops use {@link #crop}. */
    public static final Set<String> SINGLE = Set.of(NAME, VILLAGE, DISTRICT, STATE, USUAL_MANDI, LANGUAGE);
    /** The language codes the schema allows. */
    public static final Set<String> LANGUAGES = Set.of("HI", "EN", "HI_EN", "BN");

    private static final int MAX_VALUE_CHARS = 60;

    private ProfileFields() {}

    /** The field for one crop, normalised: trimmed, lower case, single spaces. */
    public static String crop(String crop) {
        return CROP_PREFIX + normaliseCrop(crop);
    }

    public static boolean isCrop(String field) {
        return field.startsWith(CROP_PREFIX) && field.length() > CROP_PREFIX.length();
    }

    /**
     * Checks a field and value proposed by the LLM against the whitelist. Accepts {@code crops} or {@code crop} as the
     * field for one crop.
     *
     * @return the field name to store, or empty when the field is not allowed or the value is unusable
     */
    public static Optional<String> allowed(String field, String value) {
        if (field == null || value == null || value.isBlank() || value.strip().length() > MAX_VALUE_CHARS) {
            return Optional.empty();
        }
        String f = field.strip();
        if (f.equals("crops") || f.equals("crop")) {
            return Optional.of(crop(value));
        }
        if (isCrop(f)) {
            return Optional.of(crop(f.substring(CROP_PREFIX.length())));
        }
        if (!SINGLE.contains(f)) {
            return Optional.empty();
        }
        if (f.equals(LANGUAGE) && !LANGUAGES.contains(value.strip().toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(f);
    }

    private static String normaliseCrop(String crop) {
        return crop.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
