package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SecretScrubberTest {
    private static final String X = SecretScrubber.REMOVED;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        // Secrets: OTPs, PINs, phone, account and Aadhaar numbers, spoken in groups or glued to words.
        "my OTP is 482913                       | my OTP is " + X,
        "OTP 4829 13 please                     | OTP " + X + " please",
        "call +91 98765 43210 now               | call +" + X + " now",
        "account 123456789012.                  | account " + X + ".",
        "aadhaar 1234-5678-9012                 | aadhaar " + X,
        "OTP482913                              | OTP" + X,
        "pin 12345678 then                      | pin " + X + " then",
        "date 20261340 is not a date            | date " + X + " is not a date",
        // Kept: quantities, prices, dates and short numbers.
        "agreed 6 quintal at Rs 1,200           | agreed 6 quintal at Rs 1,200",
        "Rs 120000 for the lot                  | Rs 120000 for the lot",
        "₹250000 total                          | ₹250000 total",
        "price 600000 rupees                    | price 600000 rupees",
        "1200000 per tonne                      | 1200000 per tonne",
        "sold 100000 kg                         | sold 100000 kg",
        "100000kg of onion                      | 100000kg of onion",
        "1,20,000 rupees and about 1.5 lakh     | 1,20,000 rupees and about 1.5 lakh",
        "harvest on 2026-11-08                  | harvest on 2026-11-08",
        "harvest on 08-11-2026                  | harvest on 08-11-2026",
        "harvest on 20261108                    | harvest on 20261108",
        "call c-1103 about 20 quintal           | call c-1103 about 20 quintal",
        "rate 12345.75 per quintal              | rate 12345.75 per quintal",
    })
    void scrubsOnlySecretLookingRuns(String input, String expected) {
        assertThat(SecretScrubber.scrub(input)).isEqualTo(expected);
        assertThat(SecretScrubber.hasSecret(input)).isEqualTo(!input.equals(expected));
    }
}
