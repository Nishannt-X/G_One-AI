package com.example.g_one.agent.tools.weather;

import com.example.g_one.speech.Http;
import com.example.g_one.speech.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Daily forecast from the free, keyless Open-Meteo API (https://api.open-meteo.com/v1/forecast) for a district in
 * {@link DistrictTable}. Read-only GET, short timeout, bounded body. A failure is returned as
 * {@link WeatherResult.Unavailable}, never thrown, so the assistant can say so instead of going silent.
 *
 * <p>The base URL and timeout can be overridden with G_ONE_WEATHER_BASE_URL and G_ONE_WEATHER_TIMEOUT_SECONDS (read
 * through Spring's environment, defaults below); tests point it at a local fake server.
 */
@Component
public class WeatherClient {
    public static final String DEFAULT_BASE_URL = "https://api.open-meteo.com";
    static final String DAILY = "temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,weather_code";
    static final int FORECAST_DAYS = 3;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(WeatherClient.class);

    private final DistrictTable districts;
    private final RestClient client;
    private final ObjectMapper json;

    public WeatherClient(DistrictTable districts, ObjectMapper json,
                         @Value("${g-one.weather.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                         @Value("${g-one.weather.timeout-seconds:5}") int timeoutSeconds) {
        this.districts = districts;
        this.json = json;
        this.client = Http.client(baseUrl, timeoutSeconds);
    }

    public WeatherResult forecast(String spokenDistrict) {
        return districts.find(spokenDistrict)
                .map(this::forecast)
                .orElseGet(() -> new WeatherResult.UnknownDistrict(spokenDistrict == null ? "" : spokenDistrict.trim(),
                        districts.all().stream().map(District::name).toList()));
    }

    public WeatherResult forecast(District d) {
        String path = String.format(Locale.ROOT,
                "/v1/forecast?latitude=%.4f&longitude=%.4f&daily=%s&timezone=Asia/Kolkata&forecast_days=%d",
                d.lat(), d.lon(), DAILY, FORECAST_DAYS);
        try {
            Http.Result r = Http.call("weather_fetch", client.get().uri(path).accept(MediaType.APPLICATION_JSON), MAX_BODY_BYTES);
            if (!r.ok()) return unavailable(d, "HTTP " + r.status());
            if (r.body().length > MAX_BODY_BYTES) return unavailable(d, "response too large");
            return new WeatherResult.Found(new Forecast(d, parse(json.readTree(r.body()))));
        } catch (UpstreamException e) {
            return unavailable(d, "timeout or network error");
        } catch (IOException e) {
            return unavailable(d, "unreadable JSON");
        } catch (IllegalStateException e) {
            return unavailable(d, e.getMessage());
        }
    }

    private static WeatherResult unavailable(District d, String reason) {
        log.warn("Open-Meteo forecast for {} unavailable: {}", d.name(), reason);
        return new WeatherResult.Unavailable(d, reason);
    }

    /** @throws IllegalStateException when the daily block is missing, ragged or not numeric */
    static List<Forecast.Day> parse(JsonNode root) {
        JsonNode daily = root == null ? null : root.path("daily");
        if (daily == null || !daily.isObject()) throw new IllegalStateException("no daily block");
        JsonNode time = daily.path("time");
        if (!time.isArray() || time.isEmpty()) throw new IllegalStateException("no forecast days");
        JsonNode max = array(daily, "temperature_2m_max", time.size());
        JsonNode min = array(daily, "temperature_2m_min", time.size());
        JsonNode rain = array(daily, "precipitation_sum", time.size());
        JsonNode chance = daily.path("precipitation_probability_max");
        JsonNode code = daily.path("weather_code");
        List<Forecast.Day> days = new ArrayList<>();
        for (int i = 0; i < time.size(); i++) {
            LocalDate date;
            try {
                date = LocalDate.parse(time.get(i).asText(""));
            } catch (DateTimeParseException e) {
                throw new IllegalStateException("forecast date is not a date");
            }
            if (!max.get(i).isNumber() || !min.get(i).isNumber()) throw new IllegalStateException("temperature missing");
            double rainMm = rain.get(i).isNumber() ? rain.get(i).asDouble() : 0;
            Integer pct = chance.path(i).isNumber() ? (int) Math.round(chance.path(i).asDouble()) : null;
            int wmo = code.path(i).isNumber() ? code.path(i).asInt() : -1;
            days.add(new Forecast.Day(date, max.get(i).asDouble(), min.get(i).asDouble(), rainMm, pct, wmo));
        }
        return days;
    }

    private static JsonNode array(JsonNode daily, String field, int size) {
        JsonNode a = daily.path(field);
        if (!a.isArray() || a.size() != size) throw new IllegalStateException(field + " missing or wrong length");
        return a;
    }
}
