package com.example.g_one.console;

import com.example.g_one.core.CallId;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import java.util.List;

/** One call that has started and not ended, as listed by {@code GET /api/calls/active}. */
public record ActiveCall(CallId callId, long startedMs, String language, List<Leg> legs) {

    /** A leg that has joined the call and not ended. */
    public record Leg(LegId legId, LegRole role, LegSource source, String language) {}
}
