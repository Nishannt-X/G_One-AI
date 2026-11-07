# 0002: The product is a phone-call middleman, not a browser bridge

- **Status:** accepted, 2026-11-07 (topology details pending the Twilio research and go/no-go tickets)
- **Decided by:** Nikhil

## Context

The previous project was a two-phone browser translation bridge (tap to talk). The hackathon rewards real-world use
for farmers (Real Deal) and telephonic audio (Noise Canceller). Farmers use ordinary phone calls, not apps.

## Decision

- A farmer dials a **Twilio phone number**. The assistant talks with them over a bidirectional media stream, learns the
  need, and with the farmer's spoken yes **dials the matching buyer** as a second leg.
- During **mediation** the assistant is in the middle: it translates each turn for the listener and can whisper private
  advice or cautions to the farmer only.
- The backend keeps **memory** per farmer (profile plus call gists, keyed by hashed caller number) so returning callers
  get a personal, short intake.
- Languages: Hindi, English and Hinglish first. Bengali and Benglish are a secondary test.
- The **call simulator** (browser microphone into the same pipeline) is the fallback floor and is never presented as a
  phone call.

## Open (tracked on the map)

Exact topology (two streamed calls relayed by the backend vs a conference), Twilio account tier and numbers, and the
data store.

## Consequences

- Real PSTN needs a paid Twilio account (trials block Media Streams). If that fails, the demo shows the simulator plus
  whatever real leg works, with honest wording.
- Audio is 8 kHz mulaw, which is what Prisma is marketed for (telephonic audio) and what Timbre output must be resampled to.
