package com.example.g_one;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agreement.SummarySender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Two real WebSocket "phones" against the running app, with Gnani and the LLM replaced by HTTP fakes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TwoPhoneFlowTest {
    private static final FakeUpstreams UP;

    static {
        try {
            UP = new FakeUpstreams();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("g-one.gnani.base-url", UP::baseUrl);
        r.add("g-one.gnani.api-key", () -> FakeUpstreams.KEY);
        r.add("g-one.llm.base-url", UP::baseUrl);
        r.add("g-one.llm.api-key", () -> FakeUpstreams.KEY);
        r.add("g-one.llm.model", () -> "test-model");
    }

    @AfterAll
    static void stop() {
        UP.close();
    }

    /** Stands in for the future WhatsApp sender: records what would have been sent and reports it as sent. */
    static class RecordingSender implements SummarySender {
        final List<Summary> sent = new CopyOnWriteArrayList<>();

        @Override
        public Delivery send(Summary summary) {
            sent.add(summary);
            return Delivery.delivered();
        }
    }

    @TestConfiguration
    static class SenderConfig {
        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    @Autowired
    RecordingSender sender;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    // ---- helpers ---------------------------------------------------------
    record Phone(String roomId, String token, String role, BlockingQueue<JsonNode> inbox) {}

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(Phone p, byte[] audio) throws Exception {
        String boundary = "b" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"turn.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(audio);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(url("/api/rooms/" + p.roomId + "/turns")))
                .header("Authorization", "Bearer " + p.token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Phone connect(String roomId, String token, String role) {
        BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        StringBuilder partial = new StringBuilder();
        http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/ws?room=" + roomId + "&token=" + token),
                new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            try {
                                inbox.add(json.readTree(partial.toString()));
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                            partial.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                }).join();
        return new Phone(roomId, token, role, inbox);
    }

    /** Waits for the next message of a type, skipping others (e.g. presence updates). */
    private JsonNode next(Phone p, String type) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            JsonNode m = p.inbox.poll(200, TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.path("type").asText())) return m;
        }
        throw new AssertionError("no " + type + " message for " + p.role);
    }

    private boolean none(Phone p, String type, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            JsonNode m = p.inbox.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.path("type").asText())) return false;
        }
        return true;
    }

    private Phone[] pair(String farmerLang, String buyerLang) throws Exception {
        JsonNode c = json.readTree(post("/api/rooms", null,
                "{\"role\":\"farmer\",\"language\":\"" + farmerLang + "\"}").body());
        JsonNode j = json.readTree(post("/api/rooms/" + c.path("roomId").asText() + "/join", null,
                "{\"language\":\"" + buyerLang + "\"}").body());
        assertThat(j.path("role").asText()).isEqualTo("buyer");
        Phone farmer = connect(c.path("roomId").asText(), c.path("token").asText(), "farmer");
        Phone buyer = connect(j.path("roomId").asText(), j.path("token").asText(), "buyer");
        next(farmer, "state");
        next(buyer, "state");
        return new Phone[] {farmer, buyer};
    }

    // ---- tests -----------------------------------------------------------
    @Test
    void longerTurnIsCompressedBeforePrismaUploadOnDemoMac() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/afconvert")));
        UP.prisma.clear();
        Phone[] ph = pair("hi-IN", "en-IN");
        assertThat(upload(ph[0], TestAudio.wav(4, 16000)).statusCode()).isEqualTo(200);
        assertThat(UP.prisma).hasSize(1);
        assertThat(UP.prisma.get(0).bodyText()).contains("filename=\"turn.m4a\"").contains("ftypM4A");
    }

    @Test
    void buyerTurnReachesBothPhonesVoiceOnlyToFarmerAndAdvisoryOnlyToFarmer() throws Exception {
        UP.prisma.clear();
        UP.timbre.clear();
        UP.sttTranscript = "I offer 4000 rupees per quintal for red onion";
        UP.offerJson = "{\"status\":\"clear\",\"commodity\":\"onion\",\"variety\":\"red\",\"market\":null,"
                + "\"price\":4000,\"currency\":\"INR\",\"unit\":\"quintal\"}";
        Phone[] ph = pair("hi-IN", "en-IN");
        Phone farmer = ph[0];
        Phone buyer = ph[1];

        HttpResponse<String> res = upload(buyer, TestAudio.wav(2, 16000));
        assertThat(res.statusCode()).isEqualTo(200);

        JsonNode toFarmer = next(farmer, "turn");
        JsonNode toBuyer = next(buyer, "turn");
        assertThat(toFarmer.path("transcript").asText()).isEqualTo("I offer 4000 rupees per quintal for red onion");
        assertThat(toFarmer.path("translation").asText()).startsWith("T[");
        assertThat(toBuyer.path("translation").asText()).isEqualTo(toFarmer.path("translation").asText());
        // Translated voice goes to the listener (farmer) only.
        assertThat(toFarmer.has("audioUrl")).isTrue();
        assertThat(toBuyer.has("audioUrl")).isFalse();

        // Prisma got the speaker's language and key; Timbre got only text, the listener's voice and language.
        assertThat(UP.prisma).hasSize(1);
        assertThat(UP.prisma.get(0).headers().get("key")).isEqualTo(FakeUpstreams.KEY);
        assertThat(UP.prisma.get(0).bodyText()).contains("name=\"language_code\"").contains("en-IN")
                .contains("name=\"audio_file\"");
        JsonNode tts = json.readTree(UP.timbre.get(0).body());
        assertThat(tts.path("language").asText()).isEqualTo("hi-IN");
        assertThat(tts.path("model").asText()).isEqualTo("timbre-v2.5");
        assertThat(tts.path("text").asText()).startsWith("S[");
        assertThat(new String(UP.timbre.get(0).body(), StandardCharsets.ISO_8859_1)).doesNotContain("RIFF");

        // Only the farmer can download the voice; the buyer is refused.
        HttpResponse<byte[]> farmerAudio = get(farmer, toFarmer.path("audioUrl").asText());
        assertThat(farmerAudio.statusCode()).isEqualTo(200);
        assertThat(new String(farmerAudio.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        String key = toFarmer.path("audioUrl").asText();
        assertThat(get(buyer, key).statusCode()).isEqualTo(403);

        // Farmer-only dated advisory.
        JsonNode advice = next(farmer, "advisory");
        assertThat(advice.path("text").asText()).contains("dated reference").contains("4,500").contains("4 November 2026")
                .contains("11% below");
        assertThat(advice.has("audioUrl")).isTrue();
        assertThat(none(buyer, "advisory", 500)).isTrue();
    }

    private static final String THREAT_QUOTE = "I won't let you sell to anyone if you won't buy from me at my price";

    private static boolean hasType(JsonNode events, String type) {
        for (JsonNode e : events) if (type.equals(e.path("type").asText())) return true;
        return false;
    }

    @Test
    void buyerThreatGivesFarmerAPrivateWarningInTheirLanguageAndNeverReachesBuyer() throws Exception {
        UP.llm.clear();
        UP.sttTranscript = THREAT_QUOTE;
        UP.threatJson = "{\"status\":\"threat\"}";
        UP.offerJson = "{\"status\":\"none\"}";
        UP.timbre.clear();
        Phone[] ph = pair("hi-IN", "en-IN");
        Phone farmer = ph[0];
        Phone buyer = ph[1];

        assertThat(upload(buyer, TestAudio.wav(2, 16000)).statusCode()).isEqualTo(200);
        // Normal translation and playback are untouched.
        JsonNode toFarmer = next(farmer, "turn");
        assertThat(toFarmer.path("translation").asText()).startsWith("T[").contains("sell to anyone");
        assertThat(toFarmer.has("audioUrl")).isTrue();
        assertThat(next(buyer, "turn").has("audioUrl")).isFalse();

        // Farmer: actionable caution, translated and voiced in the farmer's language, no safety guarantee.
        JsonNode warning = next(farmer, "warning");
        String text = warning.path("text").asText();
        assertThat(text).startsWith("T[Caution:").contains("sounded like a threat").contains("say you need time to decide")
                .contains("ask a trusted person").contains("cannot stop the buyer")
                .contains("threat or intimidation").contains("pressure you in this negotiation")
                .doesNotContain("accepting their price") // the classifier also flags violence or retaliation
                .doesNotContain("you are safe").doesNotContain("blocked");
        assertThat(warning.path("turnId").asText()).isEqualTo(toFarmer.path("turnId").asText());
        assertThat(get(farmer, warning.path("audioUrl").asText()).statusCode()).isEqualTo(200);
        assertThat(UP.timbre.stream().map(FakeUpstreams.Seen::bodyText)) // Timbre got the farmer's language
                .anyMatch(b -> b.contains("sounded like a threat") && b.contains("\"language\":\"hi-IN\""));

        // Buyer: no live warning, cannot fetch its voice, and a reconnect replays no warning either.
        assertThat(none(buyer, "warning", 500)).isTrue();
        assertThat(get(buyer, warning.path("audioUrl").asText()).statusCode()).isEqualTo(403);
        Phone buyerAgain = connect(buyer.roomId, buyer.token, "buyer");
        assertThat(hasType(next(buyerAgain, "state").path("events"), "warning")).isFalse();
        Phone farmerAgain = connect(farmer.roomId, farmer.token, "farmer");
        assertThat(hasType(next(farmerAgain, "state").path("events"), "warning")).isTrue();

        // The model saw the buyer's words as data in a classification prompt.
        assertThat(UP.llm.stream().map(FakeUpstreams.Seen::bodyText))
                .anyMatch(b -> b.contains("threat or intimidation") && b.contains("sell to anyone"));
    }

    @Test
    void benignPriceOfferGetsPriceAdviceButNoThreatWarning() throws Exception {
        UP.sttTranscript = "My final offer is 4000 rupees per quintal for red onion, take it or leave it";
        UP.threatJson = "{\"status\":\"none\"}";
        UP.offerJson = "{\"status\":\"clear\",\"commodity\":\"onion\",\"variety\":\"red\",\"market\":null,"
                + "\"price\":4000,\"currency\":\"INR\",\"unit\":\"quintal\"}";
        Phone[] ph = pair("hi-IN", "en-IN");

        assertThat(upload(ph[1], TestAudio.wav(2, 16000)).statusCode()).isEqualTo(200);
        assertThat(next(ph[0], "advisory").path("text").asText()).contains("11% below");
        assertThat(none(ph[0], "warning", 600)).isTrue();
        assertThat(none(ph[1], "warning", 100)).isTrue();
    }

    @Test
    void unknownOrMalformedThreatAnswersGiveNoWarning() throws Exception {
        UP.sttTranscript = "Maybe I will buy later";
        UP.offerJson = "{\"status\":\"none\"}";
        Phone[] ph = pair("hi-IN", "en-IN");
        for (String answer : new String[] {"{\"status\":\"maybe\"}", "{}", "{\"status\":null}"}) {
            UP.threatJson = answer;
            assertThat(upload(ph[1], TestAudio.wav(1, 16000)).statusCode()).isEqualTo(200);
            next(ph[0], "turn");
            assertThat(none(ph[0], "warning", 400)).isTrue();
        }
    }

    @Test
    void threatCheckFailureNeverBlocksTheTurnOrThePriceAdvice() throws Exception {
        UP.sttTranscript = "I offer 4000 rupees per quintal for red onion";
        UP.threatJson = "{\"status\":\"threat\"}";
        UP.offerJson = "{\"status\":\"clear\",\"commodity\":\"onion\",\"variety\":\"red\",\"market\":null,"
                + "\"price\":4000,\"currency\":\"INR\",\"unit\":\"quintal\"}";
        Phone[] ph = pair("hi-IN", "en-IN");

        UP.threatFailures.set(1);
        assertThat(upload(ph[1], TestAudio.wav(2, 16000)).statusCode()).isEqualTo(200);
        assertThat(next(ph[0], "turn").has("audioUrl")).isTrue();
        assertThat(next(ph[0], "advisory").path("text").asText()).contains("dated reference");
        assertThat(none(ph[0], "warning", 600)).isTrue(); // skipped, not guessed
        assertThat(none(ph[0], "turn_failed", 100)).isTrue();

        // Next turn the check works again.
        assertThat(upload(ph[1], TestAudio.wav(2, 16000)).statusCode()).isEqualTo(200);
        assertThat(next(ph[0], "warning").path("text").asText()).contains("sounded like a threat");
    }

    @Test
    void warningVoiceFailureStillDeliversTheTextWarning() throws Exception {
        UP.sttTranscript = THREAT_QUOTE;
        UP.threatJson = "{\"status\":\"threat\"}";
        UP.offerJson = "{\"status\":\"none\"}";
        UP.timbreFailOnText = "sounded like a threat";
        try {
            Phone[] ph = pair("hi-IN", "en-IN");
            assertThat(upload(ph[1], TestAudio.wav(2, 16000)).statusCode()).isEqualTo(200);
            assertThat(next(ph[0], "turn").has("audioUrl")).isTrue();
            JsonNode warning = next(ph[0], "warning");
            assertThat(warning.path("text").asText()).contains("sounded like a threat");
            assertThat(warning.has("audioUrl")).isFalse();
        } finally {
            UP.timbreFailOnText = "";
        }
    }

    @Test
    void farmerSpeechIsNeverThreatChecked() throws Exception {
        UP.llm.clear();
        UP.sttTranscript = THREAT_QUOTE;
        UP.threatJson = "{\"status\":\"threat\"}";
        Phone[] ph = pair("hi-IN", "en-IN");

        assertThat(upload(ph[0], TestAudio.wav(1, 16000)).statusCode()).isEqualTo(200);
        next(ph[1], "turn");
        assertThat(none(ph[0], "warning", 500)).isTrue();
        assertThat(none(ph[1], "warning", 100)).isTrue();
        assertThat(UP.llm.stream().map(FakeUpstreams.Seen::bodyText)).noneMatch(b -> b.contains("threat or intimidation"));
    }

    @Test
    void farmerTurnAndUnclearOffersProduceNoNumericAdvice() throws Exception {
        UP.sttTranscript = "price kitna hai";
        Phone[] ph = pair("hi-IN", "en-IN");
        assertThat(upload(ph[0], TestAudio.wav(1, 16000)).statusCode()).isEqualTo(200);
        next(ph[1], "turn");
        assertThat(none(ph[0], "advisory", 600)).isTrue();
        assertThat(none(ph[1], "advisory", 100)).isTrue();
    }

    @Test
    void failedStageReportsToBothPhonesAndRetryOfSameRecordingWorks() throws Exception {
        UP.offerJson = "{\"status\":\"none\"}";
        Phone[] ph = pair("hi-IN", "en-IN");
        byte[] recording = TestAudio.wav(1, 16000);

        UP.prismaFailures.set(1);
        HttpResponse<String> failed = upload(ph[1], recording);
        assertThat(failed.statusCode()).isEqualTo(502);
        assertThat(json.readTree(failed.body()).path("code").asText()).isEqualTo("stt_failed");
        assertThat(next(ph[0], "turn_failed").path("code").asText()).isEqualTo("stt_failed");
        assertThat(next(ph[1], "turn_failed").path("speaker").asText()).isEqualTo("buyer");

        // Same bytes again succeed; the room is not stuck "busy".
        assertThat(upload(ph[1], recording).statusCode()).isEqualTo(200);
        assertThat(next(ph[0], "turn").path("transcript").asText()).isNotBlank();

        UP.timbreFailures.set(1);
        HttpResponse<String> ttsFail = upload(ph[1], recording);
        assertThat(json.readTree(ttsFail.body()).path("code").asText()).isEqualTo("tts_failed");
    }

    @Test
    void rejectsUnauthenticatedInvalidAndEarlyTurns() throws Exception {
        JsonNode c = json.readTree(post("/api/rooms", null, "{\"role\":\"farmer\",\"language\":\"hi-IN\"}").body());
        Phone alone = new Phone(c.path("roomId").asText(), c.path("token").asText(), "farmer", null);
        assertThat(upload(alone, TestAudio.wav(1, 16000)).statusCode()).isEqualTo(409); // no partner yet

        Phone[] ph = pair("hi-IN", "kn-IN");
        assertThat(upload(ph[0], "not audio".getBytes()).statusCode()).isEqualTo(400);
        assertThat(upload(ph[0], TestAudio.wav(31, 16000)).statusCode()).isEqualTo(413);
        assertThat(upload(new Phone(ph[0].roomId, "wrong", "farmer", null), TestAudio.wav(1, 16000)).statusCode())
                .isEqualTo(401);
        assertThat(post("/api/rooms", null, "{\"role\":\"farmer\",\"language\":\"xx-XX\"}").statusCode()).isEqualTo(400);
        assertThat(post("/api/rooms/" + ph[0].roomId + "/join", null, "{\"language\":\"en-IN\"}").statusCode())
                .isEqualTo(409); // room is full
    }

    // ---- agreement ---------------------------------------------------------
    private static final String YES = "{\"intent\":\"yes\",\"newTerms\":false}";
    private static final String ONION_4000 = "{\"status\":\"clear\",\"commodity\":\"onion\",\"variety\":\"red\","
            + "\"market\":null,\"price\":4000,\"currency\":\"INR\",\"unit\":\"quintal\"}";
    private static final String PRIVATE_BITS = "dated reference|Caution|threat|AGMARKNET|4,500|Lasalgaon";

    /** Collects everything a phone receives until the condition holds (private and shared events race each other). */
    private List<JsonNode> until(Phone p, Predicate<List<JsonNode>> done) throws Exception {
        List<JsonNode> got = new ArrayList<>();
        long end = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < end) {
            JsonNode m = p.inbox.poll(100, TimeUnit.MILLISECONDS);
            if (m != null) got.add(m);
            if (done.test(got)) return got;
        }
        throw new AssertionError("expected messages did not reach the " + p.role + "; got "
                + got.stream().map(m -> m.path("type").asText() + ":" + m.path("state").asText()).toList());
    }

    private static JsonNode find(List<JsonNode> msgs, String type) {
        for (JsonNode m : msgs) if (type.equals(m.path("type").asText())) return m;
        return null;
    }

    /** The newest `agreement` state event with this state, or null. */
    private static JsonNode agreement(List<JsonNode> msgs, String state) {
        JsonNode found = null;
        for (JsonNode m : msgs) {
            if ("agreement".equals(m.path("type").asText()) && state.equals(m.path("state").asText())) found = m;
        }
        return found;
    }

    private static boolean turnFrom(List<JsonNode> msgs, String speaker) {
        return msgs.stream().anyMatch(m -> "turn".equals(m.path("type").asText()) && speaker.equals(m.path("speaker").asText()));
    }

    private static boolean confirmedBy(List<JsonNode> msgs, String role) {
        JsonNode c = agreement(msgs, "confirming");
        return c != null && c.path("confirmed").path(role).asBoolean(false);
    }

    private void speak(Phone who, String transcript, String modelAnswer) throws Exception {
        UP.sttTranscript = transcript;
        UP.replies.put(transcript, modelAnswer);
        assertThat(upload(who, TestAudio.wav(1, 16000)).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> reportPlayed(Phone p, JsonNode readback) throws Exception {
        return post("/api/rooms/" + p.roomId + "/agreement/readback-played", p.token,
                "{\"version\":" + readback.path("version").asInt() + "}");
    }

    /** Each phone gets its read-back and reports playing it; a reply must begin after that, like a real person's. */
    private void playBothReadbacks(Phone farmer, Phone buyer) throws Exception {
        JsonNode f = find(until(farmer, m -> find(m, "agreement_readback") != null), "agreement_readback");
        JsonNode b = find(until(buyer, m -> find(m, "agreement_readback") != null), "agreement_readback");
        assertThat(reportPlayed(farmer, f).statusCode()).isEqualTo(200);
        assertThat(reportPlayed(buyer, b).statusCode()).isEqualTo(200);
        until(farmer, m -> agreement(m, "confirming") != null);
        until(buyer, m -> agreement(m, "confirming") != null);
        Thread.sleep(1100); // the test recordings are 1 s long
    }

    /** Buyer offers 4000 for red onion, the farmer accepts by voice, both phones play the read-back. */
    private void toConfirming(Phone farmer, Phone buyer) throws Exception {
        UP.offerJson = ONION_4000;
        speak(buyer, "I offer 4000 rupees per quintal for red onion", YES);
        until(farmer, m -> agreement(m, "offer_open") != null);
        until(buyer, m -> agreement(m, "offer_open") != null);
        speak(farmer, "Yes I accept", YES);
        playBothReadbacks(farmer, buyer);
    }

    private void resetAgreementFakes() {
        UP.offerJson = "{\"status\":\"none\"}";
        UP.threatJson = "{\"status\":\"none\"}";
        UP.replies.clear();
        UP.replyFailures.set(0);
        UP.offers.clear();
        UP.offerDelays.clear();
        UP.timbreFailOnText = "";
        UP.sttTranscript = "hello";
    }

    @Test
    void twoSpokenYesRepliesAgreeOnceAndNothingPrivateReachesTheBuyerOrTheSummary() throws Exception {
        try {
            int before = sender.sent.size();
            UP.threatJson = "{\"status\":\"threat\"}";
            UP.offerJson = ONION_4000;
            Phone[] ph = pair("hi-IN", "en-IN");
            Phone farmer = ph[0];
            Phone buyer = ph[1];

            // The buyer's threatening offer: private caution and dated reference go to the farmer only.
            speak(buyer, "I won't let you sell to anyone unless you take 4000 rupees per quintal for red onion", YES);
            List<JsonNode> toFarmer = until(farmer, m -> find(m, "warning") != null && find(m, "advisory") != null
                    && agreement(m, "offer_open") != null);
            until(buyer, m -> agreement(m, "offer_open") != null);
            assertThat(agreement(toFarmer, "offer_open").path("terms").path("price").asText()).isEqualTo("4000");
            UP.threatJson = "{\"status\":\"none\"}";

            // The farmer accepts by voice only; each person is read the same exact terms in their own language.
            speak(farmer, "Yes I accept", YES);
            List<JsonNode> f = until(farmer, m -> find(m, "agreement_readback") != null);
            List<JsonNode> b = until(buyer, m -> find(m, "agreement_readback") != null);
            String terms = "onion, variety red, market not stated, 4,000 rupees per quintal. If you agree";
            JsonNode farmerReadback = find(f, "agreement_readback");
            JsonNode buyerReadback = find(b, "agreement_readback");
            assertThat(farmerReadback.path("text").asText()).startsWith("T[Please check these terms: " + terms);
            assertThat(buyerReadback.path("text").asText()).isEqualTo(farmerReadback.path("text").asText()); // fake translates identically
            assertThat(get(farmer, farmerReadback.path("audioUrl").asText()).statusCode()).isEqualTo(200);
            assertThat(get(buyer, farmerReadback.path("audioUrl").asText()).statusCode()).isEqualTo(403);
            assertThat(get(buyer, buyerReadback.path("audioUrl").asText()).statusCode()).isEqualTo(200);
            assertThat(UP.timbre.stream().map(FakeUpstreams.Seen::bodyText))
                    .anyMatch(t -> t.contains("Please check these terms") && t.contains("\"language\":\"hi-IN\""))
                    .anyMatch(t -> t.contains("Please check these terms") && t.contains("\"language\":\"en-IN\""));
            assertThat(sender.sent).hasSize(before);

            // Nothing counts until both phones report playing it; one report is not enough.
            speak(farmer, "Yes I agree", YES);
            until(buyer, m -> turnFrom(m, "farmer"));
            assertThat(reportPlayed(farmer, farmerReadback).body()).contains("\"counted\":true");
            assertThat(reportPlayed(farmer, farmerReadback).body()).contains("\"counted\":false");
            assertThat(none(farmer, "agreement_final", 300)).isTrue();
            assertThat(agreement(until(buyer, m -> agreement(m, "readback") != null), "readback")
                    .path("confirmed").path("farmer").asBoolean()).isFalse();
            assertThat(reportPlayed(buyer, buyerReadback).body()).contains("\"counted\":true");
            until(farmer, m -> agreement(m, "confirming") != null);
            until(buyer, m -> agreement(m, "confirming") != null);
            Thread.sleep(1100); // the test recordings are 1 s long

            // One yes is not enough, whoever says it.
            speak(farmer, "Yes I agree", YES);
            until(farmer, m -> confirmedBy(m, "farmer"));
            until(buyer, m -> confirmedBy(m, "farmer"));
            assertThat(sender.sent).hasSize(before);
            assertThat(none(farmer, "agreement_final", 400)).isTrue();

            // The buyer's yes completes it: exactly one summary each, in each person's language.
            speak(buyer, "Yes I agree", YES);
            List<JsonNode> farmerEnd = until(farmer, m -> find(m, "agreement_final") != null && agreement(m, "agreed") != null);
            List<JsonNode> buyerEnd = until(buyer, m -> find(m, "agreement_final") != null && agreement(m, "agreed") != null);
            JsonNode farmerFinal = find(farmerEnd, "agreement_final");
            JsonNode buyerFinal = find(buyerEnd, "agreement_final");
            assertThat(farmerFinal.path("text").asText()).startsWith("T[G-One demo summary (nonbinding)")
                    .contains("Price: ₹4,000 per quintal").contains("Not a contract").contains("identities were not verified");
            assertThat(farmerFinal.path("delivery").asText()).isEqualTo("sent");
            assertThat(farmerFinal.path("summaryId").asText()).isEqualTo(buyerFinal.path("summaryId").asText());
            assertThat(sender.sent).hasSize(before + 1);
            assertThat(sender.sent.get(before).text()).startsWith("G-One demo summary (nonbinding)")
                    .contains("Variety: red").contains("Market: not stated");

            // Nothing the farmer alone was told appears in the shared summary, the agreement states or the sender.
            for (String shared : new String[] {farmerFinal.toString(), buyerFinal.toString(), sender.sent.get(before).text(),
                    agreement(farmerEnd, "agreed").toString(), agreement(buyerEnd, "agreed").toString()}) {
                assertThat(shared).doesNotContainPattern(PRIVATE_BITS);
            }
            assertThat(none(buyer, "warning", 300)).isTrue();
            assertThat(none(buyer, "advisory", 100)).isTrue();

            // The same recording again, a late duplicate and reconnects never add a second summary.
            assertThat(upload(buyer, TestAudio.wav(1, 16000)).statusCode()).isEqualTo(200);
            assertThat(upload(farmer, TestAudio.wav(1, 16000)).statusCode()).isEqualTo(200);
            assertThat(none(buyer, "agreement_final", 700)).isTrue();
            assertThat(none(farmer, "agreement_final", 100)).isTrue();
            assertThat(sender.sent).hasSize(before + 1);

            Phone buyerAgain = connect(buyer.roomId, buyer.token, "buyer");
            JsonNode buyerHistory = next(buyerAgain, "state").path("events");
            assertThat(count(buyerHistory, "agreement_final")).isEqualTo(1);
            assertThat(hasType(buyerHistory, "warning")).isFalse();
            assertThat(hasType(buyerHistory, "advisory")).isFalse();
            assertThat(buyerHistory.toString()).doesNotContainPattern(PRIVATE_BITS);
            Phone farmerAgain = connect(farmer.roomId, farmer.token, "farmer");
            JsonNode farmerHistory = next(farmerAgain, "state").path("events");
            assertThat(count(farmerHistory, "agreement_final")).isEqualTo(1);
            assertThat(hasType(farmerHistory, "warning")).isTrue();
            assertThat(sender.sent).hasSize(before + 1);
        } finally {
            resetAgreementFakes();
        }
    }

    private static int count(JsonNode events, String type) {
        int n = 0;
        for (JsonNode e : events) if (type.equals(e.path("type").asText())) n++;
        return n;
    }

    @Test
    void anUnclearConfirmationGetsASpokenRetryForTheSpeakerOnlyAndAFailingModelNeverBlocksTranslation() throws Exception {
        try {
            Phone[] ph = pair("hi-IN", "en-IN");
            Phone farmer = ph[0];
            Phone buyer = ph[1];
            int before = sender.sent.size();
            toConfirming(farmer, buyer);

            // The model wrongly says yes to a conditional reply; the code refuses and the farmer is asked, aloud, to repeat.
            speak(farmer, "Yes, but only if you pay for transport", YES);
            JsonNode retry = find(until(farmer, m -> find(m, "agreement_retry") != null), "agreement_retry");
            assertThat(retry.path("text").asText()).startsWith("T[I could not tell whether you agree to these terms: onion")
                    .contains("Yes, I agree");
            assertThat(get(farmer, retry.path("audioUrl").asText()).statusCode()).isEqualTo(200);
            assertThat(sender.sent).hasSize(before);

            // The reply was still translated and spoken to the buyer as a normal turn, and the buyer got no prompt.
            List<JsonNode> toBuyer = until(buyer, m -> turnFrom(m, "farmer"));
            assertThat(toBuyer.toString()).contains("pay for transport");
            assertThat(find(toBuyer, "agreement_retry")).isNull();
            assertThat(none(buyer, "agreement_retry", 500)).isTrue();

            // A classifier outage: nothing counts, translation still works, the speaker is asked again.
            UP.replyFailures.set(1);
            speak(buyer, "Yes I agree", YES);
            until(farmer, m -> turnFrom(m, "buyer"));
            until(buyer, m -> find(m, "agreement_retry") != null);
            assertThat(none(farmer, "agreement_retry", 300)).isTrue();

            // A clear yes afterwards counts for the buyer alone.
            speak(buyer, "Yes I agree", YES);
            until(farmer, m -> confirmedBy(m, "buyer") && !confirmedBy(m, "farmer"));
            assertThat(sender.sent).hasSize(before);
        } finally {
            resetAgreementFakes();
        }
    }

    @Test
    void aChangedOfferCancelsConfirmationsAndANoEndsTheDraftWithoutAnySummary() throws Exception {
        try {
            Phone[] ph = pair("hi-IN", "en-IN");
            Phone farmer = ph[0];
            Phone buyer = ph[1];
            int before = sender.sent.size();
            toConfirming(farmer, buyer);
            speak(farmer, "Yes I agree", YES);
            until(buyer, m -> confirmedBy(m, "farmer"));

            // The buyer now offers a different price: both are told, both confirmations are gone, the new terms are open.
            UP.offerJson = ONION_4000.replace("4000", "4200");
            speak(buyer, "Actually I offer 4200 rupees per quintal for red onion", "{\"intent\":\"none\",\"newTerms\":true}");
            List<JsonNode> f = until(farmer, m -> agreement(m, "closed") != null && agreement(m, "offer_open") != null
                    && "4200".equals(agreement(m, "offer_open").path("terms").path("price").asText()));
            assertThat(agreement(f, "closed").path("outcome").asText()).isEqualTo("voided");
            assertThat(agreement(f, "offer_open").path("terms").path("price").asText()).isEqualTo("4200");
            assertThat(agreement(f, "offer_open").path("confirmed").path("farmer").asBoolean()).isFalse();
            until(buyer, m -> find(m, "agreement_notice") != null);

            // A no from the farmer ends the open offer.
            speak(farmer, "No", "{\"intent\":\"no\",\"newTerms\":false}");
            assertThat(none(farmer, "agreement_final", 500)).isTrue();
            assertThat(sender.sent).hasSize(before);
        } finally {
            resetAgreementFakes();
        }
    }

    @Test
    void aSlowlyReadOlderOfferNeverReplacesTheBuyersNewerOffer() throws Exception {
        try {
            Phone[] ph = pair("hi-IN", "en-IN");
            Phone farmer = ph[0];
            Phone buyer = ph[1];
            UP.offers.put("I offer 3400 for red onion", ONION_4000.replace("4000", "3400"));
            UP.offerDelays.put("I offer 3400 for red onion", 1500L);
            UP.offers.put("Now I offer 3600 for red onion", ONION_4000.replace("4000", "3600"));

            speak(buyer, "I offer 3400 for red onion", YES);
            speak(buyer, "Now I offer 3600 for red onion", YES);
            List<JsonNode> seen = until(farmer, m -> agreement(m, "offer_open") != null);
            assertThat(agreement(seen, "offer_open").path("terms").path("price").asText()).isEqualTo("3600");

            Thread.sleep(2000); // the slow read of the earlier sentence has answered by now
            JsonNode history = next(connect(farmer.roomId, farmer.token, "farmer"), "state").path("events");
            List<String> prices = new ArrayList<>();
            for (JsonNode e : history) {
                if ("agreement".equals(e.path("type").asText())) prices.add(e.path("terms").path("price").asText());
            }
            assertThat(prices).containsExactly("3600");
        } finally {
            resetAgreementFakes();
        }
    }

    @Test
    void aFailedReadbackVoiceSendsNothingAndAPlaybackReportCanOnlyBeForTheCallersOwnPhone() throws Exception {
        try {
            Phone[] ph = pair("hi-IN", "en-IN");
            Phone farmer = ph[0];
            Phone buyer = ph[1];
            UP.offerJson = ONION_4000;
            speak(buyer, "I offer 4000 rupees per quintal for red onion", YES);
            until(farmer, m -> agreement(m, "offer_open") != null);

            UP.timbreFailOnText = "Please check these terms";
            speak(farmer, "Yes I accept", YES);
            List<JsonNode> f = until(farmer, m -> agreement(m, "offer_open") != null
                    && "readback_failed".equals(agreement(m, "offer_open").path("outcome").asText()));
            assertThat(find(f, "agreement_readback")).isNull();
            assertThat(none(buyer, "agreement_readback", 300)).isTrue();
            UP.timbreFailOnText = "";

            // No token, or somebody else's room: refused. A report for a draft with no read-back counts for nothing.
            assertThat(post("/api/rooms/" + farmer.roomId + "/agreement/readback-played", null, "{\"version\":1}").statusCode())
                    .isBetween(400, 401);
            assertThat(post("/api/rooms/" + farmer.roomId + "/agreement/readback-played", "wrong", "{\"version\":1}").statusCode())
                    .isEqualTo(401);
            assertThat(post("/api/rooms/" + farmer.roomId + "/agreement/readback-played", farmer.token, "{\"version\":1}").body())
                    .contains("\"counted\":false");
        } finally {
            resetAgreementFakes();
        }
    }

    private HttpResponse<byte[]> get(Phone p, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).header("Authorization", "Bearer " + p.token).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
