package com.example.g_one_legacy.service.impl;

import com.example.g_one_legacy.service.LiveMarketPriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hardcoded commodity market prices for MVP.
 * Replace with actual live API (e.g., Agmarknet, data.gov.in) later.
 */
@Service
public class LiveMarketPriceServiceImpl implements LiveMarketPriceService {

    private static final Logger log = LoggerFactory.getLogger(LiveMarketPriceServiceImpl.class);

    private static final Map<String, String> MARKET_PRICES = new ConcurrentHashMap<>(Map.of(
            "wheat", "₹28 per kg",
            "rice", "₹32 per kg",
            "cotton", "₹75 per kg",
            "soybean", "₹46 per kg",
            "maize", "₹22 per kg",
            "sugar", "₹36 per kg",
            "mustard", "₹54 per kg",
            "chana", "₹52 per kg",
            "bajra", "₹25 per kg",
            "jowar", "₹31 per kg"));

    @Override
    public String getMarketPrice(String commodity) {
        if (commodity == null || commodity.isBlank())
            return null;

        String normalized = commodity.toLowerCase().trim();
        String price = MARKET_PRICES.get(normalized);

        if (price != null) {
            log.info("[MARKET] Lookup for '{}': {}", normalized, price);
        } else {
            log.warn("[MARKET] No price data for commodity: '{}'", normalized);
        }
        return price;
    }
}
