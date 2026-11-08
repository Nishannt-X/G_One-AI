package com.example.g_one.memory;

import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.MemoryStore;

/** The reference store against the shared contract, so the contract itself is known to describe the reference. */
class InMemoryMemoryStoreContractTest extends MemoryStoreContract {
    @Override
    protected MemoryStore newStore() {
        return new InMemoryMemoryStore();
    }
}
