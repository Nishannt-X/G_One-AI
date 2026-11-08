package com.example.g_one.simulator;

import com.example.g_one.core.Language;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the simulator page offers: synthetic caller numbers and the languages the backend supports. */
@RestController
public class SimulatorConfigController {
    public record LanguageOption(String code, String label) {}

    public record Config(List<SimulatorCallers.Caller> callers, List<LanguageOption> languages) {}

    private final SimulatorCallers callers;
    private final Language.Registry languages;

    public SimulatorConfigController(SimulatorCallers callers, Language.Registry languages) {
        this.callers = callers;
        this.languages = languages;
    }

    @GetMapping("/api/simulator/config")
    Config config() {
        return new Config(callers.all(),
                languages.all().stream().map(l -> new LanguageOption(l.code(), l.label())).toList());
    }
}
