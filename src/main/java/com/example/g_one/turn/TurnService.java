package com.example.g_one.turn;

import com.example.g_one.GOneProperties;
import com.example.g_one.ApiException;
import com.example.g_one.Language;
import com.example.g_one.advisory.AdvisoryService;
import com.example.g_one.agreement.AgreementService;
import com.example.g_one.room.Role;
import com.example.g_one.room.Room;
import com.example.g_one.room.RoomEvent;
import com.example.g_one.room.RoomHub;
import com.example.g_one.speech.PrismClient;
import com.example.g_one.speech.TimbreClient;
import com.example.g_one.speech.UpstreamException;
import com.example.g_one.speech.Wav;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One completed turn: Prisma speech-to-text, translation, Timbre text-to-speech, then notify both phones.
 * The uploaded recording lives only in this call: it is never stored and never sent to the other phone.
 */
@Service
public class TurnService {
    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    private final GOneProperties props;
    private final Language.Registry languages;
    private final PrismClient stt;
    private final TranslationService translator;
    private final TimbreClient tts;
    private final AdvisoryService advisory;
    private final AgreementService agreement;
    private final RoomHub hub;
    private final SecureRandom random = new SecureRandom();

    public TurnService(GOneProperties props, Language.Registry languages, PrismClient stt,
                       TranslationService translator, TimbreClient tts, AdvisoryService advisory,
                       AgreementService agreement, RoomHub hub) {
        this.props = props;
        this.languages = languages;
        this.stt = stt;
        this.translator = translator;
        this.tts = tts;
        this.advisory = advisory;
        this.agreement = agreement;
        this.hub = hub;
    }

    public boolean ready() {
        return stt.configured() && translator.configured();
    }

    /** @return the new turn id; throws ApiException (with a retryable code) on any failure */
    public String process(Room room, Role speaker, byte[] wav) {
        if (!ready()) {
            throw new ApiException(503, "not_configured", "The server is missing speech or translation credentials.");
        }
        Instant received = Instant.now();
        double seconds = Wav.validate(wav, props.maxTurnSeconds());
        Room.Participant listener = room.participant(speaker.other());
        if (listener == null) {
            throw new ApiException(409, "waiting_for_partner", "Wait for the other person to join.");
        }
        if (!room.tryStartTurn()) {
            throw new ApiException(409, "busy", "Another turn is being processed.");
        }
        String turnId = HexFormat.of().formatHex(randomBytes(6));
        try {
            if (!room.countTurn(props.maxTurnsPerRoom())) {
                throw new ApiException(429, "turn_limit", "This demo room reached its turn limit.");
            }
            long turnSeq = room.nextTurnSeq();
            Language from = languages.require(room.participant(speaker).language());
            Language to = languages.require(listener.language());
            hub.broadcast(room, RoomEvent.of("turn_started", null, Map.of("turnId", turnId, "speaker", speaker.wire())));
            hub.broadcastPresence(room);

            String transcript = stt.transcribe(wav, from.code());
            Translation t = from.code().equals(to.code())
                    ? new Translation(transcript, transcript)
                    : translator.translate(transcript, from.label(), to);
            byte[] voice = tts.synthesize(t.spoken(), to);

            String audioKey = "t-" + turnId;
            room.putClip(audioKey, listener.role, voice);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("turnId", turnId);
            data.put("speaker", speaker.wire());
            data.put("sourceLanguage", from.code());
            data.put("targetLanguage", to.code());
            data.put("transcript", transcript);
            data.put("translation", t.text());
            RoomEvent event = new RoomEvent("turn", null, data, listener.role, audioKey);
            room.record(event);
            hub.broadcast(room, event);

            if (speaker == Role.BUYER) advisory.adviseAsync(room, turnId, turnSeq, transcript, from);
            agreement.turnAsync(room, turnId, turnSeq, speaker, transcript, from,
                    received.minusMillis((long) (seconds * 1000)));
            return turnId;
        } catch (UpstreamException e) {
            log.warn("Turn {} failed at {}: {}", turnId, e.stage(), e.getMessage());
            fail(room, turnId, speaker, e.stage());
            throw new ApiException(502, e.stage(), message(e));
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Turn {} failed unexpectedly", turnId, e);
            fail(room, turnId, speaker, "internal");
            throw new ApiException(500, "internal", "Something went wrong. Try again.");
        } finally {
            room.endTurn();
            hub.broadcastPresence(room);
        }
    }

    private void fail(Room room, String turnId, Role speaker, String code) {
        hub.broadcast(room, RoomEvent.of("turn_failed", null,
                Map.of("turnId", turnId, "speaker", speaker.wire(), "code", code)));
    }

    private static String message(UpstreamException e) {
        return switch (e.stage()) {
            case "no_speech" -> "We did not hear anything. Record again.";
            case "stt_failed" -> "Speech recognition failed. " + e.getMessage();
            case "translation_failed" -> "Translation failed. " + e.getMessage();
            default -> "Voice generation failed. " + e.getMessage();
        };
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }
}
