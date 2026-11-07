package com.example.g_one.core;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Where a leg's audio comes from. The pipeline treats both the same. JSON: "twilio", "simulator". */
public enum LegSource {
    TWILIO, SIMULATOR;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
