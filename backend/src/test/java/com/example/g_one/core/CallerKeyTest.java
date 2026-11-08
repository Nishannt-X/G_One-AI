package com.example.g_one.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CallerKeyTest {
    // Twilio's documented magic test numbers: synthetic, never a real person.
    private static final String NUMBER = "+15005550006";
    private static final String OTHER_NUMBER = "+15005550001";
    // 32 bytes each, the minimum. Synthetic test salts, not secrets.
    private static final byte[] SALT = "salt-one-not-a-secret-32-bytes!!".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OTHER_SALT = "salt-two-not-a-secret-32-bytes!!".getBytes(StandardCharsets.US_ASCII);

    @Test
    void theSameNumberAndSaltAlwaysGiveTheSameKey() {
        assertThat(CallerKey.fromPhone(NUMBER, SALT)).isEqualTo(CallerKey.fromPhone(NUMBER, SALT.clone()));
        assertThat(CallerKey.fromPhone("  " + NUMBER + " ", SALT)).isEqualTo(CallerKey.fromPhone(NUMBER, SALT));
    }

    @Test
    void anotherSaltOrAnotherNumberGivesAnotherKey() {
        CallerKey key = CallerKey.fromPhone(NUMBER, SALT);

        assertThat(CallerKey.fromPhone(NUMBER, OTHER_SALT)).isNotEqualTo(key);
        assertThat(CallerKey.fromPhone(OTHER_NUMBER, SALT)).isNotEqualTo(key);
    }

    @Test
    void theKeyIsSixtyFourHexCharactersAndNeverContainsTheNumber() throws Exception {
        CallerKey key = CallerKey.fromPhone(NUMBER, SALT);
        String json = new ObjectMapper().writeValueAsString(key);

        assertThat(key.value()).matches("[0-9a-f]{64}");
        assertThat(json).isEqualTo("\"" + key.value() + "\"");
        for (String shown : new String[] {key.value(), key.toString(), json}) {
            assertThat(shown).doesNotContain("5005550006").doesNotContain(NUMBER);
        }
    }

    @Test
    void aKeyReadFromJsonEqualsTheOriginal() throws Exception {
        CallerKey key = CallerKey.fromPhone(NUMBER, SALT);
        ObjectMapper json = new ObjectMapper();

        assertThat(json.readValue(json.writeValueAsString(key), CallerKey.class)).isEqualTo(key);
    }

    @Test
    void aNumberThatIsNotE164IsRejectedWithoutEchoingIt() {
        for (String bad : new String[] {"anonymous", "", "15005550006", "+0123456789", "+1 500 555 0006", "+12"}) {
            assertThatThrownBy(() -> CallerKey.fromPhone(bad, SALT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("5550006"));
        }
        assertThatThrownBy(() -> CallerKey.fromPhone(null, SALT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSaltShorterThan32BytesIsRejected() {
        assertThat(SALT).hasSize(CallerKey.MIN_SALT_BYTES);
        assertThatThrownBy(() -> CallerKey.fromPhone(NUMBER, new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallerKey.fromPhone(NUMBER, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallerKey.fromPhone(NUMBER, new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("5550006"));
        assertThat(CallerKey.fromPhone(NUMBER, new byte[32]).value()).hasSize(64);
    }

    @Test
    void onlyAHashCanBeAKey() {
        assertThatThrownBy(() -> new CallerKey(NUMBER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallerKey("A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CallerKey("a".repeat(64)).value()).hasSize(64);
    }
}
