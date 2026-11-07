# CONTEXT.md: domain language

Use these terms exactly in code, UI, issues and the video. Each term is defined once. "Avoid" lists words that mean the
same thing and must not be used.

## People

- **Farmer**: the primary user. Calls the G-One number to sell produce or get help. Avoid: user, customer, caller (in
  code, "caller" is only a call-leg property).
- **Buyer**: a trader, mandi agent or kirana owner the assistant connects to the farmer on a second leg. In the demo,
  every buyer is a synthetic entry in the **buyer directory**. Avoid: merchant, vendor.
- **Kirana owner**: a neighbourhood shop owner. Secondary user, stretch scope. When a kirana owner buys from a farmer,
  they are a buyer.
- **Assistant**: the G-One AI voice on the call. It has one persona and speaks the listener's language. Avoid: bot,
  agent (in user-facing copy; "agent" is fine in code for the LLM loop).

## Calls

- **Call**: one farmer session from dial-in to hang-up. It has one or two legs. Avoid: session, room (old project).
- **Leg**: one phone connection between Twilio and a person. The **farmer leg** comes in first. A **buyer leg** is
  dialled out by the assistant. Each leg has its own media stream.
- **Media stream**: Twilio's bidirectional WebSocket carrying a leg's audio (mulaw, 8 kHz, 20 ms frames, base64).
- **Turn**: one person's stretch of speech, from speech start to end of speech as detected by turn detection.
- **Turn detection**: deciding a person has finished speaking (VAD or Gnani endpointing). Avoid: Tap Start/Stop (old
  browser model).
- **Barge-in**: the person starts speaking while the assistant is talking. The assistant stops (Twilio `clear`).
- **Intake**: the opening of a farmer leg, where the assistant learns what the farmer wants (crop, quantity, place,
  price expectation). A returning farmer gets a short intake because memory fills in what is known.
- **Connect**: the assistant dialling a buyer leg. It happens only after the farmer says yes to connecting.
- **Mediation**: the phase where farmer and buyer talk through the assistant. The assistant translates each turn into
  the listener's language and may add a whisper.
- **Whisper**: a private message the assistant speaks to the farmer only (price reference, scam caution, negotiation
  tip). The buyer never hears it. Avoid: shield, intercept.
- **Caution**: a whisper triggered by a scam pattern (asking for an OTP or PIN, "type your PIN to receive money", a
  threat). Fixed wording. It never repeats the secret.
- **Deal summary**: the terms both sides confirmed by voice at the end of mediation (crop, quantity, price per unit,
  pickup), read back and confirmed with a spoken yes. Nonbinding. Avoid: contract, agreement (legal sense).

## Knowledge and memory

- **Farmer profile**: stable facts about a farmer (name they gave, village/district, crops, usual mandi, preferred
  language), each with **provenance** (which call it came from, said by the farmer or inferred).
- **Call gist**: a short summary written at the end of each call: what was asked, what happened, open follow-ups.
- **Memory**: farmer profile plus recent call gists, keyed by the caller's phone number (stored hashed). Loaded at the
  start of a farmer leg and given to the assistant. Caller ID is not identity: never call a profile "verified".
- **Dated reference**: a market price stated as "AGMARKNET listed ₹X per quintal for <crop, variety, market> on
  <date>". Never "today's price", "fair price" or "live rate".
- **Buyer directory**: the list of synthetic buyers (name, crops wanted, district, price range, language, phone env var
  key). The real demo phone numbers live only in env vars.
- **Tool**: a function the assistant's LLM can call (mandi price, weather, find buyers, connect buyer, save note).

## Delivery

- **Console**: the web dashboard showing a live call (transcripts, translations, whispers, tool calls, memory). It is for
  the demo and the team, not for farmers.
- **Call simulator**: a web page that acts as a phone leg over the browser microphone and feeds the same pipeline. It is
  the fallback if Twilio fails. Never present it as a phone call.
- **Fallback floor**: the most reliable demo path that works at every checkpoint (single-leg call, or the simulator).
