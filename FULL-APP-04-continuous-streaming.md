---
title: Continuous streaming on both legs
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: [FULL-APP-01-telephony-vendor-feasibility.md, FULL-APP-02-gnani-streaming-and-language-facts.md]
---

## Question

What does "continuous streaming on both legs" mean for the full application and what is promised by Nov 10: how is the end of an utterance decided, can both people talk at once, how does it fail, what latency counts as acceptable and how is it measured, and what happens to the features that today assume one finished turn?

## Type and mode

Grilling (HITL). The user defines what "continuous" means to them first. A rough latency prototype may graduate from here, but only after the user's meaning is captured; do not build before that.

## Known

- Today's path is completed-turn REST: Start, speak, Stop, one clip of at most 30 s, then Prisma, then translation, then Timbre, and the listener fetches the audio. Both phones get events over WebSocket (notifications only). Stop-to-audible latency has **never been measured**; very short turns sometimes need a retry for an unisolated cause.
- Turn-based features built on that path: the caution check (one model call per turn, either person), the buyer offer reader, the agreement state machine (frozen terms, read-back, playback reports, 120 s limit), the failure rule "abort the whole turn, retry or re-record" ([FARMER-CALL-06](FARMER-CALL-06-failure-behavior.md), provisional).
- The earlier rule: do not add streaming until measured latency shows it is needed ([WAYFINDER-01](WAYFINDER-01-gnani-speech-contract.md)). The user's instruction now asks for streaming, so that rule no longer settles it; the evidence it asked for does not exist yet.
- The old phone-leg speech contract [FARMER-CALL-04](FARMER-CALL-04-speech-contract.md) is open and unedited; its question is absorbed here.

## Sub-questions

1. **Meaning.** Which of these does the user mean? (a) Each leg's speech is transcribed continuously and the other person hears translated speech as it is produced. (b) The same, but only one person speaks at a time. (c) Full duplex with overlap. (d) Something else. Offered as readings to choose from, not as a guess.
2. **End of utterance.** Who decides: the speech service, our side with silence detection, or a person-driven signal? Consequences for cutting people off.
3. **Overlap and barge-in.** If both talk, what does each hear? Is the listener's current playback interrupted?
4. **Latency.** What is the target and how is it measured end to end? What does the app say when it cannot meet it? (No "real time" or "instant" claim without a measurement; that wording is banned today.)
5. **What a "turn" becomes** for each downstream feature: caution check, offer reading, agreement confirmation, read-back playback reports. Does each run per utterance, per sentence, per call segment? Does a late caution (after audio already delivered) still make sense? This links directly to [Bilateral shield escalation](FULL-APP-09-bilateral-shield-escalation.md).
6. **Failure mid-stream.** Reconnect, resend, drop and tell? What replaces "abort the whole turn"?
7. **Fallback.** Does the completed-turn path stay available as a switch if streaming misbehaves in rehearsal? (The floor is the only thing with real-phone evidence.)
8. **Cost and limits.** Minutes of streaming per rehearsal and per demo against the unknown Gnani credit balance; concurrency from [Gnani realtime streaming and language facts](FULL-APP-02-gnani-streaming-and-language-facts.md); the carrier path from [Telephony path and vendor feasibility](FULL-APP-01-telephony-vendor-feasibility.md).
9. **Audio hygiene.** Echo and feedback when both people are on speaker in the same room (the stage demo shares one microphone). Decide whether this is an accepted limit.

## Constraints

- Streaming must not weaken the nonbinding agreement rules (exact frozen terms, role-bound confirmations, fail closed). Anything that changes them needs the user.
- Keep the fixed-wording, no-echo behaviour of cautions.
- No latency or "real time" claim until measured on the phones that will be filmed.

## Done when

The user's meaning of "continuous", the chosen turn and overlap rules, the per-feature turn definition, the failure rule, the latency target and how it will be measured, and whether the completed-turn path remains as a fallback. Everything is tagged "full target" or "Nov 10 slice" as far as the facts allow; the final cut belongs to [the route ticket](FULL-APP-10-integrated-nov10-route.md).
