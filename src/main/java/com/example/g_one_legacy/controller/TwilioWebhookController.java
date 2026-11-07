package com.example.g_one_legacy.controller;

import com.example.g_one_legacy.entity.Organization;
import com.example.g_one_legacy.repository.TwilioNumberRepository;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/voice")
public class TwilioWebhookController {

    @Value("${TWILIO_ACCOUNT_SID}")
    private String accountSid;

    @Value("${TWILIO_AUTH_TOKEN}")
    private String authToken;

    // ✅ UPDATED: Your live Ngrok WebSocket URL is injected here
    private static final String STREAM_TWIML =
            "<Response><Connect><Stream url=\"wss://ungushing-tabatha-unprimly.ngrok-free.dev/api/voice/stream\" /></Connect></Response>";

    private static final String NOT_RECOGNIZED_TWIML =
            "<Response><Say>Number not recognized</Say></Response>";

    private final TwilioNumberRepository twilioNumberRepository;

    public TwilioWebhookController(TwilioNumberRepository twilioNumberRepository) {
        this.twilioNumberRepository = twilioNumberRepository;
    }

    @PostMapping(value = "/incoming", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> incomingCall(@RequestParam("To") String toNumber) {
        String normalizedToNumber = toNumber == null ? "" : toNumber.trim();

        // Checks if the Twilio number you bought exists in your MySQL database
        boolean organizationFound = twilioNumberRepository.findByPhoneNumber(normalizedToNumber)
                .map(twilioNumber -> twilioNumber.getOrganization())
                .map(Organization::getId)
                .isPresent();

        String twiml = organizationFound ? STREAM_TWIML : NOT_RECOGNIZED_TWIML;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(twiml);
    }

    @GetMapping("/start-outbound")
    public ResponseEntity<String> startOutboundCall() {
        Twilio.init(accountSid, authToken);

        String twimlString = "<Response><Connect><Stream url=\"wss://ungushing-tabatha-unprimly.ngrok-free.dev/api/voice/stream\" /></Connect></Response>";

        Call call = Call.creator(
                        new com.twilio.type.PhoneNumber("+917207658298"),
                        new com.twilio.type.PhoneNumber("+12603083509"),
                        new com.twilio.type.Twiml(twimlString))
                .create();

        return ResponseEntity.ok("Outbound Twilio call started successfully. Call SID: " + call.getSid());
    }

    public static class StartGOneRequest {
        public String speakerA_Phone;
        public String speakerB_Phone;
    }

    @PostMapping(value = {"/g-one/start", "/setu/start"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> startGOneCall(@org.springframework.web.bind.annotation.RequestBody StartGOneRequest request) {
        Twilio.init(accountSid, authToken);

        String nGrokDomain = "ungushing-tabatha-unprimly.ngrok-free.dev";
        String twilioNumber = "+12603083509"; // Using your existing from number

        String twimlA = "<Response><Connect><Stream url=\"wss://" + nGrokDomain + "/api/voice/stream/A\" /></Connect></Response>";
        String twimlB = "<Response><Connect><Stream url=\"wss://" + nGrokDomain + "/api/voice/stream/B\" /></Connect></Response>";

        Call callA = null;
        Call callB = null;

        try {
            callA = Call.creator(
                    new com.twilio.type.PhoneNumber(request.speakerA_Phone),
                    new com.twilio.type.PhoneNumber(twilioNumber),
                    new com.twilio.type.Twiml(twimlA))
                    .create();
        } catch (Exception e) {
            System.err.println("Error calling speaker A: " + e.getMessage());
        }

        try {
            callB = Call.creator(
                    new com.twilio.type.PhoneNumber(request.speakerB_Phone),
                    new com.twilio.type.PhoneNumber(twilioNumber),
                    new com.twilio.type.Twiml(twimlB))
                    .create();
        } catch (Exception e) {
            System.err.println("Error calling speaker B: " + e.getMessage());
        }

        return ResponseEntity.ok("Dual outbound calls started.\nCall A SID: " + (callA != null ? callA.getSid() : "FAILED") + "\nCall B SID: " + (callB != null ? callB.getSid() : "FAILED"));
    }

    @GetMapping(value = {"/g-one/start-browser", "/setu/start-browser"})
    public ResponseEntity<String> startGOneCallBrowser(
            @RequestParam("speakerA") String speakerA,
            @RequestParam("speakerB") String speakerB) {
        Twilio.init(accountSid, authToken);

        String nGrokDomain = "ungushing-tabatha-unprimly.ngrok-free.dev";
        String twilioNumber = "+12603083509"; // Using your existing from number

        // Ensure numbers have plus sign if they start with 91 but are passed without it via URL encoding
        if (!speakerA.startsWith("+")) speakerA = "+" + speakerA.trim();
        if (!speakerB.startsWith("+")) speakerB = "+" + speakerB.trim();

        // Adding a short <Pause> keeps Twilio from hanging up the call while waiting for the stream to establish
        String twimlA = "<Response><Connect><Stream url=\"wss://" + nGrokDomain + "/api/voice/stream/A\" /></Connect><Pause length=\"86400\"/></Response>";
        String twimlB = "<Response><Connect><Stream url=\"wss://" + nGrokDomain + "/api/voice/stream/B\" /></Connect><Pause length=\"86400\"/></Response>";

        Call callA = null;
        Call callB = null;
        String errorA = "";
        String errorB = "";

        try {
            callA = Call.creator(
                    new com.twilio.type.PhoneNumber(speakerA),
                    new com.twilio.type.PhoneNumber(twilioNumber),
                    new com.twilio.type.Twiml(twimlA))
                    .create();
        } catch (Exception e) {
            errorA = e.getMessage();
            System.err.println("Error calling speaker A: " + e.getMessage());
        }

        try {
            callB = Call.creator(
                    new com.twilio.type.PhoneNumber(speakerB),
                    new com.twilio.type.PhoneNumber(twilioNumber),
                    new com.twilio.type.Twiml(twimlB))
                    .create();
        } catch (Exception e) {
            errorB = e.getMessage();
            System.err.println("Error calling speaker B: " + e.getMessage());
        }

        String responseMsg = "Dual outbound calls started from Browser.\n" +
                             "Call A SID: " + (callA != null ? callA.getSid() : "FAILED (" + errorA + ")") + "\n" +
                             "Call B SID: " + (callB != null ? callB.getSid() : "FAILED (" + errorB + ")");

        return ResponseEntity.ok(responseMsg);
    }
}