package com.example.g_one.console;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lists the calls the console is following right now, oldest first. */
@RestController
public class ActiveCallsController {
    private final ConsoleState state;

    public ActiveCallsController(ConsoleState state) {
        this.state = state;
    }

    @GetMapping("/api/calls/active")
    public List<ActiveCall> activeCalls() {
        return state.activeCalls();
    }
}
