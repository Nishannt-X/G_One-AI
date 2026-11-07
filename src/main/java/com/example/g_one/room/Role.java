package com.example.g_one.room;

import com.example.g_one.ApiException;
import java.util.Locale;

public enum Role {
    FARMER, BUYER;

    public Role other() {
        return this == FARMER ? BUYER : FARMER;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role parse(String s) {
        if ("farmer".equals(s)) return FARMER;
        if ("buyer".equals(s)) return BUYER;
        throw new ApiException(400, "bad_role", "Role must be farmer or buyer.");
    }
}
