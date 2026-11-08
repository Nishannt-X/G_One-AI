package com.example.g_one.console;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development only. Plays a synthetic call onto the console bus so the web console can be built and filmed without a
 * phone. The endpoint does not exist unless {@code g-one.console.replay-enabled=true} (env
 * {@code G_ONE_CONSOLE_REPLAY_ENABLED=true}); it is off by default and must stay off in the demo.
 */
@RestController
@ConditionalOnProperty(name = "g-one.console.replay-enabled", havingValue = "true")
public class ReplayController {
    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);
    private static final int MAX_SPEED = 1000;

    private final ConsoleBus bus;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "console-replay");
        t.setDaemon(true);
        return t;
    });

    public ReplayController(ConsoleBus bus) {
        this.bus = bus;
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /**
     * Starts a new synthetic call and returns at once; the events arrive over time.
     *
     * @param speed 1 plays in about 25 seconds; a larger number plays faster (clamped to 1..1000)
     */
    @PostMapping("/api/dev/console/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> replay(@RequestParam(defaultValue = "1") int speed) {
        int factor = Math.max(1, Math.min(speed, MAX_SPEED));
        CallId call = new CallId("replay-" + java.util.UUID.randomUUID());
        long start = System.currentTimeMillis();
        List<ReplayScript.Step> steps = ReplayScript.sale(call, start);
        long delay = 0;
        for (ReplayScript.Step step : steps) {
            delay += step.delayMs() / factor;
            scheduler.schedule(() -> {
                try {
                    bus.publish(step.event().apply(System.currentTimeMillis()));
                } catch (RuntimeException e) {
                    log.warn("Console replay step failed", e);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
        return Map.of("callId", call.value());
    }
}
