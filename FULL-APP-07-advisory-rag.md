---
title: Advisory RAG
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: [FULL-APP-03-language.md, FULL-APP-05-per-phone-memory-privacy.md]
---

## Question

What does the advisory become in the full application when it is backed by retrieval: which topics it covers, which sources it may draw on, how every statement is grounded and dated, what it does when evidence is missing, who hears it, and what stack (if any) it needs?

## Type and mode

Grilling (HITL). The user states what the advisory should do; the agent lists options and hazards. If a source-quality fact is missing, a research ticket graduates from here (it is not created now because the topic list is not sharp).

## Known

- The advisory today is not retrieval. After a clear buyer offer, plain code compares it with **one** dated reference: a live read-only AGMARKNET call for one configured commodity, variety and market (Common rice at Burdwan APMC for Nov 10), or a bundled dated snapshot. The model only reads the offer out of the sentence. The comparison needs matching commodity, unit and currency, and otherwise says a reliable comparison is unavailable. Only the farmer hears it. Kilogram and quintal offers are deliberately not converted.
- The live fetch was verified with fakes, a captured response and one real read-only call on 2026-11-06. It was never run in a live phone session. The endpoint is undocumented; stale or closed-market data on Nov 10 is likely (the Diwali closure is unverified).
- Wording rule: "AGMARKNET listed ₹X per quintal for <crop, variety, market> on <date>, a dated reference". Never "today's price", "modal", "fair" or "live rate".
- The 2026-11-07 handoff (`HANDOFF-2026-11-07.md`, branch `docs/project-handoff-2026-11-07`) mentions an untracked research file with seven unapproved feature ideas (unit disambiguation, net price, multi-mandi spread, and others). It was written against pre-merge code and its citations were not re-checked. Treat it as input only if the user brings it in.
- No retrieval index, vector store or embedding dependency exists, and the trimmed dependency set was a decision ([DECISIONS](DECISIONS.md), 2026-11-06).

## Sub-questions

1. **Job.** Does "advisory" stay a price reference, or grow into other guidance? Name the topics the user wants. Anything the user does not name stays out.
2. **Audience.** Farmer only (today), or both parties? Does any content ever reach the counterpart?
3. **Sources.** Which sources may be retrieved from? AGMARKNET is the only wired one. For each proposed source: who publishes it, its date, its licence and access terms, and whether retrieval from it is allowed. Do not assume any exists until a source is named and checked.
4. **Retrieval method.** Candidate options to weigh: (A) extend the deterministic lookup to more commodities and markets; (B) a small curated corpus held in memory or a static file with simple retrieval; (C) an external vector store and embeddings. B and C add a component, break the no-database and no-new-dependency decisions, and need the user's approval and a `DECISIONS` entry. A lean to examine, not a decision: stay at A or B for Nov 10 unless a chosen topic cannot work without C.
5. **Grounding rule.** Every statement carries its source and date, or it is not said. What does the advisory say with no evidence? ("Unavailable" is today's behaviour.) How is the generative step kept from adding numbers or claims not in the retrieved text?
6. **Staleness and closures.** What is the fallback when the newest data is old or the market is closed: say unavailable, or use a clearly dated earlier row (open human choice carried from the old map)?
7. **Language.** Retrieve in the source language and translate, or keep a corpus per language? Fixed numbers must survive translation and be spoken correctly (number pronunciation in Hindi is unchecked). Depends on [Language scope and selection](FULL-APP-03-language.md).
8. **Personalisation.** May the advisory use profile or memory (for example the farmer's usual market)? Only with the consent decided in [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md).
9. **Evaluation bar.** What test set and pass bar would justify claiming the advisory is grounded? Nothing has been measured against the live model.
10. **Claim limits.** No advice presented as financial or legal advice, no "fair price", no "best price". Decide the disclaimer sentence.

## Constraints

- No external call, no key, no index built during planning.
- Keep or consciously replace the dated-reference wording; the replacement must be approved by the user.
- Spoken content and any retrieved context reach the model; state this (the documented model-exposure pattern).

## Done when

The advisory's topics, audience, allowed sources (each with publisher, date and access terms), the retrieval option with its cost to the dependency rule, the grounding and refusal rules, the stale-data rule, the language rule, the personalisation rule, the evaluation bar and the allowed wording.

## Feeds

[Consent to match](FULL-APP-08-consent-to-match.md) (if matching uses advisory data), [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md).
