package com.example.g_one.agent.tools.mandi;

/** What a mandi price question produced. Every case has a spoken form ({@link MandiWording}). */
public sealed interface MandiAnswer {
    /**
     * A dated reference for the crop asked about. {@code askedMarket} is the market the farmer named when it differs
     * from the reference's market (the reference is still given, with its own market), otherwise null.
     */
    record Found(MandiSnapshot snapshot, String askedMarket) implements MandiAnswer {}

    /**
     * No dated reference for this crop. {@code available} is the crop a reference does exist for, or null when there
     * is no trustworthy reference at all right now.
     */
    record NoReference(String crop, String available) implements MandiAnswer {}
}
