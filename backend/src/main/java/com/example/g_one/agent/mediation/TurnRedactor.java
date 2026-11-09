package com.example.g_one.agent.mediation;

import com.example.g_one.memory.SecretScrubber;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from a mediated turn before anything else sees it: the console, the caution classifier, the
 * translator, the price read-out and the other leg's voice. It runs {@link SecretScrubber} (labelled OTP, PIN, CVV,
 * card and Aadhaar numbers, phone numbers, long digit runs) and then one more rule for speech: a code spelled out as
 * digit words right after an OTP, PIN, CVV or password label ({@code my OTP is four eight two nine},
 * {@code mera PIN chaar aath do nau}, {@code ओटीपी चार आठ दो नौ}) is removed too, since speech-to-text often writes
 * digits as words.
 */
final class TurnRedactor {
    /** The text with secrets replaced by {@link SecretScrubber#REMOVED}; {@code changed} when anything was removed. */
    record Redacted(String text, boolean changed) {}

    private static final String NOT_IN_WORD = "(?![\\p{L}\\p{M}])";
    private static final String LABEL = "(?:upi[ \\-]?pin|atm[ \\-]?pin|m[ \\-]?pin|pin|o\\.?t\\.?p\\.?"
            + "|one[ \\-]?time[ \\-]?(?:password|passcode|code|pin)|pass[ \\-]?(?:word|code)|cvv|cvc"
            + "|यूपीआई\\s?पिन|एटीएम\\s?पिन|पिन|ओ\\s?टी\\s?पी|ओटीपी|पासवर्ड|सीवीवी)";
    private static final String FILLER = "(?:[\\s:=#,\\-]*(?:is|was|hai|he|h|ka|ki|ke|number|code|mera|meri|my|your"
            + "|aapka|apka|the|नंबर|नम्बर|है|का|की|के|मेरा|मेरी|कोड|आपका)" + NOT_IN_WORD + ")*[\\s:=#,\\-]*";
    private static final String DIGIT = "(?:\\d|(?:zero|oh|one|two|three|four|five|six|seven|eight|nine"
            + "|shunya|ek|do|teen|char|chaar|panch|paanch|chhah|chhe|saat|aath|nau"
            + "|शून्य|एक|दो|तीन|चार|पांच|पाँच|छह|छः|सात|आठ|नौ)" + NOT_IN_WORD + ")";
    /** Three or more digits or digit words in a row. */
    private static final String RUN = DIGIT + "(?:[\\s,.\\-]*" + DIGIT + "){2,}";
    private static final Pattern SPELLED_AFTER_LABEL = Pattern.compile(
            "(?<![\\p{L}\\p{M}])(" + LABEL + NOT_IN_WORD + FILLER + ")" + RUN + "(?![\\p{L}\\p{M}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private TurnRedactor() {}

    static Redacted redact(String text) {
        String scrubbed = SecretScrubber.scrub(text);
        Matcher m = SPELLED_AFTER_LABEL.matcher(scrubbed);
        StringBuilder out = new StringBuilder(scrubbed.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + SecretScrubber.REMOVED));
        }
        m.appendTail(out);
        String result = out.toString();
        return new Redacted(result, !result.equals(text));
    }
}
