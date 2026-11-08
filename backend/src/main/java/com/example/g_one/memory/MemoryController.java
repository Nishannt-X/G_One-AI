package com.example.g_one.memory;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only memory view for the console: {@code GET /api/memory/{callerKey}}. The console is public through the tunnel
 * (ADR 0004), so when {@code G_ONE_ACCESS_CODE} is set every request needs it in the {@value #HEADER} header. Blank
 * turns the check off for local dev. All data in this repo and demo is synthetic.
 */
@RestController
public class MemoryController {
    public static final String HEADER = "X-Access-Code";

    /** One fact as the console shows it; {@code at} is ISO-8601 UTC. */
    public record FactView(String field, String value, String source, String callId, String at) {
        static FactView of(ProfileFact f) {
            return new FactView(f.field(), f.value(), f.provenance().saidOrInferred().wire(),
                    f.provenance().callId().value(), Instant.ofEpochMilli(f.provenance().atMs()).toString());
        }
    }

    public record GistView(String callId, String startedAt, String endedAt, String summary, List<String> followUps) {
        static GistView of(CallGist g) {
            return new GistView(g.callId().value(), Instant.ofEpochMilli(g.startedMs()).toString(),
                    Instant.ofEpochMilli(g.endedMs()).toString(), g.summary(), g.followUps());
        }
    }

    /** {@code known} is false when nothing is stored for the key (first-time caller, or forgotten). */
    public record MemoryView(String callerKey, boolean known, List<FactView> facts, List<GistView> gists) {}

    private final MemoryStore store;
    private final MemoryProperties props;

    public MemoryController(MemoryStore store, MemoryProperties props) {
        this.store = store;
        this.props = props;
    }

    @GetMapping("/api/memory/{callerKey}")
    public ResponseEntity<?> memory(@PathVariable String callerKey,
                                    @RequestHeader(value = HEADER, required = false) String accessCode) {
        if (!allowed(accessCode)) {
            return ResponseEntity.status(401).body(Map.of("error", "access_code_required"));
        }
        CallerKey key;
        try {
            key = CallerKey.of(callerKey);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad_caller_key"));
        }
        return ResponseEntity.ok(store.load(key)
                .map(m -> view(key, m))
                .orElseGet(() -> new MemoryView(key.value(), false, List.of(), List.of())));
    }

    private static MemoryView view(CallerKey key, FarmerMemory m) {
        return new MemoryView(key.value(), true, m.facts().stream().map(FactView::of).toList(),
                m.gists().stream().map(GistView::of).toList());
    }

    private boolean allowed(String given) {
        if (!props.accessCheckEnabled()) {
            return true;
        }
        if (given == null) {
            return false;
        }
        // Constant-time comparison, so response timing does not reveal how much of the code matched.
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                props.accessCode().getBytes(StandardCharsets.UTF_8));
    }
}
