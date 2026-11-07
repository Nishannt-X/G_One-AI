# Two-phone browser transport sketch

> Update 2026-11-06: the human chose WebSocket, so the working proposal below (HTTP polling) is superseded; see [DEMO.md](DEMO.md) for what was built. The sketch is kept for the comparison.

This is a rough interaction sketch for [Choose the browser audio transport](WAYFINDER-02-browser-audio-transport.md), not a final contract.

```text
Phone A                         Spring Boot + Redis                       Phone B
Create room ──────────────────> room ID, join link ─────────────────────> Join
Choose own language ──────────> room participant A     participant B <── Choose own language
Tap Start/Stop ── WAV upload ─> Prisma → translate → Timbre ──────────> translated WAV
Both screens <──────────────── source transcript + translation ─────────> Both screens
                                                                      B hears voice
```

## Candidate delivery paths

| Path | How the listener learns a turn is ready | Benefit | Cost for this demo |
| --- | --- | --- | --- |
| HTTP polling | Each phone asks for new room events about once a second | Smallest server change; reconnects by asking for events since the last ID | Adds up to about one second of notification delay and repeated requests |
| Server-sent events | Server keeps a one-way event stream to each phone | Faster notification with plain HTTP | Mobile reconnect and event replay need more care |
| WebSocket | Server maintains a two-way channel to each phone | Fits later live/streaming turns | More connection state and recovery code before the first demo |

**Working proposal:** Start with HTTPS upload and HTTP polling for completed turns. Use an opaque room ID and server-side session expiry; keep the Gnani API key on the backend. Store each completed turn with an event ID so a reconnecting phone can fetch missed text and audio. Let the listener replay the WAV manually if the browser blocks automatic playback. Measure the delay from Stop to audible playback on two phones before considering a live stream.

**Recording format check:** [Prisma REST](https://docs.gnani.ai/api/STT/speech-to-text) accepts WAV, MP3, OGG, FLAC, AAC, and M4A, but does not list WebM. The browser should detect its supported recording type with [`MediaRecorder.isTypeSupported()`](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/isTypeSupported_static), then normalize the completed recording to mono PCM WAV before upload. A small browser-side decode and WAV encode step avoids adding a server transcoder; the actual phones must prove it works.

The exact room lifetime, polling interval, WAV conversion, and playback behavior still need to be verified on the actual phones and Gnani account.
