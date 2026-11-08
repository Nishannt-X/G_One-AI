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
        "call +91 98765 43210 now               | call " + X + " now",
        "account 123456789012.                  | account " + X + ".",
        "aadhaar 1234-5678-9012                 | aadhaar " + X,
        "OTP482913                              | OTP" + X,
        "pin 12345678 then                      | pin " + X + " then",
        "date 20261340 is not a date            | date " + X + " is not a date",
        // Sensitive labels (English, Hinglish, Devanagari): even a short number after them goes.
        "my PIN is 4829                         | my PIN is " + X,
        "UPI PIN 1234 hai                       | UPI PIN " + X + " hai",
        "mera pin hai 4829                      | mera pin hai " + X,
        "atm pin: 9988                          | atm pin: " + X,
        "4829 is my OTP                         | " + X + " is my OTP",
        "4829 mera otp hai                      | " + X + " mera otp hai",
        "o.t.p. 7341 aaya                       | o.t.p. " + X + " aaya",
        "password 9988                          | password " + X,
        "CVV 123                                | CVV " + X,
        "मेरा ओटीपी 4829 है                     | मेरा ओटीपी " + X + " है",
        "पिन 1234                               | पिन " + X,
        "पासवर्ड है 5566                        | पासवर्ड है " + X,
        "आधार नंबर 1234 5678 9012               | आधार नंबर " + X,
        "aadhar no. 1234 5678 9012              | aadhar no. " + X,
        "OTP 482913 per quintal                 | OTP " + X + " per quintal",
        "Rs 4829 pin                            | Rs " + X + " pin",
        // Grouped identifiers, however they are separated, and never exempted by price or unit context.
        "card 4111,1111,1111,1111               | card " + X,
        "card no. 4111.1111.1111.1111           | card no. " + X,
        "4111.1111.1111.1111 hai                | " + X + " hai",
        "4111,1111,1111,1111                    | " + X,
        "4111 1111 1111 1111                    | " + X,
        "1234 5678 9012                         | " + X,
        "1234.5678.9012                         | " + X,
        "1234-5678-9012 kg                      | " + X + " kg",
        "9876543210                             | " + X,
        "+919876543210 pe                       | " + X + " pe",
        "98765, 43210 pe call karo              | " + X + " pe call karo",
        "0 98765-43210                          | " + X,
        "Rs 9876543210                          | Rs " + X,
        "₹ 98765 43210                          | ₹ " + X,
        "9876543210 rupees                      | " + X + " rupees",
        "9876543210 kg                          | " + X + " kg",
        "98765.43210 per quintal                | " + X + " per quintal",
        // Kept: quantities, prices, dates and short numbers.
        "OTP 2 minute mein aayega               | OTP 2 minute mein aayega",
        "price 4829 per quintal                 | price 4829 per quintal",
        "Kisan credit card loan of Rs 50000     | Kisan credit card loan of Rs 50000",
        "sold 20 quintal at 2,450 rupees        | sold 20 quintal at 2,450 rupees",
        "1,20,00,000 rupees total               | 1,20,00,000 rupees total",
        "Rs 123456789 for the season            | Rs 123456789 for the season",
        "seasons 2024 and 2025, 2026            | seasons 2024 and 2025, 2026",
        "pickup 08.11.2026 at 10.30             | pickup 08.11.2026 at 10.30",
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
