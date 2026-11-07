package com.example.g_one.advisory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Comparison logic is plain code over the bundled dated sample snapshot (Red onion, Lasalgaon, 4500/quintal). */
class AdvisoryServiceTest {
    private final AdvisoryService svc = new AdvisoryService(null, null, null, null, null, null);
    private final MandiSnapshot sample = MandiSnapshot.load(new ObjectMapper().findAndRegisterModules(), "");
    private static final MandiSnapshot RICE = new MandiSnapshot("AGMARKNET", "https://example.test/rice", "2026-11-06",
            LocalDate.of(2026, 11, 4), "Rice", "Common", "Burdwan APMC", new BigDecimal("3400.0"), "INR", "quintal");

    private String compose(AdvisoryService.Offer o) {
        return svc.compose(o, sample);
    }

    private static AdvisoryService.Offer offer(String status, String commodity, String variety, String market,
                                               String price, String currency, String unit) {
        return new AdvisoryService.Offer(status, commodity, variety, market,
                price == null ? null : new BigDecimal(price), currency, unit);
    }

    @Test
    void comparesMatchingOfferAndStatesDatedReference() {
        String text = compose(offer("clear", "Onions", "red", null, "4000", "INR", "Quintals"));
        assertThat(text).contains("AGMARKNET listed ₹4,500 per quintal for Red onion at Lasalgaon on 4 November 2026")
                .contains("dated reference, not today's rate").contains("₹4,000").contains("11% below");
    }

    @Test
    void sayingAboveAndSameAreWorded() {
        assertThat(compose(offer("clear", "onion", null, null, "5000", "INR", "qtl"))).contains("11% above");
        assertThat(compose(offer("clear", "onion", null, null, "4500", "INR", "quintal"))).contains("about the same as");
    }

    @Test
    void mismatchedOrUnclearOffersGetNoNumbers() {
        String unavailable = "A reliable comparison with the market reference is unavailable for this offer.";
        assertThat(compose(offer("clear", "onion", null, null, "40", "INR", "kg"))).isEqualTo(unavailable);
        assertThat(compose(offer("clear", "wheat", null, null, "4000", "INR", "quintal"))).isEqualTo(unavailable);
        assertThat(compose(offer("clear", "onion", "white", null, "4000", "INR", "quintal"))).isEqualTo(unavailable);
        assertThat(compose(offer("clear", "onion", null, "Pune", "4000", "INR", "quintal"))).isEqualTo(unavailable);
        assertThat(compose(offer("clear", "onion", null, null, "4000", "USD", "quintal"))).isEqualTo(unavailable);
        assertThat(compose(offer("unclear", "onion", null, null, "4000", "INR", "quintal"))).isEqualTo(unavailable);
        assertThat(compose(offer("clear", "onion", null, null, null, "INR", "quintal"))).isEqualTo(unavailable);
    }

    @Test
    void noOfferMeansNoAdvice() {
        assertThat(compose(offer("none", null, null, null, null, null, null))).isNull();
    }

    @Test
    void onlyAnExplicitThreatStatusTriggersTheWarning() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(AdvisoryService.isThreat(json.readTree("{\"status\":\"threat\"}"))).isTrue();
        assertThat(AdvisoryService.isThreat(json.readTree("{\"status\":\" Threat \"}"))).isTrue();
        assertThat(AdvisoryService.isThreat(json.readTree("{\"status\":\"none\"}"))).isFalse();
        assertThat(AdvisoryService.isThreat(json.readTree("{\"status\":\"unclear\"}"))).isFalse();
        assertThat(AdvisoryService.isThreat(json.readTree("{\"status\":null}"))).isFalse();
        assertThat(AdvisoryService.isThreat(json.readTree("{}"))).isFalse();
    }

    @Test
    void riceOfferIsComparedWithTheConfiguredVarietyAndNamesIt() {
        String text = svc.compose(offer("clear", "rice", "Common", "Burdwan mandi", "3000", "INR", "quintal"), RICE);
        assertThat(text).contains("AGMARKNET listed ₹3,400 per quintal for Common rice at Burdwan APMC on 4 November 2026")
                .contains("dated reference, not today's rate").contains("₹3,000").contains("12% below");
        // No variety stated: still compared, but the message names the variety the reference is for.
        assertThat(svc.compose(offer("clear", "Rice", null, null, "3400", "INR", "qtl"), RICE))
                .contains("for Common rice").contains("about the same as");
    }

    @Test
    void riceOfferForAnotherVarietyMarketOrUnitGetsNoNumbers() {
        String unavailable = "A reliable comparison with the market reference is unavailable for this offer.";
        assertThat(svc.compose(offer("clear", "rice", "Fine", null, "3800", "INR", "quintal"), RICE)).isEqualTo(unavailable);
        assertThat(svc.compose(offer("clear", "rice", null, "Bardhaman", "3400", "INR", "quintal"), RICE)).isEqualTo(unavailable);
        assertThat(svc.compose(offer("clear", "rice", null, null, "34", "INR", "kg"), RICE)).isEqualTo(unavailable);
        assertThat(svc.compose(offer("clear", "paddy", null, null, "3400", "INR", "quintal"), RICE)).isEqualTo(unavailable);
    }

    @Test
    void noReferencePriceMeansUnavailableNeverAGuess() {
        assertThat(svc.compose(offer("clear", "rice", null, null, "3400", "INR", "quintal"), null))
                .isEqualTo("A reliable comparison with the market reference is unavailable for this offer.");
        assertThat(svc.compose(offer("none", null, null, null, null, null, null), null)).isNull();
    }

    @Test
    void anOfferThatCannotMatchNeverTriggersTheReferenceLookup() {
        MandiSource mandi = mock(MandiSource.class);
        AdvisoryService withMandi = new AdvisoryService(mandi, null, null, null, null, null);
        for (AdvisoryService.Offer cannotMatch : new AdvisoryService.Offer[] {
                offer("unclear", "rice", null, null, "3400", "INR", "quintal"),
                offer("none", null, null, null, null, null, null),
                offer("clear", "rice", null, null, null, "INR", "quintal"),     // no price
                offer("clear", "rice", null, null, "0", "INR", "quintal"),
                offer("clear", null, null, null, "3400", "INR", "quintal"),     // no commodity
                offer("clear", "rice", null, null, "3400", "USD", "quintal"),   // not rupees
                offer("clear", "rice", null, null, "3400", null, "quintal"),
                offer("clear", "rice", null, null, "3400", "INR", null)}) {      // no unit
            assertThat(withMandi.referenceFor(cannotMatch)).isEmpty();
        }
        verifyNoInteractions(mandi);
    }

    @Test
    void aCompleteClearOfferStillLooksUpTheReference() {
        MandiSource mandi = mock(MandiSource.class);
        when(mandi.current()).thenReturn(Optional.of(RICE));
        AdvisoryService withMandi = new AdvisoryService(mandi, null, null, null, null, null);
        assertThat(withMandi.referenceFor(offer("clear", "rice", null, null, "3400", "INR", "quintal"))).contains(RICE);
        verify(mandi).current();
    }
}
