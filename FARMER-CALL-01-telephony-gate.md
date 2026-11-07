---
title: Choose a viable two-leg phone provider for the demo
parent: FARMER-CALL-WAYFINDER.md
labels: [wayfinder:research]
status: closed
assignee: Codex
blocked_by: []
---

## Question

Can two student-owned Indian phones receive separate calls whose inbound audio reaches our backend and whose translated audio returns exclusively to the intended leg before November 10? Compare paid Twilio with a verified viable alternative, including account setup, India calling, media-stream access, cost, and a browser fallback trigger.

## Resolution

Given the human's free-first preference, the first viable build path is the browser WebSocket call: two phone browsers over mobile data, no carrier fee, not PSTN. Paid Twilio is the best fit for the existing Java prototype only if the human arranges payment, and is **conditional on an account-specific live test**. Twilio's [trial blocks `<Stream>`](https://www.twilio.com/docs/usage/trials/try-out-voice). A paid account can place separate outbound calls; each [bidirectional Media Stream](https://www.twilio.com/docs/voice/media-streams) has its own WebSocket and call/stream IDs, allowing the backend to send translated audio only to the intended leg. For Indian destinations, [Twilio requires a non-Indian caller ID](https://www.twilio.com/en-us/guidelines/in/voice) and outbound dialing permission. Its published India-mobile rate is [$0.0496 per leg per minute plus $0.0044 per stream per minute](https://www.twilio.com/en-us/voice/pricing/in), about $0.108 per two-leg minute before number rental, taxes, and Gnani/translation usage.

[Vonage](https://api.support.vonage.com/hc/en-us/articles/20053288510620-India-Voice-Features-and-Restrictions) supports [bidirectional WebSocket audio](https://developer.vonage.com/en/voice/voice-api/concepts/websockets), but its free trial **cannot** support two Indian callers from one account: [Voice calls can only be made to the account's registered number](https://api.support.vonage.com/hc/en-us/articles/212554438-What-are-the-limitations-of-a-trial-account), and the additional [verified test numbers](https://api.support.vonage.com/hc/en-us/articles/204014853-How-do-I-add-test-numbers-during-my-Vonage-API-trial) are SMS-only. A Vonage one-leg test could not prove two-call feasibility and would distract from the deadline, so it is not part of the active gate. No free PSTN path is verified. Exotel's free-trial basic calls do not imply streaming: [AgentStream requires company-document KYC and Exotel approval](https://support.exotel.com/support/solutions/articles/3000132268-quick-guide-to-get-started-with-exotel-streaming-services). [Plivo's India domestic path requires an India-registered business](https://www.plivo.com/docs/voice/concepts/india-calling).

Sequence: (1) build the browser WebSocket call first, targeting November 8; (2) attempt a phone path only if the human arranges paid Twilio. Go/no-go for any phone path: verify two consenting student phones answer, two distinct bidirectional WebSockets connect, and test audio is delivered to only its intended leg. No account-specific call, payment, or model access has been tested yet. See [Validate the paid phone-call account](FARMER-CALL-05-account-probe.md).
