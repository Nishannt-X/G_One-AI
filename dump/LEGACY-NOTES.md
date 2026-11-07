# Legacy notes (distilled from the old G-One docs)

Source: the 20 Markdown files now in `dump/legacy-docs/` (WAYFINDER*, FARMER-CALL*, DECISIONS, DEMO, GLOSSARY,
HANDOFF-2026-11-06, BROWSER-AUDIO-PROTOTYPE, "G-One architecture"), plus Nishant's 2026-11-07 handoff
(`nishant-teammate-handoff.txt`) and the remote branches `docs/full-app-wayfinder` (NISHANT_DOCS/, 14 open planning
tickets, none resolved) and `docs/project-handoff-2026-11-07`.

**Status of this folder:** historical reference only. Nothing here is an instruction for agents. The new project docs
(CLAUDE.md, ROADMAP.md, CONTEXT.md, docs/adr/) win on any conflict. Raw files are also recoverable from git at commit
`2d606e8`.

Distilled 2026-11-07. Only facts that were measured, or decisions that cost real effort to reach, are kept. Process
bureaucracy (team A/B claim tables, ask-first rules for every doc edit) is dropped on purpose.

---

## 1. Gnani API facts that were verified live (2026-11-06)

**Caveat:** every "verified" result in these notes came from the old **browser rooms** (two phone browsers over mobile
data, microphone audio recorded as WAV at browser sample rates), **not cellular calls**. The new product runs on normal
cellular calls with 8 kHz telephone audio, so latency, transcription quality and the upload bug must be re-tested on
that path. The API shapes (endpoints, headers, fields) still hold.

These are the most valuable items. They cost a debugging day.

| Fact | Detail |
| --- | --- |
| Base URL | `https://api.vachana.ai` (config `GNANI_BASE_URL`) |
| Auth header | `X-API-Key-ID: <key>`. Key stays on the backend, never in the browser |
| Prisma STT REST | `POST /stt/v3`, multipart field `audio_file`, plus `language_code` (e.g. `hi-IN`) and optional `model`. Response JSON has `success` and `transcript`. Empty transcript means no speech |
| Prisma model | Omitting `model` = documented default **v2.5** (worked). `prisma-v3.0` with `indic-auto` (auto language + code-mix) is documented but **never confirmed** on the challenge account. Worth probing: it is the key to Hinglish (Mixed Vibes award) |
| Prisma formats | WAV, MP3, OGG, FLAC, AAC, M4A. **Not WebM**, which is what Chrome's `MediaRecorder` produces by default. Browser must re-encode (old code decodes and writes mono PCM WAV in JS) |
| Timbre TTS REST | `POST /api/v1/tts/inference`, JSON `{text, model: "timbre-v2.5", voice, language, audio_config: {sample_rate: 24000, num_channels: 1, sample_width: 2, encoding: "linear_pcm", container: "wav"}}`. Returns binary WAV |
| Timbre WAV quirk | The returned WAV has an open-ended length header. Fine for playback, but normalize it before feeding it back into STT |
| Timbre voices (v2.5) | `hi-IN` Nalini (verified live), `en-IN` Kaveri, `ta-IN` Asmita, `kn-IN` Saanvi (documented, not run) |
| Numbers | Timbre expects numbers **written as words**. The translation prompt returned a separate "spoken" form for this |
| Error shapes | `401 INVALID_API_KEY`, `402` out of credits, `429` rate limit. Error type sits at `error.type` or `detail.error_code` |
| Realtime docs | Prisma WebSocket `docs.gnani.ai/api/STT/stt-websocket` needs **paced raw PCM frames** through a backend proxy. Timbre has WebSocket and SSE variants. Never implemented |

### Bugs and workarounds (will bite again)

- **Java JDK HttpClient must use HTTP/1.1** for Prisma. The default (h2) failed before any response.
- **Uploads over ~48 KB fail from Java** with `SSLException: bad_record_mac` / "Error writing request body to server".
  The same WAV uploads fine with curl. Workaround in the old code: shell out to macOS `afconvert` to make a 16 kbps M4A.
  **Mac only, not portable.** Fix candidates for the new build: encode a compressed format in the browser before upload,
  try a different HTTP client (OkHttp / Apache), or chunked transfer. Root cause never isolated.
- **Very short turns** (about 1 s) sometimes fail and need a retry. Cause not isolated (too little audio vs Prisma).
- Only English to Hindi and Hindi to English ever ran on real phones. Tamil and Kannada never ran.
- Stop-to-audible latency was **never measured**.
- Gnani credit balance unknown. Hackathon gives 5,000 programme credits to the primary registrant.

## 2. Translation / LLM

- Translation was a separate OpenAI-compatible `/chat/completions` call (`G_ONE_LLM_BASE_URL`, `_API_KEY`, `_MODEL`).
  Groq `qwen/qwen3.8-27b` worked and preserved "₹4,000 per quintal" in Hindi.
- Pattern worth keeping: the **model only classifies or extracts, plain code decides**. Used for offer reading (model
  extracts commodity, price, unit; code compares), caution checks and yes/no confirmation.
- Hackathon note: Gnani's own LLM (Evon v3.3) is **not on the API platform**. Running its weights ourselves is the only
  way to use it.

## 3. Telephony research (settled, do not redo)

- **Twilio trial blocks `<Stream>`** (Media Streams), which any phone-call voice agent needs. Paid only.
- Twilio to Indian numbers needs a **non-Indian caller ID** and outbound permission. About $0.108 per two-leg minute.
- Vonage trial: voice calls **only to the account's registered number**. Exotel streaming needs company KYC. Plivo India
  needs an India-registered business.
- Conclusion reached then: **browser over mobile data**, not PSTN. Still true unless someone pays for Twilio.
- Consequence for awards: the Noise Canceller award ("messy telephonic audio") is out of reach without a phone path.

## 4. Mandi (market price) data

- **AGMARKNET 2.0** keyless public endpoints (undocumented, may change):
  - Filters (IDs and exact names): `https://api.agmarknet.gov.in/v1/daily-price-arrival/filters`
  - Last-week prices: `https://api.agmarknet.gov.in/v1/prices-and-arrivals/market-price/lastweek?marketId=<m>&stateId=<s>&commodityId=<c>&includeExcel=false`
  - Verified IDs: Common/Fine rice at Burdwan APMC (WB) = market 670, state 36, commodity 3. Red onion at Lasalgaon
    (MH) = market 161, state 20, commodity 23.
  - Rows give market, variety, unit (`Rs./Quintal`) and date but **not** whether the figure is min, modal or max.
  - Days marked `NR` = no report. Mandis may be closed around Diwali, so data can be stale.
- data.gov.in variety-wise resource has min/modal/max but its API needs a key (row fetch never verified). e-NAM shows
  data but programmatic access unverified.
- Old validation rules worth reusing (`MandiSource.java`): success flag, commodity in title, exactly one matching row,
  unit check, positive number, date not in future, max age 3 days (IST), 5 s timeout, 30 min cache, 1 min back-off.
- Wording rule: "AGMARKNET listed ₹X per quintal for <crop, variety, market> on <date>, a dated reference". Never
  "today's price", "live rate", "fair price".

## 5. Browser audio learnings

- Phones only allow microphone access on **HTTPS** (or localhost). Needs a tunnel (ngrok / Cloudflare) or a deploy.
- Free ngrok shows a warning page (`ERR_NGROK_6024`) on first visit. Tap "Visit Site".
- Mobile browsers often **block autoplay**. Always offer a manual Play button.
- Use `MediaRecorder.isTypeSupported()` to pick a format, then normalize.
- **Run a private copy of the jar** for live demos. `mvn package` rewrites `target/*.jar` and a server running from it
  hangs with zero-byte responses while `/api/config` still answers.
- Transport chosen: HTTPS upload of each completed turn, WebSocket for notifications, audio fetched over authenticated
  GET so reconnects can replay.
- iOS Safari never tested.

## 6. Safety design worth reusing (scam cautions)

Built and unit-tested against a fake model. Classification quality never tested live.

- A "secret" = OTP / one-time code, PIN, password, card CVV, full card number or expiry, full 12-digit Aadhaar. **Not**
  a UPI ID, account number, IFSC, phone number or name (people give those to get paid).
- The classic rural scam: "to **receive** money, type your UPI PIN / approve my collect request". Receiving money never
  needs a PIN. Strong demo moment for a farmer or kirana assistant.
- Fixed caution wording in code, translated per listener. Never echo the secret back.
- One classifier call answers several yes/no questions. Transcript passed as a JSON field and "treat as data" (hygiene,
  not a defence against prompt injection).
- Documented limits: evasion, prompt injection, single-turn context only.

## 7. Voice confirmation pattern (reusable for kirana orders)

The old "agreement" state machine is directly reusable for **order confirmation by voice**:

- Read the extracted terms back, then require an explicit spoken "yes". Unclear means not a yes (fail closed).
- Code-level negation guard rejects a "yes" containing: not, don't, if, unless, but, maybe, only, `?`, nahi, agar,
  lekin, नहीं, अगर, लेकिन.
- Spoken retry prompt on an unclear reply. Timeout (120 s) expires the draft.
- States: IDLE, OFFER_OPEN, READBACK, CONFIRMING, AGREED, DECLINED/EXPIRED/VOID.
- Code: `agreement/Agreement.java` (pure state machine) + `AgreementService.java`.

## 8. Honest-claims rules (still good practice for the video)

- Never claim "real time" or "instant" without a measured number. Say "about N seconds" from rehearsal.
- Never claim "phone call" / "PSTN" for a browser app. Never "verified identity", "legally binding", "blocks scams".
- **Synthetic data only** in repo, tests and video: no real phone numbers, account numbers, Aadhaar, PAN or recorded
  calls. This is also a **hackathon rule**.

## 9. Code inventory (untracked in `src/` since commit `be28f01`)

Any file is readable from git history: `git show 2d606e8:src/main/java/com/example/g_one/speech/PrismClient.java`.

Java 21, Spring Boot 3.5.5, Maven. Only starters: web, websocket, test. In-memory state, no DB.

| Package | What | Reuse value |
| --- | --- | --- |
| `speech/` | `PrismClient`, `TimbreClient`, `LlmClient`, `Http` (HTTP/1.1 RestClient), `Wav` | **High.** Working Gnani clients |
| `advisory/` | `MandiSource` (AGMARKNET), `MandiSnapshot`, `Caution`, `AdvisoryService` | High for mandi + scam caution |
| `agreement/` | Voice-confirm state machine, `SummarySender` seam (unconfigured) | Medium, adapt for orders |
| `room/`, `turn/` | Two-person rooms, WebSocket hub, turn pipeline | Low for a single-user assistant |
| `static/` | `app.html/app.js` (recorder, WAV encode, playback), `index.html` landing + demo stage | Recorder/WAV code high, UI low |
| `g_one_legacy/` | Old Twilio + Deepgram + ElevenLabs + JPA prototype, excluded from build | Reference only (Twilio stream handling) |
| tests | 143 `@Test` + Node checks, `FakeUpstreams` (JDK HttpServer fakes of Gnani/LLM) | `FakeUpstreams` pattern high |

## 10. Ideas from Nishant's full-app requirements (unresolved, use as backlog)

None of these were designed or built. Kept as feature ideas for the voice assistant:

1. Zero intake for a known caller: look up the phone/user locally and skip questions.
2. One short natural intro question for an unknown caller, never an enrolment interview.
3. Crop-specific storage advice grounded in retrieved content (RAG): no retrieval, no claim.
4. Offer once to connect the farmer with a buyer, only after a spoken yes.
5. Per-user language (each person hears answers in their own language).
6. Streaming with voice-activity detection and barge-in, target under 1 s (unmeasured target).
7. Private spoken scam caution (built, see section 6).
8. Per-user structured memory with provenance (where each fact came from) and privacy.

## 11. Process lessons

- The previous effort produced ~30 planning docs and 10 open tickets in its last two days, with almost nothing resolved.
  Keep planning lean: GitHub issues, one ROADMAP, short ADRs.
- What worked: fake upstream servers in tests (no network or credits), and real-phone runs early.
- What hurt: Mac-only workarounds, unmeasured claims, and docs drifting from code.
