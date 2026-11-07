---
title: Choose the browser audio transport
parent: WAYFINDER.md
labels: [wayfinder:prototype]
status: open
assignee: Codex
blocked_by: []
---

## Question

What is the smallest secure way to pair two phone browsers, upload a completed tap-to-speak turn, and deliver translated Timbre audio to the other phone through the Spring Boot backend? Use a rough interaction and data-flow prototype to compare direct HTTP with polling or server events against WebSocket and hosted WebRTC. Include phone playback permission and reconnect behavior.

## Resolution

Provisional (2026-11-06): the human chose WebSocket for live connections, superseding the polling proposal. The prototype uploads each completed turn over HTTPS, notifies both phones over WebSocket, and serves the translated WAV to the listener only over an authenticated HTTP GET (so a reconnecting phone can replay it). A reconnecting phone receives the room's recent events. Left open until proven on real phones: HTTPS route, playback permission and reconnect behavior. See [DEMO.md](DEMO.md).

## Prototype

See [the two-phone transport sketch](BROWSER-AUDIO-PROTOTYPE.md). It compares short HTTP polling with server events and a WebSocket. The proposal uses the existing Spring Boot server as the room and audio relay, without a carrier account.
