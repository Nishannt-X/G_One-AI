package com.example.g_one.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
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
 * Keeps the three copies of the console contract in step: the Java records, the examples and field tables in
 * {@code docs/console-events.md}, and the TypeScript types in {@code web/src/types/console.ts}. It compares field
 * names, wire types (string, number, boolean, object, arrays, shared types), nullability, enum values and the
 * {@code type} discriminator of every event.
 */
class ConsoleEventJsonTest {
    // Tests run with the backend folder as working directory.
    private static final Path DOC = Path.of("..", "docs", "console-events.md");
    private static final Path TS = Path.of("..", "web", "src", "types", "console.ts");

    /** Enum types on the wire, by simple name. */
    private static final List<Class<? extends Enum<?>>> ENUMS = List.of(LegRole.class, LegSource.class,
            ConsoleEvent.WhisperKind.class, ConsoleEvent.DealState.class, Provenance.Origin.class);

    /** Records shared by several events. */
    private static final List<Class<?>> SHARED = List.of(Provenance.class, ProfileFact.class, CallGist.class);

    /** Fields every event has; the docs describe them once under "Common rules" instead of per event. */
    private static final Set<String> COMMON = Set.of("type", "callId", "atMs");

    private static final Pattern JSON_BLOCK = Pattern.compile("```json\n(.*?)\n```", Pattern.DOTALL);

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    /**
     * One field on the wire: a type token and whether it can be null. Tokens: {@code string}, {@code number},
     * {@code boolean}, {@code object} (free-form JSON), a shared type name such as {@code LegRole} or
     * {@code ProfileFact}, or one of those followed by {@code []} for an array.
     */
    record Wire(String type, boolean nullable) {
        @Override
        public String toString() {
            return type + (nullable ? " | null" : "");
        }
    }

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
        Matcher m = JSON_BLOCK.matcher(read(DOC));
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
    void discriminatorsMatchOneToOneAcrossJavaDocsAndTypeScript() throws Exception {
        String ts = read(TS);
        Map<String, Map<String, String>> tsRaw = tsInterfaceTypes(ts);
        Map<String, String> javaByInterface = new LinkedHashMap<>();
        typeNames().forEach((cls, name) -> javaByInterface.put(cls.getSimpleName(), name));

        for (Map.Entry<String, String> e : javaByInterface.entrySet()) {
            assertThat(tsRaw).as("TypeScript interface %s", e.getKey()).containsKey(e.getKey());
            assertThat(tsRaw.get(e.getKey()).get("type")).as("%s.type", e.getKey())
                    .isEqualTo("'" + e.getValue() + "'");
        }
        Set<String> tsEventInterfaces = new LinkedHashSet<>();
        tsRaw.forEach((name, fields) -> {
            if (fields.containsKey("type")) {
                tsEventInterfaces.add(name);
            }
        });
        assertThat(tsEventInterfaces).isEqualTo(javaByInterface.keySet());
        assertThat(tsUnionMembers(ts, "ConsoleEvent")).containsExactlyInAnyOrderElementsOf(javaByInterface.keySet());
        assertThat(tsKnownTypes(ts)).containsExactlyInAnyOrderElementsOf(javaByInterface.values());
        assertThat(docEventSections(read(DOC)).keySet())
                .containsExactlyInAnyOrderElementsOf(javaByInterface.values());
    }

    @Test
    void enumWireValuesMatchAcrossJavaDocsAndTypeScript() throws Exception {
        Map<String, Set<String>> ts = tsStringUnions(read(TS));
        Map<String, Set<String>> doc = docEnumValues(read(DOC));

        for (Class<? extends Enum<?>> e : ENUMS) {
            Set<String> wire = new LinkedHashSet<>();
            for (Enum<?> constant : e.getEnumConstants()) {
                wire.add(json.convertValue(constant, String.class));
            }
            assertThat(ts.get(e.getSimpleName())).as("TypeScript %s", e.getSimpleName()).isEqualTo(wire);
            assertThat(doc.get(e.getSimpleName())).as("docs %s", e.getSimpleName()).isEqualTo(wire);
        }
    }

    @Test
    void everyFieldHasTheSameTypeAndNullabilityInJavaDocsAndTypeScript() throws Exception {
        Map<String, Map<String, Wire>> ts = tsShapes(read(TS));
        String doc = read(DOC);
        Map<String, Map<String, Wire>> docEvents = docEventShapes(doc);
        Map<String, Map<String, Wire>> docShared = docSharedShapes(doc);

        Map<Class<?>, String> events = typeNames();
        List<Class<?>> all = new ArrayList<>(events.keySet());
        all.addAll(SHARED);
        for (Class<?> record : all) {
            String name = record.getSimpleName();
            boolean isEvent = events.containsKey(record);
            Map<String, Wire> tsFields = new LinkedHashMap<>(ts.getOrDefault(name, Map.of()));
            tsFields.remove("type");
            Map<String, Wire> docFields = isEvent ? docEvents.get(events.get(record)) : docShared.get(name);
            assertThat(docFields).as("docs table for %s", name).isNotNull();

            List<String> components = components(record);
            assertThat(tsFields.keySet()).as("TypeScript fields of %s", name).containsExactlyElementsOf(components);
            List<String> documented = components.stream().filter(c -> !(isEvent && COMMON.contains(c))).toList();
            assertThat(docFields.keySet()).as("docs fields of %s", name)
                    .containsExactlyInAnyOrderElementsOf(documented);

            for (RecordComponent c : record.getRecordComponents()) {
                String field = name + "." + c.getName();
                Wire t = tsFields.get(c.getName());
                assertThat(t.type()).as("TypeScript type of %s", field).isEqualTo(javaWireType(c.getGenericType()));
                if (c.getType().isPrimitive()) {
                    assertThat(t.nullable()).as("%s is a Java primitive, so never null", field).isFalse();
                }
                Wire d = docFields.get(c.getName());
                if (d != null) {
                    assertThat(d).as("docs type of %s", field).isEqualTo(t);
                }
            }
        }
    }

    @Test
    void aNullInADocExampleIsAllowedByTheTypes() throws Exception {
        Map<String, Map<String, Wire>> ts = tsShapes(read(TS));
        Map<String, String> interfaceByType = new LinkedHashMap<>();
        typeNames().forEach((cls, name) -> interfaceByType.put(name, cls.getSimpleName()));
        Matcher m = JSON_BLOCK.matcher(read(DOC));
        while (m.find()) {
            JsonNode example = json.readTree(m.group(1));
            String type = example.get("type").asText();
            Map<String, Wire> fields = ts.get(interfaceByType.get(type));
            example.properties().forEach(f -> {
                if (f.getValue().isNull()) {
                    assertThat(fields.get(f.getKey()).nullable()).as("%s.%s", type, f.getKey()).isTrue();
                }
            });
        }
    }

    private static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** The wire type token of a Java record component. */
    private static String javaWireType(Type type) {
        if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
            return javaWireType(p.getActualTypeArguments()[0]) + "[]";
        }
        Class<?> c = (Class<?>) type;
        if (c == String.class || c == CallId.class || c == LegId.class || c == CallerKey.class) {
            return "string";
        }
        if (c == long.class || c == int.class || c == double.class || c == Long.class || c == Integer.class
                || c == BigDecimal.class) {
            return "number";
        }
        if (c == boolean.class || c == Boolean.class) {
            return "boolean";
        }
        if (c == JsonNode.class) {
            return "object";
        }
        if (c.isEnum() || c.isRecord()) {
            return c.getSimpleName();
        }
        throw new AssertionError("No wire type known for " + c + "; extend javaWireType");
    }

    // ---- TypeScript parsing -------------------------------------------------------------------------------------

    /** Raw TypeScript field types per interface, such as {@code "string | null"}. */
    private static Map<String, Map<String, String>> tsInterfaceTypes(String source) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("export interface (\\w+) \\{\n(.*?)\n}", Pattern.DOTALL).matcher(source);
        while (m.find()) {
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher f = Pattern.compile("^\\s*(\\w+)(\\??):\\s*(.+?)\\s*$", Pattern.MULTILINE).matcher(m.group(2));
            while (f.find()) {
                assertThat(f.group(2)).as("%s.%s: every field is always present, so none is optional", m.group(1),
                        f.group(1)).isEmpty();
                fields.put(f.group(1), f.group(3));
            }
            out.put(m.group(1), fields);
        }
        return out;
    }

    private static Map<String, Map<String, Wire>> tsShapes(String source) {
        Map<String, Map<String, Wire>> out = new LinkedHashMap<>();
        tsInterfaceTypes(source).forEach((name, fields) -> {
            Map<String, Wire> shape = new LinkedHashMap<>();
            fields.forEach((field, raw) -> shape.put(field, tsWire(raw)));
            out.put(name, shape);
        });
        return out;
    }

    private static Wire tsWire(String raw) {
        List<String> parts = new ArrayList<>(Arrays.stream(raw.split("\\|")).map(String::strip).toList());
        boolean nullable = parts.remove("null");
        assertThat(parts).as("TypeScript type %s", raw).hasSize(1);
        String t = parts.get(0);
        if (t.startsWith("Record<")) {
            t = "object";
        }
        return new Wire(t, nullable);
    }

    /** Aliases written as {@code export type X = 'a' | 'b'}. */
    private static Map<String, Set<String>> tsStringUnions(String source) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("^export type (\\w+) = ('[^']*'(?: \\| '[^']*')*)$", Pattern.MULTILINE)
                .matcher(source);
        while (m.find()) {
            Set<String> values = new LinkedHashSet<>();
            Matcher v = Pattern.compile("'([^']*)'").matcher(m.group(2));
            while (v.find()) {
                values.add(v.group(1));
            }
            out.put(m.group(1), values);
        }
        return out;
    }

    private static List<String> tsUnionMembers(String source, String alias) {
        Matcher m = Pattern.compile("export type " + alias + " =\n((?:\\s*\\| \\w+\n?)+)").matcher(source);
        assertThat(m.find()).as("TypeScript union %s", alias).isTrue();
        List<String> members = new ArrayList<>();
        Matcher member = Pattern.compile("\\| (\\w+)").matcher(m.group(1));
        while (member.find()) {
            members.add(member.group(1));
        }
        return members;
    }

    private static List<String> tsKnownTypes(String source) {
        Matcher m = Pattern.compile("const KNOWN_TYPES[^{]*\\{\n(.*?)\n}", Pattern.DOTALL).matcher(source);
        assertThat(m.find()).as("KNOWN_TYPES in console.ts").isTrue();
        List<String> keys = new ArrayList<>();
        Matcher k = Pattern.compile("^\\s*(\\w+): true,?$", Pattern.MULTILINE).matcher(m.group(1));
        while (k.find()) {
            keys.add(k.group(1));
        }
        return keys;
    }

    // ---- Docs parsing -------------------------------------------------------------------------------------------

    /** The text of each {@code ### type} section in the docs, keyed by type. */
    private static Map<String, String> docEventSections(String doc) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("^### (\\w+)\n(.*?)(?=^### |\\z)", Pattern.MULTILINE | Pattern.DOTALL)
                .matcher(doc);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    private static Map<String, Map<String, Wire>> docEventShapes(String doc) {
        Map<String, Map<String, Wire>> out = new LinkedHashMap<>();
        docEventSections(doc).forEach((type, text) -> {
            Map<String, Wire> fields = new LinkedHashMap<>();
            Matcher row = Pattern.compile("^\\| `(\\w+)` \\| ([^|]+?) \\|", Pattern.MULTILINE).matcher(text);
            while (row.find()) {
                fields.put(row.group(1), docWire(row.group(2)));
            }
            out.put(type, fields);
        });
        return out;
    }

    /** Rows of the "Shared types" table: name, JSON kind, values or fields. */
    private static List<String[]> docSharedRows(String doc) {
        int from = doc.indexOf("## Shared types");
        assertThat(from).as("docs section \"Shared types\"").isNotNegative();
        int to = doc.indexOf("\n## ", from + 1);
        List<String[]> rows = new ArrayList<>();
        Matcher row = Pattern.compile("^\\| `(\\w+)` \\| (\\w+) \\| (.+) \\|$", Pattern.MULTILINE)
                .matcher(doc.substring(from, to < 0 ? doc.length() : to));
        while (row.find()) {
            rows.add(new String[] {row.group(1), row.group(2), row.group(3)});
        }
        return rows;
    }

    private static Map<String, Set<String>> docEnumValues(String doc) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String[] r : docSharedRows(doc)) {
            if (r[1].equals("string")) {
                Set<String> values = new LinkedHashSet<>();
                Matcher v = Pattern.compile("`\"([^\"]*)\"`").matcher(r[2]);
                while (v.find()) {
                    values.add(v.group(1));
                }
                out.put(r[0], values);
            }
        }
        return out;
    }

    private static Map<String, Map<String, Wire>> docSharedShapes(String doc) {
        Map<String, Map<String, Wire>> out = new LinkedHashMap<>();
        for (String[] r : docSharedRows(doc)) {
            if (r[1].equals("object")) {
                Map<String, Wire> fields = new LinkedHashMap<>();
                for (String part : r[2].split(", ")) {
                    Matcher f = Pattern.compile("`(\\w+)` (.+)").matcher(part.strip());
                    assertThat(f.matches()).as("docs field \"%s\" of %s", part, r[0]).isTrue();
                    fields.put(f.group(1), docWire(f.group(2)));
                }
                out.put(r[0], fields);
            }
        }
        return out;
    }

    /** Docs JSON types read like "string", "ProfileFact array" or "number or null". */
    private static Wire docWire(String raw) {
        String t = raw.strip();
        boolean nullable = t.endsWith(" or null");
        if (nullable) {
            t = t.substring(0, t.length() - " or null".length());
        }
        if (t.endsWith(" array")) {
            t = t.substring(0, t.length() - " array".length()) + "[]";
        }
        return new Wire(t, nullable);
    }
}
