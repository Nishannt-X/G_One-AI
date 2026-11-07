---
title: Advisory RAG
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: []
informed_by: [FULL-APP-03-language.md, FULL-APP-05-per-phone-memory-privacy.md, FULL-APP-08-consent-to-match.md]
---

## Question

How does the user-approved, mandatory **crop-specific storage RAG** work: which crops and which storage topics it covers, which sources feed the corpus, how retrieval and the store work, how every statement is grounded in retrieved content and dated, what it does when retrieval finds nothing, who hears it, and what stack it needs?

## Type and mode

Grilling (HITL). The requirement is approved ([register item 6](FULL-APP-REQUIREMENTS.md#a-approved-by-the-user-for-this-effort-user-now)): the advisory is retrieval-backed over crop-specific storage content, **not** a price-only advisory and **not** a deterministic price lookup. The user states the topics and sources; the agent lists options and hazards, labelled as such. If a source-quality fact is missing, a research ticket graduates from here (it is not created now because the topic list is not sharp).

## Approved (fixed input)

- Crop-specific storage RAG is mandatory.
- Retrieved content grounds the answers. A claim the retrieved text does not support is not made.
- After the advisory, the app makes the one-time buyer-connection offer; matching and calling wait for a spoken yes ([Consent to match](FULL-APP-08-consent-to-match.md)).

## Known

- What exists today is **not retrieval**. After a clear buyer offer, plain code compares it with **one** dated reference: a live read-only AGMARKNET call for one configured commodity, variety and market (Common rice at Burdwan APMC for Nov 10), or a bundled dated snapshot. The model only reads the offer out of the sentence. The comparison needs matching commodity, unit and currency, and otherwise says a reliable comparison is unavailable. Only the farmer hears it. Kilogram and quintal offers are deliberately not converted. It is a possible *source* for the RAG (a dated price passage), not the advisory itself.
- The live fetch was verified with fakes, a captured response and one real read-only call on 2026-11-06. It was never run in a live phone session. The endpoint is undocumented; stale or closed-market data on Nov 10 is likely (the Diwali closure is unverified).
- Wording rule for any price statement: "AGMARKNET listed ₹X per quintal for <crop, variety, market> on <date>, a dated reference". Never "today's price", "modal", "fair" or "live rate".
- The 2026-11-07 handoff (`HANDOFF-2026-11-07.md`, branch `docs/project-handoff-2026-11-07`) mentions an untracked research file with seven unapproved feature ideas (unit disambiguation, net price, multi-mandi spread, and others). It was written against pre-merge code and its citations were not re-checked. Treat it as input only if the user brings it in.
- No retrieval index, vector store or embedding component exists. The 2026-11-06 trimmed dependency set ([DECISIONS](DECISIONS.md)) belonged to the browser demo and is **not a barrier**; the retrieval stack is a choice for question 4.

## Sub-questions

1. **Scope of "crop-specific storage".** In the user's words, which crops and which storage topics? (Agent reading, not approved: how to keep a given crop after harvest, and how long. Anything about selling, price or hold-or-sell timing that the user wants is named by the user.) Anything the user does not name stays out.
2. **Audience.** Farmer only, or both parties? Does any content ever reach the counterpart?
3. **Sources.** Which sources feed the corpus? For each proposed source: who publishes it, its date, its licence and access terms, and whether use is allowed. Do not assume any exists until a source is named and checked. AGMARKNET is the only source already wired, and only for price.
4. **Retrieval stack.** The RAG is required; the stack is open. Options to weigh, labelled as such: (A) a corpus in a local store with keyword or lexical retrieval; (B) a local store with embedding retrieval; (C) an external vector store and embeddings service. For each: what new component or dependency it adds, what it costs to build and verify by Nov 10, and what the model sees. Adding one needs a `DECISIONS` entry and the user's approval first; whether a retrieval component exists is not in question. The corpus store may be the same local database as caller profiles ([Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md)) or separate: decide.
5. **Grounding rule.** Every statement carries its source and date, or it is not said. What does the advisory say when retrieval returns nothing relevant? ("Unavailable" is today's price behaviour.) How is the generative step kept from adding numbers or claims not in the retrieved text?
6. **Staleness and closures.** What is the fallback when the newest passage or price row is old or the market is closed: say unavailable, or use a clearly dated earlier one (open human choice carried from the old map)?
7. **Language.** Retrieve in the source language and translate, or keep a corpus per language? Fixed numbers must survive translation and be spoken correctly (number pronunciation in Hindi is unchecked). Depends on [Language scope and selection](FULL-APP-03-language.md).
8. **Personalisation.** The profile can supply the farmer's crop and market so the advisory needs no questions ([Caller-ID profile and lookup](FULL-APP-06-caller-id-profile-lookup.md)); decide what may be used, under the consent from [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md).
9. **The buyer-connection offer.** Where in the advisory does the single offer come, and what does it say? The yes protocol belongs to [Consent to match](FULL-APP-08-consent-to-match.md).
10. **Evaluation bar.** What test set and pass bar would justify claiming the advisory is grounded? Nothing has been measured against the live model.
11. **Claim limits.** No advice presented as financial or legal advice, no "fair price", no "best price", no guarantee about spoilage or yield. Decide the disclaimer sentence.

## Constraints

- No external call, no key, no index built during planning.
- Keep or consciously replace the dated-reference wording for price statements; the replacement must be approved by the user.
- Spoken content and any retrieved context reach the model; state this (the documented model-exposure pattern).
- Option labels in question 4 are agent offers; they may not narrow the requirement in the approved list.

## Done when

The advisory's crops and storage topics, audience, allowed sources (each with publisher, date and access terms), the chosen retrieval stack with its cost and its `DECISIONS` entry request, the grounding and refusal rules, the stale-data rule, the language rule, the personalisation rule, where the buyer-connection offer sits, the evaluation bar and the allowed wording.

## Feeds

[Consent to match](FULL-APP-08-consent-to-match.md) (the offer's position), [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md).
