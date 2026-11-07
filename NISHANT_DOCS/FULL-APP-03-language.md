---
title: Language scope and selection
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: [FULL-APP-02-gnani-streaming-and-language-facts.md]
informed_by: [FULL-APP-06-caller-id-profile-lookup.md]
---

## Question

Which languages and which language behaviours does the full application promise on Nov 10, how does each person's language get chosen and changed, and what exactly may the app claim about language quality?

## Type and mode

Grilling (HITL). The user speaks for themselves; no agent answers on the user's behalf. Capture the user's words in the resolution, apart from agent suggestions.

## Known

- Today: a configured list (default Hindi, English, Tamil, Kannada); each person picks theirs on screen and can change it between turns. English to Hindi and Hindi to English ran on two real phones on 2026-11-06. The public pair was never chosen; the plan was to pick it after measuring quality and latency.
- Safeguards written for two languages only: the agreement's negation guard covers English and Hindi ([DECISIONS](../DECISIONS.md), 2026-11-07). The caution classifier's quality in Hindi, Tamil and Kannada is unmeasured.
- Without a screen (a phone call), a language menu or an automatic guess is needed; [FARMER-CALL-02](../FARMER-CALL-02-call-interaction.md) once planned keypad choices from a spoken menu. That ticket is history, not a decision for the full app.

## Sub-questions

1. **The set.** Which languages, if any beyond Hindi and English, does the full app promise? Which are "supported", which "tried, no promise", which "not offered"?
2. **Choosing.** A known caller gets zero intake ([register item 5](FULL-APP-REQUIREMENTS.md#a-approved-by-the-user-for-this-effort-user-now)), so their language comes from the stored profile ([Caller-ID profile and lookup](FULL-APP-06-caller-id-profile-lookup.md)), not from a menu. For an unknown caller: automatic detection, the short intro question, or a mix? What is the default when nothing is known? What happens when detection and the person disagree?
3. **Changing mid-call.** Allowed? How does a person with no screen do it?
4. **Code-mixed speech** (for example Hindi with English words): in scope for Nov 10, tolerated, or excluded? Who bears a wrong transcript?
5. **The pair matrix.** Not every pair works equally. Does the app offer every pair, or only pairs it has measured? What does it say to someone on an unmeasured pair?
6. **Ancillary speech.** In which language do cautions, the price advisory, the agreement read-back and notices go? The user's own, always? (Today they do.)
7. **Quality claims.** What evidence earns a sentence such as "works in <language>"? The earlier rule is to claim only what was verified, and the only live check recorded is English and Hindi.
8. **Failure.** If STT, translation or TTS does not support a language the person asked for, what do both people hear?

## Constraints

- No unmeasured language quality claims. Any language outside the verified pair is "available, unverified" at best.
- Stay consistent with the facts from [Gnani realtime streaming and language facts](FULL-APP-02-gnani-streaming-and-language-facts.md); do not decide anything those facts rule out.
- Do not weaken the wording table in [FARMER-CALL-07](../FARMER-CALL-07-nov10-architecture-scope.md).

## Done when

A decided language set with a status for each language, the selection and change behaviour per form factor (screen and no screen), what each ancillary message uses, the exact quality wording allowed, and a list of unmeasured pairs that [the route ticket](FULL-APP-10-integrated-nov10-route.md) must either measure or leave out.

## Feeds

[Advisory RAG](FULL-APP-07-advisory-rag.md) (language of retrieval and answers), [Continuous streaming on both legs](FULL-APP-04-continuous-streaming.md) (detection during a stream), [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md).
