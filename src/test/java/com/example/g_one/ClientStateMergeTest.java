package com.example.g_one;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Regression for the reconnect race: a private warning that reaches the phone just before the `state` snapshot must not
 * be erased by it. Runs the real static/app.js under Node (skipped, visibly, if Node is not installed).
 */
class ClientStateMergeTest {
    @Test
    void warningArrivingBeforeStateSnapshotSurvivesIt() throws Exception {
        assumeTrue(run("node", "--version") == 0, "node is not installed");
        Path script = Path.of("src/test/js/state-merge-check.js").toAbsolutePath();
        Path app = Path.of("src/main/resources/static/app.js").toAbsolutePath();
        assertThat(run("node", script.toString(), app.toString())).as("node state-merge-check.js").isZero();
    }

    /** A tab opened before the rename keeps its room token under the old sessionStorage key; a reload must still find it. */
    @Test
    void roomTokenSavedUnderTheFormerKeyIsMigrated() throws Exception {
        assumeTrue(run("node", "--version") == 0, "node is not installed");
        Path script = Path.of("src/test/js/session-store-check.js").toAbsolutePath();
        Path app = Path.of("src/main/resources/static/app.js").toAbsolutePath();
        assertThat(run("node", script.toString(), app.toString())).as("node session-store-check.js").isZero();
    }

    private static int run(String... cmd) throws Exception {
        try {
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            return p.waitFor();
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}
