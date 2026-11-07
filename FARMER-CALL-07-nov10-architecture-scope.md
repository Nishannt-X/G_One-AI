---
title: Lock the November 10 demo architecture and truthful scope
parent: FARMER-CALL-WAYFINDER.md
labels: [wayfinder:grilling]
status: closed
assignee: Claude
blocked_by: []
---

## Question

What exactly does the November 10 browser demo promise for its three pillars (translated speech with a private caution, farmer-only market advice, voice-confirmed agreement summary), what is the smallest data flow and state machine that delivers them, and what may the demo and submission say about each?

## Decided by the human (stated 2026-11-06; recorded, not re-opened)

- Demo form: **browser on two phones**, not PSTN. Translated speech is **delivered** to the listener; the farmer also gets a **private caution** after a threatening buyer turn.
- Advice: **Common rice at Burdwan APMC**, dated AGMARKNET reference.
- Agreement: **voice-only dual confirmation** (no tap or button), then a **nonbinding** WhatsApp summary through the **Twilio WhatsApp Sandbox** (planned; separate from the paid voice account in [FARMER-CALL-05](FARMER-CALL-05-account-probe.md)).

## Status of each pillar on 2026-11-06

| Pillar | Built | Verified | Not verified |
| --- | --- | --- | --- |
| Translation | yes | Two real phones, English→Hindi and Hindi→English, several-second recordings | Stop→audible latency; very short turns sometimes need a retry |
| Private caution | yes, unit-tested with a fake model | no | Classifier against the live model and real Hindi speech |
| Market advice | live AGMARKNET fetch built | one read-only call and fakes | **Live app not restarted with it**; never run inside a phone call; Nov 10 data |
| Agreement + WhatsApp | agreement built with a fake sender on 2026-11-07 (see [DEMO](DEMO.md#agreement-by-voice-nonbinding)); no WhatsApp sender | fakes only | live classifier, real phones, any real sender |

## Resolution

### Data flow (`[new]` = not built)

```text
Phone ──turn (WAV)──> Prisma → translate → Timbre ──> listener hears it         (built)
                 └─ async, never blocks the turn:
                    buyer turn  → threat check → farmer-only `warning`          (built)
                    buyer turn  → offer read   → farmer-only `advisory`         (built)
                    [new] clear buyer offer    → room.openOffer = terms
                    [new] any turn while an offer is open → intent check (close | yes | no | none)
Room.agreement (in memory, per room) [new] ── state machine below
Final state AGREED ──> `agreement_final` to both screens ──> [new] WhatsApp summary via Twilio Sandbox
```

Reuse the offer the advisory already reads (commodity, variety, market, price, currency, unit); do not add a second extractor. Agreement events reuse `RoomEvent` audiences: the read-back is two events, one per role, each voiced in that person's language. No new dependency beyond the Twilio HTTP call (plain `RestClient`, no SDK), no database.

### Agreement state machine (agent proposal; the human has not reviewed the details)

| State | Enters when | Leaves when |
| --- | --- | --- |
| `IDLE` | room start, or after a void | a clear buyer offer arrives → `OFFER_OPEN` |
| `OFFER_OPEN` | latest clear buyer offer stored as the terms | a farmer turn is classified **accept** → `READBACK`; a newer clear offer replaces the terms |
| `READBACK` | terms frozen and spoken back to each person in their own language, as voice | both phones report playing it to the end → `CONFIRMING` ([exact guarantee](DEMO.md#agreement-by-voice-nonbinding)); a voice that cannot be made → `OFFER_OPEN` |
| `CONFIRMING` | farmer and buyer each `pending` | each person's **own authenticated** later turn classified `yes` marks only that role; both `yes` → `AGREED`; any `no` → `DECLINED`; new terms in a reply → closed until the buyer states a clear new offer |
| `AGREED` | both `yes` on the same frozen terms | summary send attempted once → delivery `sent` or `failed` |
| `DECLINED` / `EXPIRED` / `VOID` | a `no`; a time limit (suggest 120 s); a different offer or role change | return to `OFFER_OPEN` or `IDLE` |

Invariants: (1) **Fail closed**: only an explicit `yes` counts; unclear, unrelated or failed classification changes nothing. (2) A confirmation counts only for the speaker's own role, after both phones played the read-back, and for the exact frozen terms; new terms reset both, and only a clear buyer offer opens them. (3) Confirmation turns are ordinary translated turns, so each person hears the other say yes. (4) The classifier reads a transcript, so a speech-recognition error can create or hide a `yes`; the read-back of exact numbers is the only mitigation. (5) A failed classifier, voice or WhatsApp send never blocks translation; the app screen is the primary record and shows `sent` or `not sent`.

Summary content: crop, variety, market, price per unit (from the frozen terms), time, and "Nonbinding summary produced by a G-One demo from two spoken confirmations. Not a contract or a payment instruction." No quantity, delivery or payment terms unless a later decision adds quantity.

### Truth in the demo (use this wording; it is the single source for the video and submission)

| Say | Never say |
| --- | --- |
| "Two browsers on phones over mobile data" | "phone call", "PSTN", "works on any phone" |
| "Each turn is translated and spoken to the other person; the farmer gets a private caution when the buyer's words sound like a threat" | "intercepts", "blocks", "drops", "filters", "shield", "protects the farmer", "zero-trust", "safe conversation" |
| "AGMARKNET listed ₹X per quintal for Common rice at Burdwan APMC on <date>, a dated reference" | "today's price", "live rate", "modal", "fair price", "best price" |
| "Both said yes by voice; a nonbinding summary went to WhatsApp (Twilio Sandbox, demo numbers)" | "smart contract", "legally binding", "verified identity", "locked deal", "secure agreement" |
| Measured Stop-to-audible seconds from rehearsal | "real time", "instant" |

Say the limits out loud: classifier can miss or misfire; very short turns may need a retry; phone numbers and recordings stay out of the video (the Gnani rules require synthetic data). The untracked legacy `README.md` and `G-One architecture` describe a PSTN, real-time, deal-locking system; do not reuse their claims.

### Route to the destination (proposal)

1. **Task (human):** restart the live app with the six `G_ONE_AGMARKNET_*` variables for Common rice at Burdwan APMC and fresh secrets; check `/` and a real buyer offer.
2. Build the agreement state machine with a fake WhatsApp sender in tests; no network.
3. Add the Twilio Sandbox sender behind the same interface; human joins both demo phones to the sandbox and confirms current sandbox limits and message rules first (unverified).
4. Feature freeze after November 8; November 9: three consecutive scripted runs on the two phones from a private jar copy; record the measured latency and any retry.

Acceptance: in each scripted run the buyer offer yields a farmer-only dated reference, a staged threat line yields only the farmer's caution, two spoken yes turns yield `AGREED` and a WhatsApp message (or an honest `not sent`), and the buyer sees no private event.

## Open (human) choices, kept on the map's fog

~~Spoken retry for an unclear confirmation~~ (decided by the human: spoken retry; built); where WhatsApp numbers come from; dated-snapshot fallback if AGMARKNET is stale. Not decided here.
