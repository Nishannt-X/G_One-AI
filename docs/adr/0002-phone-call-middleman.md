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

## Topology (settled 2026-11-07 by research issue #4)

- **Two independent Twilio calls, each with its own bidirectional `<Connect><Stream>`**, relayed by our backend. A
  conference or `<Dial>` bridge mixes raw audio and cannot keep whispers or untranslated speech from the buyer.
- A **paid** Twilio account is required (trials block `<Stream>`).
- Twilio has **no Indian local or mobile numbers**. Farmer dial-in means calling a foreign number (an international call).
  Outbound calls to Indian mobiles work with a non-Indian caller ID. Whether the farmer dials in or G-One calls back is
  decided in the call-flow ticket (#8), using the go/no-go results (#6).
- Speech contract (#5): Prisma v3.0 realtime WebSocket on 8 kHz PCM16, Timbre v2.5 WebSocket emitting 8 kHz mulaw.

## Open (tracked on the map)

Twilio account tier and number (#6), dial-in vs callback (#8), data store (#10).

## Consequences

- Real PSTN needs a paid Twilio account (trials block Media Streams). If that fails, the demo shows the simulator plus
  whatever real leg works, with honest wording.
- Audio is 8 kHz mulaw, which is what Prisma is marketed for (telephonic audio) and what Timbre output must be resampled to.
