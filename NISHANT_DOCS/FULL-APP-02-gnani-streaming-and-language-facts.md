---
title: Gnani realtime streaming and language facts
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:research]
status: open
assignee: null
blocked_by: []
---

## Question

What do Gnani's **documented** realtime speech-to-text (Prisma) and text-to-speech (Timbre) interfaces actually support that the full application depends on: concurrent streams, audio formats and sample rates, partial and final transcripts, end-of-speech detection, language and code-mix handling, languages and voices per model, published latency, and the challenge account's limits? Which of these remain unknown until a human-approved account probe?

## Type and mode

Research (AFK): read documentation only. **No API call, no key use, no account probe.** The repo already links the pages to start from: [Prisma realtime](https://docs.gnani.ai/api/STT/stt-websocket), [Timbre realtime](https://docs.gnani.ai/api/TTS/tts-websocket), [Prisma REST](https://docs.gnani.ai/api/STT/speech-to-text), [Timbre voices](https://docs.gnani.ai/api/TTS/available-voices).

## Already known (repo, 2026-11-06 to 2026-11-07)

- The built app uses the **REST** interfaces on completed clips. The earlier speech-contract ticket ([WAYFINDER-01](../WAYFINDER-01-gnani-speech-contract.md)) said not to add streaming until measured latency required it. It also named `indic-auto` as a candidate probe model for code-mixed input; that was never confirmed.
- Account access was verified on one synthetic Hindi turn ([WAYFINDER-04](../WAYFINDER-04-gnani-account-probe.md)). The Java upload needed HTTP/1.1, and clips over 48 KB currently need a macOS AAC conversion on the demo machine. Remaining credit is unknown.
- Default configured languages are Hindi, English, Tamil and Kannada with documented Timbre v2.5 voices. Only English and Hindi were exercised on real phones.
- The old phone-leg speech question, [FARMER-CALL-04](../FARMER-CALL-04-speech-contract.md), is open and unedited. Its question is folded in here (formats) and into [Continuous streaming on both legs](FULL-APP-04-continuous-streaming.md) (design).

## Sub-questions

1. **Concurrency:** how many simultaneous realtime sessions per key or account, for STT and TTS? Does the challenge tier change it?
2. **Audio in and out:** accepted encodings and sample rates for the streams; whether telephone-grade audio (narrowband, companded) is accepted directly or needs conversion. Source only; no assumption.
3. **Events:** are partial transcripts, final transcripts, speech-start and speech-end events documented? Is end-of-utterance detection done by Gnani, by us, or by a setting?
4. **Language handling:** per-model language list for STT and for TTS; whether auto-detect exists, for which models and languages; how code-mixed speech is handled; what language codes the streams take; which languages have a voice, and how many voices per language.
5. **Latency:** any published latency or real-time-factor figure, with its conditions; otherwise "unpublished".
6. **Limits and cost:** rate limits, max session length, max clip length for streams, pricing basis, and anything stated about credits.
7. **Translation is not Gnani.** Translation is a separate OpenAI-compatible model call configured by environment ([DECISIONS](../DECISIONS.md)). State which language pairs and streaming modes that call supports as **configuration-dependent and unmeasured**, not as a Gnani fact.
8. **Probe list.** For each unknown above, what is the smallest human-approved probe that would settle it, and what would it cost in credits?

## Done when

A facts table: item, documented value, source URL, date read, confidence; a separate unknowns list with the smallest probe for each; and a one-paragraph "what this means for streaming and language" with no design choice made. The design is for [Language scope and selection](FULL-APP-03-language.md) and [Continuous streaming on both legs](FULL-APP-04-continuous-streaming.md).

## Hazards

- Documentation can describe a model the challenge account cannot use. Mark "documented" apart from "available to us".
- Do not carry over the old assumption that REST behaviour equals streaming behaviour.
