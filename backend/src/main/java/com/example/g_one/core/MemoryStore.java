package com.example.g_one.core;

import java.util.List;
import java.util.Optional;

/**
 * Farmer memory, keyed by {@link CallerKey} only: a store never sees a raw phone number. Implementations are safe to
 * call from any thread.
 */
public interface MemoryStore {
    /** @return the caller's profile and gists (newest first), or empty when nothing was ever saved for this key */
    Optional<FarmerMemory> load(CallerKey key);

    /** Saves a call's gist. Saving again for the same call id replaces that call's gist. */
    void saveGist(CallerKey key, CallGist gist);

    /** Adds facts, replacing any existing fact with the same field. */
    void upsertFacts(CallerKey key, List<ProfileFact> facts);
}
