package com.example.g_one.agent;

import java.util.Locale;

/**
 * The two sides of a deal. TODO(#14): replace with {@code core.LegRole} once the core contracts land.
 */
public enum Role {
    FARMER, BUYER;

    public Role other() {
        return this == FARMER ? BUYER : FARMER;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
