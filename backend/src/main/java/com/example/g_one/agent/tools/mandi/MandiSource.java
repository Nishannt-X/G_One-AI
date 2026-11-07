package com.example.g_one.agent.tools.mandi;

import com.example.g_one.GOneProperties;
import com.example.g_one.speech.Http;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The one reference price behind the farmer's advisory. Either the preloaded dated snapshot (default), or, when the
 * AGMARKNET settings are present, a read-only lookup of the public "last week" prices for ONE variety at ONE market.
 *
 * <p>Live data is trusted only if every check passes: success flag, commodity name in the title, exactly one row for
 * the configured market and variety, unit "Rs./Quintal", a positive numeric price, and a price date that is not in the
 * future and not older than {@code maxAgeDays}. A day reported "NR" (no report) is skipped to the previous day, which
 * keeps its own date; a missing, null or any other non-numeric figure rejects the response. The source does not say
 * whether a figure is minimum, modal or maximum, so it is only ever presented as the "listed" price on its date. When the lookup fails, the last valid result is served while it is
 * still inside the age window; otherwise there is no price (callers say a comparison is unavailable). The bundled
 * sample is never used as a fallback for live mode.
 */
@Component
public class MandiSource {
    private static final Logger log = LoggerFactory.getLogger(MandiSource.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String PATH = "/v1/prices-and-arrivals/market-price/lastweek"
            + "?marketId=%s&stateId=%s&commodityId=%s&includeExcel=false";
    /** After a failed lookup, wait this long before trying again so every buyer offer does not pay the timeout. */
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final Pattern ID = Pattern.compile("\\d{1,9}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TITLE_COMMODITY = Pattern.compile("Commodity:\\s*(.+?),\\s*State/UT:");
    private static final DateTimeFormatter COLUMN_TITLE = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    private final MandiSnapshot fixed;
    private final GOneProperties.Agmarknet cfg;
    private final RestClient client;
    private final ObjectMapper json;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private MandiSnapshot cached; // last valid live price; guarded by lock
    private Instant cachedAt = Instant.MIN; // guarded by lock
    private Instant retryAt = Instant.MIN; // guarded by lock

    @Autowired
    public MandiSource(GOneProperties props, ObjectMapper json) {
        this(props, json, Clock.system(INDIA));
    }

    MandiSource(GOneProperties props, ObjectMapper json, Clock clock) {
        GOneProperties.Agmarknet a = props.agmarknet();
        long given = Stream.of(a.marketId(), a.stateId(), a.commodityId(), a.commodity(), a.market(), a.variety())
                .filter(v -> v != null && !v.isBlank()).count();
        boolean live = given > 0;
        if (live) validate(a, given, props.mandiSnapshotFile());
        this.json = json;
        this.clock = clock;
        this.cfg = live ? a : null;
        this.client = live ? Http.client(a.baseUrl(), a.timeoutSeconds()) : null;
        this.fixed = live ? null : MandiSnapshot.load(json, props.mandiSnapshotFile());
    }

    private static void validate(GOneProperties.Agmarknet a, long given, String snapshotFile) {
        if (given != 6) {
            throw new IllegalStateException("AGMARKNET settings are incomplete: set all of G_ONE_AGMARKNET_MARKET_ID, "
                    + "_STATE_ID, _COMMODITY_ID, _COMMODITY, _MARKET and _VARIETY, or none of them");
        }
        if (!snapshotFile.isBlank()) {
            throw new IllegalStateException("Use either G_ONE_MANDI_SNAPSHOT_FILE or the G_ONE_AGMARKNET_* settings, not both");
        }
        if (!ID.matcher(a.marketId().trim()).matches() || !ID.matcher(a.stateId().trim()).matches()
                || !ID.matcher(a.commodityId().trim()).matches()) {
            throw new IllegalStateException("AGMARKNET market, state and commodity IDs must be whole numbers");
        }
        if (a.baseUrl() == null || a.baseUrl().isBlank()) {
            throw new IllegalStateException("G_ONE_AGMARKNET_BASE_URL must not be empty");
        }
    }

    /** @return the reference price to compare against, or empty when no trustworthy, current-enough price exists. */
    public Optional<MandiSnapshot> current() {
        return fixed != null ? Optional.of(fixed) : live();
    }

    private Optional<MandiSnapshot> live() {
        lock.lock();
        try {
            Instant now = clock.instant();
            Optional<MandiSnapshot> last = cached == null ? Optional.empty() : usable(cached);
            if (last.isPresent() && now.isBefore(cachedAt.plus(Duration.ofMinutes(cfg.cacheMinutes())))) return last;
            if (now.isBefore(retryAt)) return last;
            try {
                cached = fetch();
                cachedAt = now;
                return Optional.of(cached);
            } catch (RuntimeException e) {
                log.warn("AGMARKNET price unavailable ({}); {}", e.getMessage(),
                        last.isPresent() ? "using the last valid price" : "no recent valid price to fall back on");
                retryAt = now.plus(RETRY_AFTER_FAILURE);
                return last;
            }
        } finally {
            lock.unlock();
        }
    }

    private Optional<MandiSnapshot> usable(MandiSnapshot s) {
        return Optional.of(s).filter(x -> inWindow(x.priceDate(), LocalDate.now(clock)));
    }

    private boolean inWindow(LocalDate priceDate, LocalDate today) {
        return !priceDate.isAfter(today) && !priceDate.isBefore(today.minusDays(cfg.maxAgeDays()));
    }

    private MandiSnapshot fetch() {
        String path = PATH.formatted(cfg.marketId().trim(), cfg.stateId().trim(), cfg.commodityId().trim());
        Http.Result r = Http.call("mandi_fetch", client.get().uri(path).accept(MediaType.APPLICATION_JSON),
                MAX_BODY_BYTES);
        if (!r.ok()) throw new IllegalStateException("HTTP " + r.status());
        if (r.body().length > MAX_BODY_BYTES) throw rejected("response too large");
        JsonNode root;
        try {
            root = json.readTree(r.body());
        } catch (IOException e) {
            throw rejected("unreadable JSON");
        }
        return parse(root, cfg.baseUrl() + path, LocalDate.now(clock));
    }

    private MandiSnapshot parse(JsonNode root, String url, LocalDate today) {
        if (!root.path("success").isBoolean() || !root.path("success").booleanValue()) {
            throw rejected("success flag is not true");
        }
        Matcher title = TITLE_COMMODITY.matcher(root.path("title").asText(""));
        if (!title.find() || !label(title.group(1)).equals(label(cfg.commodity()))) {
            throw rejected("response is not for the configured commodity");
        }
        String want = label(cfg.market() + " - " + cfg.variety());
        JsonNode row = null;
        for (JsonNode candidate : root.path("data")) {
            if (!want.equals(label(candidate.path("variety").asText("")))) continue;
            if (row != null) throw rejected("more than one row for the configured market and variety");
            row = candidate;
        }
        if (row == null) throw rejected("no row for the configured market and variety");
        if (!"rs./quintal".equals(label(row.path("unitOfPrice").asText("")))) {
            throw rejected("price unit is not Rs./Quintal");
        }
        List<LocalDate> days = new ArrayList<>();
        for (JsonNode column : root.path("columns")) {
            String key = column.path("key").asText("");
            if (!ISO_DATE.matcher(key).matches()) continue; // "variety", "unitOfPrice"
            try {
                LocalDate day = LocalDate.parse(key);
                if (!day.format(COLUMN_TITLE).equals(column.path("title").asText(""))) {
                    throw rejected("a date column's title disagrees with its key");
                }
                days.add(day);
            } catch (DateTimeParseException e) {
                throw rejected("a date column is not a real date");
            }
        }
        days.sort(Comparator.reverseOrder());
        for (LocalDate day : days) {
            JsonNode v = row.path(day.toString());
            if (v.isTextual() && "NR".equals(v.asText())) continue; // only the literal marker means "no report"
            if (!v.isNumber() || v.decimalValue().signum() <= 0) {
                throw rejected("price for " + day + " is not a positive number");
            }
            if (!inWindow(day, today)) throw rejected("newest price is dated " + day + ", outside the allowed age");
            return new MandiSnapshot("AGMARKNET", url, today.toString(), day, cfg.commodity().trim(),
                    cfg.variety().trim(), cfg.market().trim(), v.decimalValue(), "INR", "quintal");
        }
        throw rejected("no price reported in the listed days");
    }

    private static String label(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException rejected(String why) {
        return new IllegalStateException("rejected AGMARKNET data: " + why);
    }
}
