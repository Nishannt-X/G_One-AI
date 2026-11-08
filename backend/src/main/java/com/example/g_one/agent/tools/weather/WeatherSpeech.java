package com.example.g_one.agent.tools.weather;

import com.example.g_one.agent.llm.SpokenEnglish;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link WeatherResult} into a short English summary with numbers as words, for the model to pass on in the
 * farmer's language. Covers today and tomorrow, plus a heads-up for heavy rain later in the forecast. It always says
 * "forecast": nothing here is an observation or a promise.
 */
public final class WeatherSpeech {
    /** Daily rain at or above this is called heavy (IMD calls 64.5 mm "heavy"; 20 mm already stops field work). */
    static final double HEAVY_RAIN_MM = 20;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final int MAX_KNOWN_LISTED = 6;

    private final Clock clock;

    public WeatherSpeech() {
        this(Clock.system(INDIA));
    }

    public WeatherSpeech(Clock clock) {
        this.clock = clock;
    }

    public String spoken(WeatherResult result) {
        return switch (result) {
            case WeatherResult.Found f -> found(f.forecast());
            case WeatherResult.UnknownDistrict u -> {
                List<String> known = u.known().subList(0, Math.min(MAX_KNOWN_LISTED, u.known().size()));
                String asked = u.asked().isBlank() ? "that place" : u.asked();
                yield "No weather forecast is available for " + asked + ". Ask for a nearby district, for example "
                        + String.join(", ", known) + ".";
            }
            case WeatherResult.Unavailable n -> "The weather forecast for " + n.district().name()
                    + " could not be fetched. Offer to try again later.";
        };
    }

    private String found(Forecast f) {
        LocalDate today = LocalDate.now(clock);
        List<String> parts = new ArrayList<>();
        String heavyLater = null;
        for (Forecast.Day d : f.days()) {
            long ahead = d.date().toEpochDay() - today.toEpochDay();
            if (ahead < 0) continue;
            if (ahead <= 1) {
                String label = dayLabel(ahead, d.date());
                parts.add(Character.toUpperCase(label.charAt(0)) + label.substring(1) + ", " + describe(d));
            } else if (heavyLater == null && d.rainMm() >= HEAVY_RAIN_MM) {
                heavyLater = "Heavy rain is forecast " + dayLabel(ahead, d.date()) + ".";
            }
        }
        if (parts.isEmpty()) return "No weather forecast is available for " + f.district().name() + " for the coming days.";
        String text = "The Open-Meteo forecast for " + f.district().name() + " says: " + String.join(". ", parts) + ".";
        return heavyLater == null ? text : text + " " + heavyLater;
    }

    private static String dayLabel(long ahead, LocalDate date) {
        if (ahead == 0) return "today";
        if (ahead == 1) return "tomorrow";
        if (ahead == 2) return "the day after tomorrow";
        return "on " + SpokenEnglish.date(date);
    }

    private static String describe(Forecast.Day d) {
        List<String> bits = new ArrayList<>();
        bits.add(sky(d.weatherCode()) + ", between " + degrees(d.minC()) + " and " + degrees(d.maxC()) + " degrees");
        if (d.rainChancePercent() != null) {
            int pct = (int) (Math.round(d.rainChancePercent() / 10.0) * 10);
            bits.add(pct == 0 ? "almost no chance of rain" : "rain chance about " + SpokenEnglish.number(pct) + " percent");
        }
        if (d.rainMm() >= 1) {
            long mm = Math.round(d.rainMm());
            bits.add("about " + SpokenEnglish.number(mm) + (mm == 1 ? " millimetre" : " millimetres") + " of rain"
                    + (d.rainMm() >= HEAVY_RAIN_MM ? ", which is heavy" : ""));
        }
        return String.join(", ", bits);
    }

    private static String degrees(double c) {
        return SpokenEnglish.number(Math.round(c));
    }

    /** WMO weather code as plain words. */
    static String sky(int code) {
        if (code == 0) return "clear sky";
        if (code == 1 || code == 2) return "partly cloudy";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code == 65 || code == 82) return "heavy rain";
        if ((code >= 61 && code <= 67) || code == 80 || code == 81) return "rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code == 95) return "thunderstorms";
        if (code == 96 || code == 99) return "thunderstorms with hail";
        return "mixed weather";
    }
}
