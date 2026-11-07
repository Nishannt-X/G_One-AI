package com.example.g_one_legacy.service;

import com.example.g_one_legacy.event.TranslationRequestEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.example.g_one_legacy.websocket.TwilioAudioWebSocketHandler;

import java.util.concurrent.CompletableFuture;

/**
 * Grand Finale Event Listener — orchestrates the full G-One pipeline:
 *
 * NORMAL FLOW:
 * 1. Check if LedgerLock state machine is awaiting confirmation → intercept
 * 2. Store transcript in Redis sliding window
 * 3. Fetch full conversation context from Redis
 * 4. Call Groq with context-enriched prompt
 * 5. Parse Groq JSON response
 * 6. Play TRANSLATION audio to target speaker
 * 7. Play WHISPERER audio (with market price) to target speaker AFTER
 * translation
 * 8. If deal_confirmed → trigger LedgerLock state machine
 *
 * LEDGER LOCK FLOW (when awaiting confirmation):
 * - Check transcript for "Yes"/"Haan"/"हाँ"
 * - Register confirmation for that speaker
 * - If both confirmed → send WhatsApp contract, play confirmation audio
 */
@Service
public class TranslationEventListener {

    private static final Logger log = LoggerFactory.getLogger(TranslationEventListener.class);

    private final GroqLlmService groqLlmService;
    private final ElevenLabsService elevenLabsService;
    private final TwilioAudioWebSocketHandler twilioAudioWebSocketHandler;
    private final ConversationContextService conversationContextService;
    private final LiveMarketPriceService liveMarketPriceService;
    private final LedgerLockStateMachine ledgerLockStateMachine;
    private final ContractMessagingService contractMessagingService;
    private final ObjectMapper objectMapper;

    public TranslationEventListener(GroqLlmService groqLlmService,
            ElevenLabsService elevenLabsService,
            TwilioAudioWebSocketHandler twilioAudioWebSocketHandler,
            ConversationContextService conversationContextService,
            LiveMarketPriceService liveMarketPriceService,
            LedgerLockStateMachine ledgerLockStateMachine,
            ContractMessagingService contractMessagingService,
            ObjectMapper objectMapper) {
        this.groqLlmService = groqLlmService;
        this.elevenLabsService = elevenLabsService;
        this.twilioAudioWebSocketHandler = twilioAudioWebSocketHandler;
        this.conversationContextService = conversationContextService;
        this.liveMarketPriceService = liveMarketPriceService;
        this.ledgerLockStateMachine = ledgerLockStateMachine;
        this.contractMessagingService = contractMessagingService;
        this.objectMapper = objectMapper;
    }

    @Async
    @EventListener
    public void handleTranslationRequestEvent(TranslationRequestEvent event) {
        String source = event.getSourceSpeakerId();
        String target = event.getTargetSpeakerId();
        String text = event.getOriginalText();
        String sourceLang = "A".equals(source) ? "Hindi" : "English";
        String targetLang = "A".equals(target) ? "Hindi" : "English";

        log.info("[ROUTER] Event received. Source: {} ({}) → Target: {} ({}), Text: '{}'",
                source, sourceLang, target, targetLang, text);

        System.out.println("\n=======================================================");
        System.out.println("🗣️ STT (DEEPGRAM) Speaker " + source + " (" + sourceLang + "): " + text);
        System.out.println("=======================================================\n");

        // ─────────────────────────────────────────────────────────────────────────
        // INTERCEPT: Is LedgerLock awaiting dual confirmation?
        // ─────────────────────────────────────────────────────────────────────────
        if (ledgerLockStateMachine.isAwaitingConfirmation()) {
            handleDealConfirmation(source, target, text);
            return; // Skip the normal translation pipeline
        }

        try {
            // ─── 1. Store in Redis sliding window ────────────────────────────
            conversationContextService.addTurn(source, text);

            // ─── 2. Fetch full conversation context ──────────────────────────
            String context = conversationContextService.getFullContextForGroq();
            log.debug("[ROUTER][REDIS] Context block:\n{}", context);

            // ─── 3. Call Groq with context ───────────────────────────────────
            String groqJsonResponse = groqLlmService.processTranslation(text, context).join();

            if (groqJsonResponse == null || groqJsonResponse.isBlank()) {
                log.error("[ROUTER][GROQ] Empty response from Groq. Skipping pipeline.");
                return;
            }

            log.info("[ROUTER][GROQ] Raw JSON: {}", groqJsonResponse);

            // ─── 4. Parse the Groq JSON response ────────────────────────────
            JsonNode groqJson = objectMapper.readTree(groqJsonResponse);

            String translatedText = groqJson.path("translated_text").asText("").trim();
            boolean sentimentFlag = groqJson.path("sentiment_shield_flag").asBoolean(false);
            String whispererAdvice = groqJson.path("ai_whisperer_advice").asText("");
            boolean dealConfirmed = groqJson.path("deal_confirmed").asBoolean(false);
            JsonNode ledgerLock = groqJson.path("ledger_lock");

            // ─── 5. Sentiment Shield console output ──────────────────────────
            if (sentimentFlag) {
                System.out.println("\n🚨🚨🚨 SENTIMENT SHIELD ALERT 🚨🚨🚨");
                System.out.println("⚠️  Manipulative tactics detected from Speaker " + source);
                System.out.println("💡 AI Whisperer Advice: " + whispererAdvice);
                System.out.println("🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨\n");
                log.warn("[ROUTER][SENTIMENT] ⚠️ FLAG from Speaker {}. Advice: {}", source, whispererAdvice);
            }

            // ─── 6. Ledger Lock data logging ─────────────────────────────────
            String detectedItem = null;
            double detectedPrice = 0;
            double detectedQuantity = 0;
            String detectedCurrency = "INR";

            if (ledgerLock != null && !ledgerLock.isMissingNode()) {
                JsonNode priceNode = ledgerLock.path("price");
                JsonNode quantityNode = ledgerLock.path("quantity");
                detectedCurrency = ledgerLock.path("currency").asText("INR");
                detectedItem = ledgerLock.path("item").asText(null);

                if (!priceNode.isNull())
                    detectedPrice = priceNode.asDouble(0);
                if (!quantityNode.isNull())
                    detectedQuantity = quantityNode.asDouble(0);

                if (detectedPrice > 0 || detectedQuantity > 0) {
                    System.out.println("\n📒 LEDGER LOCK DETECTED:");
                    System.out.println("   📦 Item: " + (detectedItem != null ? detectedItem : "N/A"));
                    System.out.println("   💰 Price: " + detectedPrice);
                    System.out.println("   ⚖️ Quantity: " + detectedQuantity);
                    System.out.println("   💱 Currency: " + detectedCurrency + "\n");
                    log.info("[ROUTER][LEDGER] Item={}, Price={}, Quantity={}, Currency={}",
                            detectedItem, detectedPrice, detectedQuantity, detectedCurrency);
                }
            }

            // ─── 7. TRANSLATION TTS → play to target speaker ────────────────
            if (translatedText.isBlank()) {
                log.warn("[ROUTER][GROQ] translated_text is blank. Skipping TTS.");
                return;
            }

            System.out.println("🔄 TRANSLATION → Speaker " + target + " (" + targetLang + "): " + translatedText);

            byte[] translationAudio = elevenLabsService.synthesizeAudio(translatedText).join();

            if (translationAudio != null && translationAudio.length > 0) {
                twilioAudioWebSocketHandler.sendAudioToSpeaker(target, translationAudio);
                log.info("[ROUTER][DISPATCH] ✅ Translation audio sent: {} ({}) → {} ({})",
                        source, sourceLang, target, targetLang);
            }

            // ─── 8. WHISPERER TTS → play AFTER translation to LISTENER ──────
            // Triggers when:
            // a) Groq returned ai_whisperer_advice (sentiment-related), OR
            // b) A commodity item + price was mentioned (market price comparison)
            boolean hasWhispererAdvice = whispererAdvice != null && !whispererAdvice.isBlank();
            boolean hasPriceDiscussion = detectedItem != null && !detectedItem.isBlank() && detectedPrice > 0;

            if (hasWhispererAdvice || hasPriceDiscussion) {
                String whispererText = buildWhispererMessage(target,
                        hasWhispererAdvice ? whispererAdvice : "", detectedItem);

                System.out.println("🤫 AI WHISPERER → Speaker " + target + " (" + targetLang + "): " + whispererText);

                byte[] whispererAudio = elevenLabsService.synthesizeAudio(whispererText).join();

                if (whispererAudio != null && whispererAudio.length > 0) {
                    twilioAudioWebSocketHandler.sendAudioToSpeaker(target, whispererAudio);
                    log.info("[ROUTER][WHISPERER] ✅ Whisperer audio sent to Speaker {} ({})", target, targetLang);
                }
            }

            // ─── 9. DEAL DETECTION → trigger LedgerLock state machine ────────
            if (dealConfirmed && detectedPrice > 0 && detectedQuantity > 0) {
                log.info("[ROUTER][DEAL] 🤝 Deal confirmation detected! Triggering LedgerLock...");

                System.out.println("\n🤝🤝🤝 DEAL CONFIRM DETECTED 🤝🤝🤝");
                System.out.println("   Triggering verbal contract confirmation flow...");
                System.out.println("🤝🤝🤝🤝🤝🤝🤝🤝🤝🤝🤝🤝🤝🤝🤝🤝\n");

                ledgerLockStateMachine.proposeDeal(detectedPrice, detectedQuantity, detectedCurrency, detectedItem);

                // Play confirmation prompt to BOTH speakers in parallel
                playConfirmationPromptToBothSpeakers();
            }

        } catch (Exception e) {
            log.error("[ROUTER] Error processing translation event.", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // AI Whisperer message builder (with market price lookup)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the whisperer audio text in the listener's language.
     * Includes market price lookup if a commodity item was detected.
     */
    private String buildWhispererMessage(String targetSpeakerId, String advice, String item) {
        StringBuilder sb = new StringBuilder();
        boolean isHindi = "A".equals(targetSpeakerId);
        boolean hasAdvice = advice != null && !advice.isBlank();

        // Prefix in listener's language
        sb.append(isHindi ? "यह G-One है। " : "This is G-One. ");

        // Append whisperer advice if present
        if (hasAdvice) {
            sb.append(advice);
        }

        // Append market price if commodity detected
        if (item != null && !item.isBlank()) {
            String marketPrice = liveMarketPriceService.getMarketPrice(item);
            if (marketPrice != null) {
                if (hasAdvice)
                    sb.append(". "); // separator between advice and market price
                if (isHindi) {
                    sb.append(item).append(" का वर्तमान बाजार मूल्य ").append(marketPrice).append(" है।");
                } else {
                    sb.append("The current market price for ").append(item).append(" is ").append(marketPrice)
                            .append(".");
                }
            }
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // LedgerLock: Confirmation prompt to both speakers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Plays the deal confirmation prompt to BOTH speakers in parallel.
     * Speaker A hears it in Hindi, Speaker B hears it in English.
     */
    private void playConfirmationPromptToBothSpeakers() {
        String hindiPrompt = ledgerLockStateMachine.getConfirmationPromptHindi();
        String englishPrompt = ledgerLockStateMachine.getConfirmationPromptEnglish();

        log.info("[LEDGER_LOCK] Playing confirmation prompts to both speakers...");
        System.out.println("🔊 To Speaker A (Hindi): " + hindiPrompt);
        System.out.println("🔊 To Speaker B (English): " + englishPrompt);

        // Synthesize both prompts in parallel
        CompletableFuture<byte[]> audioAFuture = elevenLabsService.synthesizeAudio(hindiPrompt);
        CompletableFuture<byte[]> audioBFuture = elevenLabsService.synthesizeAudio(englishPrompt);

        CompletableFuture.allOf(audioAFuture, audioBFuture).thenRun(() -> {
            try {
                byte[] audioA = audioAFuture.join();
                byte[] audioB = audioBFuture.join();

                if (audioA != null && audioA.length > 0) {
                    twilioAudioWebSocketHandler.sendAudioToSpeaker("A", audioA);
                    log.info("[LEDGER_LOCK] ✅ Confirmation prompt sent to Speaker A");
                }
                if (audioB != null && audioB.length > 0) {
                    twilioAudioWebSocketHandler.sendAudioToSpeaker("B", audioB);
                    log.info("[LEDGER_LOCK] ✅ Confirmation prompt sent to Speaker B");
                }
            } catch (Exception e) {
                log.error("[LEDGER_LOCK] Failed to send confirmation prompts", e);
            }
        }).exceptionally(ex -> {
            log.error("[LEDGER_LOCK] Error synthesizing confirmation prompts", ex);
            return null;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // LedgerLock: Handle "Yes"/"Haan" confirmation from speakers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Handles incoming transcript when LedgerLock is awaiting dual confirmation.
     * Checks for affirmative response and triggers WhatsApp contract if both
     * confirm.
     */
    private void handleDealConfirmation(String speakerId, String targetSpeakerId, String transcript) {
        log.info("[LEDGER_LOCK] Checking confirmation from Speaker {}: '{}'", speakerId, transcript);

        String lower = transcript.toLowerCase().trim();
        boolean isAffirmative = lower.contains("yes") ||
                lower.contains("haan") ||
                lower.contains("haa") ||
                lower.contains("ha") ||
                transcript.contains("हाँ") ||
                transcript.contains("हां") ||
                transcript.contains("जी") ||
                transcript.contains("जी हाँ");

        if (!isAffirmative) {
            log.info("[LEDGER_LOCK] Speaker {} did not confirm. Transcript: '{}'", speakerId, transcript);
            // Optionally, you could translate and play their response normally here
            return;
        }

        System.out.println("✅ Speaker " + speakerId + " said YES to the deal!");

        boolean bothConfirmed = ledgerLockStateMachine.registerConfirmation(speakerId);

        if (bothConfirmed) {
            // ── BOTH CONFIRMED — TRIGGER CONTRACT ────────────────────────────
            String contractSummary = ledgerLockStateMachine.getContractSummary();

            System.out.println("\n🎉🎉🎉 DEAL FINALIZED! 🎉🎉🎉");
            System.out.println(contractSummary);
            System.out.println("🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉\n");

            // 1. Send WhatsApp contract to both parties
            contractMessagingService.sendContractToWhatsApp(contractSummary);

            // 2. Play confirmation audio to both speakers
            playDealConfirmedAudio();

            // 3. Reset state machine
            ledgerLockStateMachine.reset();
        }
    }

    /**
     * Plays "Deal confirmed" audio to both speakers after WhatsApp contract is
     * sent.
     */
    private void playDealConfirmedAudio() {
        String hindiConfirm = "यह G-One है। बधाई हो! डील कन्फर्म हो गई है। " +
                "अनुबंध का विवरण आपके WhatsApp पर भेज दिया गया है।";
        String englishConfirm = "This is G-One. Congratulations! The deal has been confirmed. " +
                "Contract details have been sent to your WhatsApp.";

        CompletableFuture<byte[]> audioAFuture = elevenLabsService.synthesizeAudio(hindiConfirm);
        CompletableFuture<byte[]> audioBFuture = elevenLabsService.synthesizeAudio(englishConfirm);

        CompletableFuture.allOf(audioAFuture, audioBFuture).thenRun(() -> {
            try {
                byte[] audioA = audioAFuture.join();
                byte[] audioB = audioBFuture.join();

                if (audioA != null && audioA.length > 0) {
                    twilioAudioWebSocketHandler.sendAudioToSpeaker("A", audioA);
                }
                if (audioB != null && audioB.length > 0) {
                    twilioAudioWebSocketHandler.sendAudioToSpeaker("B", audioB);
                }
                log.info("[LEDGER_LOCK] ✅ Deal confirmed audio sent to both speakers");
            } catch (Exception e) {
                log.error("[LEDGER_LOCK] Failed to send deal confirmed audio", e);
            }
        });
    }
}
