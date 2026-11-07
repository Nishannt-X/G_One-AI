package com.example.g_one;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The home page, the app page and the old room links, against the running app. No upstream is called. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "g-one.access-code=")
class SiteRoutingTest {
    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String createRoom() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rooms"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"farmer\",\"language\":\"hi-IN\",\"accessCode\":\"\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(res.body());
        return body.get("roomId").asText();
    }

    @Test
    void homePageIsTheLandingPageWithTheStage() throws Exception {
        HttpResponse<String> res = get("/");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("id=\"stageForm\"", "site.css", "stage.js").doesNotContain("id=\"micBtn\"");
    }

    @Test
    void appPageIsServedDirectly() throws Exception {
        HttpResponse<String> res = get("/app.html");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("id=\"micBtn\"", "src=\"app.js\"");
    }

    @Test
    void oldRoomLinksStillOpenTheAppAtTheSameUrl() throws Exception {
        String room = createRoom();
        for (String path : new String[] {"/?room=" + room, "/index.html?room=" + room}) {
            HttpResponse<String> res = get(path);
            // Forwarded, not redirected: the browser keeps /?room=<id>, which the page script and share link rely on.
            assertThat(res.statusCode()).as(path).isEqualTo(200);
            assertThat(res.headers().firstValue("Location")).as(path).isEmpty();
            assertThat(res.body()).as(path).contains("id=\"micBtn\"", "src=\"app.js\"").doesNotContain("id=\"stageForm\"");
        }
        // A seat URL from the stage reaches the same page.
        assertThat(get("/app.html?room=" + room + "&seat=farmer").body()).contains("id=\"seatless\"");
    }

    @Test
    void everyPageMayBeFramedByItsOwnSiteOnly() throws Exception {
        String room = createRoom();
        for (String path : new String[] {"/", "/app.html", "/?room=" + room, "/stage.js"}) {
            HttpResponse<String> res = get(path);
            String csp = res.headers().firstValue("Content-Security-Policy").orElse("");
            assertThat(csp).as(path).contains("frame-ancestors 'self'").contains("default-src 'self'")
                    .doesNotContain("frame-ancestors 'none'").doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
            assertThat(res.headers().firstValue("Referrer-Policy")).as(path).contains("no-referrer");
            assertThat(res.headers().firstValue("Permissions-Policy")).as(path).contains("microphone=(self)");
        }
    }

    @Test
    void everyLocalAssetThePagesReferenceIsServed() throws Exception {
        Pattern ref = Pattern.compile("(?:src|href)=\"([^\"]+)\"");
        Set<String> paths = new LinkedHashSet<>();
        for (String page : new String[] {"/", "/app.html"}) {
            Matcher m = ref.matcher(get(page).body());
            while (m.find()) {
                String target = m.group(1);
                assertThat(target).as("%s links outside the site: %s", page, target).doesNotStartWith("http").doesNotStartWith("//");
                if (!target.startsWith("#")) paths.add(target.startsWith("/") ? target : "/" + target);
            }
        }
        assertThat(paths).contains("/site.css", "/stage-core.js", "/stage.js", "/style.css", "/app.js", "/favicon.svg");
        for (String path : paths) assertThat(get(path).statusCode()).as(path).isEqualTo(200);
    }

    @Test
    void apiConfigIsUnchanged() throws Exception {
        JsonNode cfg = json.readTree(get("/api/config").body());
        assertThat(cfg.has("languages")).isTrue();
        assertThat(cfg.get("accessCodeRequired").asBoolean()).isFalse();
        assertThat(cfg.has("ready")).isTrue();
    }
}
