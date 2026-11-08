package com.example.g_one.memory;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * (ADR 0004), so the endpoint fails closed:
 * <ul>
 *   <li>{@code G_ONE_ACCESS_CODE} set: every request needs it in the {@value #HEADER} header (constant-time compare),
 *       else 401;</li>
 *   <li>unset: 403 for everyone, unless {@code G_ONE_MEMORY_DEV_OPEN=true} and the request comes straight from
 *       loopback. "Straight" means the TCP peer is loopback and the request carries no proxy header
 *       ({@code Forwarded}, {@code X-Forwarded-*}, {@code X-Real-IP}, {@code CF-Connecting-IP}): a local tunnel such as
 *       cloudflared or ngrok also connects from loopback but adds those headers, so tunnelled traffic stays closed.</li>
 * </ul>
 * The checks read the container's own request, below Spring's forwarded-header wrapper, so a spoofed
 * {@code X-Forwarded-For: 127.0.0.1} cannot pass for loopback. All data in this repo and demo is synthetic.
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
                                    @RequestHeader(value = HEADER, required = false) String accessCode,
                                    HttpServletRequest request) {
        if (props.accessCheckEnabled()) {
            if (!codeMatches(accessCode)) {
                return ResponseEntity.status(401).body(Map.of("error", "access_code_required"));
            }
        } else if (!(props.devOpen() && directLoopback(request))) {
            return ResponseEntity.status(403).body(Map.of("error", "memory_api_closed"));
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

    private boolean codeMatches(String given) {
        if (given == null) {
            return false;
        }
        // Constant-time comparison, so response timing does not reveal how much of the code matched.
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                props.accessCode().getBytes(StandardCharsets.UTF_8));
    }

    private static final List<String> PROXY_HEADERS = List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host",
            "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-Prefix", "X-Real-IP", "CF-Connecting-IP",
            "True-Client-IP");

    /** True when the container's TCP peer is loopback and no proxy or tunnel header is present. */
    static boolean directLoopback(HttpServletRequest request) {
        ServletRequest raw = request;
        while (raw instanceof ServletRequestWrapper w) {
            raw = w.getRequest(); // below ForwardedHeaderFilter: the real peer and the original headers
        }
        if (!(raw instanceof HttpServletRequest http)) {
            return false;
        }
        for (String h : PROXY_HEADERS) {
            if (http.getHeader(h) != null) {
                return false;
            }
        }
        String peer = http.getRemoteAddr();
        if (peer == null) {
            return false;
        }
        peer = peer.replace("[", "").replace("]", "");
        int zone = peer.indexOf('%');
        if (zone >= 0) {
            peer = peer.substring(0, zone);
        }
        boolean ipv4 = peer.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
        boolean ipv6 = peer.contains(":") && peer.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) {
            return false; // only literal IPs; never resolve a host name
        }
        try {
            return InetAddress.getByName(peer).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
