package com.example.g_one;

import com.example.g_one.agreement.AgreementService;
import com.example.g_one.room.Role;
import com.example.g_one.room.Room;
import com.example.g_one.room.RoomHub;
import com.example.g_one.room.RoomService;
import com.example.g_one.turn.TurnService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    record CreateRoom(String role, String language, String accessCode) {}

    record JoinRoom(String language) {}

    record SetLanguage(String language) {}

    record ReadbackPlayed(int version) {}

    private final GOneProperties props;
    private final Language.Registry languages;
    private final RoomService rooms;
    private final RoomHub hub;
    private final TurnService turns;
    private final AgreementService agreement;

    public ApiController(GOneProperties props, Language.Registry languages, RoomService rooms, RoomHub hub,
                         TurnService turns, AgreementService agreement) {
        this.props = props;
        this.languages = languages;
        this.rooms = rooms;
        this.hub = hub;
        this.turns = turns;
        this.agreement = agreement;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        List<Map<String, String>> langs = languages.all().stream()
                .map(l -> Map.of("code", l.code(), "label", l.label())).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("languages", langs);
        m.put("maxTurnSeconds", props.maxTurnSeconds());
        m.put("accessCodeRequired", !props.accessCode().isBlank());
        // Names only, never values: tells the operator what is still missing.
        m.put("ready", turns.ready());
        return m;
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateRoom req) {
        var s = rooms.create(Role.parse(req.role()), languages.require(req.language()).code(), req.accessCode());
        return credentials(s);
    }

    @PostMapping("/rooms/{roomId}/join")
    public Map<String, Object> join(@PathVariable String roomId, @RequestBody JoinRoom req) {
        var s = rooms.join(roomId, languages.require(req.language()).code());
        hub.broadcastPresence(s.room());
        return credentials(s);
    }

    @PostMapping("/rooms/{roomId}/language")
    public Map<String, String> setLanguage(@PathVariable String roomId,
                                           @RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                           @RequestBody SetLanguage req) {
        var s = rooms.authenticate(roomId, auth);
        if (s.room().turnInFlight()) throw new ApiException(409, "busy", "Wait for the current turn to finish.");
        s.participant().language(languages.require(req.language()).code());
        hub.broadcastPresence(s.room());
        return Map.of("language", s.participant().language());
    }

    @PostMapping(path = "/rooms/{roomId}/turns", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> turn(@PathVariable String roomId,
                                    @RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                    @RequestParam("audio") MultipartFile audio) throws IOException {
        var s = rooms.authenticate(roomId, auth);
        return Map.of("turnId", turns.process(s.room(), s.participant().role, audio.getBytes()));
    }

    /** The caller's phone played its read-back to the end. Counts for the caller's own role, whatever else is sent. */
    @PostMapping("/rooms/{roomId}/agreement/readback-played")
    public Map<String, Boolean> readbackPlayed(@PathVariable String roomId,
                                               @RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                               @RequestBody ReadbackPlayed req) {
        var s = rooms.authenticate(roomId, auth);
        return Map.of("counted", agreement.readbackPlayed(s.room(), s.participant().role, req.version()));
    }

    /** Only the listener the clip was made for can fetch it; the speaker's raw recording is never stored. */
    @GetMapping("/rooms/{roomId}/audio/{key}")
    public ResponseEntity<byte[]> audio(@PathVariable String roomId, @PathVariable String key,
                                        @RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        var s = rooms.authenticate(roomId, auth);
        Room.Clip clip = s.room().clip(key);
        // Same answer for "no such clip" and "someone else's clip", so a guessed key cannot reveal a private clip exists.
        if (clip == null || clip.owner() != s.participant().role) throw new ApiException(404, "no_audio", "Audio not found.");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(clip.wav());
    }

    private static Map<String, Object> credentials(RoomService.Session s) {
        return Map.of("roomId", s.room().id, "token", s.participant().token(), "role", s.participant().role.wire());
    }
}
