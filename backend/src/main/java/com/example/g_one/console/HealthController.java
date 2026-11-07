package com.example.g_one.console;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check for the console, the tunnel and the demo runbook. Needs no configuration. */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }
}
