---
title: Consent to match
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: [FULL-APP-05-per-phone-memory-privacy.md, FULL-APP-06-caller-id-profile-lookup.md]
---

## Question

What does "consent-to-match" mean in this product, who consents to what, when and how, how is it withdrawn, and what happens when someone says no?

## Type and mode

Grilling (HITL). **The first act is the user saying what they mean.** The repo has no definition. The readings below are the author's guesses at the phrase, offered so the user can pick, correct or replace them.

## Candidate readings (not approved; choose, edit or replace)

- **R1, counterpart matching.** A farmer or buyer agrees to be introduced or connected to another caller drawn from stored profiles.
- **R2, data matching.** A person agrees that their offer or profile may be compared with stored data (prices, past summaries) to produce advice.
- **R3, record matching.** A person agrees that this call may be linked to their earlier calls or stored profile via the number.
- **R4, something else the user describes.**

Today nothing matches people to anything. The only consent in the app is the voice-only agreement to a frozen offer ([FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md)). **That is a different consent**: it confirms terms, not a match, and must not be reused or blurred.

## Sub-questions

1. Which reading (or combination) is meant, in the user's words?
2. **Who consents:** the person matched, the other party, both? Can one match happen without the other's consent?
3. **When:** before any lookup or storage, at first call, per call, per match?
4. **How, with no screen:** a spoken yes in the person's language, tied to the voice-only principle already used for the agreement? What exact words are said, in which languages, and how does the app handle an unclear or conditional reply? (The agreement already fails closed: only an explicit yes counts. Check whether that rule fits here.)
5. **What the other side learns** on a match, and when. Candidate default to examine: nothing identifying until both consent.
6. **Revocation:** how to withdraw by voice, and what that deletes or stops, tied to [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md).
7. **Record of consent:** what is kept as proof, for how long, with what, and is it itself personal data?
8. **Refusal and failure:** no is a normal outcome; the call continues unmatched. A consent classifier misreading is a hazard (the classifier reads a transcript and speech recognition can create or hide a yes), so what is the mitigation, analogous to the exact-number read-back?
9. **Sybil and abuse:** a presented number can be forged ([Caller-ID profile and lookup](FULL-APP-06-caller-id-profile-lookup.md)). Decide what a forged match request can cause.
10. **Wording:** what may the app and the public video say about matching? Nothing implying verified buyers or sellers, vetted parties, or protection.

## Constraints

- No matching, storing or comparing before the consent decided here.
- Fail closed: an unclear reply is no consent.
- Never described as identity verification, a guarantee or a safety feature.

## Done when

The user's definition, the consent protocol (who, when, how, exact spoken wording per language), the withdrawal rule, the record-keeping rule, the refusal behaviour, the abuse rule and the allowed wording.

## Feeds

[Advisory RAG](FULL-APP-07-advisory-rag.md) (R2), [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md).
