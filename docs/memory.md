# Farmer memory

- **Status:** decided 2026-11-08 by Nikhil (#9). Drafted by an agent; every drafted default was accepted: name check
  before memory is used, summary (gist) only and no stored transcripts, `forget_me` in v1, nothing stored about buyers
  beyond deal terms and directory id, synthetic seed farmer Suresh (red onion, Lasalgaon). Store: SQLite (ADR 0004).

Pairs with `docs/call-flow.md` (name check, notice line) and
`docs/adr/0004-runtime-and-store.md` (store). All data shown is synthetic.

## Schema

Every remembered fact is a `Fact<T>`:
`{ value: T, source: "said" | "inferred", callId: String, at: Instant }`.
A **said** fact (the farmer stated it) beats an **inferred** one (the LLM deduced it). A newer said fact replaces an
older one, and the gist records the change.

**FarmerProfile**

| Field | Type | Notes |
|---|---|---|
| `farmerKey` | String (64 hex) | HMAC of the caller number. This is the only identifier stored. |
| `name` | Fact\<String\> | The name the farmer gave. Never "verified". |
| `village`, `district`, `state` | Fact\<String\> each | Free text, as said. |
| `crops` | List\<Fact\<String\>\> | For example "red onion". |
| `usualMandi` | Fact\<String\> | For example "Lasalgaon APMC". |
| `language` | Fact\<Language\> | `HI`, `EN`, `HI_EN`, `BN`. |
| `noticeGivenAt` | Instant | When the memory notice was spoken. |
| `createdAt`, `updatedAt` | Instant | |

**CallGist**

| Field | Type | Notes |
|---|---|---|
| `callId` | String | |
| `farmerKey` | String | |
| `startedAt`, `endedAt` | Instant | |
| `direction` | `CALLBACK` \| `INBOUND` | |
| `language` | Language | |
| `gist` | String | **≤ 80 words**, third person, plain English. |
| `followUps` | List\<`{ text, dueHint, status: OPEN \| DONE }`\> | |
| `outcome` | `INFO_ONLY` \| `NO_ANSWER` \| `BUYER_DECLINED` \| `DEAL_CONFIRMED` \| `DEAL_UNCONFIRMED` | |
| `dealId` | String? | |

**Deal** (deal history)

| Field | Type | Notes |
|---|---|---|
| `dealId`, `callId`, `farmerKey` | String | |
| `buyerId` | String | Buyer directory id. No buyer phone number. |
| `crop` | String | |
| `quantity` | int | |
| `unit` | `"quintal"` | |
| `pricePerUnitInr` | int | |
| `pickup` | String | |
| `farmerYes`, `buyerYes` | boolean | |
| `status` | `AGREED` \| `UNCONFIRMED` \| `DECLINED` | |
| `reference` | `{ amountInr, market, date, source: "AGMARKNET" }`? | The dated reference quoted on the call. |
| `at` | Instant | |

Every deal is nonbinding, and the docs and UI say so.

Not stored: audio (ever) and full transcripts. Transcripts live in RAM for the live console only and are dropped at
hang-up. Secrets are never stored: the gist writer drops any 6+ digit run that is not a quantity, price or date.

## Keying

- `farmerKey = hex(HMAC-SHA256(G_ONE_MEMORY_HMAC_KEY, E.164 number))`. The key is ≥ 32 random bytes from env and is
  never committed. Raw numbers are never written to disk or logs.
- **Callback variant:** we dial the number. A real number lives only in env (`G_ONE_DEMO_FARMER_PHONE`) or in RAM for
  a pending callback request.
- **Withheld or anonymous caller ID:** don't load memory and don't write it. Say once: "आपका नंबर नहीं दिख रहा, इसलिए
  यह बातचीत याद नहीं रख पाऊँगी।" (*Aapka number nahin dikh raha, isliye yeh baatcheet yaad nahin rakh paaoongi.* /
  "I can't see your number, so I won't remember this call.")
- **Shared phone:** caller ID is not identity. A returning profile is used **only after the name check**
  ("क्या मैं सुरेश जी से बात कर रही हूँ?"). If the answer is no or unclear, run a new-farmer intake, reveal no stored
  facts, leave the profile unchanged and write a gist flagged `otherSpeaker`.
- **"मुझे भूल जाओ / forget me"** calls the `forget_me` tool, which deletes the profile, gists and deals for that key.
  It confirms with "ठीक है, मैंने आपकी जानकारी मिटा दी है।" ("Done, I've deleted what I had about you.").

## Write timing

- **At farmer-leg hang-up**, run one async LLM call (20 s timeout, 1 retry). Input: the call transcript (both legs,
  translated text) and the current profile. Output JSON: `{ profileUpdates[{field,value,source}], gist, followUps[],
  outcome }`.
- Code then validates it: a field whitelist, the 80-word cap, the secret scrub, and "inferred never overwrites said".
  Then it merges the result.
- **The deal is written by code at `AGREED`** (the confirmation state machine), not by the LLM. A failed gist call
  never loses a deal.
- If the LLM fails, write a template gist: "Call {date}: {crop?}, outcome {outcome}."
- When a follow-up is acted on, mark it `DONE` in that call's gist write.

## Prompt budget

The injected block is capped at **500 tokens**. It holds the profile, the last deal, the 3 newest gists and open
follow-ups. When over the cap, drop the oldest gist first. The example below is about **230 tokens**.

```text
FARMER MEMORY (caller-ID match only, NOT verified identity)
Use only after the farmer confirms the name "Suresh". Never read this list aloud. Ask before assuming.
Profile: name Suresh (said, c-1103) | near Lasalgaon, Nashik, Maharashtra (said, c-1103)
crops: red onion (said, c-1103); tomato (said, c-1028) | usual mandi: Lasalgaon APMC (said, c-1103)
language: Hindi with some English words (inferred, c-1028)
Last deal 2026-10-28: 6 quintal tomato to Shinde Traders, Rs 1,200/quintal, pickup at farm, both said yes (nonbinding).
Recent calls (newest first):
- 2026-11-03 c-1103: Asked red onion price at Lasalgaon; heard dated AGMARKNET reference. Harvest expected about
  Nov 8, roughly 20 quintal. Did not sell yet.
- 2026-10-28 c-1028: Sold 6 quintal tomato to Shinde Traders after a price whisper.
Open follow-ups:
- Find a buyer for ~20 quintal red onion after harvest (due ~2026-11-08).
```

## Returning-farmer greeting

The greeting has two turns, from `docs/call-flow.md`.

1. "नमस्ते, जी-वन बोल रही हूँ। क्या मैं सुरेश जी से बात कर रही हूँ?"
2. After a yes: "आपने कहा था कि कटाई के बाद प्याज़ के लिए ख़रीदार ढूँढूँ। क्या अभी लगभग बीस क्विंटल लाल प्याज़
   बेचना है?"

The returning-farmer intake then shrinks to this one confirmation.

## Privacy wording (honest)

- **On the call (new farmer, once):** "बेहतर मदद के लिए मैं इस बातचीत का छोटा सा नोट रखूँगी। न चाहें तो कभी भी
  'मुझे भूल जाओ' कहिए।" ("To help you better, I'll keep a short note of this call. Say 'forget me' any time if you
  don't want that.")
- **README and video:** "G-One keeps a short text note of each call (what was asked, what was agreed, what to follow
  up), stored under a hashed phone number. It does not store call recordings or full transcripts. Caller ID is not
  proof of identity, so G-One asks your name before using what it remembers, and 'forget me' deletes it. Deal
  summaries are nonbinding. Everyone in this demo is synthetic."
- Never say "verified", "secure identity" or "never forgets".

## Demo seed farmer (synthetic)

The seed file is `G_ONE_MEMORY_SEED_FILE`. The loader computes `farmerKey` from the env var named in `phoneEnvKey`,
so no number is ever in the repo.

```json
{
  "phoneEnvKey": "G_ONE_DEMO_FARMER_PHONE",
  "profile": {
    "name":      { "value": "Suresh", "source": "said", "callId": "c-1028", "at": "2026-10-28T10:12:00+05:30" },
    "village":   { "value": "near Lasalgaon", "source": "said", "callId": "c-1103", "at": "2026-11-03T09:40:00+05:30" },
    "district":  { "value": "Nashik", "source": "said", "callId": "c-1103", "at": "2026-11-03T09:40:00+05:30" },
    "state":     { "value": "Maharashtra", "source": "inferred", "callId": "c-1103", "at": "2026-11-03T09:40:00+05:30" },
    "crops": [
      { "value": "tomato", "source": "said", "callId": "c-1028", "at": "2026-10-28T10:12:00+05:30" },
      { "value": "red onion", "source": "said", "callId": "c-1103", "at": "2026-11-03T09:40:00+05:30" }
    ],
    "usualMandi": { "value": "Lasalgaon APMC", "source": "said", "callId": "c-1103", "at": "2026-11-03T09:40:00+05:30" },
    "language":   { "value": "HI_EN", "source": "inferred", "callId": "c-1028", "at": "2026-10-28T10:12:00+05:30" },
    "noticeGivenAt": "2026-10-28T10:10:30+05:30",
    "createdAt": "2026-10-28T10:10:00+05:30",
    "updatedAt": "2026-11-03T09:52:00+05:30"
  },
  "gists": [
    { "callId": "c-1028", "startedAt": "2026-10-28T10:10:00+05:30", "endedAt": "2026-10-28T10:19:00+05:30",
      "direction": "CALLBACK", "language": "HI_EN",
      "gist": "Suresh wanted to sell tomato. Heard the dated AGMARKNET reference for Lasalgaon. Connected to Shinde Traders; after a price whisper he asked for more and agreed 6 quintal at Rs 1,200 per quintal, pickup at farm.",
      "followUps": [], "outcome": "DEAL_CONFIRMED", "dealId": "d-1028-1" },
    { "callId": "c-1103", "startedAt": "2026-11-03T09:40:00+05:30", "endedAt": "2026-11-03T09:52:00+05:30",
      "direction": "CALLBACK", "language": "HI_EN",
      "gist": "Suresh asked the red onion price at Lasalgaon and heard the dated AGMARKNET reference. Harvest expected around Nov 8, roughly 20 quintal. He did not want a buyer yet.",
      "followUps": [ { "text": "Find a buyer for ~20 quintal red onion after harvest", "dueHint": "2026-11-08", "status": "OPEN" } ],
      "outcome": "INFO_ONLY", "dealId": null }
  ],
  "deals": [
    { "dealId": "d-1028-1", "callId": "c-1028", "buyerId": "shinde-traders", "crop": "tomato",
      "quantity": 6, "unit": "quintal", "pricePerUnitInr": 1200, "pickup": "at farm",
      "farmerYes": true, "buyerYes": true, "status": "AGREED", "reference": null,
      "at": "2026-10-28T10:18:00+05:30" }
  ]
}
```

The seed's `reference` is `null` on purpose. A real AGMARKNET figure appears only when the live or snapshot source
returns one, so the demo never invents a price.
