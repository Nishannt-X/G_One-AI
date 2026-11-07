---
title: Consent to match
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: []
informed_by: [FULL-APP-05-per-phone-memory-privacy.md, FULL-APP-06-caller-id-profile-lookup.md, FULL-APP-07-advisory-rag.md]
---

## Question

How does the user-approved buyer-connection consent work in detail: the app offers to connect the farmer with a buyer **once**, and nothing is matched and nobody is called until the person says yes. What exactly is said, in which languages, what is asked of the buyer side, how is a yes withdrawn, what is kept, and what happens when someone says no?

## Type and mode

Grilling (HITL). The core is approved ([register item 7](FULL-APP-REQUIREMENTS.md#a-approved-by-the-user-for-this-effort-user-now)); the user decides the rest in their own words. Agent readings are labelled as such and may not narrow the core.

## Approved (fixed input)

- The offer to connect with a buyer is made **once** (the user's wording). It is not repeated or pressed. What exactly counts as "once" is question 3.
- A spoken **yes** comes before any matching and before any calling. No yes, no match, no call.
- It follows the advisory ([Advisory RAG](FULL-APP-07-advisory-rag.md)); the position of the offer is decided there.

Today nothing matches people to anything. The only consent in the app is the voice-only agreement to a frozen offer ([FARMER-CALL-07](../FARMER-CALL-07-nov10-architecture-scope.md)). **That is a different consent**: it confirms terms, not a connection, and must not be reused or blurred.

## Sub-questions

1. **Who is matched.** From what pool does the app pick a buyer (stored buyer profiles, per [Caller-ID profile and lookup](FULL-APP-06-caller-id-profile-lookup.md))? What does the match use (crop, market, language)? How is a match ranked or chosen?
2. **Buyer side.** The buyer is a user too. Is the buyer asked before being called, or does the call itself carry the question? Can one side be connected without the other's consent? What does the buyer hear first?
3. **"Once".** What counts as the one offer: per call, per farmer, per match? What happens after a no, an unclear reply, or silence: is the offer dropped for the rest of the call? When may it come again, on a later call?
4. **How, with no screen:** a spoken yes in the person's language, tied to the voice-only principle already used for the agreement. What exact words are said, in which languages, and how does the app handle an unclear or conditional reply? (The agreement already fails closed: only an explicit yes counts. Check whether that rule fits here.)
5. **What each side learns** on a match, and when.
6. **Revocation:** how to withdraw by voice, and what that stops or deletes, tied to [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md).
7. **Record of consent:** what is kept as proof, for how long, with what, and is it itself personal data?
8. **Refusal and failure:** no is a normal outcome; the call continues unmatched. A consent classifier misreading is a hazard (the classifier reads a transcript and speech recognition can create or hide a yes), so what is the mitigation, analogous to the exact-number read-back?
9. **Sybil and abuse:** a presented number can be forged ([Caller-ID profile and lookup](FULL-APP-06-caller-id-profile-lookup.md)). Decide what a forged match request or a forged yes can cause.
10. **Wording:** what may the app and the public video say about connecting? Nothing implying verified buyers or sellers, vetted parties, or protection.

## Constraints

- No matching or calling before the spoken yes decided here. (Storing a caller's own profile is governed by [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md), not by this consent.)
- Fail closed: an unclear reply is no consent.
- Never described as identity verification, a guarantee or a safety feature.

## Done when

The match pool and rule, the buyer-side protocol, the meaning of "once", the consent protocol (exact spoken wording per language), the withdrawal rule, the record-keeping rule, the refusal behaviour, the abuse rule and the allowed wording.

## Feeds

[Advisory RAG](FULL-APP-07-advisory-rag.md) (where the offer sits), [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md).
