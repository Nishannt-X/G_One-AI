---
title: Plan the G-One farmer–buyer phone-call demo
labels: [wayfinder:map]
status: open
tracker: local-markdown
---

## Destination

An implementation-ready route to a near-perfect November 10 public demo on two phone browsers (WebSocket over data, no carrier fee, not PSTN): a farmer and a buyer choose languages supported by the speech models, speak in turns, and hear translated speech from each other. Three pillars: translated delivery with a private farmer caution, a farmer-only dated market reference (Common rice, Burdwan APMC), and a voice-confirmed, nonbinding WhatsApp summary. Real phone calls are a post-November-10 upgrade only if a paid carrier account is arranged and verified.

## Notes

- This primary map supersedes the general-language-bridge direction; the [browser-demo map](WAYFINDER.md) is the reference plan for the browser path.
- The older Twilio prototype and Markdown are references, not architectural constraints. Keep only components that serve the farmer–buyer demo.
- The human chose WebSocket transport for live connections, a phone call as the preferred demo, and market-rate advice as the first farmer-specific capability. The exact language pair is selected later.
- Twilio trial accounts [block `<Stream>`](https://www.twilio.com/docs/usage/trials/try-out-voice); India outbound calls through Twilio [require an international caller ID](https://www.twilio.com/en-us/guidelines/in/voice). A student account has no assumed India business KYC. A Vonage trial can place Voice calls [only to its registered number](https://api.support.vonage.com/hc/en-us/articles/212554438-What-are-the-limitations-of-a-trial-account), so it cannot reach two Indian callers; Exotel streaming needs KYC and approval.
- The [challenge](https://www.gnani.ai/internship) requires a working Gnani-powered app and public video. Use synthetic identities and examples; exclude real phone numbers and recorded calls from the project and demo.
- Keep the three pillars distinct and claim only what each has verified. Per-pillar status and the wording the demo may use are in [Lock the November 10 demo architecture and truthful scope](FARMER-CALL-07-nov10-architecture-scope.md); the older "sentiment shield" name is retired because the app does not block anything.
- **Prototype status (2026-11-06):** the browser-first build (not PSTN) runs with automated tests against fakes, and two real phones have completed English→Hindi and Hindi→English turns of several seconds; very short turns sometimes need a retry. Latency is unmeasured. The threat caution and live AGMARKNET fetch are built but the running app has not been restarted with them. The WhatsApp agreement is not built. Run and probe steps: [DEMO.md](DEMO.md).
- [Validate the paid phone-call account](FARMER-CALL-05-account-probe.md) and [Adapt the speech pipeline to isolated phone legs](FARMER-CALL-04-speech-contract.md) belong to the post-November-10 phone upgrade and are off the November 10 route. The Twilio WhatsApp Sandbox is a separate, unverified account step.
- Tickets are sibling Markdown files because no tracker is configured. Their resolutions hold detail; this map stays an index.

## Decisions so far

- [Choose a viable two-leg phone provider for the demo](FARMER-CALL-01-telephony-gate.md): free-first, so build the browser WebSocket call first for November 8 (no free PSTN path is verified; the Vonage trial is Voice-to-registered-number only); paid Twilio only if the human arranges it.
- [Define the farmer and buyer call interaction](FARMER-CALL-02-call-interaction.md): two isolated calls, keypad language choices, `#` turn end, automatic offer detection, and a private farmer advisory.
- [Ground farmer-only market-rate advice](FARMER-CALL-03-mandi-advice.md): compare clear offers only against a preloaded, dated official snapshot with matching market, variety, and unit.

- [Decide the failed-turn behavior](FARMER-CALL-06-failure-behavior.md): provisional for the browser demo: abort the turn, tell both phones, let the speaker retry the same recording or re-record.
- [Lock the November 10 demo architecture and truthful scope](FARMER-CALL-07-nov10-architecture-scope.md): browser demo, translated speech delivered with a private caution, Common rice at Burdwan APMC, voice-only dual confirmation then a nonbinding Twilio Sandbox WhatsApp summary; plus the data flow, agreement state machine, approved demo wording and build route.

## Not yet specified

- Acceptance run and public-video framing, once the agreement flow exists to rehearse.
- The public language pair (commodity and market are settled: Common rice, Burdwan APMC), after Hindi↔English quality and latency are measured.
- **Waiting on the human (each is a sharp question; ticket one per session):**
  - Unclear voice confirmation: after an unclear reply, does the app speak a retry prompt, wait silently, or void after the time limit?
  - WhatsApp numbers: held in private server settings, or entered by each participant in the room? Either way, never shown, logged, committed or filmed.
  - Stale AGMARKNET data: say "unavailable", or fall back to a clearly dated captured snapshot of the same variety and market? Mandis may be closed around Diwali (about 8 November; unverified), so this is likely.
- Very short turns that sometimes need a retry: cause unknown (too little audio versus a Prisma response); decide whether a script instruction or a minimum-length hint is enough.
- Whether the summary must carry quantity (the offer reader does not capture it).

## Out of scope

- Post-hackathon carrier deployment and business KYC.
- A claim that the sentiment shield can safely block harmful audio without measured false-positive and latency behavior.
- A legally binding “smart contract”, payment, or any claim that the app blocks, drops or verifies anything. Only the nonbinding Sandbox summary is in the demo.
