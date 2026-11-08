package com.example.g_one.memory;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads the synthetic demo farmer from a seed file in the docs/memory.md "Demo seed farmer" format. The file never holds
 * a phone number: {@code phoneEnvKey} names the env var that does, and only the {@link CallerKey} made from it is
 * stored.
 *
 * <p>Seeding only fills gaps: a seed gist is inserted only when no gist with its call id is stored, and a seed fact
 * only when its field is not stored. So running it on every start never undoes a later edit (a follow-up marked done, a
 * corrected village), and a newer fact from a real call is never overwritten by the seed's older one. A farmer who
 * said "forget me" stays forgotten: the store's tombstone drops the seed's older writes. To reseed before a recording,
 * delete {@code g-one.db} first.
 *
 * <p>Profile timestamps ({@code noticeGivenAt}, {@code createdAt}, {@code updatedAt}) and {@code deals} have no place
 * in the {@link MemoryStore} contract yet and are skipped.
 */
public final class MemorySeeder {
    public enum Status { SEEDED, SKIPPED_NO_PHONE, SKIPPED_NO_HMAC_KEY }

    /** {@code facts} and {@code gists} count what this run handed to the store (absent before it). */
    public record Result(Status status, int facts, int gists) {}

    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final List<String> SINGLE = List.of(ProfileFields.NAME, ProfileFields.VILLAGE,
            ProfileFields.DISTRICT, ProfileFields.STATE, ProfileFields.USUAL_MANDI, ProfileFields.LANGUAGE);

    private MemorySeeder() {}

    /**
     * @param seed the parsed seed file
     * @param env looks up the env var named by {@code phoneEnvKey}; returns null when unset
     * @param salt the HMAC key ({@code G_ONE_MEMORY_HMAC_KEY}), empty when unset
     * @throws IllegalStateException when the seed is malformed or holds a secret-looking number
     */
    public static Result seed(MemoryStore store, JsonNode seed, Function<String, String> env, Optional<byte[]> salt) {
        String envKey = seed.path("phoneEnvKey").asText("");
        if (!ENV_NAME.matcher(envKey).matches()) {
            throw new IllegalStateException("Seed phoneEnvKey must name an env var, never hold a number");
        }
        List<ProfileFact> facts = facts(seed.path("profile"));
        List<CallGist> gists = gists(seed.path("gists"));

        String phone = env.apply(envKey);
        if (phone == null || phone.isBlank()) {
            return new Result(Status.SKIPPED_NO_PHONE, 0, 0);
        }
        if (salt.isEmpty()) {
            return new Result(Status.SKIPPED_NO_HMAC_KEY, 0, 0);
        }
        CallerKey key;
        try {
            key = CallerKey.fromPhone(phone, salt.get());
        } catch (IllegalArgumentException e) {
            // CallerKey's message never contains the number.
            throw new IllegalStateException("Seed phone " + envKey + ": " + e.getMessage());
        }
        FarmerMemory stored = store.load(key).orElse(FarmerMemory.empty(key));
        Set<String> storedFields = stored.facts().stream().map(ProfileFact::field).collect(Collectors.toSet());
        Set<CallId> storedCalls = stored.gists().stream().map(CallGist::callId).collect(Collectors.toSet());
        List<ProfileFact> newFacts = facts.stream().filter(f -> !storedFields.contains(f.field())).toList();
        List<CallGist> newGists = gists.stream().filter(g -> !storedCalls.contains(g.callId())).toList();
        store.upsertFacts(key, newFacts);
        newGists.forEach(g -> store.saveGist(key, g));
        return new Result(Status.SEEDED, newFacts.size(), newGists.size());
    }

    private static List<ProfileFact> facts(JsonNode profile) {
        if (!profile.isObject()) {
            throw new IllegalStateException("Seed needs a profile object");
        }
        List<ProfileFact> out = new ArrayList<>();
        for (String field : SINGLE) {
            JsonNode f = profile.path(field);
            if (!f.isMissingNode() && !f.isNull()) {
                out.add(fact(field, f));
            }
        }
        for (JsonNode crop : profile.path("crops")) {
            out.add(fact(ProfileFields.crop(crop.path("value").asText("")), crop));
        }
        return out;
    }

    private static ProfileFact fact(String field, JsonNode f) {
        String value = text(f, "value");
        String source = text(f, "source").toLowerCase(Locale.ROOT);
        Provenance.Origin origin = switch (source) {
            case "said" -> Provenance.Origin.SAID;
            case "inferred" -> Provenance.Origin.INFERRED;
            default -> throw new IllegalStateException("Seed fact source must be said or inferred");
        };
        if (ProfileFields.allowed(field, value).isEmpty()) {
            throw new IllegalStateException("Seed fact " + field + " is not a profile field or has a bad value");
        }
        return new ProfileFact(field, clean(value),
                new Provenance(CallId.of(text(f, "callId")), origin, millis(text(f, "at"))));
    }

    private static List<CallGist> gists(JsonNode gists) {
        List<CallGist> out = new ArrayList<>();
        for (JsonNode g : gists) {
            List<String> followUps = new ArrayList<>();
            for (JsonNode f : g.path("followUps")) {
                if (!"DONE".equalsIgnoreCase(f.path("status").asText("OPEN"))) {
                    String due = f.path("dueHint").isTextual() ? f.path("dueHint").asText() : null;
                    followUps.add(clean(GistWriter.followUp(text(f, "text"), due)));
                }
            }
            String summary = clean(text(g, "gist"));
            if (!GistWriter.capWords(summary, GistWriter.MAX_GIST_WORDS).equals(summary)) {
                throw new IllegalStateException("Seed gist " + text(g, "callId") + " is over "
                        + GistWriter.MAX_GIST_WORDS + " words");
            }
            out.add(new CallGist(CallId.of(text(g, "callId")), millis(text(g, "startedAt")),
                    millis(text(g, "endedAt")), summary, followUps));
        }
        return out;
    }

    /** Seeds are synthetic and hand-written; a secret-looking number in one is a mistake, so refuse it. */
    private static String clean(String text) {
        if (SecretScrubber.hasSecret(text)) {
            throw new IllegalStateException("Seed text holds a 6+ digit number that is not a quantity, price or date");
        }
        return text;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new IllegalStateException("Seed is missing " + field);
        }
        return v.asText();
    }

    private static long millis(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Seed time is not ISO-8601 with an offset");
        }
    }
}
