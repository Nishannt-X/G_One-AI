package com.example.g_one.memory;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a farmer's memory at hang-up (docs/memory.md "Write timing"): one summarizer (LLM) call, retried once, then
 * code checks the answer before anything is stored:
 * <ul>
 *   <li>the gist is at most {@value #MAX_GIST_WORDS} words; follow-ups are capped too;</li>
 *   <li>every 6+ digit run that is not a quantity, price or date is removed ({@link SecretScrubber}), in the
 *       transcript before it leaves for the LLM and again in everything the LLM returns;</li>
 *   <li>only whitelisted profile fields are kept ({@link ProfileFields}), always with provenance "inferred", so the
 *       store's precedence makes sure an inferred fact never replaces a said one.</li>
 * </ul>
 * When the summarizer is unavailable or fails twice, a template gist is written instead: "Call {date}: {crop},
 * outcome {outcome}."
 *
 * <p>A new gist keeps only this call's open follow-ups. An earlier follow-up the summarizer marks DONE is removed from
 * the earlier gist that holds it (that gist is saved again under its own call id), so later calls no longer load it
 * as open.
 *
 * <p>The summarizer and the template only ever see the memory snapshot loaded at call start, the one the name check
 * was asked about; the profile is never reloaded from the store for them. A first-time caller's snapshot is empty, so
 * their gist holds only this call, even if a profile for the number appeared in the store meanwhile.
 *
 * <p>The name-check result arrives as {@link CallTranscript#otherSpeaker()}: the call-end handler sets it to true when
 * the caller did not confirm the stored name. Then even the snapshot is not used: the summarizer gets an empty profile
 * and no earlier follow-ups, the template gist names no stored crop, no profile fact is written, and the gist is
 * flagged with {@value #OTHER_SPEAKER_PREFIX}.
 *
 * <p>Nothing is read or written when the caller ID is withheld ({@link FarmerMemory#withheld}). When memory existed at
 * call start but the store now holds nothing for the key, a forget happened meanwhile, so nothing is sent to the
 * summarizer or written ({@link Status#SKIPPED_FORGOTTEN}). The store's tombstone still drops any write from a call in
 * which the farmer asked to be forgotten. Deals are not written here: the confirmation state machine writes them.
 */
public final class GistWriter implements AutoCloseable {
    public static final int MAX_GIST_WORDS = 80;
    static final int MAX_FOLLOW_UPS = 5;
    static final int MAX_FOLLOW_UP_WORDS = 25;
    static final String OTHER_SPEAKER_PREFIX = "Other speaker (the name check failed): ";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(GistWriter.class);

    public enum Status { SKIPPED_WITHHELD, SKIPPED_FORGOTTEN, WRITTEN }

    public enum Source { NONE, SUMMARIZER, TEMPLATE }

    /**
     * What was handed to the store. A store tombstone (after forget) may still drop it, silently.
     */
    public record Result(Status status, Source source, Optional<CallGist> gist, List<ProfileFact> facts) {
        static final Result WITHHELD = new Result(Status.SKIPPED_WITHHELD, Source.NONE, Optional.empty(), List.of());
        static final Result FORGOTTEN = new Result(Status.SKIPPED_FORGOTTEN, Source.NONE, Optional.empty(), List.of());
    }

    private final MemoryStore store;
    private final CallSummarizer summarizer;
    private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();

    public GistWriter(MemoryStore store, CallSummarizer summarizer) {
        this.store = Objects.requireNonNull(store, "store");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
    }

    /** {@link #write} on a background thread, so hang-up handling never waits for the LLM. */
    public CompletableFuture<Result> writeAsync(FarmerMemory memoryAtStart, CallTranscript transcript) {
        return CompletableFuture.supplyAsync(() -> write(memoryAtStart, transcript), async);
    }

    /**
     * @param memoryAtStart the memory snapshot loaded at call start and authorized by the name check: the only memory
     *     the summarizer and the template see ({@link FarmerMemory#withheld} means write nothing)
     * @param transcript the finished call, in RAM only; it is not stored
     */
    public Result write(FarmerMemory memoryAtStart, CallTranscript transcript) {
        Objects.requireNonNull(memoryAtStart, "memoryAtStart");
        Objects.requireNonNull(transcript, "transcript");
        if (memoryAtStart.isWithheld()) {
            return Result.WITHHELD;
        }
        CallerKey key = memoryAtStart.callerKey().orElseThrow();
        if (forgottenSince(key, memoryAtStart)) {
            return Result.FORGOTTEN;
        }
        // Only the call-start snapshot, never a fresh load: the store may hold memory this call was never allowed to
        // see. A failed name check means someone else spoke: they must not hear (via the summarizer) or get written
        // into the gist anything from the farmer's memory, so the prompt and the template both see an empty memory.
        FarmerMemory current = transcript.otherSpeaker() ? FarmerMemory.empty(key) : memoryAtStart;
        CallTranscript scrubbed = scrubbed(transcript);

        Optional<CallSummarizer.Draft> draft = draft(scrubbed, current);
        String summary = draft.map(d -> capWords(SecretScrubber.scrub(d.gist()).strip(), MAX_GIST_WORDS)).orElse("");
        Source source = Source.SUMMARIZER;
        if (summary.isBlank()) {
            summary = template(transcript, current);
            source = Source.TEMPLATE;
        }
        if (transcript.otherSpeaker()) {
            summary = capWords(OTHER_SPEAKER_PREFIX + summary, MAX_GIST_WORDS);
        }
        List<String> followUps = draft.map(d -> followUps(d.followUps())).orElse(List.of());
        List<ProfileFact> facts = transcript.otherSpeaker() || draft.isEmpty()
                ? List.of()
                : facts(draft.get().profileUpdates(), transcript);

        CallGist gist = new CallGist(transcript.callId(), transcript.startedMs(), transcript.endedMs(), summary,
                followUps);
        store.upsertFacts(key, facts);
        store.saveGist(key, gist);
        draft.ifPresent(d -> closeEarlierFollowUps(key, current, d.followUps(), transcript.callId()));
        return new Result(Status.WRITTEN, source, Optional.of(gist), facts);
    }

    /**
     * True when memory existed at call start and the store now holds nothing for the key. Only a forget deletes memory,
     * so this call must not summarize or write. The store is read only for this check, never for the prompt. A
     * first-time caller (empty snapshot) is not checked: there is nothing to compare, and the store's tombstone drops
     * the writes of a call in which the farmer asked to be forgotten.
     */
    private boolean forgottenSince(CallerKey key, FarmerMemory memoryAtStart) {
        return !memoryAtStart.isEmpty() && store.load(key).map(FarmerMemory::isEmpty).orElse(true);
    }

    /**
     * A follow-up the summarizer marked DONE is removed from the earlier gist that holds it, so later calls no longer
     * load it as open. The earlier gist is saved again under its own call id ({@link MemoryStore#saveGist} replaces it),
     * so a forget tombstone still drops the rewrite. A DONE follow-up is matched by the id it was shown with
     * ({@link CallSummarizer#openFollowUps}) or else by its text; one that matches nothing shown is ignored.
     */
    private void closeEarlierFollowUps(CallerKey key, FarmerMemory current, List<CallSummarizer.FollowUp> drafts,
            CallId thisCall) {
        List<CallSummarizer.OpenFollowUp> shown = CallSummarizer.openFollowUps(current);
        Set<CallSummarizer.OpenFollowUp> done = new HashSet<>();
        for (CallSummarizer.FollowUp f : drafts) {
            if (f.open()) {
                continue;
            }
            shown.stream().filter(s -> matches(s, f)).findFirst().ifPresent(done::add);
        }
        if (done.isEmpty()) {
            return;
        }
        for (CallGist earlier : current.gists().stream().limit(CallSummarizer.FOLLOW_UP_GISTS).toList()) {
            if (earlier.callId().equals(thisCall)) {
                continue;
            }
            // openFollowUps lists each shown gist's follow-ups in the gist's own order, so the two lists line up.
            List<CallSummarizer.OpenFollowUp> mine = shown.stream()
                    .filter(s -> s.callId().equals(earlier.callId()))
                    .toList();
            List<String> remaining = new ArrayList<>();
            for (int i = 0; i < earlier.followUps().size(); i++) {
                if (i >= mine.size() || !done.contains(mine.get(i))) {
                    remaining.add(earlier.followUps().get(i));
                }
            }
            if (remaining.size() != earlier.followUps().size()) {
                store.saveGist(key, new CallGist(earlier.callId(), earlier.startedMs(), earlier.endedMs(),
                        earlier.summary(), remaining));
            }
        }
    }

    /** By the id it was shown with when that id is known, else by text. */
    private static boolean matches(CallSummarizer.OpenFollowUp shown, CallSummarizer.FollowUp done) {
        String ref = done.ref() == null ? "" : done.ref().strip();
        if (!ref.isEmpty() && shown.id().equalsIgnoreCase(ref)) {
            return true;
        }
        boolean refKnown = !ref.isEmpty() && ref.matches("(?i)F\\d+");
        return !refKnown && done.text() != null && !normalized(done.text()).isEmpty()
                && normalized(shown.text()).equals(normalized(done.text()));
    }

    /** Lower case, no "(due ~...)" suffix, no trailing punctuation, single spaces. */
    static String normalized(String followUp) {
        return followUp.strip()
                .replaceAll("\\s*\\(due ~[^)]*\\)$", "")
                .replaceAll("[\\s.!?]+$", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        async.close();
    }

    private Optional<CallSummarizer.Draft> draft(CallTranscript scrubbed, FarmerMemory current) {
        if (!summarizer.available()) {
            return Optional.empty();
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return Optional.of(summarizer.summarize(scrubbed, current));
            } catch (RuntimeException e) {
                // Never log the transcript or the key; the exception type and attempt are enough.
                log.warn("Gist summarizer failed (attempt {} of 2): {}", attempt, e.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    private static CallTranscript scrubbed(CallTranscript t) {
        List<CallTranscript.Line> lines = t.lines().stream()
                .map(l -> new CallTranscript.Line(l.speaker(), SecretScrubber.scrub(l.text()), l.atMs()))
                .toList();
        return new CallTranscript(t.callId(), t.startedMs(), t.endedMs(), lines, t.otherSpeaker(), t.outcome());
    }

    private static List<String> followUps(List<CallSummarizer.FollowUp> drafts) {
        List<String> out = new ArrayList<>();
        for (CallSummarizer.FollowUp f : drafts) {
            if (!f.open() || f.text() == null) {
                continue;
            }
            String text = capWords(SecretScrubber.scrub(f.text()).strip(), MAX_FOLLOW_UP_WORDS);
            if (text.isBlank()) {
                continue;
            }
            out.add(followUp(text, f.dueHint() == null ? "" : SecretScrubber.scrub(f.dueHint())));
            if (out.size() == MAX_FOLLOW_UPS) {
                break;
            }
        }
        return out;
    }

    /** One stored follow-up line: "Find a buyer for ~20 quintal red onion (due ~2026-11-08)". */
    static String followUp(String text, String dueHint) {
        String due = dueHint == null ? "" : dueHint.strip();
        return due.isEmpty() || due.length() > 30 ? text.strip() : text.strip() + " (due ~" + due + ")";
    }

    /** Whitelisted, secret-free facts, one per field (the last one proposed wins), all "inferred". */
    private static List<ProfileFact> facts(List<CallSummarizer.FactUpdate> updates, CallTranscript t) {
        Map<String, ProfileFact> byField = new LinkedHashMap<>();
        Provenance provenance = Provenance.inferred(t.callId(), t.endedMs());
        for (CallSummarizer.FactUpdate u : updates) {
            String value = u.value() == null ? "" : u.value().strip();
            if (SecretScrubber.hasSecret(value)) {
                continue; // a fact is short; one with a secret-looking number is dropped, not patched
            }
            Optional<String> field = ProfileFields.allowed(u.field(), value);
            if (field.isEmpty()) {
                continue;
            }
            if (field.get().equals(ProfileFields.LANGUAGE)) {
                value = value.toUpperCase(Locale.ROOT);
            }
            byField.put(field.get(), new ProfileFact(field.get(), value, provenance));
        }
        return List.copyOf(byField.values());
    }

    /** "Call {date}: {crop}, outcome {outcome}." from docs/memory.md, for when the summarizer is off or failed. */
    static String template(CallTranscript t, FarmerMemory current) {
        String date = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(t.startedMs()).atZone(INDIA));
        String text = String.join(" ", t.lines().stream().map(CallTranscript.Line::text).toList())
                .toLowerCase(Locale.ROOT);
        Optional<String> crop = current.facts().stream()
                .map(ProfileFact::field)
                .filter(ProfileFields::isCrop)
                .map(f -> f.substring(ProfileFields.CROP_PREFIX.length()))
                .filter(text::contains)
                .findFirst();
        String outcome = t.outcome()
                .map(o -> o.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .orElse("not recorded");
        return "Call " + date + ": " + crop.map(c -> c + ", ").orElse("") + "outcome " + outcome + ".";
    }

    static String capWords(String text, int max) {
        String[] words = text.strip().split("\\s+");
        if (words.length <= max) {
            return text.strip().replaceAll("\\s+", " ");
        }
        return String.join(" ", Arrays.copyOf(words, max)) + "…";
    }
}
