package com.example.g_one.core;

import java.util.List;
import java.util.Optional;

/**
 * Farmer memory, keyed by {@link CallerKey} only: a store never sees a raw phone number. Implementations are safe to
 * call from any thread. Nothing calls a store when the caller ID is withheld ({@link FarmerMemory#withheld}).
 */
public interface MemoryStore {
    /**
     * @return the caller's profile and gists (newest call first, by {@link CallGist#startedMs()}), or empty when nothing
     *     is saved for this key
     */
    Optional<FarmerMemory> load(CallerKey key);

    /**
     * Saves a call's gist. Saving again for the same call id replaces that call's gist. Gists stay ordered by call
     * start time, newest first, whatever order they are written in. Dropped when a {@link #forget} covers it.
     */
    void saveGist(CallerKey key, CallGist gist);

    /**
     * Merges facts into the profile, one field at a time, atomically per key. An incoming fact replaces the stored fact
     * for its field only when it ranks at least as high: a said fact beats an inferred one whatever their times, so an
     * inferred fact never replaces a said one; between two facts of the same origin the newer {@link Provenance#atMs()}
     * wins, so an older said fact never replaces a newer said one; on equal times the incoming fact wins. Facts that
     * lose are dropped, and so are facts a {@link #forget} covers.
     */
    void upsertFacts(CallerKey key, List<ProfileFact> facts);

    /**
     * Deletes everything stored for this key: profile facts, gists and any deals the store keeps (the {@code forget_me}
     * tool). Afterwards {@link #load} returns empty until a later call saves something new.
     *
     * <p>Forgetting also leaves a tombstone so that writes already on their way cannot bring the data back. Atomically
     * with every other write for the key, the store drops (silently, as if never made):
     * <ul>
     *   <li>every {@link #saveGist} and {@link #upsertFacts} write from {@code duringCall} (its gist's
     *       {@link CallGist#callId()} or the fact's {@link Provenance#callId()}), whenever it arrives: memory stays off
     *       for the rest of the call in which the farmer asked to be forgotten, including its hang-up gist;</li>
     *   <li>every gist whose {@link CallGist#startedMs()} is at or before {@code atMs} and every fact whose
     *       {@link Provenance#atMs()} is at or before {@code atMs}: late or in-flight writes from this or earlier calls.
     *       </li>
     * </ul>
     * A later call (another call id, and a gist start or fact time after {@code atMs}) may write again. Forgetting more
     * than once keeps every tombstoned call and the latest forget time. A write that wins the race and lands before
     * {@code forget} is deleted by it; one that lands after is dropped, so either way nothing from before the forget
     * survives. Implementations that persist data must keep the tombstone at least as long as a late write can arrive.
     *
     * @param duringCall the call in which the farmer asked to be forgotten
     * @param atMs when the farmer asked, epoch milliseconds from the same clock as fact and gist times
     * @return true when anything was stored for the key
     */
    boolean forget(CallerKey key, CallId duringCall, long atMs);
}
