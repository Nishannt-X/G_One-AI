package com.example.g_one.advisory;

import com.example.g_one.room.Role;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The private cautions a spoken turn can earn, who each one is for, and its fixed wording. The classifier only says
 * which kinds apply; it never writes, quotes or repeats anything, and nothing a person said is copied into a caution,
 * so a secret that was spoken cannot be echoed back.
 *
 * Supported secrets are deliberately narrow: an OTP or one-time code, a PIN, a password, a card CVV and a full Aadhaar
 * number. Details needed to receive money (a UPI ID, a bank account number, an IFSC code) are not secrets here.
 */
public enum Caution {
    /** The speaker threatens or pressures the listener. For the listener. */
    THREAT,
    /** The speaker asks the listener for a supported secret. For the listener. */
    SECRET_REQUEST,
    /** The speaker says a supported secret of their own aloud. For the speaker, who is the one at risk. */
    SECRET_SHARE;

    static final String SECRETS = "an OTP or one-time code, a PIN, a password, a card CVV or a full Aadhaar number";

    /** Fixed wording the farmer has always been shown for a threat; the buyer's version differs only in who is named. */
    private static final String THREAT_TEXT = "Caution: the %s's last words sounded like a threat or intimidation "
            + "meant to pressure you in this negotiation. You do not have to agree to anything because of pressure or "
            + "a threat. Stay calm, say you need time to decide, and do not agree to anything now. If you feel unsafe, "
            + "step away and ask %s for help. This app cannot stop the %s or check what they "
            + "can actually do.";

    private static final String REQUEST_TEXT = "Caution: the %s's last words asked you for private security "
            + "information, such as " + SECRETS + ". Do not share these with anyone on this call. A normal sale or "
            + "payment does not need you to say them aloud. If you already did, contact your bank or the service that "
            + "issued it right away. This app cannot check who the %s is or stop them.";

    private static final String SHARE_TEXT = "Caution: your last words seemed to include a private security detail, "
            + "such as " + SECRETS + ". The other person was sent a translation of what you said, so assume they have "
            + "it. Do not say more of it. If it was real, contact your bank or the service that issued it now to block "
            + "or change it. This app cannot take back what was already said.";

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Only the role that could be harmed is ever told. */
    public Role recipient(Role speaker) {
        return this == SECRET_SHARE ? speaker : speaker.other();
    }

    /** English wording for the recipient, before it is translated into their language. */
    public String text(Role speaker) {
        String other = speaker.wire();
        return switch (this) {
            case THREAT -> THREAT_TEXT.formatted(other, speaker == Role.BUYER
                    ? "a trusted person, your farmer group or the mandi officials"
                    : "a trusted person or the mandi officials", other);
            case SECRET_REQUEST -> REQUEST_TEXT.formatted(other, other);
            case SECRET_SHARE -> SHARE_TEXT;
        };
    }

    /**
     * Reads the classifier's answer. A kind counts only on an explicit threat status or an explicit JSON {@code true};
     * a missing, unknown, text or otherwise unusual value means "no", so uncertainty never produces a caution. Each
     * kind appears at most once, so one turn never yields the same caution twice.
     */
    public static Set<Caution> read(JsonNode answer) {
        Set<Caution> found = EnumSet.noneOf(Caution.class);
        if ("threat".equalsIgnoreCase(answer.path("status").asText("").trim())) found.add(THREAT);
        if (answer.path("secretRequest").isBoolean() && answer.path("secretRequest").asBoolean()) found.add(SECRET_REQUEST);
        if (answer.path("secretShare").isBoolean() && answer.path("secretShare").asBoolean()) found.add(SECRET_SHARE);
        return found;
    }
}
