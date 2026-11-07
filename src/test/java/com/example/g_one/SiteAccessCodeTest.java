package com.example.g_one;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The stage creates rooms through the same endpoint as the app, so the access code still guards it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "g-one.access-code=test-only-code")
class SiteAccessCodeTest {
    @LocalServerPort
    int port;

    private HttpResponse<String> create(String code) throws Exception {
        String body = new ObjectMapper().writeValueAsString(java.util.Map.of("role", "farmer", "language", "hi-IN", "accessCode", code));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rooms"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void configAdvertisesTheCodeSoTheStageAsksForIt() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/config")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(new ObjectMapper().readTree(res.body()).get("accessCodeRequired").asBoolean()).isTrue();
        assertThat(res.body()).doesNotContain("test-only-code");
    }

    @Test
    void roomCreationNeedsTheCodeWhetherTheAppOrTheStageAsks() throws Exception {
        assertThat(create("").statusCode()).isEqualTo(403);
        assertThat(create("wrong").statusCode()).isEqualTo(403);
        assertThat(create("test-only-code").statusCode()).isEqualTo(201);
    }
}
