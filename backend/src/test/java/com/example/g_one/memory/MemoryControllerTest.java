package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** The console memory endpoint behind {@code G_ONE_ACCESS_CODE}, on the in-memory store. Synthetic data only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "g-one.memory.store=in-memory",
    "g-one.memory.access-code=test-access-code-not-a-secret",
})
class MemoryControllerTest {
    private static final String CODE = "test-access-code-not-a-secret";
    private static final CallerKey SURESH = FakeLeg.FARMER_KEY;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate http;
    @Autowired
    MemoryStore store;

    private ResponseEntity<String> get(String key, String code) {
        HttpHeaders headers = new HttpHeaders();
        if (code != null) {
            headers.set(MemoryController.HEADER, code);
        }
        return http.exchange("/api/memory/" + key, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void theInMemoryStoreIsWiredWhenAskedFor() {
        assertThat(store).isInstanceOf(InMemoryMemoryStore.class);
    }

    @Test
    void withoutTheRightAccessCodeNothingIsShown() {
        store.upsertFacts(SURESH, List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("c-1"), 1))));

        for (String code : new String[] {null, "", "wrong", CODE + "x"}) {
            ResponseEntity<String> res = get(SURESH.value(), code);
            assertThat(res.getStatusCode().value()).as("code %s", code).isEqualTo(401);
            assertThat(res.getBody()).doesNotContain("Suresh");
        }
    }

    @Test
    void theRightAccessCodeShowsFactsWithProvenanceAndGistsNewestFirst() throws Exception {
        CallerKey key = CallerKey.fromPhone("+15005550009", FakeLeg.SALT);
        store.upsertFacts(key, List.of(
                new ProfileFact("name", "Suresh", Provenance.said(CallId.of("c-1028"), 1_000)),
                new ProfileFact("state", "Maharashtra", Provenance.inferred(CallId.of("c-1103"), 2_000))));
        store.saveGist(key, new CallGist(CallId.of("c-1028"), 1_000, 2_000, "Sold tomato.", List.of()));
        store.saveGist(key, new CallGist(CallId.of("c-1103"), 3_000, 4_000, "Asked onion price.",
                List.of("Find a buyer")));

        ResponseEntity<String> res = get(key.value(), CODE);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.path("callerKey").asText()).isEqualTo(key.value());
        assertThat(body.path("known").asBoolean()).isTrue();
        assertThat(body.path("facts").get(0).toString()).isEqualTo(
                "{\"field\":\"name\",\"value\":\"Suresh\",\"source\":\"said\",\"callId\":\"c-1028\","
                        + "\"at\":\"1970-01-01T00:00:01Z\"}");
        assertThat(body.path("facts").get(1).path("source").asText()).isEqualTo("inferred");
        assertThat(body.path("gists").get(0).path("callId").asText()).isEqualTo("c-1103");
        assertThat(body.path("gists").get(0).path("followUps").get(0).asText()).isEqualTo("Find a buyer");
        assertThat(body.path("gists").get(1).path("summary").asText()).isEqualTo("Sold tomato.");
    }

    @Test
    void anUnknownCallerIsAnEmptyMemoryAndABadKeyIsRejected() throws Exception {
        CallerKey nobody = CallerKey.fromPhone("+15005550008", FakeLeg.SALT);

        JsonNode empty = JSON.readTree(get(nobody.value(), CODE).getBody());
        assertThat(empty.path("known").asBoolean()).isFalse();
        assertThat(empty.path("facts")).isEmpty();
        assertThat(empty.path("gists")).isEmpty();

        ResponseEntity<String> bad = get("15005550006", CODE);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(bad.getBody()).doesNotContain("15005550006");
    }

    @Test
    void withNoAccessCodeConfiguredTheCheckIsOff() {
        MemoryStore local = new InMemoryMemoryStore();
        local.upsertFacts(SURESH, List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("c-1"), 1))));
        MemoryController open = new MemoryController(local, new MemoryProperties("in-memory", null, null, null, ""));

        ResponseEntity<?> res = open.memory(SURESH.value(), null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(((MemoryController.MemoryView) res.getBody()).facts()).hasSize(1);
    }
}
