---
title: Plan the G-One two-phone hackathon demo
labels: [wayfinder:map]
status: open
tracker: local-markdown
---

## Destination

An implementation-ready plan for a live, two-person G-One browser demo on two phones by November 10, 2026. It must specify the browser audio path, Gnani Prisma STT and Timbre TTS integration, language configuration, smallest code changes, and checks that prove clear translated audio reaches the other phone.

## Notes

- Wayfinder is planning this effort first; implementation follows after the decisions are resolved. The map is complete when no build-blocking decisions remain.
- The first demo uses Tap Start/Stop, translation after Stop, and translated voice only. The language pair is configurable and will be chosen later. Translation and clear playback take priority over the trust and contract features.
- The team has two eligible members and a Gnani challenge account. The demo path uses browser audio on two phones, without PSTN provisioning.
- The current Spring Boot code accepts Twilio Media Streams, sends audio to Deepgram, translates through Groq, and synthesizes through ElevenLabs. Speaker state is keyed globally as A/B.
- Treat the old code and Markdown as references, not constraints on the hackathon structure. Keep only what serves the two-phone demo.
- The [challenge rules](https://www.gnani.ai/internship) require a working application using Gnani technology and a public demo. Use synthetic data; do not include real phone numbers or recorded calls in the project or demo.
- Protocol references: [Prisma realtime STT](https://docs.gnani.ai/api/STT/stt-websocket) and [Timbre realtime TTS](https://docs.gnani.ai/api/TTS/tts-websocket). Account access and exact model behavior still need verification.
- The repo has no configured issue tracker, so sibling `WAYFINDER-*.md` files are the local Markdown child tickets. Each ticket stores its own answer when resolved; this map only links a one-line gist.
- Shared terms are in the [G-One hackathon glossary](GLOSSARY.md).
- **Prototype status (2026-11-06):** the browser farmer–buyer slice is built and tested against HTTP fakes of Gnani and the translation service; it has **not** been run against live Gnani, a live translation model, or real phones. See [DEMO.md](DEMO.md) and [DECISIONS.md](DECISIONS.md). The [farmer–buyer map](FARMER-CALL-WAYFINDER.md) is the primary plan; this map's tap-to-speak mechanics are what the prototype implements.

## Decisions so far

- [Choose the Gnani speech contract](WAYFINDER-01-gnani-speech-contract.md): process each completed tap-to-speak turn through backend-hosted Prisma and Timbre REST calls; probe the account before fixing the exact Prisma model and voice.

## Not yet specified

- The simplest secure HTTPS route for two real phones and the session lifetime/reconnection behavior, after the browser transport is chosen.
- The acceptance run and public video framing, after the interaction and audio path are settled.

## Out of scope

- PSTN carriers, Twilio account setup, telephony awards, and post-hackathon telecom deployment.
- Deal confirmation, WhatsApp contracts, payment flow, and sentiment advice in the first demo.
- Simultaneous speech, barge-in, and automatic turn detection for the first demo.
