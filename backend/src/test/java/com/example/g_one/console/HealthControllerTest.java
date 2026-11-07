package com.example.g_one.console;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/** Boots the whole app on defaults only (no env vars needed) and checks the health endpoint and security headers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthControllerTest {
    @Autowired
    TestRestTemplate http;

    @Test
    void healthSaysOkWithNoConfiguration() {
        ResponseEntity<String> res = http.getForEntity("/api/health", String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("{\"ok\":true}");
        assertThat(res.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
