---
title: Caller-ID profile and lookup
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: [FULL-APP-01-telephony-vendor-feasibility.md, FULL-APP-05-per-phone-memory-privacy.md]
---

## Question

When a call starts, what does the app look up from the caller's number, what is in a "profile", how is one created and changed, what is spoken or shown from it and to whom, and what happens when the number is unknown, withheld, shared or not trustworthy?

## Type and mode

Grilling (HITL). The user defines what "profile" means to them first. Agent readings are labelled as such.

## Known

- Today there is no caller ID and no profile. A person joins a room by link; the creator picks the role (farmer or buyer) and each person picks a language on screen. Auth is an unguessable room id plus a per-person bearer token ([DEMO](DEMO.md#how-it-works)).
- Whether a number is observed at all depends on the call path ([Telephony path and vendor feasibility](FULL-APP-01-telephony-vendor-feasibility.md)): if the app dials both people it already knows the numbers it was given; if people dial in, the network presents one.
- What may be stored is for [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md) to decide first.
- The only identity statement in the repo is a negative one: the summary says identities were not verified.

## Sub-questions

1. **Meaning of the profile.** Which fields, each justified by a feature? Candidates to react to: role, language, a spoken name or alias, crop or market interests, location. None is approved.
2. **Where the number comes from.** Presented caller ID, a number the operator enters, or a number the person says. How is each treated differently?
3. **Trust.** A presented number can be wrong or forged. Decide what the app is allowed to do on an unconfirmed number, and what is withheld until the person confirms something. State plainly that caller ID is not identity (consistent with the "identities were not verified" wording).
4. **Creation.** First-call enrolment without a screen: what is asked, in what language, and when does storage begin (after the consent decided in the privacy ticket)?
5. **Changes and deletion.** How does a person correct or delete their profile by voice?
6. **Lookup outcomes.** Known, unknown, withheld, number shared by several people, lookup service down. For each: what does the call do, and what is the fallback? A failed lookup must not block the call (candidate rule to examine: proceed as anonymous).
7. **Disclosure.** What may be said to the caller on a known number (greeting by alias? nothing?) and what may the counterpart learn? The counterpart's default is a decision, not an assumption.
8. **Role binding.** Does the profile set the role (farmer or buyer), or does each call still choose? How does it interact with the agreement rules, which bind confirmations to the speaker's authenticated role?
9. **Demo.** How is the profile shown publicly with synthetic numbers only?
10. **Claims.** Which sentences about recognising callers are allowed? Nothing like "verified" or "secure login".

## Constraints

- No profile storage until [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md) decides consent and retention.
- Not identity verification, in words or in logic.
- Lookup failure never blocks translation.

## Done when

The profile field list with the feature each field serves, the number sources and how each is trusted, the lookup outcome table, the enrolment and deletion flow, the disclosure rule, the role-binding rule, and the allowed wording.

## Feeds

[Consent to match](FULL-APP-08-consent-to-match.md), [Language scope and selection](FULL-APP-03-language.md) (stored language), [Advisory RAG](FULL-APP-07-advisory-rag.md) (personalisation), [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md).
