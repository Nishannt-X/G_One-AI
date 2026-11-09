package com.example.g_one.agent.mediation;

import com.example.g_one.agent.caution.Caution;
import com.example.g_one.core.Language;
import java.util.Locale;

/**
 * Fixed lines the mediator says itself (docs/call-flow.md, "Exact first lines"). Hindi for Hindi listeners, English for
 * everyone else. None of them is ever sent through the translation model.
 *
 * <p>The call-flow lines name the farmer, the crop and the buyer ("Mehta ji line par hain"). The mediator does not get
 * those details yet (there is no buyer directory in the backend), so the buyer introduction and the "buyer joined" line
 * use generic words in the same shape.
 */
final class MediationLines {
    static final String HINDI_WHISPER_OPENING = "यह सिर्फ़ आप सुन रहे हैं: ";
    static final String ENGLISH_WHISPER_OPENING = "Only you can hear this: ";

    static final String HINDI_HOLD = "एक पल रुकिए।";
    static final String ENGLISH_HOLD = "One moment please.";

    static final String HINDI_NOT_TRANSLATED = "माफ़ कीजिए, मैं इसका अनुवाद नहीं कर पाई। कृपया फिर से बोलिए।";
    static final String ENGLISH_NOT_TRANSLATED = "Sorry, I could not translate that. Please say it again.";

    /** Said to the speaker when their turn was withheld because it may hold a secret or could not be screened. */
    static final String HINDI_WITHHELD = "माफ़ कीजिए, यह बात मैं आगे नहीं बता सकती। कृपया PIN, OTP या पासवर्ड जैसी "
            + "निजी बातें छोड़कर फिर से बोलिए।";
    static final String ENGLISH_WITHHELD = "Sorry, I can't pass that on. Please say it again without private details "
            + "like a PIN, OTP or password.";

    /** Call-flow "Caution (fixed)": for a request for a PIN or OTP. */
    static final String HINDI_CAUTION_REQUEST = HINDI_WHISPER_OPENING
            + "पैसे लेने के लिए कभी PIN या OTP नहीं लगता। किसी को मत बताइए।";
    static final String ENGLISH_CAUTION_REQUEST = ENGLISH_WHISPER_OPENING
            + "receiving money never needs a PIN or OTP. Don't share it with anyone.";
    static final String HINDI_CAUTION_THREAT = HINDI_WHISPER_OPENING
            + "धमकी की वजह से किसी बात पर हाँ मत कहिए, सोचने का समय माँगिए।";
    static final String ENGLISH_CAUTION_THREAT = ENGLISH_WHISPER_OPENING
            + "don't agree to anything because of a threat; ask for time to decide.";
    static final String HINDI_CAUTION_SHARE = HINDI_WHISPER_OPENING
            + "अपना PIN या OTP कभी ज़ोर से मत बोलिए, इस कॉल पर भी नहीं।";
    static final String ENGLISH_CAUTION_SHARE = ENGLISH_WHISPER_OPENING
            + "never say your PIN or OTP aloud, not even on this call.";

    static final String HINDI_BUYER_INTRO = "नमस्ते, मैं जी-वन हूँ, एक AI सहायक। एक किसान अपनी फ़सल बेचना चाहते हैं। "
            + "मैं बीच में अनुवाद करूँगी। क्या अभी बात कर सकते हैं? हाँ या ना बोलिए।";
    static final String ENGLISH_BUYER_INTRO = "Hello, this is G-One, an AI assistant, calling for a farmer who wants "
            + "to sell their crop. They speak %s; I will translate between you. Is now a good time? Please say yes or no.";

    static final String HINDI_BUYER_JOINED = "ख़रीदार लाइन पर हैं। अब बोलिए, मैं उन्हें %s में बताऊँगी। "
            + "जवाब में कुछ सेकंड लगेंगे।";
    static final String ENGLISH_BUYER_JOINED = "The buyer is on the line. Go ahead; I'll tell them in %s. "
            + "Replies take a few seconds.";

    static final String HINDI_BUYER_APOLOGY = "माफ़ कीजिए, परेशान करने के लिए। नमस्ते।";
    static final String ENGLISH_BUYER_APOLOGY = "Sorry to have troubled you. Goodbye.";

    static final String HINDI_BUYER_DECLINED = "ख़रीदार ने अभी बात करने के लिए हाँ नहीं कहा, इसलिए मैंने उनका फ़ोन रख "
            + "दिया है।";
    static final String ENGLISH_BUYER_DECLINED = "The buyer did not say yes to talking now, so I have ended their call.";

    /** How a withheld number is said aloud when a turn is re-voiced in the same language. */
    static final String HINDI_REMOVED_SPOKEN = "नंबर हटाया गया";
    static final String ENGLISH_REMOVED_SPOKEN = "number removed";

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

    /** Said to the speaker when their turn was withheld; nothing of it reached the console or the other leg. */
    static String withheld(Language language) {
        return hindi(language) ? HINDI_WITHHELD : ENGLISH_WITHHELD;
    }

    /** The fixed one-sentence caution for the farmer, opening included. */
    static String caution(Caution caution, Language language) {
        boolean hi = hindi(language);
        return switch (caution) {
            case SECRET_REQUEST -> hi ? HINDI_CAUTION_REQUEST : ENGLISH_CAUTION_REQUEST;
            case THREAT -> hi ? HINDI_CAUTION_THREAT : ENGLISH_CAUTION_THREAT;
            case SECRET_SHARE -> hi ? HINDI_CAUTION_SHARE : ENGLISH_CAUTION_SHARE;
        };
    }

    /** What the buyer hears first: who is calling, why, that an AI translates, and a yes-or-no question. */
    static String buyerIntro(Language buyer, Language farmer) {
        return hindi(buyer) ? HINDI_BUYER_INTRO : ENGLISH_BUYER_INTRO.formatted(englishName(farmer));
    }

    /** What the farmer hears once the buyer said yes. */
    static String buyerJoined(Language farmer, Language buyer) {
        return hindi(farmer) ? HINDI_BUYER_JOINED.formatted(hindiName(buyer))
                : ENGLISH_BUYER_JOINED.formatted(englishName(buyer));
    }

    static String buyerApology(Language buyer) {
        return hindi(buyer) ? HINDI_BUYER_APOLOGY : ENGLISH_BUYER_APOLOGY;
    }

    static String buyerDeclined(Language farmer) {
        return hindi(farmer) ? HINDI_BUYER_DECLINED : ENGLISH_BUYER_DECLINED;
    }

    static String removedSpoken(Language language) {
        return hindi(language) ? HINDI_REMOVED_SPOKEN : ENGLISH_REMOVED_SPOKEN;
    }

    private static String hindiName(Language language) {
        String code = language.code().toLowerCase(Locale.ROOT);
        if (code.startsWith("en")) return "अंग्रेज़ी";
        if (code.startsWith("hi")) return "हिंदी";
        if (code.startsWith("bn")) return "बांग्ला";
        return language.label();
    }

    private static String englishName(Language language) {
        String code = language.code().toLowerCase(Locale.ROOT);
        if (code.startsWith("en")) return "English";
        if (code.startsWith("hi")) return "Hindi";
        if (code.startsWith("bn")) return "Bengali";
        return language.label();
    }
}
