package com.example.g_one.agent.tools.mandi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** One preloaded, dated public market price (see FARMER-CALL-03). Never presented as today's rate. */
public record MandiSnapshot(
        String sourceName,
        String sourceUrl,
        String capturedAt,
        LocalDate priceDate,
        String commodity,
        String variety,
        String market,
        BigDecimal price,
        String currency,
        String unit) {

    /** Loads the configured file, or the bundled development sample when none is configured. */
    public static MandiSnapshot load(ObjectMapper json, String file) {
        try (InputStream in = file.isBlank()
                ? MandiSnapshot.class.getResourceAsStream("/mandi-snapshot.sample.json")
                : Files.newInputStream(Path.of(file))) {
            return json.readValue(in, MandiSnapshot.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read the Mandi snapshot (G_ONE_MANDI_SNAPSHOT_FILE)", e);
        }
    }
}
