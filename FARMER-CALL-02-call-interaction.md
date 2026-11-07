---
title: Define the farmer and buyer call interaction
parent: FARMER-CALL-WAYFINDER.md
labels: [wayfinder:grilling]
status: closed
assignee: Codex
blocked_by: []
---

## Question

How do the two phone legs pair, how does each caller choose a language from the supported set, how is a spoken turn ended without a screen, and what does each person hear during processing or failure? Specify how a farmer-only advisory interrupts neither translation nor the buyer's audio.

## Interview notes

- Both parties should receive a standard phone call and choose their own supported language.
- Turn-taking remains acceptable. The speaker presses `#` after a short turn.
- A private operator page starts the two outbound calls using numbers entered at run time; no number is committed or shown in the public demo.
- Each caller selects their own language by pressing a number from a spoken menu of model-supported options.
- The farmer-only price advisory is triggered automatically when the buyer clearly quotes an offer.
- Failure and retry behavior will be decided later at the human's request; see [Decide the failed-turn behavior](FARMER-CALL-06-failure-behavior.md).

## Resolution

The operator starts one session with two isolated call legs, identified as farmer and buyer. Each leg plays a language menu before its media stream begins. The selected languages determine Prisma's transcription hint, translation direction, and Timbre's spoken output. Neither caller hears the other's raw speech.

During a turn, the speaker talks and presses `#`. The backend processes the buffered turn, then sends translated speech only to the listener's leg. Both hear a short wait cue while processing; the exact cue and failure path remain for the later failure-behavior ticket. For a clear buyer offer matching the configured commodity and unit, the farmer hears the translated offer followed by a separate, private advisory grounded in the dated Mandi snapshot. The buyer does not hear the advisory. Ambiguous offers cause no numeric comparison.
