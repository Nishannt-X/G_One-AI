package com.example.g_one.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Keeps the three copies of the console contract in step: the Java records, the examples in
 * {@code docs/console-events.md} and the TypeScript types in {@code web/src/types/console.ts}.
 */
class ConsoleEventJsonTest {
    // Tests run with the backend folder as working directory.
    private static final Path DOC = Path.of("..", "docs", "console-events.md");
    private static final Path TS = Path.of("..", "web", "src", "types", "console.ts");

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    private static String read(Path p) throws IOException {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /** JSON type name for each event record, from the {@code @JsonSubTypes} on {@link ConsoleEvent}. */
    private static Map<Class<?>, String> typeNames() {
        Map<Class<?>, String> names = new LinkedHashMap<>();
        for (JsonSubTypes.Type t : ConsoleEvent.class.getAnnotation(JsonSubTypes.class).value()) {
            names.put(t.value(), t.name());
        }
        return names;
    }

    @Test
    void everyEventRecordHasOneUniqueJsonTypeName() {
        Map<Class<?>, String> names = typeNames();

        assertThat(names.keySet()).containsExactlyInAnyOrder(ConsoleEvent.class.getPermittedSubclasses());
        assertThat(new LinkedHashSet<>(names.values())).hasSize(names.size());
        assertThat(names.values()).allMatch(n -> n.matches("[a-z]+(_[a-z]+)*"));
    }

    @Test
    void everyDocExampleReadsIntoItsRecordAndWritesBackIdentically() throws Exception {
        Matcher m = Pattern.compile("```json\n(.*?)\n```", Pattern.DOTALL).matcher(read(DOC));
        List<String> seen = new ArrayList<>();
        while (m.find()) {
            JsonNode example = json.readTree(m.group(1));
            ConsoleEvent event = json.readValue(m.group(1), ConsoleEvent.class);

            assertThat(typeNames().get(event.getClass())).isEqualTo(example.get("type").asText());
            assertThat(json.readTree(json.writeValueAsString(event))).isEqualTo(example);
            seen.add(example.get("type").asText());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(typeNames().values());
    }

    @Test
    void serializingARecordAddsTheTypeAndUsesWireValues() throws Exception {
        ConsoleEvent e = new ConsoleEvent.DealUpdated(CallId.of("call-1"), 5, ConsoleEvent.DealState.AGREED, "Onion",
                null, null, "20 quintal", new BigDecimal("3400"), "quintal", null, true, true);

        JsonNode node = json.readTree(json.writeValueAsString(e));

        assertThat(node.get("type").asText()).isEqualTo("deal_updated");
        assertThat(node.get("callId").asText()).isEqualTo("call-1");
        assertThat(node.get("state").asText()).isEqualTo("agreed");
        assertThat(node.has("variety")).isTrue();
        assertThat(node.get("variety").isNull()).isTrue();
    }

    @Test
    void theTypeScriptTypesHaveExactlyTheFieldsOfTheJavaRecords() throws Exception {
        Map<String, Set<String>> ts = tsInterfaces(read(TS));

        for (Map.Entry<Class<?>, String> e : typeNames().entrySet()) {
            Class<?> record = e.getKey();
            Set<String> javaFields = new LinkedHashSet<>(List.of("type"));
            javaFields.addAll(components(record));

            assertThat(ts).as("TypeScript interface %s", record.getSimpleName()).containsKey(record.getSimpleName());
            assertThat(ts.get(record.getSimpleName())).as(record.getSimpleName()).isEqualTo(javaFields);
            assertThat(read(TS)).contains("type: '" + e.getValue() + "'");
        }
        for (Class<?> shared : List.of(ProfileFact.class, Provenance.class, CallGist.class)) {
            assertThat(ts.get(shared.getSimpleName())).as(shared.getSimpleName())
                    .isEqualTo(new LinkedHashSet<>(components(shared)));
        }
    }

    private static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(c -> c.getName()).toList();
    }

    private static Map<String, Set<String>> tsInterfaces(String source) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("export interface (\\w+) \\{\n(.*?)\n}", Pattern.DOTALL).matcher(source);
        while (m.find()) {
            Set<String> fields = new LinkedHashSet<>();
            Matcher f = Pattern.compile("^\\s*(\\w+)\\??:", Pattern.MULTILINE).matcher(m.group(2));
            while (f.find()) {
                fields.add(f.group(1));
            }
            out.put(m.group(1), fields);
        }
        return out;
    }
}
