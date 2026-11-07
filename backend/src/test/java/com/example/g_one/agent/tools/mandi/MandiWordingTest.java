package com.example.g_one.agent.tools.mandi;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The dated-reference wording, the crop/market recognition and the quote rules on top of MandiSource. */
class MandiWordingTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> BANNED = List.of(
            "today's price", "todays price", "today", "live rate", "live", "current rate", "fair price", "modal",
            "real time", "guaranteed");

    private static final MandiSnapshot ONION = new MandiSnapshot("AGMARKNET", "https://example.invalid", "2026-11-06",
            LocalDate.of(2026, 11, 4), "Onion", "Red", "Lasalgaon", new BigDecimal("4500"), "INR", "quintal");

    /** MandiSource in its default mode: the bundled dated sample (red onion at Lasalgaon, 4 Nov 2026). */
    private static MandiQuote sampleQuote() {
        GOneProperties.Agmarknet off = new GOneProperties.Agmarknet("https://api.agmarknet.gov.in", "", "", "", "", "", "", 5, 3, 30);
        MandiSource source = new MandiSource(new GOneProperties(List.of("a:A:v", "b:B:v"), "", off, null, null), JSON);
        return new MandiQuote(source, new MandiCatalog(JSON));
    }

    private static void assertHonest(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String b : BANNED) assertThat(lower).as(text).doesNotContain(b);
    }

    @Test
    void spokenWordingIsTheDatedReferenceWithNumbersAsWords() {
        String s = MandiWording.spoken(new MandiAnswer.Found(ONION, null));
        assertThat(s).isEqualTo("AGMARKNET listed four thousand five hundred rupees per quintal for Red onion at "
                + "Lasalgaon on the fourth of November, twenty twenty-six, a dated reference.");
        assertThat(s).doesNotContainPattern("[0-9₹]");
        assertHonest(s);
    }

    @Test
    void displayWordingKeepsDigitsAndRupeeSign() {
        String d = MandiWording.display(new MandiAnswer.Found(ONION, null));
        assertThat(d).isEqualTo("AGMARKNET listed ₹4,500 per quintal for Red onion at Lasalgaon on 4 Nov 2026, a dated reference.");
        assertHonest(d);
        assertThat(MandiWording.indianGrouping(150000)).isEqualTo("1,50,000");
        assertThat(MandiWording.indianGrouping(999)).isEqualTo("999");
        assertThat(MandiWording.indianGrouping(12345678)).isEqualTo("1,23,45,678");
    }

    @Test
    void fractionalPricesAreRoundedToWholeRupees() {
        MandiSnapshot rice = new MandiSnapshot("AGMARKNET", "u", "2026-11-06", LocalDate.of(2026, 11, 4), "Rice",
                "Common", "Burdwan APMC", new BigDecimal("3400.50"), "INR", "quintal");
        assertThat(MandiWording.spoken(new MandiAnswer.Found(rice, null)))
                .startsWith("AGMARKNET listed three thousand four hundred one rupees per quintal for Common rice at Burdwan APMC");
    }

    @Test
    void theFarmersWordsForTheCropFindTheSampleReference() {
        MandiQuote quote = sampleQuote();
        for (String said : List.of("onion", "Pyaz", "pyaaz", "kanda", "प्याज़", "प्याज", "পেঁয়াজ")) {
            MandiAnswer a = quote.quote(said, null);
            assertThat(a).as(said).isInstanceOfSatisfying(MandiAnswer.Found.class, f -> {
                assertThat(f.snapshot().commodity()).isEqualTo("Onion");
                assertThat(f.askedMarket()).isNull();
            });
        }
        assertThat(quote.quote("pyaz", "Lasalgaon APMC")).isInstanceOfSatisfying(MandiAnswer.Found.class,
                f -> assertThat(f.askedMarket()).isNull());
    }

    @Test
    void anotherCropNeverGetsTheOnionNumber() {
        MandiAnswer a = sampleQuote().quote("aloo", null);
        assertThat(a).isEqualTo(new MandiAnswer.NoReference("Potato", "Onion"));
        String s = MandiWording.spoken(a);
        assertThat(s).isEqualTo("There is no recent dated AGMARKNET reference for potato. "
                + "A dated reference is available only for onion.");
        assertThat(s).doesNotContainPattern("[0-9]").doesNotContain("thousand");
        assertHonest(s);

        assertThat(MandiWording.spoken(sampleQuote().quote("dragon fruit", null)))
                .startsWith("There is no recent dated AGMARKNET reference for dragon fruit.");
    }

    @Test
    void anotherMarketStillGetsTheReferenceLabelledWithItsOwnMarket() {
        MandiAnswer a = sampleQuote().quote("onion", "Pimpalgaon");
        String s = MandiWording.spoken(a);
        assertThat(s).contains("at Lasalgaon on").endsWith("There is no dated reference for Pimpalgaon.");
        assertHonest(s);
    }

    @Test
    void noTrustworthyReferenceAtAllIsSaidPlainly() {
        String s = MandiWording.spoken(new MandiAnswer.NoReference("Onion", null));
        assertThat(s).isEqualTo("There is no recent dated AGMARKNET reference for onion.");
        assertHonest(s);
    }

    @Test
    void catalogRecognisesMarketsAndListsWhatItKnows() {
        MandiCatalog c = new MandiCatalog(JSON);
        assertThat(c.market("Bardhaman")).contains("Burdwan APMC");
        assertThat(c.market("लासलगांव")).contains("Lasalgaon");
        assertThat(c.commodity("chawal")).contains("Rice");
        assertThat(c.commodity("nothing")).isEmpty();
        assertThat(c.commodities()).contains("Onion", "Rice");
    }
}
