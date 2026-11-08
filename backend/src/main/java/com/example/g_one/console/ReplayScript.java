package com.example.g_one.console;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.DealState;
import com.example.g_one.core.ConsoleEvent.WhisperKind;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * A synthetic onion sale, as console events with a delay before each, for building and filming the console without a
 * phone call. Every name, number and price here is invented.
 */
final class ReplayScript {
    /** An event to publish {@code delayMs} after the previous one, built with the time it is published at. */
    record Step(long delayMs, LongFunction<ConsoleEvent> event) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HI = "hi-IN";
    private static final String EN = "en-IN";
    /** Synthetic key: 64 hex characters that are not the hash of any real number. */
    private static final String KEY = "5eed".repeat(16);

    private ReplayScript() {}

    static List<Step> sale(CallId call, long startMs) {
        LegId farmer = LegId.random();
        LegId buyer = LegId.random();
        List<Step> steps = new ArrayList<>();
        // Start times of the buyer turn [1] and the second farmer turn [2]; their translations point at these.
        long[] turn = new long[3];;

        steps.add(new Step(0, at -> new ConsoleEvent.CallStarted(call, at, HI)));
        steps.add(new Step(300, at -> new ConsoleEvent.LegJoined(call, at, farmer, LegRole.FARMER,
                LegSource.SIMULATOR, HI)));
        steps.add(new Step(500, at -> new ConsoleEvent.MemoryLoaded(call, at, CallerKey.of(KEY), true,
                List.of(new ProfileFact("district", "Nashik", Provenance.said(CallId.of("call-earlier-1"), startMs - 864_000_000L)),
                        new ProfileFact("main_crop", "onion", Provenance.said(CallId.of("call-earlier-1"), startMs - 864_000_000L)),
                        new ProfileFact("usual_buyer_type", "mandi trader", Provenance.inferred(CallId.of("call-earlier-2"), startMs - 259_200_000L))),
                List.of(new CallGist(CallId.of("call-earlier-2"), startMs - 259_200_000L, startMs - 258_900_000L,
                        "Asked the onion price at Lasalgaon; did not sell, wanted to wait for a better price.",
                        List.of("Check the price again before the next call"))))));
        steps.add(new Step(1200, at -> new ConsoleEvent.AssistantSaid(call, at, farmer, LegRole.FARMER, HI,
                "नमस्ते! पिछली बार आपने प्याज का भाव पूछा था। आज क्या बेचना है?")));
        steps.add(new Step(2500, at -> {
            return new ConsoleEvent.TurnHeard(call, at, farmer, LegRole.FARMER, HI,
                    "नमस्ते, आज मुझे प्याज बेचना है, करीब दस क्विंटल।", at - 3_000, at);
        }));
        steps.add(new Step(600, at -> new ConsoleEvent.ToolCalled(call, at, "mandi_price",
                JSON.createObjectNode().put("commodity", "onion").put("market", "Lasalgaon"), true,
                "Onion modal price 2150 rupees per quintal at Lasalgaon, sample snapshot dated 2026-11-06.", 420)));
        steps.add(new Step(900, at -> new ConsoleEvent.WhisperSent(call, at, farmer, WhisperKind.PRICE_REFERENCE, HI,
                "लासलगांव में प्याज का भाव करीब इक्कीस सौ पचास रुपये क्विंटल था। इससे कम पर जल्दी हाँ मत कहिए।")));
        steps.add(new Step(1200, at -> new ConsoleEvent.AssistantSaid(call, at, farmer, LegRole.FARMER, HI,
                "ठीक है, मैं एक खरीदार को लाइन पर लेता हूँ।")));
        steps.add(new Step(1500, at -> new ConsoleEvent.ToolCalled(call, at, "buyer_match",
                JSON.createObjectNode().put("commodity", "onion").put("quantity", "10 quintal"), true,
                "Matched buyer 'Sharma Traders' (sample directory).", 310)));
        steps.add(new Step(800, at -> new ConsoleEvent.LegJoined(call, at, buyer, LegRole.BUYER,
                LegSource.SIMULATOR, EN)));
        steps.add(new Step(1500, at -> new ConsoleEvent.AssistantSaid(call, at, buyer, LegRole.BUYER, EN,
                "Hello, a farmer from Nashik has ten quintals of onion to sell.")));
        steps.add(new Step(2500, at -> {
            turn[1] = at - 3_500;
            return new ConsoleEvent.TurnHeard(call, at, buyer, LegRole.BUYER, EN,
                    "Hello. I can take ten quintals at twenty two rupees a kilo.", turn[1], at);
        }));
        steps.add(new Step(700, at -> new ConsoleEvent.TurnTranslated(call, at, buyer, LegRole.BUYER,
                LegRole.FARMER, EN, HI, "नमस्ते। मैं बाईस रुपये किलो पर दस क्विंटल ले सकता हूँ।", turn[1])));
        steps.add(new Step(500, at -> new ConsoleEvent.DealUpdated(call, at, DealState.DRAFT, "onion", null,
                "Lasalgaon", "10 quintal", new BigDecimal("22"), "kg", null, false, false)));
        steps.add(new Step(900, at -> new ConsoleEvent.WhisperSent(call, at, farmer, WhisperKind.TIP, HI,
                "यह भाव सैंपल मंडी भाव के बराबर है। चाहें तो उठाने की जगह और दिन भी तय कर लीजिए।")));
        steps.add(new Step(2500, at -> {
            turn[2] = at - 4_000;
            return new ConsoleEvent.TurnHeard(call, at, farmer, LegRole.FARMER, HI,
                    "ठीक है, बाईस रुपये किलो पर हाँ। कल सुबह खेत से उठा लें।", turn[2], at);
        }));
        steps.add(new Step(700, at -> new ConsoleEvent.TurnTranslated(call, at, farmer, LegRole.FARMER,
                LegRole.BUYER, HI, EN, "Okay, yes at twenty two rupees a kilo. Please pick it up from the farm tomorrow morning.",
                turn[2])));
        steps.add(new Step(600, at -> new ConsoleEvent.DealUpdated(call, at, DealState.CONFIRMING, "onion", null,
                "Lasalgaon", "10 quintal", new BigDecimal("22"), "kg", "farm, tomorrow morning", true, false)));
        steps.add(new Step(1800, at -> new ConsoleEvent.TurnHeard(call, at, buyer, LegRole.BUYER, EN,
                "Yes, agreed. We will come tomorrow morning.", at - 2_500, at)));
        steps.add(new Step(500, at -> new ConsoleEvent.DealUpdated(call, at, DealState.AGREED, "onion", null,
                "Lasalgaon", "10 quintal", new BigDecimal("22"), "kg", "farm, tomorrow morning", true, true)));
        steps.add(new Step(1500, at -> new ConsoleEvent.LegEnded(call, at, buyer, LegRole.BUYER, "hangup")));
        steps.add(new Step(800, at -> new ConsoleEvent.LegEnded(call, at, farmer, LegRole.FARMER, "hangup")));
        steps.add(new Step(200, at -> new ConsoleEvent.CallEnded(call, at, at - startMs, "hangup")));
        return List.copyOf(steps);
    }
}
