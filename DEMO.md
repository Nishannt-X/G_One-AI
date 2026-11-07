# G-One two-phone browser demo (prototype)

> **Status: prototype.** The code path is covered by automated tests that replace Gnani and the translation service
> with HTTP fakes. On 2026-11-06 two real phones completed English→Hindi and Hindi→English turns of several seconds
> through live Prisma, Groq translation and Timbre; very short turns sometimes needed a retry. The threat caution,
> live AGMARKNET fetch and the voice-only agreement are built but were not yet running in the live app (the agreement
> has only been exercised against fakes; its WhatsApp sender does not exist yet). See [Not yet verified](#not-yet-verified).
> Scope and approved wording for November 10: [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md).

A farmer and a buyer open one link on two phones, each picks a language, and they take turns: **Start → speak → Stop**
(max 30 s). The backend runs Gnani Prisma (speech-to-text) → translation → Gnani Timbre (text-to-speech). Both phones
get the transcript and translation over WebSocket; **only the listener** gets the translated voice. After a clear buyer
price offer, **only the farmer** gets a private advisory comparing it with a dated, preloaded market price. If the
buyer's words sound like a threat or intimidation, **only the farmer** also gets a private caution in their language.
After the buyer's clear offer and the farmer's spoken acceptance, both phones are read the same terms back and each person
confirms with a spoken "yes" (no button); two yes replies produce a nonbinding summary
([Agreement by voice](#agreement-by-voice-nonbinding)). The speaker's raw recording is never sent to the other phone. On the demo Mac, recordings over 48 KB are written
briefly to a private temporary directory for AAC conversion, then deleted before the Prisma request completes.

This is the browser-first path chosen in [FARMER-CALL-01](FARMER-CALL-01-telephony-gate.md). It is not PSTN and uses no
carrier. A Twilio path remains conditional (see [FARMER-CALL-05](FARMER-CALL-05-account-probe.md)).

## Run locally

Requires Java 21+ and Maven. The live demo currently also needs macOS `afconvert` for turns over 48 KB; a portable
converter is follow-up work. Spring Boot 3.5.5 is downloaded from Maven Central on first build.

```bash
cp .env.example .env        # fill in values; .env is git-ignored
set -a; . ./.env; set +a
mvn spring-boot:run         # http://localhost:8080
mvn test                    # 149 tests, no network or credentials needed (JS checks run under Node if installed)
```

Without credentials the app still starts; creating/joining rooms works and a turn returns `not_configured`.
`GET /api/config` reports `"ready": true` only when Gnani and translation settings are present (names only, no values).

**For a live demo, run a private copy of the jar.** `mvn package` can rewrite `target/*.jar` in place. A server already
running from that file then fails to load classes: worker threads die and requests hang with zero bytes. Endpoints whose
classes are already loaded may keep working, so `/api/config` alone does not prove health; also check `/`.

```bash
mvn package -DskipTests
RUN_DIR="$(mktemp -d)"; cp target/g_one-0.0.1-SNAPSHOT.jar "$RUN_DIR/app.jar"
set -a; . ./.env; set +a
PORT=18080 java -jar "$RUN_DIR/app.jar"
```

Languages offered come from `G_ONE_LANGUAGES` (`code:label:voice`). Defaults are Hindi, English, Tamil and Kannada
(`hi-IN`, `en-IN`, `ta-IN`, `kn-IN`), which the [Prisma](https://docs.gnani.ai/api/STT/speech-to-text) and
[Timbre](https://docs.gnani.ai/api/TTS/available-voices) docs list. Default voices are documented Timbre v2.5 names
(Nalini, Kaveri, Asmita, Saanvi); if you switch `GNANI_TTS_MODEL`, set voices that belong to that model.

## Use it on two phones

Phones only allow microphone access on **HTTPS** (or localhost), so expose the local server through an HTTPS tunnel
you control (for example a Cloudflare Tunnel or ngrok; this repo does not bundle or configure one). A free ngrok URL
first shows a browser warning page (`ERR_NGROK_6024`) on each phone; tap **Visit Site** to continue. Then:

1. Set `G_ONE_ACCESS_CODE` first: anyone with the public URL could otherwise create rooms and spend your credits.
2. Phone A opens `<HTTPS URL>/app.html` (the plain app; `/` is the home page with the demo stage), picks role + language,
   taps **Create room**, and sends the shown link to Phone B. Links of the form `<HTTPS URL>/?room=<id>` still open the
   app, so rooms shared before the home page existed keep working.
3. Phone B opens the link, picks a language, taps **Join** (it gets the other role).
4. Take turns with the big button. If a turn fails, choose **Retry same recording** or **Record again**.
5. If a phone blocks automatic playback, tap **▶ Play** on the message.

Rooms live in server memory (single instance), expire after idle time, and are capped (`G_ONE_MAX_ROOMS`,
`G_ONE_MAX_TURNS_PER_ROOM`). Use synthetic names and examples only; do not record real people or phone numbers.

## Home page and demo stage

`/` is the G-One home page (`index.html`): what the prototype is, a featured farmer and buyer use case with its status
and limits, and a **live demo stage**. The plain two-phone app moved to `/app.html`; nothing about its behavior or APIs
changed.

- **Stage:** *Start a session* creates one room through the normal `POST /api/rooms` and `/join` calls (farmer first,
  then buyer; it asks for the access code when `G_ONE_ACCESS_CODE` is set) and frames the real app twice, side by side,
  as `/app.html?room=<id>&seat=farmer` and `&seat=buyer`. Both seats run in one browser, so they share its microphone
  and speakers: use headphones and one speaker at a time. It is not the two-phone demo; for that use `/app.html` on two
  phones. *End session* clears the seats from this tab. A reload in the same tab takes the seats back while the room
  lives (rooms still expire on the server).
- **Nothing is simulated.** The lamps and the event rail react only to events a seat received from the server
  (`turn_started`, `turn`, `turn_failed`, `advisory`, `warning`, `agreement*`). Each rail entry names the seats that
  reported it, so a private advisory or caution shows one seat only. The rail never carries transcripts, translations,
  tokens or audio links. With no speech or translation credentials the stage says so and turns fail, as in the app. The
  "how a turn moves" diagram on the page is static and labelled as such.
- **Seats and storage.** Two same-origin frames of one tab share one `sessionStorage`, so a seat frame keeps its token
  under `g_one:<room>:farmer` or `g_one:<room>:buyer` (the plain app still uses `g_one:<room>`). The stage writes those
  entries; the token never travels in a URL. A seat entry whose saved role differs from the seat is ignored, and a seat
  with no entry shows "This seat is not signed in" instead of joining.
- **Messages.** A seat frame posts `{source: "g-one-seat", v: 1, seat, kind, ...}` to its parent with target origin set
  to the page's own origin (never `*`). The stage accepts a message only if its origin is the page's and its source
  window is one of the two seat frames, takes the seat from that window rather than from the message, and keeps only
  whitelisted type names and short `[A-Za-z0-9_.:-]` values.
- **Framing.** `Content-Security-Policy` now has `frame-ancestors 'self'` (was `'none'`): this site may frame its own
  pages, nobody else may. The rest of the policy is unchanged (no inline script, no external hosts). `Permissions-Policy`
  still allows the microphone for this origin only.
- **Old links.** A `GET` of `/` or `/index.html` carrying `room` is forwarded to `app.html` (not redirected), so
  `/?room=<id>` keeps its URL and the page script, share link and saved tokens work as before.
- **Checked on 2026-11-07** in headless Chrome at 390, 820 and 1366 px wide against a local server with no credentials:
  the stage starts, both seats load and connect, the rail starts empty, a reload resumes the seats, *End session*
  clears them, no horizontal scroll. **Not checked:** a real turn through the frames (no credentials), the microphone
  in a framed seat on a real phone or iOS Safari, autoplay of the listener's seat inside a frame (the seat shows
  "Tap ▶ Play" if blocked), screen readers, and any browser other than Chrome.

## Renamed configuration

The project was renamed, and every application variable moved from `AISETU_*` to `G_ONE_*`, keeping the same suffix.
Existing deployments keep working: each old name is still read when the new name is not set. `GNANI_*` and `PORT` did
not change.

| New name | Former name (still read) |
|----------|--------------------------|
| `G_ONE_LANGUAGES` | `AISETU_LANGUAGES` |
| `G_ONE_ACCESS_CODE` | `AISETU_ACCESS_CODE` |
| `G_ONE_MAX_TURNS_PER_ROOM`, `G_ONE_MAX_ROOMS`, `G_ONE_ROOM_IDLE_MINUTES` | `AISETU_MAX_TURNS_PER_ROOM`, `AISETU_MAX_ROOMS`, `AISETU_ROOM_IDLE_MINUTES` |
| `G_ONE_MANDI_SNAPSHOT_FILE` | `AISETU_MANDI_SNAPSHOT_FILE` |
| `G_ONE_AGMARKNET_*` (`BASE_URL`, `MARKET_ID`, `STATE_ID`, `COMMODITY_ID`, `COMMODITY`, `MARKET`, `VARIETY`, `TIMEOUT_SECONDS`, `MAX_AGE_DAYS`, `CACHE_MINUTES`) | `AISETU_AGMARKNET_*` |
| `G_ONE_LLM_BASE_URL`, `G_ONE_LLM_API_KEY`, `G_ONE_LLM_MODEL`, `G_ONE_LLM_TIMEOUT_SECONDS` | `AISETU_LLM_BASE_URL`, `AISETU_LLM_API_KEY`, `AISETU_LLM_MODEL`, `AISETU_LLM_TIMEOUT_SECONDS` |

- **A new name that is set wins, even when blank.** For `G_ONE_ACCESS_CODE` that would switch the room access check
  off, so the app refuses to start when `G_ONE_ACCESS_CODE` is blank but `AISETU_ACCESS_CODE` holds a code. Put the code
  in `G_ONE_ACCESS_CODE` and delete the old line (or unset the blank one). Other blank new names still hide their old name.
- A phone tab opened before the rename keeps its seat in a room: the saved token under the old `aisetu:<room>`
  sessionStorage key is read once and moved to `g_one:<room>`.
- Only these environment variables have a fallback. Spring property keys moved from `aisetu.*` to `g-one.*` with no
  alias, so update any local `application*.properties` or `--aisetu.*` argument. The prefix uses a hyphen because Spring
  does not allow an underscore there.
- The jar is now `target/g_one-0.0.1-SNAPSHOT.jar`. An app already running is unaffected until it is restarted.

## Provide credentials and probe (when you have them)

Supply values through your own secret mechanism (shell export or the git-ignored `.env`). Never paste them into chat
or commit them. Then walk this checklist and record **non-secret** results in
[WAYFINDER-04](WAYFINDER-04-gnani-account-probe.md):

1. `curl -s localhost:8080/api/config` → `"ready": true`.
2. Two browser tabs (or two phones): create + join, each speaking a different language. Say one short synthetic sentence.
3. Expect: both screens show transcript + translation; only the listener hears audio.
4. If a turn fails, the error shows the stage (`stt_failed`, `translation_failed`, `tts_failed`) and the upstream HTTP
   status or documented error type, e.g. `HTTP 401 (INVALID_API_KEY)`, `402` for credits, `429` rate limit.
5. Which Prisma model works for your account? Leave `GNANI_STT_MODEL` empty for the documented default (v2.5), or set
   `prisma-v3.0` if your account has it (docs: v3.0 supports `indic-auto`; this app sends the speaker's chosen code).
6. Say a buyer line such as "I offer four thousand rupees per quintal for red onion" in English as the buyer; the
   farmer should get a private advisory. Check that spoken numbers are pronounced correctly (Timbre expects numbers
   written as words; the translator is asked to provide a "spoken" form).
7. Say a synthetic threat as the buyer, e.g. "I won't let you sell to anyone if you won't buy from me at my price".
   The farmer should get a private caution card (and voice) in their language; the buyer must see none. Then say a firm
   but benign line ("My final offer is four thousand rupees per quintal, take it or leave it"): no caution expected.
8. Measure Stop-to-audible latency on the phones and note it.

## How it works

```text
Phone ──HTTPS multipart WAV (≤30 s)──> POST /api/rooms/{id}/turns
        Prisma REST (backend, X-API-Key-ID) → transcript
        OpenAI-compatible chat call         → translation (+ spoken form)
        Timbre REST (backend)               → WAV for the listener
Both phones <── WebSocket /ws events: turn_started · turn · turn_failed · advisory (farmer only) · warning (farmer only) · presence
                                       agreement (shared state) · agreement_readback / _retry / _notice / _final (one per person)
Listener ──GET /api/rooms/{id}/audio/{key}──> translated WAV (listener-only, bearer token)
Phone ──POST /api/rooms/{id}/agreement/readback-played {version}──> this phone played its read-back to the end
```

- **Auth:** an unguessable room id plus a per-person bearer token (returned on create/join). The WebSocket takes the token
  in its URL query; the page sets `Referrer-Policy: no-referrer`. No cookies.
- **Advisory:** the model only reads the offer out of the buyer's sentence (commodity, price, unit). The comparison is
  plain code against one dated reference price: either the live AGMARKNET lookup below, or one dated snapshot
  (`src/main/resources/mandi-snapshot.sample.json`, or your file via `G_ONE_MANDI_SNAPSHOT_FILE`). It requires a clear
  offer in INR with the same commodity and unit; any variety or market the buyer states must match (generic words
  "APMC", "mandi" and "market" in a market name are ignored, so "Burdwan mandi" matches "Burdwan APMC"). If they don't,
  or no trustworthy price exists, the farmer is told a reliable comparison is unavailable. The message always names the
  source date and calls it a dated reference, never today's rate. **The bundled sample is a development example (red
  onion, Lasalgaon, 4 Nov 2026); use the live lookup or a row you capture and verify before filming.**
- **Threat caution:** after every buyer turn (never farmer turns) a second, independent model call only classifies
  whether the buyer threatened or intimidated the farmer ("threat" or "none"; anything else, including unsure, means
  none). The caution's wording is fixed in code, then translated and voiced in the farmer's language: it says the
  words sounded like a threat or intimidation meant to pressure them in the negotiation (it does not assume the threat
  was about price), that the farmer need not agree to anything because of it, to stay calm, ask for time, agree to
  nothing now, and ask a trusted person, farmer group or mandi officials for help if unsafe. It also says the app
  cannot stop the buyer or check what they can actually do. It travels as a `warning` event with an audience of the
  farmer only: the buyer's phone never receives it, cannot fetch its voice, and a reconnect does not replay it.
  **No caution does not mean the conversation is safe**: the classifier can miss threats and can be wrong.
- **Failure behavior (minimal, settles FARMER-CALL-06 for the browser demo):** any stage failure aborts the turn, nobody
  hears partial audio, both phones are told the turn failed, and the speaker can retry the same recording or re-record.
  Advisory problems never block the translated turn; the advisory is skipped. The same holds for the threat caution:
  if its check or translation fails, no caution is shown (it is not guessed, and the farmer is not told it failed); if
  only its voice fails, the text caution is still shown.

## Agreement by voice (nonbinding)

State machine and wording rules: [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md). What is built:

1. A **clear buyer offer in rupees** (the same read the advisory makes; there is no second extractor) opens a draft with
   exactly the words spoken. A variety or market the buyer did not say stays "not stated"; nothing is filled in from the
   market reference.
2. The **farmer accepts by voice** ("yes, I accept"). Only then does the server read the *same* terms back, once to each
   person, in their own language (text plus voice, each person's voice fetchable only by them). If either voice cannot
   be produced, nobody is sent a read-back and the farmer is asked to say yes again: text alone never opens a voice
   confirmation.
3. **Both phones must report playing the read-back to its end** (see the guarantee below) before anything can count. A
   phone that missed it (dropped socket, reload) gets it again on reconnect; if autoplay is blocked the screen asks for a
   tap on ▶ Play, which only plays the audio and never confirms anything.
   Each person then says **"Yes, I agree"** (or "No"). Only a plain, explicit yes from that person's own phone counts, and
   only if that recording began after both phones finished playing. Both yes → one summary.
4. **Unclear** reply (hedged, conditional, a question, a classifier outage): nothing is counted and that person alone is
   asked aloud, in their language, to say "Yes, I agree" or "No" again. Unrelated talk changes nothing and prompts nobody.
5. **Any changed term** cancels both confirmations and both are told. **Only a clear buyer offer opens new terms**: a
   new offer replaces the draft at once, while new terms spoken in a reply (the farmer's counter-offer, or a buyer sentence
   the offer read could not use) just close it, and the buyer must then state the offer clearly. Either way the new terms
   need a fresh acceptance, read-back and two yes replies. A **no** from anyone ends the draft, also when said during the
   read-back. **120 s** after the farmer's acceptance an unconfirmed draft expires (both are told), including when a phone
   never finishes the read-back; an unanswered offer lapses after 5 minutes. Slow model answers cannot reorder this: the
   newest buyer turn's offer wins whatever order the answers arrive in, and a read-back or retry for a replaced draft is
   never sent, stops playing if it already started, and cannot be reported.
6. The summary is shown to both (in their languages, with voice) and offered **once** to the `SummarySender`. Retries of
   the same recording, repeated yes replies, reconnects and the buyer repeating the same offer never produce a second one.
   Today the only sender is "not configured", so each phone shows **"WhatsApp copy: not sent."** A Twilio sandbox sender
   plugs in behind `SummarySender` later; recipients and credentials belong to that sender, not to the summary.

The summary says: crop, variety, market, price per unit (as spoken), when, and "Nonbinding summary produced by a G-One
demo from two spoken confirmations. Not a contract or a payment instruction. The speakers' identities were not
verified." It never contains the farmer-only reference price or caution; those travel on separate farmer-only events.

Safeguards in code, independent of the model: a "yes" is rejected if the transcript contains a negation, condition,
question or hedge ("not", "don't", "if", "unless", "but", "maybe", "only", `?`, and the Hindi forms "nahi", "agar",
"lekin", "नहीं", "अगर", "लेकिन" ...) even when the model says yes; a missing or unknown model answer counts as unclear;
a model that says yes while also claiming new terms is unclear. Everything fails closed: no model, translation, voice or
sender failure ever counts as a yes, and none blocks the translated turn.

**What "played" guarantees.** The server counts a phone once that person's own authenticated page reports the browser's
`ended` event for that draft's clip, which fires only when the clip plays to its end; a blocked, failed or interrupted
clip is not reported. **A browser cannot prove anyone heard it.** Muted system volume, a silent mode switch, headphones
or a person who walked away all still report as played, and a modified client holding that person's token could report
falsely (only for their own phone). So the demo may say "both phones played the terms", never "both people heard them".
Two small time gaps remain: the server estimates when a reply began from its arrival time minus its recording length, so
a reply begun up to about the upload delay before playback ended may still count; and the phone's report takes a moment
to arrive, which only makes the gate stricter.

**Rehearsal script** (pause about two seconds after each offer so the offer is registered before the acceptance, keep both
phones' volume up): buyer "I offer three thousand four hundred rupees per quintal for Common rice at Burdwan APMC" →
farmer "Yes, I accept" → both phones play the read-back and the panel shows both as played → farmer "Yes, I agree" →
buyer "Yes, I agree" → both see the summary and "WhatsApp copy: not sent". Wait for the panel to ask for a yes: a reply
begun earlier is not counted. Also rehearse: a conditional yes ("yes, but only if you pay for transport") → spoken retry;
a changed price after one yes → both told, start over; silence for two minutes → expiry.

Limits (say them out loud): speech recognition can mishear a number or a yes, so the read-back is the only check;
the negation guard knows English and Hindi only (other languages rely on the model); the same phone and voice are
assumed, there is no identity check; a very short or quiet recording may need a retry; if the farmer accepts before the
offer was registered the acceptance is ignored and must be repeated; the draft lives in server memory only.

## Live market price (AGMARKNET, read-only, no key)

Set these **non-secret** variables to price ONE variety at ONE market from the public
[AGMARKNET 2.0](https://api.agmarknet.gov.in/v1/daily-price-arrival/filters) "last week" endpoint. Set all six or none;
it cannot be combined with `G_ONE_MANDI_SNAPSHOT_FILE`, and a partial set stops the app at startup. Example verified on
6 Nov 2026 (rice, Common, Burdwan APMC, West Bengal):

```bash
export G_ONE_AGMARKNET_MARKET_ID=670 G_ONE_AGMARKNET_STATE_ID=36 G_ONE_AGMARKNET_COMMODITY_ID=3
export G_ONE_AGMARKNET_COMMODITY=Rice G_ONE_AGMARKNET_MARKET="Burdwan APMC" G_ONE_AGMARKNET_VARIETY=Common
# "Fine" works the same way (Burdwan APMC listed Fine rice too). IDs and exact names come from the filters URL above.
```

With the app's other variables (see Run locally), restart and say as the buyer "I offer three thousand four hundred
rupees per quintal for rice". Expected farmer-only text: "AGMARKNET listed ₹3,400 per quintal for Common rice at Burdwan
APMC on 4 November 2026. That is a dated reference, not today's rate. …" (the figure and date are whatever AGMARKNET
lists when you run it).

What it does and refuses:

- Fetches only when a buyer offer needs it, with a 5 s limit (`G_ONE_AGMARKNET_TIMEOUT_SECONDS`), then caches the result
  for 30 min. After a failed lookup it waits 1 min before trying again. The translated turn never waits for this.
- Accepts a price only if: the response says success; its title names the configured commodity; exactly one row is
  labelled "`<market>` - `<variety>`"; the unit is `Rs./Quintal`; the price is a positive number; every date column's
  title agrees with its date; and the newest reported day is not in the future and no older than
  `G_ONE_AGMARKNET_MAX_AGE_DAYS` (default 3, judged in India time). A day marked "NR" (no report) is skipped to the
  earlier day, which keeps its own date. Anything else gives no price.
- If the lookup fails, the last valid price is reused only while it is still inside that age window; otherwise the
  farmer hears "reliable comparison unavailable". The bundled onion sample is **not** a fallback in live mode.
- The source does not say whether a figure is minimum, modal or maximum, so it is only ever "AGMARKNET listed ₹X on
  date", never "today's price", "modal" or "fair". A buyer's offer for another variety, market, commodity or unit
  (for example Fine rice, or per kg) gets no numbers.

Limits: one fixed variety. If the buyer names no variety, the offer is compared with the configured one and the message
says which (Common and Fine differ by about 12%). The data is a daily wholesale listing, may lag, and is not a quote
for any sale. A hostile or broken API response is rejected, but the app trusts the government host named in the base URL.

## Not yet verified

- Gnani and translation quality, pronunciation and latency across the eventual public-demo language pair. Only one
  synthetic Hindi turn has completed the live backend path; remaining credit balance is unknown.
- Browser recording and playback on the other phone models and browsers you will film with; the two real phones tested
  so far completed several-second turns in both directions through the macOS AAC conversion. Very short turns sometimes
  need a retry (cause not isolated). Stop-to-audible latency is unmeasured.
- Threat-detection quality on real speech: the prompt is untested against the live model and across languages (Hindi
  or Kannada threats, sarcasm, quoted speech, false alarms on hard bargaining), and the check adds an LLM call per
  buyer turn. The tests use a fake model, so they prove routing, privacy and failure behavior, not classification.
- The agreement flow: covered by 80+ automated tests that replace the model, translation, voice and sender with fakes
  (including two real WebSocket clients, and the real page script against a stub player). It has not run in the live app,
  on real phones, against the live classifier (Hindi yes/no phrasing, accents, short replies), or with any real sender;
  read-back and summary translation quality and pronunciation of the numbers in Hindi are unchecked. Its latency (accept →
  read-back audible) is unmeasured. The playback report has never run in a real phone browser, so `ended` behavior on the
  phones you will film with (iOS Safari especially) and a screen lock mid-clip are unchecked.
- Behavior with more than a handful of concurrent rooms (single in-memory instance by design).
- Kilogram↔quintal offers are intentionally "unavailable", not converted.
- Live AGMARKNET lookup: verified against fake servers, the real captured response, and one real read-only call on 6 Nov
  2026. Not yet exercised inside a live phone call; the endpoint is undocumented, so its shape may change (the checks
  above then reject it). It has not been confirmed for the public-demo crop/market or what AGMARKNET lists on Nov 10.
