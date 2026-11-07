---
title: Ground farmer-only market-rate advice
parent: FARMER-CALL-WAYFINDER.md
labels: [wayfinder:research]
status: closed
assignee: Codex
blocked_by: []
---

## Question

Which official Mandi data source can provide a dated price for a specified commodity, variety, market, and unit during the call? Define a safe comparison and a farmer-only spoken advisory, including what the system says when matching data is missing or stale. Decide whether the demo needs a live fetch or may use a clearly dated snapshot as fallback.

## Starting source

The [Government of India daily Mandi price catalog](https://www.data.gov.in/catalog/current-daily-price-various-commodities-various-markets-mandi) lists minimum, modal, and maximum wholesale prices. Access, freshness, and the exact demo crop/market remain unverified.

## Research notes

- The public [AGMARKNET 2.0 filters](https://api.agmarknet.gov.in/v1/daily-price-arrival/filters) and [last-week price endpoint](https://api.agmarknet.gov.in/v1/prices-and-arrivals/market-price/lastweek?marketId=161&stateId=20&commodityId=23&includeExcel=false) worked without a key on November 6. A test query returned Red onion at APMC Lasalgaon, ₹4,500/quintal for November 4. The row states market, variety, unit, and date, but not whether its figure is minimum, modal, or maximum.
- The [data.gov.in variety-wise resource](https://www.data.gov.in/resource/variety-wise-daily-market-prices-data-commodity) lists min, modal, and max fields, but its API required authorization in a test; an actual row fetch remains unverified. The [e-NAM dashboard](https://www.enam.gov.in/web/dashboard/agmarknet) visibly lists min, modal, max, unit, and date, but programmatic access is unverified.
- A buyer offer can be compared only to a matching commodity, variety, market, and unit, with the source date spoken to the farmer. A November 4 result is historical by the November 10 deadline and must not be presented as current then.

## Update 2026-11-06

An optional live lookup of the same AGMARKNET endpoint for one configured variety and market now exists; see
[DEMO](DEMO.md#live-market-price-agmarknet-read-only-no-key) and [DECISIONS](DECISIONS.md). The snapshot below remains
the default and the wording rules in the resolution still apply.

## Resolution

The first demo uses a **preloaded, clearly dated official snapshot**, not a call-time price lookup. Before filming, save a selected public AGMARKNET row with source URL, capture time, published price date, commodity, variety, market, value, and unit. The November 4 Lasalgaon red-onion row is a development example, not a claim about the market on November 10. The public demo commodity/market can be chosen after model and voice checks.

When a buyer clearly quotes an offer, the system may compare it to the snapshot only when commodity, variety, market, currency, and unit match. The farmer-only message states the snapshot date and calls it a **dated reference**, never today's rate or a modal price. For example: “AGMARKNET listed ₹4,500 per quintal for red onion at Lasalgaon on November 4. That is a dated reference; today's sale price may differ.” If the match or value is unclear, omit the numeric comparison and say that a reliable comparison is unavailable. The buyer hears none of this advice.
