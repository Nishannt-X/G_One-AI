package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MemorySeederTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Twilio's magic test number: synthetic. */
    private static final String PHONE = "+15005550006";
    private static final Map<String, String> ENV = Map.of("G_ONE_DEMO_FARMER_PHONE", PHONE);
    private static final Optional<byte[]> SALT = Optional.of(FakeLeg.SALT);

    private final MemoryStore store = new InMemoryMemoryStore();

    private static long millis(String iso) {
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
    }

    private static JsonNode bundledSeed() {
        return MemoryConfig.readSeed("classpath:memory/seed-suresh.json", JSON);
    }

    @Test
    void theBundledSeedLoadsSureshUnderTheKeyOfTheEnvNumber() {
        MemorySeeder.Result r = MemorySeeder.seed(store, bundledSeed(), ENV::get, SALT);

        assertThat(r.status()).isEqualTo(MemorySeeder.Status.SEEDED);
        CallerKey key = CallerKey.fromPhone(PHONE, FakeLeg.SALT);
        FarmerMemory m = store.load(key).orElseThrow();
        assertThat(m.fact("name").orElseThrow())
                .isEqualTo(new ProfileFact("name", "Suresh",
                        Provenance.said(CallId.of("c-1028"), millis("2026-10-28T10:12:00+05:30"))));
        assertThat(m.fact("state").orElseThrow().provenance().saidOrInferred()).isEqualTo(Provenance.Origin.INFERRED);
        assertThat(m.fact("usualMandi").orElseThrow().value()).isEqualTo("Lasalgaon APMC");
        assertThat(m.fact("language").orElseThrow().value()).isEqualTo("HI_EN");
        assertThat(m.fact("crop:red onion")).isPresent();
        assertThat(m.fact("crop:tomato")).isPresent();
        assertThat(m.facts()).hasSize(8);
        assertThat(m.gists()).extracting(g -> g.callId().value()).containsExactly("c-1103", "c-1028");
        assertThat(m.gists().get(0).followUps())
                .containsExactly("Find a buyer for ~20 quintal red onion after harvest (due ~2026-11-08)");
    }

    @Test
    void seedingTwiceChangesNothing() {
        MemorySeeder.seed(store, bundledSeed(), ENV::get, SALT);
        CallerKey key = CallerKey.fromPhone(PHONE, FakeLeg.SALT);
        FarmerMemory once = store.load(key).orElseThrow();

        MemorySeeder.seed(store, bundledSeed(), ENV::get, SALT);

        assertThat(store.load(key).orElseThrow()).isEqualTo(once);
    }

    @Test
    void theSeedNeverOverwritesNewerMemoryFromARealCall() {
        CallerKey key = CallerKey.fromPhone(PHONE, FakeLeg.SALT);
        long at = millis("2026-11-08T10:00:00+05:30");
        ProfileFact newer = new ProfileFact("district", "Niphad", Provenance.said(CallId.of("c-1108"), at));
        CallGist later = new CallGist(CallId.of("c-1108"), at, at + 60_000, "A later call.", List.of());
        store.upsertFacts(key, List.of(newer));
        store.saveGist(key, later);

        MemorySeeder.seed(store, bundledSeed(), ENV::get, SALT);

        FarmerMemory m = store.load(key).orElseThrow();
        assertThat(m.fact("district")).contains(newer);
        assertThat(m.gists()).first().isEqualTo(later);
        assertThat(m.gists()).hasSize(3);
    }

    @Test
    void withoutThePhoneEnvVarOrTheHmacKeyNothingIsSeeded() {
        assertThat(MemorySeeder.seed(store, bundledSeed(), k -> null, SALT).status())
                .isEqualTo(MemorySeeder.Status.SKIPPED_NO_PHONE);
        assertThat(MemorySeeder.seed(store, bundledSeed(), ENV::get, Optional.empty()).status())
                .isEqualTo(MemorySeeder.Status.SKIPPED_NO_HMAC_KEY);
        assertThat(store.load(CallerKey.fromPhone(PHONE, FakeLeg.SALT))).isEmpty();
    }

    @Test
    void aSeedThatHoldsANumberIsRefused() {
        ObjectNode numberInKey = bundledSeed().deepCopy();
        numberInKey.put("phoneEnvKey", PHONE);
        assertThatThrownBy(() -> MemorySeeder.seed(store, numberInKey, ENV::get, SALT))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(PHONE);

        ObjectNode secretInGist = bundledSeed().deepCopy();
        ((ObjectNode) secretInGist.path("gists").path(0)).put("gist", "His OTP is 482913.");
        assertThatThrownBy(() -> MemorySeeder.seed(store, secretInGist, ENV::get, SALT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.load(CallerKey.fromPhone(PHONE, FakeLeg.SALT))).isEmpty();
    }

    @Test
    void theBundledSeedFileHoldsNoPhoneNumber() throws Exception {
        String text = new String(new ClassPathResource("memory/seed-suresh.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(text).doesNotContainPattern("\\+\\d{6,}|\\d{10,}");
    }
}
