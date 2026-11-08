package com.example.g_one.console;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** The dev replay endpoint does not exist on a default configuration. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReplayDisabledTest {
    @Autowired
    TestRestTemplate http;

    @Test
    void replayEndpointIsOffByDefault() {
        assertThat(http.postForEntity("/api/dev/console/replay", null, String.class).getStatusCode().value())
                .isEqualTo(404);
    }
}
