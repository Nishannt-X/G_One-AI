package com.example.g_one.advisory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.room.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which cautions a classifier answer earns, who gets each, and the fixed wording. No model is involved. */
class CautionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Set<Caution> read(String answer) throws Exception {
        return Caution.read(JSON.readTree(answer));
    }

    @Test
    void onlyAnExplicitThreatStatusOrAJsonTrueCounts() throws Exception {
        assertThat(read("{\"status\":\"threat\"}")).containsExactly(Caution.THREAT);
        assertThat(read("{\"status\":\" Threat \"}")).containsExactly(Caution.THREAT);
        assertThat(read("{\"secretRequest\":true}")).containsExactly(Caution.SECRET_REQUEST);
        assertThat(read("{\"secretShare\":true}")).containsExactly(Caution.SECRET_SHARE);
        assertThat(read("{\"status\":\"threat\",\"secretRequest\":true,\"secretShare\":true}"))
                .containsExactlyInAnyOrder(Caution.THREAT, Caution.SECRET_REQUEST, Caution.SECRET_SHARE);
        for (String unsure : new String[] {"{\"status\":\"none\"}", "{\"status\":\"unclear\"}", "{\"status\":null}", "{}",
                "{\"status\":\"maybe\",\"secretRequest\":false,\"secretShare\":false}", "{\"secretRequest\":\"true\"}",
                "{\"secretShare\":\"yes\"}", "{\"secretShare\":1}", "{\"secretRequest\":null}", "{\"secretShare\":[true]}",
                "{\"status\":{\"x\":\"threat\"}}"}) {
            assertThat(read(unsure)).as(unsure).isEmpty();
        }
    }

    @Test
    void eachCautionGoesOnlyToTheRoleThatCouldBeHarmed() {
        assertThat(Caution.THREAT.recipient(Role.BUYER)).isEqualTo(Role.FARMER);
        assertThat(Caution.THREAT.recipient(Role.FARMER)).isEqualTo(Role.BUYER);
        assertThat(Caution.SECRET_REQUEST.recipient(Role.BUYER)).isEqualTo(Role.FARMER);
        assertThat(Caution.SECRET_REQUEST.recipient(Role.FARMER)).isEqualTo(Role.BUYER);
        assertThat(Caution.SECRET_SHARE.recipient(Role.BUYER)).isEqualTo(Role.BUYER);
        assertThat(Caution.SECRET_SHARE.recipient(Role.FARMER)).isEqualTo(Role.FARMER);
    }

    @Test
    void theFarmersThreatCautionKeepsItsOriginalWording() {
        assertThat(Caution.THREAT.text(Role.BUYER)).isEqualTo("Caution: the buyer's last words sounded like a threat or "
                + "intimidation meant to pressure you in this negotiation. You do not have to agree to anything because "
                + "of pressure or a threat. Stay calm, say you need time to decide, and do not agree to anything now. If "
                + "you feel unsafe, step away and ask a trusted person, your farmer group or the mandi officials for help. "
                + "This app cannot stop the buyer or check what they can actually do.");
    }

    @Test
    void wordingIsFixedPromisesNothingAndCarriesNoSpokenContent() {
        for (Caution c : Caution.values()) {
            for (Role speaker : Role.values()) {
                String text = c.text(speaker);
                assertThat(text).startsWith("Caution:").as(c + "/" + speaker)
                        .doesNotContain("%").doesNotContain("safe.").doesNotContain("blocked").doesNotContain("verified")
                        .doesNotContain("protect");
                assertThat(text).contains("This app cannot");
            }
        }
        assertThat(Caution.SECRET_REQUEST.text(Role.FARMER)).contains("the farmer's last words")
                .contains("an OTP or one-time code, a PIN, a password, a card CVV or a full Aadhaar number");
        // Receiving money needs only a UPI ID or account number; those are never named as secrets.
        for (Caution c : Caution.values()) {
            assertThat(c.text(Role.BUYER)).doesNotContain("UPI").doesNotContain("account number").doesNotContain("IFSC");
        }
    }
}
