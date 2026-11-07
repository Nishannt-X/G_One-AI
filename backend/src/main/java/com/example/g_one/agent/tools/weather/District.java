package com.example.g_one.agent.tools.weather;

import java.util.List;

/** A farming district the weather tool knows: display name, state, approximate headquarters coordinates, aliases. */
public record District(String name, String state, double lat, double lon, List<String> aliases) {
    public District {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
