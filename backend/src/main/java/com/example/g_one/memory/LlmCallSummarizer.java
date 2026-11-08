package com.example.g_one.memory;

import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.speech.LlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link CallSummarizer} backed by the configured OpenAI-compatible LLM ({@code G_ONE_LLM_*}, through
 * {@link LlmClient}). One request per call, JSON in and out, in the shape docs/memory.md "Write timing" gives.
 */
public final class LlmCallSummarizer implements CallSummarizer {
    static final String STAGE = "gist_failed";
    static final String SYSTEM = """
            You write the call memory for G-One, a phone assistant that helps Indian farmers sell produce.
            Reply with ONE JSON object and nothing else:
            {"gist": string,
             "followUps": [{"text": string, "dueHint": string or null, "status": "OPEN" or "DONE"}],
             "profileUpdates": [{"field": string, "value": string}]}
            Rules:
            - gist: at most 80 words, third person, plain English. Say what the farmer asked, what happened (prices
              quoted as dated references, buyer connected or not, deal terms both sides said yes to) and what is
              still open. Only facts the transcript states. Never call anything verified, binding or a live price.
            - Never write phone numbers, OTPs, PINs, account, Aadhaar or other ID numbers anywhere.
            - followUps: things G-One should do on a later call. Mark an earlier open follow-up DONE when this call
              completed it.
            - profileUpdates: only stable facts about the FARMER (never the buyer). Allowed fields: name, village,
              district, state, crops (one entry per crop), usualMandi, language (one of HI, EN, HI_EN, BN). Leave a
              field out when unsure.
            """;

    private final LlmClient llm;
    private final ObjectMapper json;

    public LlmCallSummarizer(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    @Override
    public boolean available() {
        return llm.configured();
    }

    @Override
    public Draft summarize(CallTranscript transcript, FarmerMemory current) {
        JsonNode answer = llm.askJson(STAGE, SYSTEM, userMessage(transcript, current));
        return parse(answer);
    }

    String userMessage(CallTranscript transcript, FarmerMemory current) {
        ObjectNode root = json.createObjectNode();
        ArrayNode profile = root.putArray("profile");
        for (ProfileFact f : current.facts()) {
            profile.addObject().put("field", f.field()).put("value", f.value())
                    .put("source", f.provenance().saidOrInferred().wire());
        }
        ArrayNode open = root.putArray("openFollowUps");
        current.gists().stream().limit(3).flatMap(g -> g.followUps().stream()).forEach(open::add);
        ArrayNode lines = root.putArray("transcript");
        for (CallTranscript.Line line : transcript.lines()) {
            lines.addObject().put("speaker", line.speaker().name().toLowerCase(Locale.ROOT)).put("text", line.text());
        }
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode the gist request", e);
        }
    }

    static Draft parse(JsonNode answer) {
        String gist = answer.path("gist").asText("");
        List<FollowUp> followUps = new ArrayList<>();
        for (JsonNode f : answer.path("followUps")) {
            if (f.isTextual()) {
                followUps.add(new FollowUp(f.asText(), null, true));
            } else if (f.isObject()) {
                String due = f.path("dueHint").isTextual() ? f.path("dueHint").asText() : null;
                boolean open = !"DONE".equalsIgnoreCase(f.path("status").asText("OPEN"));
                followUps.add(new FollowUp(f.path("text").asText(""), due, open));
            }
        }
        List<FactUpdate> updates = new ArrayList<>();
        for (JsonNode u : answer.path("profileUpdates")) {
            if (u.path("field").isTextual() && u.path("value").isTextual()) {
                updates.add(new FactUpdate(u.path("field").asText(), u.path("value").asText()));
            }
        }
        return new Draft(gist, followUps, updates);
    }
}
