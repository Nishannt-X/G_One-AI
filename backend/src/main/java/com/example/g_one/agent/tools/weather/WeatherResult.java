package com.example.g_one.agent.tools.weather;

import java.util.List;

/** What a weather lookup produced. Every case has a spoken form ({@link WeatherSpeech}); none is an exception. */
public sealed interface WeatherResult {
    record Found(Forecast forecast) implements WeatherResult {}

    /** The farmer named a district the table does not know. {@code known} lists the names it does know. */
    record UnknownDistrict(String asked, List<String> known) implements WeatherResult {
        public UnknownDistrict {
            known = List.copyOf(known);
        }
    }

    /** The district is known but the forecast could not be fetched or read. {@code reason} is for logs only. */
    record Unavailable(District district, String reason) implements WeatherResult {}
}
