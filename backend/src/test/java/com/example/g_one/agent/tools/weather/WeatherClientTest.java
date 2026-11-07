package com.example.g_one.agent.tools.weather;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** WeatherClient and WeatherSpeech against a fake Open-Meteo server speaking the real response shape. No network. */
class WeatherClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 8 Nov 2026, 9 am IST. */
    private static final Clock CLOCK = Clock.fixed(LocalDate.of(2026, 11, 8).atTime(9, 0).atZone(IST).toInstant(), IST);

    /** Shape copied from an Open-Meteo /v1/forecast daily response. Synthetic numbers. */
    private static final String NASHIK = """
            {"latitude":20.0,"longitude":73.75,"generationtime_ms":0.05,"utc_offset_seconds":19800,
             "timezone":"Asia/Kolkata","timezone_abbreviation":"GMT+5:30","elevation":584.0,
             "daily_units":{"time":"iso8601","temperature_2m_max":"°C","temperature_2m_min":"°C",
               "precipitation_sum":"mm","precipitation_probability_max":"%","weather_code":"wmo code"},
             "daily":{"time":["2026-11-08","2026-11-09","2026-11-10"],
               "temperature_2m_max":[31.4,29.6,27.2],"temperature_2m_min":[18.2,19.5,20.1],
               "precipitation_sum":[0.0,7.6,32.4],"precipitation_probability_max":[4,63,90],
               "weather_code":[1,61,65]}}""";

    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private volatile long delayMillis;
    private final List<String> queries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        status = 200;
        body = NASHIK;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/forecast", x -> {
            queries.add(URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            try {
                x.sendResponseHeaders(status, b.length);
                x.getResponseBody().write(b);
            } catch (IOException clientGone) {
                // timed out first
            }
            x.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private WeatherClient client(int timeoutSeconds) {
        return new WeatherClient(new DistrictTable(JSON), JSON,
                "http://127.0.0.1:" + server.getAddress().getPort(), timeoutSeconds);
    }

    @Test
    void parsesTheDailyForecastForAKnownDistrict() {
        WeatherResult r = client(2).forecast("nashik district");
        assertThat(r).isInstanceOf(WeatherResult.Found.class);
        Forecast f = ((WeatherResult.Found) r).forecast();
        assertThat(f.district().name()).isEqualTo("Nashik");
        assertThat(f.days()).hasSize(3);
        Forecast.Day tomorrow = f.days().get(1);
        assertThat(tomorrow.date()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(tomorrow.maxC()).isEqualTo(29.6);
        assertThat(tomorrow.minC()).isEqualTo(19.5);
        assertThat(tomorrow.rainMm()).isEqualTo(7.6);
        assertThat(tomorrow.rainChancePercent()).isEqualTo(63);
        assertThat(tomorrow.weatherCode()).isEqualTo(61);

        String q = queries.get(0);
        assertThat(q).contains("latitude=19.9975").contains("longitude=73.7898")
                .contains("daily=" + WeatherClient.DAILY).contains("timezone=Asia/Kolkata").contains("forecast_days=3");
    }

    @Test
    void spokenSummaryIsShortWordsOnlyAndSaysForecast() {
        String s = new WeatherSpeech(CLOCK).spoken(client(2).forecast("Nasik"));
        assertThat(s).isEqualTo("The Open-Meteo forecast for Nashik says: "
                + "Today, partly cloudy, between eighteen and thirty-one degrees, almost no chance of rain. "
                + "Tomorrow, rain, between twenty and thirty degrees, rain chance about sixty percent, "
                + "about eight millimetres of rain. Heavy rain is forecast the day after tomorrow.");
        assertThat(s).doesNotContainPattern("[0-9]");
        assertThat(s.toLowerCase()).doesNotContain("real time").doesNotContain("live").doesNotContain("guarantee");
    }

    @Test
    void aliasesAndScriptsFindTheSameDistrict() {
        DistrictTable t = new DistrictTable(JSON);
        assertThat(t.find("Burdwan")).get().extracting(District::name).isEqualTo("Purba Bardhaman");
        assertThat(t.find("bardhaman")).get().extracting(District::name).isEqualTo("Purba Bardhaman");
        assertThat(t.find("বর্ধমান")).get().extracting(District::name).isEqualTo("Purba Bardhaman");
        assertThat(t.find("नासिक")).get().extracting(District::name).isEqualTo("Nashik");
        assertThat(t.find("Hooghly")).isPresent();
        assertThat(t.find("Nadia")).isPresent();
        assertThat(t.find("Ludhiana")).isPresent();
        assertThat(t.find("Indore")).isPresent();
        assertThat(t.find("Guntur")).isPresent();
        assertThat(t.find("Kolar")).isPresent();
        assertThat(t.find("Pune")).isPresent();
        assertThat(t.all()).hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    void anUnknownDistrictMakesNoRequestAndSuggestsKnownOnes() {
        WeatherResult r = client(2).forecast("Atlantis");
        assertThat(r).isInstanceOfSatisfying(WeatherResult.UnknownDistrict.class,
                u -> assertThat(u.asked()).isEqualTo("Atlantis"));
        assertThat(queries).isEmpty();
        String s = new WeatherSpeech(CLOCK).spoken(r);
        assertThat(s).startsWith("No weather forecast is available for Atlantis.").contains("Nashik");
        assertThat(new WeatherSpeech(CLOCK).spoken(client(2).forecast(" "))).contains("that place");
    }

    @Test
    void serverErrorsBadBodiesAndTimeoutsAreUnavailableNotExceptions() {
        WeatherSpeech speech = new WeatherSpeech(CLOCK);
        status = 400;
        body = "{\"error\":true,\"reason\":\"Latitude must be in range of -90 to 90°.\"}";
        assertThat(client(2).forecast("Pune")).isInstanceOf(WeatherResult.Unavailable.class);

        status = 200;
        for (String bad : List.of("<html>down</html>", "{}",
                "{\"daily\":{\"time\":[\"2026-11-08\"],\"temperature_2m_max\":[30],\"temperature_2m_min\":[]," +
                        "\"precipitation_sum\":[0]}}",
                "{\"daily\":{\"time\":[\"2026-11-08\"],\"temperature_2m_max\":[null],\"temperature_2m_min\":[20]," +
                        "\"precipitation_sum\":[0]}}")) {
            body = bad;
            WeatherResult r = client(2).forecast("Pune");
            assertThat(r).as(bad).isInstanceOf(WeatherResult.Unavailable.class);
            assertThat(speech.spoken(r)).isEqualTo("The weather forecast for Pune could not be fetched. Offer to try again later.");
        }

        body = NASHIK;
        delayMillis = 3500;
        long start = System.nanoTime();
        assertThat(client(1).forecast("Pune")).isInstanceOf(WeatherResult.Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void missingRainChanceAndWeatherCodeAreTolerated() {
        body = "{\"daily\":{\"time\":[\"2026-11-08\"],\"temperature_2m_max\":[30.2],\"temperature_2m_min\":[21.0],"
                + "\"precipitation_sum\":[null]}}";
        String s = new WeatherSpeech(CLOCK).spoken(client(2).forecast("Indore"));
        assertThat(s).isEqualTo("The Open-Meteo forecast for Indore says: Today, mixed weather, between twenty-one and thirty degrees.");
    }
}
