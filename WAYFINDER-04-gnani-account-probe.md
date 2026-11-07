---
title: Verify Gnani challenge-account speech access
parent: WAYFINDER.md
labels: [wayfinder:task]
status: closed
assignee: Codex
blocked_by: [WAYFINDER-01-gnani-speech-contract.md]
---

## Question

Can the challenge account transcribe one synthetic short utterance with the preferred Prisma model and synthesize one target-language Timbre v2.5 WAV clip, within the available credits and acceptable response time? Record which model, voice, locale, and audio response actually work, without recording credentials or personal audio in the ticket.

## Resolution

2026-11-06 — The challenge key completed a live Timbre v2.5 request: Hindi `hi-IN`, voice `Nalini`, mono 24 kHz PCM WAV. A short synthetic utterance in that audio was transcribed by live Prisma v2.5 as “नमस्ते किसान”. The same valid WAV passed through the running app's Prisma → Groq → Timbre path and the turn endpoint returned HTTP 200. The Groq model `qwen/qwen3.8-27b` returned a Hindi translation of a synthetic red-onion offer with ₹4,000 per quintal preserved.

The Java upstream client needed HTTP/1.1 for the live multipart request; its previous default request failed before a response. The initial Timbre WAV had an open-ended length header, so the STT probe used a normalized WAV. Neither key nor audio was committed. This verifies basic account access, not real-phone recording, voice quality, all language pairs, or remaining credit balance.

A later real-phone test found that a longer Hindi reply failed during the Java WAV upload. A synthetic 5.6-second clip reproduced it. On the demo Mac, converting turns over 48 KB to M4A with `afconvert` let the same longer clip complete through Prisma → Groq → Timbre. This needs another real-phone test and a portable conversion path after the hackathon.
