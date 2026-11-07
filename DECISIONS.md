# Decisions

Newest first.

## 2026-11-07 · Record the relayed full-app requirements as fixed inputs; design stays open
- **Status:** accepted as direction; the wording of the added items awaits the user's confirmation. Every design
  question under them is open.
- **Context:** The [requirements register](FULL-APP-REQUIREMENTS.md) fixes three behaviours (zero intake for a known
  caller, mandatory crop-specific storage RAG, one buyer offer with a spoken yes first). The user's latest list, relayed
  to the orchestrator on 2026-11-07, is wider: lookup/cache first with a short fallback question, per-user languages with
  translation, continuous full-duplex-ish streaming with voice-activity detection, barge-in and a target under one
  second, bilateral private spoken cautions with escalation **as a proposal**, and per-phone structured memory with
  provenance and privacy. A second team needs one place that says what is fixed and what is not.
- **Options considered:** A) Leave the register as it is and let each reader reconcile. B) Rewrite the register and the
  tickets now (rejected for this change: larger edit, and the added wording is not yet confirmed by the user). C) Record
  the split here and in the standalone [teammate handoff](TEAMMATE-HANDOFF-2026-11-07.md) and leave the register and
  tickets as written.
- **Decision:** C. These are fixed inputs, in the same sense as register items 5 to 7: a ticket decides how, never
  whether. **Fixed:** known caller = lookup in a local store first, nothing asked; unknown or failed lookup = one short,
  natural intro question, never a scripted enrolment, never blocks translation; crop-specific storage RAG grounds
  answers; one buyer offer, spoken yes before any matching or calling; each person has their own language with
  translation; streaming is continuous on both legs with VAD and barge-in and an under-one-second **target**;
  cautions stay private, spoken and bilateral; memory is per phone, structured, with provenance and privacy.
  **Still open:** what "cache" adds, profile fields, the store, retrieval stack and dependencies, corpus sources,
  language set and selection, what "full-duplex-ish" means and what the one second measures, mid-stream failure, the
  fallback to completed turns, all escalation levels, triggers and actions (escalation is a proposal, and any action on
  a person's call needs the user's explicit approval), consent, retention, deletion, disclosure and the Nov 10 cut line.
  Where this entry and the register's sentence "the rest of how the eight areas behave is not approved" differ for these
  items, this entry governs; the register and tickets 03, 04, 05 and 09 are not edited here and should be reconciled
  once the user confirms the wording.
- **Consequences:** No code, dependency, configuration or endpoint change, and no external call. The one-second figure is
  a target, not a measurement: "real time" and "instant" stay on the never-say list until it is measured on the phones
  that will be filmed. Caller ID still is not identity. Dependencies, schemas and stores still need their own entry and
  the user's approval first. The register and the four tickets now lag this entry until reconciled.
- **Decided by:** human (requirements, as relayed by the orchestrator) / agent (recording and split) ·
  **Related:** [teammate handoff](TEAMMATE-HANDOFF-2026-11-07.md), [map](FULL-APP-WAYFINDER.md)

## 2026-11-07 · Supersede the November 10 browser-demo scope with a full-application target (planning only)
- **Status:** accepted as direction; every design question under it is open.
- **Context:** The user replaced the scope frozen in [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md) with
  the full application by 2026-11-10, "or as close as possible", and named eight areas to decide: caller-ID profile and
  lookup, advisory RAG and consent-to-match, language, continuous streaming on both legs, bilateral shield escalation,
  per-phone memory and privacy, telephony and vendor feasibility, and the integrated Nov 10 route. The user asked for
  planning only through the wayfinder, with no feature implementation, services or external API calls.
- **Options considered:** A) Keep the browser-demo scope and treat the new asks as extras. B) Chart a new wayfinder map
  with decision tickets, mark the old map superseded for future scope, and keep its history. C) Start building the full
  app straight away (rejected: nothing about how the areas should behave has been decided, and several would break
  standing decisions on storage, dependencies and wording).
- **Decision:** B. [FULL-APP-WAYFINDER](FULL-APP-WAYFINDER.md) holds the map, ten open tickets and a
  [requirements register](FULL-APP-REQUIREMENTS.md) that separates user-approved items, carried-forward decisions,
  built status and open design. [FARMER-CALL-WAYFINDER](FARMER-CALL-WAYFINDER.md) gets a superseded banner and
  front-matter fields only; its body and the earlier entries here stand as written. The browser demo stays the fallback
  floor. The Say / never say table in FARMER-CALL-07 keeps governing public claims until a ticket replaces specific rows.
- **Consequences:** No code, dependency, config or endpoint changes. "Shield", "blocks", "phone call" and "real time"
  stay unavailable for public wording. Storage, retrieval and telephony each will need their own entry and the user's
  approval before anything is built. The decision work itself consumes part of the three days left, so the map
  recommends running both research tickets first and opening the route ticket early in triage mode. Nothing in the new
  documents is verified against a running service.
- **Decided by:** human (direction and scope) / agent (map structure and wording) · **Related:** [map](FULL-APP-WAYFINDER.md)

## 2026-11-07 · Harden the cautions after security review: receiving-money scams, wording, honesty, clip probing
- **Status:** accepted (hackathon scope); classifier quality still unverified against the live model.
- **Context:** Review of the bilateral cautions found: (P2) a request to *type* a PIN or OTP, or to approve a UPI collect
  request, "to receive money" is the usual scam but was not named; (P2) the classifier reads the speaker's words, so the
  speaker can try to steer it, and the docs claimed more than was tested; (P3) the docs said a spoken secret is never
  sent to the model again, but several checks each receive the transcript; "last words" could point at the wrong turn
  when a caution arrives after a later one; "pressure" was claimed although only threats and intimidation are
  classified; a full card number or expiry date was not covered; a guessed clip key got `403` for someone else's clip
  and `404` for none, which confirms that a private clip exists (the advisory key is derived from the turn id).
- **Options considered:** A) Do nothing. B) Add keyword rules for PINs and prompt-injection phrases (rejected: nothing
  measured shows they help, and they are brittle across Hindi, Tamil and Kannada). C) Extend the classifier prompt and
  fixed wording, isolate the transcript in a JSON field, correct the docs and state the limits. D) Redact the transcript
  before the model (rejected: edits what people say and still cannot know what is real). For the clip probe: E) random
  advisory keys. F) One `404` for missing and foreign clips.
- **Decision:** C and F. `secretRequest` now also covers asking to type or enter a secret, and to approve a collect
  request as the way to receive money; legitimate payment requests and payment details stay unflagged by instruction.
  The request caution says to never enter a PIN or OTP, or approve a payment request, to receive money. Card numbers and
  expiry dates are secrets. Caution wording says "earlier words" and "threat or intimidation" (the sentence about
  intent to pressure is gone). Only the caution classifier gets the JSON transcript field; this is described as hygiene,
  with evasion and injection written down as unmitigated limits ([DEMO](DEMO.md#how-it-works)). The audio route returns
  the same `404` for a missing clip and another person's clip. No dependency, config or endpoint change.
- **Consequences:** The fake-model tests prove routing, wording, prompt content and that a prompt without the new
  instruction yields no caution; they do not prove the live model flags these sentences, nor that it never flags a
  genuine request to be paid. The translation, offer and agreement-reply calls still send the transcript as bare text and
  share the injection risk. A client that relied on `403` for audio must now accept `404`. Advisory keys are still derivable but no longer confirm anything.
- **Decided by:** agent (human assigned the review fixes) · **Related:** [DEMO](DEMO.md), previous entry

## 2026-11-07 · Bilateral private cautions: threat, secret request, secret said aloud
- **Status:** accepted (hackathon scope); classifier quality unverified against the live model.
- **Context:** The farmer-only threat caution left the buyer with nothing when the farmer threatened them, and nobody
  was warned about the most common scam shape in this setting: someone asking for an OTP or PIN, or a person reading
  one out loud in a translated call. Translation had to stay unchanged and a spoken secret must never be copied
  anywhere new.
- **Options considered:** A) Keep farmer-only. B) Add a second classifier call per caution kind. C) One classifier call
  per turn that answers all three questions, with fixed wording per kind and per recipient. D) Redact or withhold the
  secret from the translation (rejected: the app would be editing what people say, and cannot know the secret is real).
  E) Let the model write the caution (rejected: it could echo the secret and over-promise safety). For voice keys:
  F) keep `w-<turnId>` (rejected: the other person knows the turn id and could probe for the clip). G) a random suffix.
- **Decision:** C and G. `Caution` holds the kinds, the recipient rule (threat and request go to the listener, a
  secret said aloud goes to the speaker) and the fixed English wording; `AdvisoryService.cautionAsync` runs after the
  turn is delivered, for both roles. Only an explicit `"threat"` or a JSON `true` counts. "Secret" is limited to an OTP
  or one-time code, PIN, password, card CVV and a full Aadhaar number (a full card number or expiry date was added on
  2026-11-07, see the entry above); UPI IDs, account numbers and IFSC codes are
  named in the prompt as not secrets, because they are given to be paid. The caution event carries a `kind` and a
  per-kind `id`, so several cautions in one turn do not collapse in the page. The price advice stays buyer-turn only.
  No new dependency.
- **Consequences:** One classifier call per turn now runs on farmer turns too. False negatives and positives are
  possible and untested against the live model, especially in Hindi, Tamil and Kannada and for digits spoken as words.
  Nobody is told when the check could not run. The speaker of a secret is warned after the other side already received
  the translation, so the wording says to assume they have it; the app cannot take it back. Cautions are not
  filtered from replay for their own recipient: a reconnect shows each person their own cautions again.
- **Decided by:** agent (human assigned the scope) · **Related:** [DEMO](DEMO.md), [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md)

## 2026-11-07 · Home page and same-origin demo stage; the app moves to app.html
- **Status:** accepted (prototype); the stage is checked in headless Chrome and with Node, not on a real phone.
- **Context:** The human asked for a polished G-One site (hero, two embedded phone demos, live pipeline, in the manner of
  the Maatu page) with an original identity, farmer and buyer as one featured use case, reusing the existing app and
  room APIs, keeping old room URLs, and showing no mock activity as live. No database, dependency, SMS or safety logic
  in this slice.
- **Options considered:** A) Keep the app at `/` and put the site elsewhere. B) Rebuild the app inside the home page.
  C) Move the static app to `app.html`, add a home page at `/`, frame the unchanged app twice from a stage script.
  For seat credentials: D) tokens in the iframe URL (rejected: a token in a URL lands in history and logs).
  E) The stage writes per-seat sessionStorage entries the frames read. For old links: F) a client-side redirect from the
  home page. G) A server-side forward. For reports to the rail: H) the stage polls or opens its own sockets (rejected:
  duplicates auth and would need both tokens in the stage logic). I) Each seat frame `postMessage`s metadata to the
  stage.
- **Decision:** C, E, G, I. Old `index.html` became `app.html` (git rename). `RoomLinkFilter` forwards `GET /` and
  `/index.html` with `room` to `/app.html`; it is a filter because a controller mapping with `params = "room"` made a
  plain `/` return 400. `securityHeaders` is ordered first so forwarded pages keep the headers. CSP `frame-ancestors`
  went from `'none'` to `'self'`. `app.js` gained an optional `seat` URL parameter (storage key `g_one:<room>:<seat>`,
  own role only, no join form) and metadata-only reports to its parent; without `seat` it behaves as before, including
  the `aisetu:` token migration. The stage validates origin, source window and field shapes; the seat comes from the
  window. No new dependency, endpoint, config, database or paid call; the access code still guards room creation through
  the same endpoint. Pure stage logic sits in `stage-core.js` so Node can test it.
- **Consequences:** Two seats in one browser share a microphone and speakers, so the stage is a demonstration of the
  flow and the private lanes, not a substitute for the two-phone run, and says so on the page. `/` no longer opens the
  app: anyone who bookmarked bare `/` to create a room lands on the home page (one click to `/app.html`). Anything on
  this origin can now frame the pages; there is no third-party framing. Status badges and limits on the page are copy
  that must be kept in line with DEMO.md and FARMER-CALL-07 by hand (a test bans the "never say" wording). The page
  names Gnani Prisma and Timbre and AGMARKNET as in DEMO.md; no brand assets from other sites are used.
- **Decided by:** human (scope), agent (design) · **Related:** [DEMO](DEMO.md#home-page-and-demo-stage)

## 2026-11-07 · Rename the project to G-One, keeping old configuration names as fallbacks
- **Status:** accepted
- **Context:** The project moved from the AI-Setu and Voxora names to G-One. Deployments already hold secrets under
  `AISETU_*` names and possibly local `voxora.*` keys, so a hard rename could silently drop an access code or API key.
- **Options considered:** A) Rename everything with no fallback. B) Keep the old technical names and change only visible
  text. C) Rename everything and read the old external names as fallbacks.
- **Decision:** C, with these names, as directed by the human:
  - Visible brand: `G-One`.
  - Java packages and code: `com.example.g_one`, and `com.example.g_one_legacy` for the Twilio prototype. Classes follow
    Java convention (`GOneApplication`, `GOneProperties`, `GOneLegacyApplication`).
  - Environment variables: `G_ONE_*`, each falling back to its `AISETU_*` name; see
    [DEMO.md](DEMO.md#renamed-configuration).
  - Maven artifacts and the Spring application name: `g_one` and `g_one_legacy`.
  - Spring property prefix: `g-one`, because Spring rejects an underscore there.
  - Legacy: `voxora.*` keys stay as fallbacks for `g-one.*`, and `/setu/*` routes stay as aliases of `/g-one/*`.
- **Consequences:** Restarting with existing `AISETU_*` variables keeps working. A set but blank new name hides the old
  one; for the access code the app fails closed and refuses to start (`AccessCodeGuard`) instead of turning the check
  off, and the browser moves a saved room token from the old `aisetu:` sessionStorage key. `aisetu.*` Spring property keys have no alias. Older entries in this log were
  updated to the new names and do not record what the code was called at the time; see git history for that. Remove the
  fallbacks once no deployment uses the old names.
- **Decided by:** human (names) / agent (fallbacks, legacy package placement) · **Related:** this branch

## 2026-11-07 · Gate voice confirmation on each phone's playback report
- **Status:** accepted; tested against fakes and a stubbed browser player only.
- **Context:** Review of the agreement found that a yes could count before anyone heard the read-back (it opened as soon
  as events were queued, even for a dropped socket or a text-only voice failure); that a term changed in a reply closed
  the draft with no way forward; that a slow offer read could overwrite a newer offer; and that a read-back could be sent
  just after the draft moved on.
- **Options considered:** A) Estimate playback from clip length on the server (rejected: says nothing about the phone).
  B) A tap to confirm hearing (rejected: human wants voice-only). C) Each phone reports the browser's `ended` event.
  For changed terms: D) extract a replacement from any reply (rejected: a farmer's number must not become terms, and it
  adds a paid call). E) Only a clear buyer offer opens new terms.
- **Decision:** C and E. `POST /api/rooms/{id}/agreement/readback-played` takes the role from the token; both phones must
  report for the current draft, else the 120 s limit expires it. A missing voice for either person sends nobody a read-back.
  A per-room turn sequence replaces the turn-id dedupe: the newest buyer turn's offer wins, an older reply is dropped
  unless it is a no. Read-back and retry are sent under the agreement lock, phones drop anything for an older draft, and
  state updates carry a sequence so phones can order them. No new dependency, config or paid call.
- **Consequences:** This proves the clip played to its end on that phone, not that a person heard it; see the
  [guarantee](DEMO.md#agreement-by-voice-nonbinding). Text no longer stands in when the voice fails, so a Timbre outage
  blocks confirmation (translated turns need Timbre anyway). A phone that cannot finish playback costs the draft. A slow
  phone's send can hold that room's agreement lock up to the socket limits; translated turns do not take it.
- **Decided by:** agent (mechanism), human (voice-only, no tap to confirm) · **Related:** [DEMO](DEMO.md)

## 2026-11-07 · Voice-only agreement behind a sender seam (no WhatsApp yet)
- **Status:** accepted (hackathon scope); built and tested against fakes only.
- **Context:** The November 10 plan promises a spoken yes from each person, then a nonbinding summary
  ([FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md)). The human chose a spoken retry for unclear
  confirmations, Common rice at Burdwan APMC, and the Twilio Sandbox for later. No paid or outward call may run in tests.
- **Options considered:** A) Tap-to-confirm buttons (rejected: human wants voice-only). B) Trust the model's "yes".
  C) Model reads intent; plain code decides (negation/condition guard, role, timing, terms, one-shot summary).
- **Decision:** C. New `agreement` package: `Agreement` is a pure, clock-driven state machine holding every rule;
  `AgreementService` asks the model one narrow question per relevant turn, speaks read-back/retry/notices to each person
  in their language, and offers the summary once to `SummarySender` (default: "not configured", so the phones honestly
  say "not sent"). Terms come from the offer the advisory already reads (`AdvisoryService.onOffer`); no second
  extractor, no new dependency, no config. Per-room state hangs off `Room.attachment`, so it dies with the room.
  Deviations from the FARMER-CALL-07 sketch: the 120 s limit starts at the farmer's acceptance and also covers the
  read-back; a reply begun before the read-back finished never counts; the buyer's new terms *replace*
  the draft (old confirmations cleared) instead of merely voiding it; an unanswered offer lapses after 5 minutes.
- **Consequences:** Up to one extra model call per relevant turn (none when no draft waits on that speaker) plus
  translation and voice for each read-back, retry and notice. The negation guard covers English and Hindi only. Summary
  delivery, recipients and the Twilio call are still the human's step 3. The farmer's acceptance is ignored if it beats the
  offer extraction (rehearse a two-second pause). Never claim identity checks, binding force or a locked deal.
- **Decided by:** agent (details; scope choices by the human) · **Related:** [DEMO](DEMO.md)

## 2026-11-06 · Scope the November 10 demo to three claim-limited pillars
- **Status:** accepted for planning; the agreement flow and WhatsApp sender are not built.
- **Context:** Four days remain. The prototype translates on real phones, but the caution and live price are not running
  and the agreement is unbuilt. The legacy README and architecture promise PSTN, interception and deal locking.
- **Options considered:** A) Claim the legacy design. B) Build a smaller agreement flow and claim only what is verified.
  C) Drop the agreement pillar.
- **Decision:** B, as the human directed: browser demo, translated speech delivered with a private farmer caution,
  Common rice at Burdwan APMC, voice-only dual confirmation, nonbinding Twilio Sandbox WhatsApp summary. States, data
  flow and the allowed wording live only in [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md).
- **Consequences:** Public wording must avoid interception, blocking, zero-trust and binding-contract claims. Three
  choices stay with the human (unclear-confirmation retry, WhatsApp number source, stale-price fallback); see the
  map's fog. A Twilio HTTP call adds an outward-facing paid-API dependency that needs the human's account first.
- **Decided by:** human (scope) / agent (state machine proposal) · **Related:** [map](FARMER-CALL-WAYFINDER.md)

## 2026-11-06 · Optional live AGMARKNET price for one configured variety and market
- **Status:** accepted (hackathon scope)
- **Context:** On a real phone run the rice advice said data was unavailable, because the only reference was the
  bundled onion snapshot. A keyless official endpoint (`/v1/prices-and-arrivals/market-price/lastweek`) returns dated
  rows with variety, market and unit, but does not label minimum, modal or maximum. It is not a documented API.
- **Options considered:** A) Keep hand-captured snapshots. B) Fetch live for one configured commodity/market/variety.
  C) Fetch every commodity and market from the filters endpoint and resolve names. D) Reuse the legacy hardcoded
  price service (rejected: invented numbers).
- **Decision:** B. New `MandiSource`: six env settings, all or none, selecting IDs plus the exact names to verify
  against the response; strict validation (success, commodity in title, one row, unit `Rs./Quintal`, positive number,
  date-column consistency, price date not in the future and within `G_ONE_AGMARKNET_MAX_AGE_DAYS`); 5 s timeout, 30 min
  cache, 1 min back-off after failure, last valid price reused only inside the age window, no sample fallback in live
  mode. Wording stays "AGMARKNET listed ₹X … dated reference". Reuses the existing HTTP helper (made public) and Jackson;
  no new dependency. `AdvisoryService` now takes the price from `MandiSource` and treats "no price" as "unavailable".
  The market comparison ignores the generic words APMC/mandi/market so a buyer's "Burdwan" matches "Burdwan APMC";
  nothing else about matching changed.
- **Consequences:** One fixed variety (if the buyer names none, the configured one is used and named). Depends on an
  undocumented government endpoint that may change or block; failures degrade to "unavailable". Not a modal price and
  not today's rate. Restart needed to change the crop/market. Supersedes the "snapshot only" part of
  [FARMER-CALL-03](FARMER-CALL-03-mandi-advice.md); the snapshot remains the default.
- **Decided by:** agent (human asked for a way to fetch actual mandi prices) · **Related:** [DEMO](DEMO.md)

## 2026-11-06 · Farmer-only threat caution on buyer turns
- **Status:** accepted (hackathon scope)
- **Context:** In a real-phone test the buyer said "I won't let you sell to anyone if you won't buy from me at my
  price" and the farmer got no warning. The earlier untracked prototype had a "sentiment shield" and "AI whisperer"
  idea (detect intimidation, whisper advice to the listener); only the concept is reused here.
- **Options considered:** A) Let the model write the advice. B) Model only classifies; fixed wording is translated
  and voiced. C) Do nothing and rely on the farmer reading the translation.
- **Decision:** B, in the existing `AdvisoryService`, as a separate `warning` event with the farmer as audience.
  Fixed wording avoids unsupported claims of safety or blocking, and only an explicit "threat" answer triggers it.
  Failures skip the caution silently and never affect the translated turn or the price advisory. No new dependency.
- **Consequences:** One extra LLM call per buyer turn. False negatives and false positives are possible and untested
  against the live model. The farmer is not told when the check could not run. Short-recording retries are deferred.
- **Decided by:** agent · **Related:** [DEMO](DEMO.md)

## 2026-11-06 · Compress longer Prisma uploads on the demo Mac
- **Status:** accepted (hackathon workaround)
- **Context:** A real Hindi reply failed while the Java client wrote a larger WAV to Prisma. A synthetic 5.6-second
  WAV reproduced the failure, while the same audio succeeded with direct curl and as a smaller M4A. The macOS
  `afconvert` utility is already installed on the demo machine.
- **Decision:** Convert WAV turns over 48 KB to 16 kbps M4A in a private temporary directory before Prisma upload.
  Delete the temporary WAV and M4A after conversion; keep short turns as WAV. No new library is required.
- **Consequences:** A synthetic 5.6-second Hindi turn completed Prisma → Groq → Timbre through the app. This path
  requires macOS `afconvert` for longer turns and briefly writes audio to disk. Browser audio needs a real-phone retest.
- **Decided by:** agent · **Related:** [DEMO](DEMO.md)

## 2026-11-06 · Use HTTP/1.1 for upstream speech requests
- **Status:** accepted
- **Context:** A short live WAV upload through the JDK client's default protocol failed before Prisma responded;
  the same short request reached Prisma with HTTP/1.1. A longer WAV still needed the compression workaround above.
- **Decision:** Set the shared JDK upstream client to HTTP/1.1. This uses the existing client and no new dependency.
- **Consequences:** The browser-to-backend transport is unchanged. Real-phone audio remains to be tested.
- **Decided by:** agent · **Related:** [WAYFINDER-04](WAYFINDER-04-gnani-account-probe.md)

## 2026-11-06 · Build the browser farmer–buyer demo as a minimal Spring Boot slice
- **Status:** accepted (prototype)
- **Context:** The Wayfinder maps settled on a browser-first two-phone demo (no carrier), HTTP completed-turn
  processing through Gnani Prisma and Timbre REST, WebSocket notifications, and a farmer-only dated market advisory.
- **Options considered:** A) Extend the untracked Twilio/Deepgram/ElevenLabs/JPA/Redis prototype. B) New minimal
  package in the existing `src/` with in-memory rooms. C) Polling instead of WebSocket (the earlier transport sketch).
- **Decision:** B. New `com.example.g_one` package; rooms, clips and history are in memory (single instance, a
  `ponytail` ceiling: move to Redis to scale out). WebSocket carries notifications only; audio is uploaded and
  downloaded over HTTP with bearer tokens. WebSocket was chosen by the human, superseding the polling proposal in
  [BROWSER-AUDIO-PROTOTYPE](BROWSER-AUDIO-PROTOTYPE.md). The legacy Twilio prototype is not tracked and is excluded from the build.
- **Consequences:** No database, Redis, Twilio or Deepgram dependency. Room state is lost on restart. A telephony path
  would need a separate adapter. Failure behavior is "abort the turn, retry or re-record".
- **Decided by:** human (scope, transport) / agent (structure) · **Related:** this branch

## 2026-11-06 · Dependencies: trimmed set, Spring Boot 3.5.5
- **Status:** accepted
- **Context:** The existing `pom.xml` (untracked) pulled JPA, MySQL, Redis, Twilio SDK, WebFlux and dotenv-java, none
  needed here, on Spring Boot 3.2.4 (out of OSS support).
- **Options considered:** A) Keep the old pom and exclude auto-configuration. B) Keep only what the slice uses. C) Add
  an HTTP/JSON/test library per concern.
- **Decision:** B. Only `spring-boot-starter-web`, `spring-boot-starter-websocket` and `spring-boot-starter-test`.
  Upstream calls use Spring's `RestClient` over the JDK HTTP client; tests use the JDK `HttpServer` as fake upstreams;
  no new third-party library was added. The parent moved to Boot 3.5.5 (already in the local Maven cache) to leave an
  unsupported release. The original pom is kept untracked as `pom.legacy.xml`. Legacy sources and
  `application*.properties` are excluded from compilation and packaging so local credential files are never bundled.
- **Consequences:** Environment variables only; `.env` is not auto-loaded (export it in the shell). Run `mvn clean`
  once after switching from the old build, as stale `target/` resources from it can override configuration.
- **Decided by:** agent (human authorized building the demo and necessary dependency changes).

## 2026-11-06 · Translation provider is configuration, not code
- **Status:** accepted
- **Context:** The plan fixes Prisma and Timbre but names no translation service, and no provider access is available.
- **Decision:** One small OpenAI-compatible `/chat/completions` client (`G_ONE_LLM_BASE_URL`, `_API_KEY`, `_MODEL`),
  with no default provider or model. It returns JSON with the translation plus a "spoken" form (numbers as words, which
  Timbre expects) and also reads buyer offers for the advisory. The comparison itself is deterministic code.
- **Consequences:** Any compatible provider works; translation quality and number fidelity are unverified until probed.
- **Decided by:** agent · **Related:** [WAYFINDER-04](WAYFINDER-04-gnani-account-probe.md)
