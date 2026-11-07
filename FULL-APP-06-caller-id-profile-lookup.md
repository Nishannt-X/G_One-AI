---
title: Caller-ID profile and lookup
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: []
informed_by: [FULL-APP-01-telephony-vendor-feasibility.md, FULL-APP-05-per-phone-memory-privacy.md]
---

## Question

How does the user-approved zero-intake flow work in detail: at the start of a call the app looks the number up in the local database first; a known number is asked nothing; an unknown number or a failed lookup gets one short, natural intro question and never a scripted enrolment interview. What is in a "profile", how is one created and changed, what is spoken or shown from it and to whom, and what happens when the number is withheld, shared or not trustworthy?

## Type and mode

Grilling (HITL). The flow below is approved ([register item 5](FULL-APP-REQUIREMENTS.md#a-approved-by-the-user-for-this-effort-user-now)); the user defines everything under it, starting with what "profile" means to them. Agent readings are labelled as such and may not add intake steps for a known caller.

## Approved flow (fixed input)

1. On connect, **lookup first**: the number is looked up in the local database before anything is asked.
2. **Known number:** zero intake. No question is asked; the call proceeds with the stored context.
3. **Unknown number, or the lookup fails:** the call is not blocked. The person gets **one short, natural intro question** (not a form, not a menu of questions).
4. **Never** a scripted enrolment interview, for anyone.

## Known

- Today there is no caller ID and no profile. A person joins a room by link; the creator picks the role (farmer or buyer) and each person picks a language on screen. Auth is an unguessable room id plus a per-person bearer token ([DEMO](DEMO.md#how-it-works)).
- Whether a number is observed at all depends on the call path ([Telephony path and vendor feasibility](FULL-APP-01-telephony-vendor-feasibility.md)): if the app dials both people it already knows the numbers it was given; if people dial in, the network presents one.
- What may be stored, and under what consent, is for [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md). The two tickets can be decided together; this one records the assumption it makes about storage.
- The only identity statement in the repo is a negative one: the summary says identities were not verified.

## Sub-questions

1. **Meaning of the profile.** Which fields, each justified by a feature (the advisory in [Advisory RAG](FULL-APP-07-advisory-rag.md) needs crop and storage context; matching in [Consent to match](FULL-APP-08-consent-to-match.md) needs role and market)? Candidates to react to: role, language, a spoken name or alias, crop, market, location. None is approved.
2. **Where the number comes from.** Presented caller ID, a number the operator enters, or a number the person says. How is each treated differently?
3. **Trust.** A presented number can be wrong or forged, and zero intake asks a known caller nothing. Decide what the app may say or do from a recognised number, and what waits for a later signal in the call, without adding an intake step. State plainly that caller ID is not identity (consistent with the "identities were not verified" wording).
4. **The one intro question.** For an unknown number or a failed lookup: what is asked (it must feel like a natural opening, not enrolment), in what language, how is the answer turned into a profile, and when does storage begin (after the consent decided in the privacy ticket, folded into this one question)? What happens if the person declines or says nothing?
5. **Changes and deletion.** How does a person correct or delete their profile by voice, without an interview?
6. **Lookup outcomes.** Known, unknown, withheld, number shared by several people, lookup service down. The approved rule covers known (nothing asked), unknown and failure (one short question, never blocked). Decide withheld and shared numbers, and what the call does when the lookup is slow.
7. **Disclosure.** What may be said to the caller on a known number (a greeting, an alias, nothing beyond the call starting)? What may the counterpart learn? The counterpart's default is a decision, not an assumption.
8. **Role binding.** Does the profile set the role (farmer or buyer), or does each call still choose? How does it interact with the agreement rules, which bind confirmations to the speaker's authenticated role?
9. **Demo.** How is the profile shown publicly with synthetic numbers only?
10. **Claims.** Which sentences about recognising callers are allowed? Nothing like "verified" or "secure login".

## Constraints

- The approved flow above stands; no extra question for a known caller.
- Build constraint: no profile is written before [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md) fixes consent and retention. Deciding this ticket does not wait for that.
- Not identity verification, in words or in logic.
- Lookup failure never blocks translation.

## Done when

The profile field list with the feature each field serves, the number sources and how each is trusted, the lookup outcome table, the wording of the one intro question per language, the correction and deletion flow, the disclosure rule, the role-binding rule, and the allowed wording.

## Feeds

[Consent to match](FULL-APP-08-consent-to-match.md), [Language scope and selection](FULL-APP-03-language.md) (stored language), [Advisory RAG](FULL-APP-07-advisory-rag.md) (personalisation), [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md).
