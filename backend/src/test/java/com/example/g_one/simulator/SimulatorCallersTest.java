package com.example.g_one.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.core.CallerKey;
import org.junit.jupiter.api.Test;

class SimulatorCallersTest {
    private final SimulatorCallers callers = new SimulatorCallers("");

    @Test
    void theSameSyntheticNumberAlwaysGivesTheSameKey() {
        var a = callers.keyFor("+919876543210");
        assertThat(a).isPresent().isEqualTo(callers.keyFor(" +919876543210 "));
        assertThat(a).isNotEqualTo(callers.keyFor("+919876543211"));
    }

    @Test
    void aBlankNumberIsAWithheldCallerId() {
        assertThat(callers.keyFor(null)).isEmpty();
        assertThat(callers.keyFor("  ")).isEmpty();
    }

    @Test
    void aNumberOutsideTheSyntheticListIsRefusedWithoutEchoingIt() {
        assertThatThrownBy(() -> callers.keyFor("+15005550006"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("5005550006");
    }

    @Test
    void aConfiguredSaltChangesTheKeyAndAShortOneIsRefused() {
        var other = new SimulatorCallers("a-configured-salt-that-is-32-bytes!!");
        assertThat(other.keyFor("+919876543210")).isNotEqualTo(callers.keyFor("+919876543210"));
        assertThatThrownBy(() -> new SimulatorCallers("short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyOfferedNumberIsAValidCaller() {
        for (var c : callers.all()) {
            assertThat(callers.keyFor(c.number())).isPresent().get().isInstanceOf(CallerKey.class);
        }
    }
}
