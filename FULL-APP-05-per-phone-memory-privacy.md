---
title: Per-phone memory and privacy
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: [FULL-APP-01-telephony-vendor-feasibility.md]
---

## Question

What may G-One remember about a phone across calls, with whose consent, for how long, stored where, visible to whom and deletable how, and what may the app say publicly about it, given the standing rule that the repo and the public demo contain no real phone numbers or recordings?

## Type and mode

Grilling (HITL). The user decides; an agent never answers for them. Legal and regulatory questions are flagged for the user and are **not** answered here.

## Known

- Nothing is remembered today. Rooms, clips and agreement state live in server memory on one instance and die with the room or a restart. There is no database, Redis or new dependency by an explicit decision ([DECISIONS](DECISIONS.md), 2026-11-06).
- Spoken content already reaches several model calls per turn (translation, caution check, offer reading, agreement-reply check). That is documented, not hidden ([DEMO](DEMO.md#how-it-works)).
- Caution wording never echoes what was said, and a spoken secret is not stored in a caution. The agreement summary says identities were not verified.
- The Gnani rules require synthetic data and no real recordings or numbers in the project or demo.
- Telephony feasibility ([Telephony path and vendor feasibility](FULL-APP-01-telephony-vendor-feasibility.md)) determines whether a number is observed at all, so this ticket waits for it.

## Sub-questions

1. **What is remembered.** Choose per category, each tied to a feature that needs it: language preference; role; a name or alias; past agreed summaries; price history; past cautions; transcripts; raw audio; consent records. For each: remember, remember in reduced form, or never. "No raw audio, no full transcripts" is a candidate to evaluate, not a decision.
2. **Key.** What identifies a "phone" in memory: the number as presented, a hash of it, a code the person holds, or a room token? What happens with shared or changed phones?
3. **Consent.** Before anything is stored, what must the person say or do, in a call with no screen? Is it voice-only like the agreement? How is the consent recorded and its wording translated?
4. **Retention and deletion.** How long? Can the person ask for everything about them to be forgotten, by voice? Who can see, export or delete (operator, either party, nobody)? What does the counterpart ever see? (Candidate default to examine: nothing.)
5. **Storage change.** Any persistence is a new component and breaks the no-database decision. State the smallest acceptable store and what stays in memory. A `DECISIONS` entry and the user's approval come first (ask-first rule).
6. **Disclosure on lookup.** Who may be told what is remembered? A spoofed or shared number must not unlock someone else's memory. What is the minimum safe thing to say on a returning number?
7. **Memory as prompt input.** Remembered text fed back into model prompts can carry injection. Decide what may enter prompts and in what form.
8. **Third parties.** Memory reaches the translation provider and Gnani when used. Does the person get told, once?
9. **Demo with synthetic data.** How is memory shown publicly without any real number or recording?
10. **Law.** The user should say whether a legal check is wanted (recording consent, data protection, retention). Unresearched; flag, do not assert.
11. **Wording.** What does the app say about privacy? Nothing like "secure", "private" or "encrypted" without evidence. Decide the allowed sentences.

## Constraints

- No real phone numbers, names or recordings in the repo, tests, docs or video.
- Nothing is stored without the consent decided here.
- A summary or memory must never state identity verification.

## Done when

A category-by-category memory table (remember / reduced / never), the key, the consent mechanism, retention and deletion rules, the disclosure rule, the minimum store (or "none for Nov 10"), the prompt-input rule, the allowed privacy wording, and the legal-check decision.

## Feeds

[Caller-ID profile and lookup](FULL-APP-06-caller-id-profile-lookup.md), [Consent to match](FULL-APP-08-consent-to-match.md), [Advisory RAG](FULL-APP-07-advisory-rag.md), [Bilateral shield escalation](FULL-APP-09-bilateral-shield-escalation.md).
