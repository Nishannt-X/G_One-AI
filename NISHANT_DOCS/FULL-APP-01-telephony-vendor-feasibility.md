---
title: Telephony path and vendor feasibility
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:research]
status: open
assignee: null
blocked_by: []
---

## Question

Which call path or paths can the team realistically stand up before **2026-11-10** for the full application, and for each: is the caller's number observable by our backend, can two simultaneous bidirectional audio streams run with audio delivered to one leg only, what must the human arrange (accounts, identity checks, payment, numbers) and by what date, and what is the date after which that path is dropped in favour of the browser floor?

## Type and mode

Research (AFK): read documentation and dated pricing pages only. **No sign-ups, no calls, no paid API, no account probe.** A live probe is a separate Task ticket the user approves, only if this ticket finds a path worth testing.

## Already known (from the repo, dated 2026-11-06, not re-verified)

- [FARMER-CALL-01](../FARMER-CALL-01-telephony-gate.md): browser WebSocket over mobile data was chosen because no free PSTN path was verified. A Twilio trial blocks `<Stream>`; a paid Twilio account could place separate legs with their own Media Stream WebSockets; India destinations were recorded as needing a non-Indian caller ID and outbound permission; a Vonage trial reaches only its registered number by Voice; Exotel streaming needs KYC and approval; Plivo's India domestic path needs an India-registered business. A per-minute figure was recorded for Twilio.
- [FARMER-CALL-05](../FARMER-CALL-05-account-probe.md): the paid-account probe task is open and was off the old route. This ticket decides whether it comes back.
- The legacy Twilio prototype is tracked but **excluded from compilation**; it is a reference, not a constraint ([DECISIONS](../DECISIONS.md), 2026-11-06).
- The student team has a Gnani challenge account. Its credit balance and concurrency limits are unknown.

## Sub-questions

1. **Direction of the calls.** Do we dial both people (we know both numbers because we chose them; no caller ID is "observed"), or do people dial a number we hold (caller ID is observed, but we must rent and verify a number)? This choice changes who "the caller" is for [Caller-ID profile and lookup](FULL-APP-06-caller-id-profile-lookup.md) and what a spoofed number can do.
2. **Candidate paths to compare:** browser only (today's floor); paid Twilio Voice with Media Streams; any other path the user names; the previously ruled-out vendors **only if** the user's account situation changed. Treat each as "unproven until a dated source or a probe says otherwise".
3. **What the human must arrange** per path: account type, identity or business verification, payment method, number purchase, outbound permission, caller-ID rules, expected lead time. List only what a source states; mark the rest unknown.
4. **Cost** per two-leg minute for a full rehearsal plus a recording-free demo, with the date of each price. Include Gnani usage as unknown unless ticket 02 supplies it.
5. **Consent and rules.** What do the sources say about recording, call consent, caller-ID presentation and calling hours for India? This is a pointer list for the user, **not legal advice**; mark everything unverified by counsel.
6. **Latency floor.** What does each path add before our code sees audio? Report published figures or "unknown".
7. **Go/no-go date.** Given today is 2026-11-07, what is the last day a human-arranged step can slip and still leave one rehearsal day?

## Done when

A comparison table (path, caller ID observable, two-stream support, human steps, cost, lead time, sources with dates, what could not be verified), a recommended path or "stay on the browser floor" with a confidence level, a dated go/no-go, and the exact checklist the user would follow if a Task ticket is warranted. Findings go on the ticket's resolution; assets are linked, not pasted.

## Hazards

- Dated vendor pages change. Every claim needs a source and a date.
- Do not infer streaming support from "supports voice calls". The earlier research was careful about that difference.
- A trial or sandbox path is not evidence for a production path.
