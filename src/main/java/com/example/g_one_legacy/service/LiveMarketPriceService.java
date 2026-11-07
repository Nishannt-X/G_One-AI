package com.example.g_one_legacy.service;

/**
 * Provides live commodity market prices for the AI Whisperer feature.
 * Hardcoded for MVP — will be replaced with actual market data API.
 */
public interface LiveMarketPriceService {

    /**
     * Returns the current market price string for a commodity.
     * @param commodity the commodity name (e.g., "wheat", "rice")
     * @return formatted price string, or null if commodity not found
     */
    String getMarketPrice(String commodity);
}
