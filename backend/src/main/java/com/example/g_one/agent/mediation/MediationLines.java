package com.example.g_one.agent.mediation;

import com.example.g_one.core.Language;
import java.util.Locale;

/**
 * Fixed lines the mediator says itself (docs/call-flow.md, "Exact first lines"). Hindi for Hindi listeners, English for
 * everyone else.
 */
final class MediationLines {
    static final String HINDI_WHISPER_OPENING = "यह सिर्फ़ आप सुन रहे हैं: ";
    static final String ENGLISH_WHISPER_OPENING = "Only you can hear this: ";

    static final String HINDI_HOLD = "एक पल रुकिए।";
    static final String ENGLISH_HOLD = "One moment please.";

    static final String HINDI_NOT_TRANSLATED = "माफ़ कीजिए, मैं इसका अनुवाद नहीं कर पाई। कृपया फिर से बोलिए।";
    static final String ENGLISH_NOT_TRANSLATED = "Sorry, I could not translate that. Please say it again.";

    private MediationLines() {}

    static boolean hindi(Language language) {
        return language.code().toLowerCase(Locale.ROOT).startsWith("hi-in");
    }

    /** Every whisper starts with this, so the farmer knows the buyer cannot hear it. */
    static String whisperOpening(Language language) {
        return hindi(language) ? HINDI_WHISPER_OPENING : ENGLISH_WHISPER_OPENING;
    }

    /** What the buyer hears, once, while the farmer hears a whisper. */
    static String hold(Language language) {
        return hindi(language) ? HINDI_HOLD : ENGLISH_HOLD;
    }

    /** Said to the speaker when their turn could not be translated, so they are never left wondering. */
    static String notTranslated(Language language) {
        return hindi(language) ? HINDI_NOT_TRANSLATED : ENGLISH_NOT_TRANSLATED;
    }
}
