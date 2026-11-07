---
title: Choose the Gnani speech contract
parent: WAYFINDER.md
labels: [wayfinder:research]
status: closed
assignee: Codex
blocked_by: []
---

## Question

Which documented Prisma STT and Timbre TTS models, endpoints, request/response events, language settings, and audio formats should the two-phone tap-to-speak demo use, and what remains uncertain until the challenge account is probed?

## Resolution

2026-11-06 — Use a completed-turn HTTP path for the first demo. After the user taps Stop, upload one clip of at most 30 seconds to the Spring Boot backend. The backend calls [Prisma REST](https://docs.gnani.ai/api/STT/speech-to-text) with multipart `audio_file`, then passes the completed transcript through translation. It calls [Timbre REST](https://docs.gnani.ai/api/TTS/tts-inference) for a target-language voice and returns playable WAV audio to the other phone. Keep `X-API-Key-ID` on the backend.

Prisma v3.0 with `indic-auto` is the preferred account probe because the language pair is still open and code-mixed input may be used. The challenge page names Prisma v2.5, which the REST endpoint uses by default. The model choice becomes final only after the account probe; the HTTP request/response contract does not depend on that result. Timbre v2.5 is the documented TTS target. Do not add streaming until measured completed-turn latency shows it is needed for the demo.

The human chose Tap Start/Stop and translated voice only. Live account entitlement, chosen voice quality, phone playback, and latency remain unverified and move to [Verify Gnani challenge-account speech access](WAYFINDER-04-gnani-account-probe.md).

## Research handoff (documentation only)

- For tap-to-speak, [Prisma REST](https://docs.gnani.ai/api/STT/speech-to-text) accepts a short audio upload and returns a transcript. The docs describe `prisma-v3.0` with `indic-auto`; the challenge page features v2.5, so account access to v3.0 must be checked.
- [Prisma realtime STT](https://docs.gnani.ai/api/STT/stt-websocket) requires paced raw PCM frames and API headers supplied by a backend proxy. It adds complexity for a completed-utterance demo.
- [Timbre REST](https://docs.gnani.ai/api/TTS/tts-inference) returns binary audio; [the voice catalog](https://docs.gnani.ai/api/TTS/available-voices) identifies voices by language. SSE and WebSocket variants exist if measured latency later justifies streaming.
- The Gnani API key stays on the backend. Still unverified: challenge-credit entitlement, limits, target-language voice quality, end-to-end latency, and phone playback.
