package com.example.g_one.agent.tools.weather;

import java.time.LocalDate;
import java.util.List;

/** An Open-Meteo daily forecast for one district, days in date order. */
public record Forecast(District district, List<Day> days) {
    public Forecast {
        days = List.copyOf(days);
    }

    /**
     * One forecast day. Temperatures in °C. {@code rainChancePercent} is null when the source gives none.
     * {@code weatherCode} is the WMO code Open-Meteo reports, or -1 when absent.
     */
    public record Day(LocalDate date, double maxC, double minC, double rainMm, Integer rainChancePercent, int weatherCode) {}
}
