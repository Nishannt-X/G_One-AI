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
     * start time, newest first, whatever order they are written in.
     */
    void saveGist(CallerKey key, CallGist gist);

    /**
     * Merges facts into the profile, one field at a time, atomically per key. An incoming fact replaces the stored fact
     * for its field only when it ranks at least as high: a said fact beats an inferred one whatever their times, so an
     * inferred fact never replaces a said one; between two facts of the same origin the newer {@link Provenance#atMs()}
     * wins, so an older said fact never replaces a newer said one; on equal times the incoming fact wins. Facts that
     * lose are dropped.
     */
    void upsertFacts(CallerKey key, List<ProfileFact> facts);

    /**
     * Deletes everything stored for this key: profile facts, gists and any deals the store keeps (the {@code forget_me}
     * tool). Afterwards {@link #load} returns empty until something new is saved.
     *
     * @return true when anything was stored for the key
     */
    boolean forget(CallerKey key);
}
