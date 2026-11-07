package com.example.g_one.core;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Who is on a leg. The farmer leg comes in first; a buyer leg is dialled out by the assistant. JSON: "farmer", "buyer".
 */
public enum LegRole {
    FARMER, BUYER;

    public LegRole other() {
        return this == FARMER ? BUYER : FARMER;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
