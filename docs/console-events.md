# Console events

The backend publishes `ConsoleEvent`s on the `ConsoleBus`; the console WebSocket (`/ws/console`, issue #21) sends each
one as a JSON object. This file is the contract. The Java records live in
`backend/src/main/java/com/example/g_one/core/ConsoleEvent.java`, and `web/src/types/console.ts` mirrors them exactly.
Change all three together, and only through a contract issue.

A test (`ConsoleEventJsonTest`) parses every `json` example below into the Java records and checks that serializing
them gives the same JSON, so the examples cannot drift from the code.

All data here is synthetic. Events never carry raw phone numbers or audio.

## Common rules

- Every event is an object with a `type` string (the discriminator), `callId` (string) and `atMs` (number: epoch
  milliseconds when it happened).
- Field names are camelCase. Every field listed is always present; "or null" means the value can be `null`.
- Times named `...Ms` are epoch milliseconds, except `durationMs` (a length in milliseconds).
- Language fields hold a Gnani language code, such as `"hi-IN"` or `"en-IN"`.

## Shared types

| Name | JSON | Values |
| --- | --- | --- |
| `LegRole` | string | `"farmer"`, `"buyer"` |
| `LegSource` | string | `"twilio"`, `"simulator"` |
| `WhisperKind` | string | `"price_reference"`, `"caution"`, `"tip"` |
| `DealState` | string | `"draft"` (terms heard), `"readback"` (being read back), `"confirming"` (waiting for both yeses), `"agreed"`, `"closed"` (declined, expired or replaced) |
| `Origin` | string | `"said"` (the farmer said it), `"inferred"` (the assistant inferred it) |
| `Provenance` | object | `callId` string, `saidOrInferred` Origin, `atMs` number |
| `ProfileFact` | object | `field` string, `value` string, `provenance` Provenance |
| `CallGist` | object | `callId` string, `startedMs` number, `endedMs` number, `summary` string, `followUps` string array |

A caller key (`callerKey`) is 64 lowercase hex characters: HMAC-SHA256 of the caller's number with a secret salt. It is
not the number and not an identity.

## Events

### call_started

A farmer leg opened a new call.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `language` | string | The farmer's starting language |

```json
{
  "type": "call_started",
  "callId": "call-0001",
  "atMs": 1794132000000,
  "language": "hi-IN"
}
```

### leg_joined

A leg joined the call: the farmer leg first, then a buyer leg after connect.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `legId` | string | The leg |
| `role` | LegRole | Who is on it |
| `source` | LegSource | Twilio phone leg or call simulator |
| `language` | string | The language this person is spoken to in |

```json
{
  "type": "leg_joined",
  "callId": "call-0001",
  "atMs": 1794132000050,
  "legId": "leg-farmer-0001",
  "role": "farmer",
  "source": "twilio",
  "language": "hi-IN"
}
```

### leg_ended

A leg ended.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `legId` | string | The leg |
| `role` | LegRole | Who was on it |
| `reason` | string | Short machine word, such as `"hangup"`, `"stream_stopped"` or `"error"` |

```json
{
  "type": "leg_ended",
  "callId": "call-0001",
  "atMs": 1794132240000,
  "legId": "leg-buyer-0001",
  "role": "buyer",
  "reason": "hangup"
}
```

### turn_heard

A finished turn, in the speaker's own words.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `legId` | string | The speaker's leg |
| `role` | LegRole | The speaker |
| `language` | string | The language the turn was recognised in |
| `text` | string | The transcript |
| `startedMs` | number | Speech start (epoch ms) |
| `endedMs` | number | End of speech (epoch ms) |

```json
{
  "type": "turn_heard",
  "callId": "call-0001",
  "atMs": 1794132012400,
  "legId": "leg-farmer-0001",
  "role": "farmer",
  "language": "hi-IN",
  "text": "मुझे बीस क्विंटल प्याज बेचना है",
  "startedMs": 1794132009100,
  "endedMs": 1794132011800
}
```

### turn_translated

A heard turn, translated for the other side during mediation. `legId`, `role` and `turnStartedMs` match the
`turn_heard` event it translates.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `legId` | string | The speaker's leg |
| `role` | LegRole | The speaker |
| `toRole` | LegRole | The listener |
| `fromLanguage` | string | The speaker's language |
| `toLanguage` | string | The listener's language |
| `text` | string | The translation |
| `turnStartedMs` | number | `startedMs` of the translated turn |

```json
{
  "type": "turn_translated",
  "callId": "call-0001",
  "atMs": 1794132090700,
  "legId": "leg-buyer-0001",
  "role": "buyer",
  "toRole": "farmer",
  "fromLanguage": "en-IN",
  "toLanguage": "hi-IN",
  "text": "मैं चौंतीस सौ रुपये प्रति क्विंटल दे सकता हूँ",
  "turnStartedMs": 1794132087200
}
```

### assistant_said

The assistant's own words (greeting, reply, read-back), spoken on one leg.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `legId` | string | The leg it was spoken on |
| `role` | LegRole | The listener |
| `language` | string | The language it was spoken in |
| `text` | string | What was said |

```json
{
  "type": "assistant_said",
  "callId": "call-0001",
  "atMs": 1794132001500,
  "legId": "leg-farmer-0001",
  "role": "farmer",
  "language": "hi-IN",
  "text": "नमस्ते, मैं जी-वन हूँ। आज आप क्या बेचना चाहते हैं?"
}
```

### whisper_sent

A private message spoken to the farmer only. The buyer never hears it.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `legId` | string | The farmer leg |
| `kind` | WhisperKind | Price reference, caution or negotiation tip |
| `language` | string | The language it was spoken in |
| `text` | string | What was said |

```json
{
  "type": "whisper_sent",
  "callId": "call-0001",
  "atMs": 1794132092000,
  "legId": "leg-farmer-0001",
  "kind": "price_reference",
  "language": "hi-IN",
  "text": "एगमार्कनेट पर छह नवंबर को नासिक मंडी में लाल प्याज का भाव छत्तीस सौ रुपये प्रति क्विंटल था।"
}
```

### tool_called

The assistant called a tool.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `tool` | string | The tool name |
| `args` | object or null | The arguments object the model sent |
| `ok` | boolean | Whether the tool succeeded |
| `result` | string | The content handed back to the model (text or a JSON string) |
| `durationMs` | number | How long the tool took |

```json
{
  "type": "tool_called",
  "callId": "call-0001",
  "atMs": 1794132014000,
  "tool": "get_mandi_price",
  "args": {"commodity": "Onion", "market": "Nashik"},
  "ok": true,
  "result": "AGMARKNET listed 3600 rupees per quintal for Onion (Red) at Nashik on 2026-11-06.",
  "durationMs": 180
}
```

### memory_loaded

Memory was loaded at the start of the farmer leg. `returning` is true when anything was remembered; for a first-time
caller `facts` and `gists` are empty arrays. `gists` are newest first.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `callerKey` | string | The hashed caller number (64 hex characters) |
| `returning` | boolean | Anything was remembered |
| `facts` | ProfileFact array | The farmer profile |
| `gists` | CallGist array | Past call gists, newest first |

```json
{
  "type": "memory_loaded",
  "callId": "call-0001",
  "atMs": 1794132000100,
  "callerKey": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "returning": true,
  "facts": [
    {
      "field": "name",
      "value": "Suresh",
      "provenance": {"callId": "call-0000", "saidOrInferred": "said", "atMs": 1793700000000}
    },
    {
      "field": "district",
      "value": "Nashik",
      "provenance": {"callId": "call-0000", "saidOrInferred": "inferred", "atMs": 1793700060000}
    }
  ],
  "gists": [
    {
      "callId": "call-0000",
      "startedMs": 1793699900000,
      "endedMs": 1793700120000,
      "summary": "Asked about onion prices in Nashik. No buyer was connected.",
      "followUps": ["Call back when onion prices rise"]
    }
  ]
}
```

### deal_updated

The deal summary changed. Terms are as spoken; a term nobody stated is `null`. `price` is rupees per `unit`. The deal
summary is nonbinding.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `state` | DealState | Where the deal is |
| `commodity` | string or null | Crop |
| `variety` | string or null | Variety |
| `market` | string or null | Market named in the offer |
| `quantity` | string or null | Quantity as spoken, such as `"20 quintal"` |
| `price` | number or null | Rupees per `unit` |
| `unit` | string or null | Price unit, such as `"quintal"` |
| `pickup` | string or null | Pickup place and time as spoken |
| `farmerYes` | boolean | The farmer confirmed the current terms by voice |
| `buyerYes` | boolean | The buyer confirmed the current terms by voice |

```json
{
  "type": "deal_updated",
  "callId": "call-0001",
  "atMs": 1794132150000,
  "state": "confirming",
  "commodity": "Onion",
  "variety": null,
  "market": "Nashik",
  "quantity": "20 quintal",
  "price": 3400,
  "unit": "quintal",
  "pickup": null,
  "farmerYes": true,
  "buyerYes": false
}
```

### call_ended

The call is over (the farmer leg ended).

| Field | JSON type | Meaning |
| --- | --- | --- |
| `durationMs` | number | Call length |
| `reason` | string | Short machine word, such as `"hangup"` or `"error"` |

```json
{
  "type": "call_ended",
  "callId": "call-0001",
  "atMs": 1794132250000,
  "durationMs": 250000,
  "reason": "hangup"
}
```
