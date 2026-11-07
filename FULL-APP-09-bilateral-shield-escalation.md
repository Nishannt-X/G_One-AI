---
title: Bilateral shield escalation
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: [FULL-APP-04-continuous-streaming.md, FULL-APP-05-per-phone-memory-privacy.md]
---

## Question

Beyond today's private cautions, what does "escalation" mean in the full application: for each risk, who is alerted first, what levels exist, which actions (if any) the app may take, what evidence bar must be met before each level, what is kept, and what the app and the public video may say?

## Type and mode

Grilling (HITL). The user defines "bilateral" and "escalation" in their own words first. Agent readings are labelled as such. Any automated action on a person's call is a high-blast-radius decision: it needs the user, not a default.

## Known

- Built (PR #2, hardened 2026-11-07): one classifier call per turn, from either person, asks three questions: a threat or intimidation, a request for a secret (OTP, PIN, password, CVV, full card number or expiry, full Aadhaar; including being asked to type a PIN or OTP or approve a collect request in order to receive money), a secret said aloud by the speaker. Fixed wording, never echoing what was said, goes as a private `warning` to the one person it concerns. The translation is delivered unchanged. Nothing is blocked, dropped or verified.
- **Verified:** routing, privacy, no-echo and failure behaviour against a fake model. **Not verified:** classification quality against the live model, in Hindi, Tamil or Kannada, with evasion or spoken prompt injection. Not running in the live app. If the check cannot run, nobody is told.
- Documented limits, to be repeated aloud: deliberate evasion, prompt injection and a speaker provoking a false caution are not defended against; each turn is classified alone.
- The cautions arrive after the other side already received the translation. A person who says a secret is told to assume the other has it.
- Wording: the older map retired "shield" because the app blocks nothing. The Never-say list includes "shield", "intercepts", "blocks", "drops", "filters", "protects the farmer", "zero-trust", "safe conversation".

## Sub-questions

1. **Meaning.** What does the user mean by "bilateral" (both parties are protected and both are monitored; both are told) and "escalation"? Candidate readings to react to: (a) stronger notices at stronger confidence; (b) interrupting or pausing the call; (c) notifying a third party such as a trusted contact or a helpline; (d) recording an incident. None is approved.
2. **Risks covered.** Only the three above, or others? Anything new needs wording, a prompt and a measurement of its own.
3. **Levels and triggers.** What distinguishes a caution from an escalation? One model signal, repeated signals, the speaker's own repeated attempts? Who sets the thresholds?
4. **Allowed actions.** For each level: notice only, hold delivery, end the call, alert someone else. Holding or ending a call changes what the product is. Which actions, if any, does the user approve for Nov 10?
5. **Who is told.** The person at risk, the speaker, both, a third party? Escalating to a third party needs their contact data and consent ([Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md)).
6. **Fairness.** Both parties are users. An honest farmer must not be escalated against because of a misread. What protects the wrongly flagged person?
7. **Evidence bar.** What measured false-positive and false-negative behaviour, on which languages and with what sample size, is required before any claim beyond "a courtesy from a model reading a transcript"? Who runs the measurement and when? Nothing has been measured, and the measurement itself is a candidate Task ticket.
8. **Timing under streaming.** In [Continuous streaming on both legs](FULL-APP-04-continuous-streaming.md) a caution could come earlier or later. Does earlier detection justify a different action level?
9. **What is kept.** Incident records must not store a secret. What is stored, for how long, and who can read it?
10. **Failure.** Today a failed check is silent. Is that acceptable at higher levels? What should each person hear when the check could not run?
11. **Adversaries.** Evasion, injection and false-alarm provocation exist today. Name which risks the user accepts and which the product must refuse to claim to handle.
12. **Wording.** If any action beyond notice is approved, which exact sentences replace the never-say rows, and what evidence is shown with them? If none, does the product keep calling it a caution?

## Constraints

- No claim of blocking, intercepting, shielding, filtering or safety without measured evidence and the user's approved new wording.
- Cautions stay fixed-wording and never echo the content.
- No action that ends or alters a person's call without their prior consent and the user's explicit approval.
- Fail closed means do nothing drastic on an unclear signal.

## Done when

The user's definitions, the risk list, a level and trigger table, the allowed action per level (or "notice only"), the recipients, the fairness protections, the evidence bar and owner, the record-keeping rule, the failure rule and the exact allowed wording, plus any evaluation-set Task that must precede a claim.

## Feeds

[Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md), which also owns the final public wording table.
