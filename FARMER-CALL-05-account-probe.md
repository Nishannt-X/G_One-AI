---
title: Validate the paid phone-call account
parent: FARMER-CALL-WAYFINDER.md
labels: [wayfinder:task]
status: open
assignee: null
blocked_by: [FARMER-CALL-01-telephony-gate.md]
---

## Question

Only if the human arranges a paid Twilio account: can the team make a short test to two consenting Indian phones? Verify a non-Indian voice-enabled caller ID, India outbound permission, two answered calls, two separate bidirectional WebSockets, and private audio routing. If it fails or is not arranged in time, the browser WebSocket build (not PSTN) remains the public demo. No free phone-account probe is planned: a Vonage trial reaches only its registered number by Voice, and Exotel streaming needs KYC and approval. This is the voice account only; the Twilio WhatsApp Sandbox for the November 10 summary is a separate step in [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md). The human handles signup, identity verification, payment, and credentials; no credentials or real phone numbers belong in the repository, chat, or public demo.
