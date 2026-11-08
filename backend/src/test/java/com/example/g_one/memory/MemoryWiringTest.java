package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallerKey;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The default wiring end to end: SQLite store in a temp data dir, the bundled seed loaded at startup under the key of a
 * synthetic number from the environment, and the console endpoint showing it (no access code set: local dev).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext // close the SQLite file before the temp dir is deleted (Windows cannot delete an open file)
class MemoryWiringTest {
    /** Twilio's magic test number: synthetic. */
    private static final String PHONE = "+15005550006";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void memory(DynamicPropertyRegistry r) {
        r.add("g-one.memory.data-dir", () -> dataDir.toString());
        r.add("g-one.memory.seed-file", () -> "classpath:memory/seed-suresh.json");
        r.add("g-one.memory.hmac-key", () -> Base64.getEncoder().encodeToString(FakeLeg.SALT));
        r.add("g-one.memory.access-code", () -> "");
        r.add("G_ONE_DEMO_FARMER_PHONE", () -> PHONE);
    }

    @Autowired
    MemoryStore store;
    @Autowired
    TestRestTemplate http;

    @Test
    void sqliteIsTheDefaultStoreAndTheSeedFarmerIsServed() throws Exception {
        assertThat(store).isInstanceOf(SqliteMemoryStore.class);
        assertThat(dataDir.resolve(SqliteMemoryStore.FILE_NAME)).exists();

        CallerKey key = CallerKey.fromPhone(PHONE, FakeLeg.SALT);
        JsonNode body = new ObjectMapper().readTree(http.getForObject("/api/memory/" + key.value(), String.class));

        assertThat(body.path("known").asBoolean()).isTrue();
        assertThat(body.path("facts").toString()).contains("Suresh", "Lasalgaon APMC", "crop:red onion");
        assertThat(body.path("gists").get(0).path("callId").asText()).isEqualTo("c-1103");
        assertThat(body.toString()).doesNotContain(PHONE).doesNotContain("5005550006");
    }
}
