package com.example.g_one.agent.tools.mandi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The live AGMARKNET lookup against a fake HTTP server speaking the real response shape. No network, no key. */
class MandiSourceTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String LASTWEEK = "/v1/prices-and-arrivals/market-price/lastweek";
    private static final String RICE_TITLE = "Market-wise Prices during last week Commodity: Rice, State/UT: West Bengal";

    /** A clock the test moves by hand. Starts at 6 Nov 2026, noon IST, two days after the real captured price. */
    static final class TestClock extends Clock {
        Instant now = LocalDate.of(2026, 11, 6).atTime(12, 0).atZone(IST).toInstant();

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return IST; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private HttpServer server;
    private volatile int status;
    private volatile byte[] body;
    private volatile long delayMillis;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final TestClock clock = new TestClock();

    @BeforeEach
    void startFake() throws IOException {
        status = 200;
        body = riceBody("2026-11-04", 3400.0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(LASTWEEK, x -> {
            hits.incrementAndGet();
            requests.add(x.getRequestURI().getPath() + "?" + x.getRequestURI().getRawQuery());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(status, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
    }

    @AfterEach
    void stopFake() {
        server.stop(0);
    }

    // ---- helpers ---------------------------------------------------------
    private GOneProperties.Agmarknet live(String variety, int timeoutSeconds) {
        return new GOneProperties.Agmarknet("http://127.0.0.1:" + server.getAddress().getPort(), "670", "36", "3",
                "Rice", "Burdwan APMC", variety, timeoutSeconds, 3, 30);
    }

    private static GOneProperties props(GOneProperties.Agmarknet a, String snapshotFile) {
        return new GOneProperties(List.of("a:A:v", "b:B:v"), snapshotFile, a, null, null);
    }

    private MandiSource source() {
        return new MandiSource(props(live("Common", 2), ""), JSON, clock);
    }

    /** The response shape of the real endpoint: date-keyed columns, a "Market - Variety" row, a unit, "NR" for no report. */
    private static byte[] body(String title, String rowLabel, String unit, Object... dayThenValue) {
        ObjectNode root = JSON.createObjectNode();
        root.put("success", true);
        root.put("title", title);
        ArrayNode columns = root.putArray("columns");
        columns.addObject().put("key", "variety").put("title", "Market - Variety");
        ObjectNode row = JSON.createObjectNode();
        row.put("variety", rowLabel);
        for (int i = 0; i < dayThenValue.length; i += 2) {
            String day = (String) dayThenValue[i];
            columns.addObject().put("key", day).put("title", day.substring(8) + "-" + day.substring(5, 7) + "-" + day.substring(0, 4));
            Object v = dayThenValue[i + 1];
            if (v instanceof Number n) row.put(day, n.doubleValue());
            else row.put(day, (String) v);
        }
        columns.addObject().put("key", "unitOfPrice").put("title", "Unit of Price");
        row.put("unitOfPrice", unit);
        root.putArray("data").add(row);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The row loses the field for one day entirely: a malformed response, not a "no report". */
    private static byte[] withoutValue(byte[] response, String day) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(response);
            ((ObjectNode) root.path("data").get(0)).remove(day);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] nullValue(byte[] response, String day) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(response);
            ((ObjectNode) root.path("data").get(0)).putNull(day);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] riceBody(Object... dayThenValue) {
        return body(RICE_TITLE, "Burdwan APMC - Common", "Rs./Quintal", dayThenValue);
    }

    // ---- accepted --------------------------------------------------------
    @Test
    void realCapturedResponseGivesTheDatedListedPriceForTheConfiguredVariety() throws Exception {
        body = getClass().getResourceAsStream("/agmarknet-rice-burdwan-lastweek.json").readAllBytes();

        MandiSnapshot common = source().current().orElseThrow();
        assertThat(common.price()).isEqualByComparingTo("3400");
        assertThat(common.priceDate()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(common.commodity()).isEqualTo("Rice");
        assertThat(common.variety()).isEqualTo("Common");
        assertThat(common.market()).isEqualTo("Burdwan APMC");
        assertThat(common.currency()).isEqualTo("INR");
        assertThat(common.unit()).isEqualTo("quintal");
        assertThat(common.sourceName()).isEqualTo("AGMARKNET");
        assertThat(common.capturedAt()).isEqualTo("2026-11-06");
        assertThat(requests).containsExactly(LASTWEEK + "?marketId=670&stateId=36&commodityId=3&includeExcel=false");
        assertThat(common.sourceUrl()).endsWith(requests.get(0));

        MandiSnapshot fine = new MandiSource(props(live("Fine", 2), ""), JSON, clock).current().orElseThrow();
        assertThat(fine.price()).isEqualByComparingTo("3800");
        assertThat(fine.variety()).isEqualTo("Fine");
    }

    @Test
    void realResponseIsReadWithItsOwnDateMarketAndVariety() throws Exception {
        body = getClass().getResourceAsStream("/agmarknet-rice-burdwan-lastweek.json").readAllBytes();
        MandiSnapshot snapshot = source().current().orElseThrow();
        assertThat(snapshot.price()).isEqualByComparingTo("3400");
        assertThat(snapshot.priceDate()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(snapshot.commodity()).isEqualTo("Rice");
        assertThat(snapshot.variety()).isEqualTo("Common");
        assertThat(snapshot.market()).isEqualTo("Burdwan APMC");
        assertThat(snapshot.unit()).isEqualTo("quintal");
    }

    @Test
    void aDayWithNoReportFallsBackToTheEarlierDayAndKeepsItsOwnDate() {
        body = riceBody("2026-11-05", "NR", "2026-11-04", 3400.0, "2026-11-03", 3350.0);
        MandiSnapshot s = source().current().orElseThrow();
        assertThat(s.priceDate()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(s.price()).isEqualByComparingTo("3400");
    }

    @Test
    void aPriceExactlyAtTheAgeLimitIsAcceptedOneDayOlderIsNot() {
        body = riceBody("2026-11-03", 3350.0); // 3 days before 6 Nov = the limit
        assertThat(source().current()).isPresent();
        body = riceBody("2026-11-02", 3350.0);
        assertThat(source().current()).isEmpty();
    }

    // ---- rejected: never a price we cannot vouch for -----------------------
    @Test
    void untrustworthyResponsesProduceNoPrice() {
        String ok = "Burdwan APMC - Common";
        List<byte[]> bad = List.of(
                body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-01", 3400.0),                  // stale
                body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-07", 3400.0),                  // dated in the future
                body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-04", "NR", "2026-11-03", "NR"), // never reported
                body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-04", "3400"),                  // text, not a number
                body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-04", 0.0),                     // not positive
                body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-04", -5.0),
                body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-04", "N/A", "2026-11-03", 3350.0), // garbage is not "no report"
                body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-04", "nr", "2026-11-03", 3350.0),  // only the literal "NR"
                withoutValue(body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-04", 3400.0, "2026-11-03", 3350.0), "2026-11-04"),
                nullValue(body(RICE_TITLE, ok, "Rs./Quintal", "2026-11-04", 3400.0, "2026-11-03", 3350.0), "2026-11-04"),
                body(RICE_TITLE, ok, "Rs./Kg", "2026-11-04", 34.0),                          // wrong unit
                body(RICE_TITLE, ok, "Rs./Quintal"),                                         // no day columns
                body(RICE_TITLE, "Burdwan APMC - Fine", "Rs./Quintal", "2026-11-04", 3800.0), // other variety only
                body(RICE_TITLE, "Asansol APMC - Common", "Rs./Quintal", "2026-11-04", 3400.0), // other market only
                body("Market-wise Prices during last week Commodity: Wheat, State/UT: West Bengal", ok, "Rs./Quintal",
                        "2026-11-04", 2400.0),                                                // other commodity
                body("Prices", ok, "Rs./Quintal", "2026-11-04", 3400.0),                     // no commodity in title
                "<html>maintenance</html>".getBytes(StandardCharsets.UTF_8),
                "{\"success\":false,\"message\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                "[]".getBytes(StandardCharsets.UTF_8));
        for (byte[] response : bad) {
            body = response;
            assertThat(source().current()).as(new String(response, StandardCharsets.UTF_8)).isEmpty();
        }
    }

    @Test
    void aMislabelledOrImpossibleDateColumnRejectsTheWholeResponse() throws IOException {
        body = riceBody("2026-11-04", 3400.0, "2026-11-03", 3350.0);
        ObjectNode root = (ObjectNode) JSON.readTree(body);
        ((ObjectNode) root.path("columns").get(1)).put("title", "03-11-2026"); // key says 04-11
        body = root.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(source().current()).isEmpty();

        body = riceBody("2026-02-30", 3400.0); // not a calendar date
        assertThat(source().current()).isEmpty();
    }

    @Test
    void twoRowsForTheSameMarketAndVarietyAreAmbiguousSoRejected() throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(body);
        ((ArrayNode) root.path("data")).add(root.path("data").get(0).deepCopy());
        body = root.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(source().current()).isEmpty();
    }

    @Test
    void anOversizedResponseIsRejected() {
        body = new byte[300 * 1024];
        assertThat(source().current()).isEmpty();
    }

    @Test
    void anEndlessResponseIsRejectedWithoutBeingReadToTheEnd() throws Exception {
        AtomicLong sent = new AtomicLong();
        server.removeContext(LASTWEEK); // replace the fixed-body handler with an endless one
        server.createContext(LASTWEEK, x -> {
            x.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            try {
                for (int i = 0; i < 8192; i++) { // 512 MiB cap so a broken bound cannot hang the test
                    x.getResponseBody().write(chunk);
                    x.getResponseBody().flush();
                    sent.addAndGet(chunk.length);
                }
            } catch (IOException clientHungUp) {
                // expected
            } finally {
                x.close();
            }
        });
        assertThat(source().current()).isEmpty();
        Thread.sleep(300);
        assertThat(sent.get()).isLessThan(32L * 1024 * 1024);
    }

    // ---- cache, failure and timeout ----------------------------------------
    @Test
    void aValidPriceIsServedFromCacheUntilItExpires() {
        MandiSource src = source();
        assertThat(src.current()).isPresent();
        clock.advance(Duration.ofMinutes(29));
        assertThat(src.current()).isPresent();
        assertThat(hits).hasValue(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(src.current()).isPresent();
        assertThat(hits).hasValue(2);
    }

    @Test
    void whenTheLookupFailsTheLastValidDatedPriceIsUsedOnlyWhileItIsRecent() {
        MandiSource src = source();
        assertThat(src.current().orElseThrow().priceDate()).isEqualTo(LocalDate.of(2026, 11, 4));

        status = 503;
        clock.advance(Duration.ofMinutes(31));
        MandiSnapshot kept = src.current().orElseThrow();
        assertThat(kept.priceDate()).isEqualTo(LocalDate.of(2026, 11, 4)); // still its own date, never relabelled
        assertThat(hits).hasValue(2);

        clock.advance(Duration.ofDays(2)); // 8 Nov: the 4 Nov price is now older than the 3-day limit
        assertThat(src.current()).isEmpty();
    }

    @Test
    void afterAFailureTheServiceIsNotRetriedForAMinute() {
        status = 500;
        MandiSource src = source();
        assertThat(src.current()).isEmpty();
        assertThat(src.current()).isEmpty();
        assertThat(hits).hasValue(1);
        clock.advance(Duration.ofSeconds(61));
        status = 200;
        assertThat(src.current()).isPresent();
        assertThat(hits).hasValue(2);
    }

    @Test
    void aSlowServiceTimesOutQuicklyInsteadOfHangingTheAdvisory() {
        delayMillis = 3500;
        MandiSource src = new MandiSource(props(live("Common", 1), ""), JSON, clock);
        long start = System.nanoTime();
        assertThat(src.current()).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    // ---- configuration -------------------------------------------------------
    @Test
    void withoutAgmarknetSettingsTheDatedSnapshotIsUsedAsBefore() {
        GOneProperties.Agmarknet off = new GOneProperties.Agmarknet("https://api.agmarknet.gov.in", "", "", "", "", "", "", 5, 3, 30);
        MandiSnapshot s = new MandiSource(props(off, ""), JSON, clock).current().orElseThrow();
        assertThat(s.commodity()).isEqualTo("Onion");
        assertThat(hits).hasValue(0);
    }

    @Test
    void incompleteOrConflictingSettingsFailAtStartupInsteadOfSilentlyUsingAnotherPrice() {
        String base = "http://127.0.0.1:1";
        assertThatThrownBy(() -> new MandiSource(props(new GOneProperties.Agmarknet(base, "670", "", "", "", "", "", 5, 3, 30), ""), JSON, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
        assertThatThrownBy(() -> new MandiSource(props(live("Common", 2), "/some/snapshot.json"), JSON, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not both");
        assertThatThrownBy(() -> new MandiSource(props(new GOneProperties.Agmarknet(base, "670&x=1", "36", "3", "Rice",
                "Burdwan APMC", "Common", 5, 3, 30), ""), JSON, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("whole numbers");
    }
}
