# Full-application requirements register

Companion to [FULL-APP-WAYFINDER](FULL-APP-WAYFINDER.md). It is a reference sheet, not a ticket, and it holds no design decisions. Its job is to stop three things being confused: what the user approved, what is built, and what is still open. Written 2026-11-07. Nothing here was run or verified; statuses are copied from repo docs, with their dates.

## Status vocabulary

| Tag | Meaning |
| --- | --- |
| **USER-NOW** | Stated by the user for this effort on 2026-11-07, as relayed in the planning brief. |
| **USER-EARLIER** | Decided by the human in the earlier effort and recorded in the repo. Still in force unless a ticket here changes it with the user. |
| **BUILT** | Code exists in `main` (`2d606e8`). The Verified column says how far it was checked. |
| **OPEN** | Design not decided. A ticket owns it. |
| **LEAN** | An agent suggestion written in a ticket. Not approved; the user may ignore it. |

## A. Approved by the user for this effort (USER-NOW)

1. **Target: the full application by 2026-11-10, or as close as possible.** The earlier Nov 10 browser-demo scope is superseded for future scope. History is kept.
2. **Plan with wayfinder first.** Decision tickets only. No feature implementation, no services, no external APIs in this planning pass.
3. **These eight areas are in scope as areas to decide**: caller-ID profile and lookup; advisory RAG and consent-to-match; language; continuous streaming on both legs; bilateral shield escalation; per-phone memory and privacy; telephony and vendor feasibility; the integrated Nov 10 route.
4. **Process:** docs-only change on a feature branch from `origin/main`; the user owns every PR and merge; no PR comments; no secrets requested or read.

**What is not approved:** *how any of the eight areas should behave.* The user named them. The repo holds no statement of what each must do. Each ticket's first job is to capture the user's own words and keep them apart from agent suggestions. Where a ticket offers readings or options, they are labelled as such.

## B. Carried forward and still in force (USER-EARLIER)

Source for each: [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md), [DECISIONS](DECISIONS.md), [DEMO](DEMO.md) unless noted. Any of these can be changed, but only by the user, in a ticket.

| Item | Where it could now conflict |
| --- | --- |
| Voice-only agreement (no tap or button confirms), two spoken yes replies, nonbinding summary that says it is not a contract or payment instruction and that identities were not verified | [Consent to match](FULL-APP-08-consent-to-match.md) must stay distinct from agreement consent; caller ID must not be worded as identity verification |
| Cautions are a courtesy, not a control: fixed wording, never echo what was said, translation delivered unchanged, each caution reaches one person only | [Bilateral shield escalation](FULL-APP-09-bilateral-shield-escalation.md) asks whether to go beyond this |
| The **Say / never say** wording table | "Shield", "blocks", "intercepts", "real time", "phone call", "verified identity" are all on the never-say list |
| Price wording: "AGMARKNET listed ₹X per quintal for <crop, variety, market> on <date>, a dated reference". Never "today's price", "modal", "fair" or "live rate" | [Advisory RAG](FULL-APP-07-advisory-rag.md) must keep or consciously replace it |
| Common rice at Burdwan APMC for the Nov 10 browser demo | Whether the full app keeps one fixed commodity and market is open |
| Twilio WhatsApp Sandbox for the summary (planned, not built, human's account first) | Open human choices: number source, stale-price fallback |
| Synthetic names, numbers, prices and recordings only in the repo and the demo (Gnani rules) | [Per-phone memory](FULL-APP-05-per-phone-memory-privacy.md) and [caller-ID profile](FULL-APP-06-caller-id-profile-lookup.md) both want real-number-shaped data |
| Browser over mobile data was the Nov 10 form ([FARMER-CALL-01](FARMER-CALL-01-telephony-gate.md)) | Superseded for future scope. Whether the full app is phone calls, browser, or both is [telephony feasibility](FULL-APP-01-telephony-vendor-feasibility.md)'s job. The user has not said |
| No database, no Redis, no new dependency (the "trimmed set", `DECISIONS`, 2026-11-06) | Memory, profiles and RAG each probably need storage or a new component: each needs a new `DECISIONS` entry and the user's approval first |
| Spoken retry for an unclear confirmation | Applies to the agreement; streaming may change what "a reply" is |

## C. Capability matrix: full target, today, Nov 10 slice

"Full target" is only the user's **area name**; it is not a specification. "Today" is from the repo as of `main` `2d606e8` and the 2026-11-07 handoff. "Nov 10 slice" is **undecided for every row**; the right-hand ticket decides it.

| Area | Full target (user's name only) | Today: built / verified | Nov 10 slice | Decided by |
| --- | --- | --- | --- | --- |
| Telephony and vendor | Not stated beyond "feasibility" | Not built. Legacy Twilio prototype is tracked but **excluded from the build**. A Twilio trial blocks Media Streams ([FARMER-CALL-01](FARMER-CALL-01-telephony-gate.md), 2026-11-06, not re-verified). No account tested | **Undecided** | [01](FULL-APP-01-telephony-vendor-feasibility.md) |
| Caller-ID profile and lookup | Not stated beyond the name | Not built. The browser path has no phone number at all; roles are picked by the room creator | **Undecided** | [06](FULL-APP-06-caller-id-profile-lookup.md) |
| Per-phone memory and privacy | Not stated beyond the name | Not built. Rooms, clips and agreement state live in server memory on one instance and vanish on restart | **Undecided** | [05](FULL-APP-05-per-phone-memory-privacy.md) |
| Advisory RAG | Not stated beyond "RAG" | Not built. The advisory is a deterministic comparison against one AGMARKNET row (one commodity, variety, market). Built; live call verified once read-only on 2026-11-06; never run in a live phone session | **Undecided** | [07](FULL-APP-07-advisory-rag.md) |
| Consent-to-match | Not stated; meaning itself unconfirmed | Not built. Nothing matches people today | **Undecided** | [08](FULL-APP-08-consent-to-match.md) |
| Language | Not stated beyond "language" | Configured list (default Hindi, English, Tamil, Kannada), each person picks. English to Hindi and Hindi to English phone-tested 2026-11-06. No Tamil or Kannada run is recorded. Auto-detect not built | **Undecided** | [03](FULL-APP-03-language.md), facts from [02](FULL-APP-02-gnani-streaming-and-language-facts.md) |
| Continuous streaming, both legs | Not stated beyond the name | Not built. Today: tap Start, speak, tap Stop; one clip up to 30 s through REST (Prisma, translation, Timbre). Stop-to-audible latency never measured | **Undecided** | [04](FULL-APP-04-continuous-streaming.md) |
| Bilateral shield escalation | Not stated; "escalation" undefined | Courtesy cautions built both ways (threat, secret request, secret said aloud). Tested against a **fake model** only; live-model quality unmeasured; not running in the live app | **Undecided** | [09](FULL-APP-09-bilateral-shield-escalation.md) |
| Agreement and summary | Not mentioned by the user this time | Voice-only agreement built (fakes and stubs only). No sender: phones show "WhatsApp copy: not sent" | **Undecided** whether it stays in the slice | [10](FULL-APP-10-integrated-nov10-route.md) |
| Integration and cut line | Full app, or as close as possible | n/a | **Undecided** | [10](FULL-APP-10-integrated-nov10-route.md) |

### The fallback floor (a fact, not a decision)

The two-phone browser demo is built and partly verified: two real phones completed English to Hindi and Hindi to English turns on 2026-11-06. Everything else on it is fakes, stubs or headless-Chrome checks. It is the only thing that exists to fall back on, so any plan should say how it stays deployable. The handoff records no process or tunnel running on 2026-11-07, so no demo link is live.

### Shapes the Nov 10 slice might take (LEAN, for the route ticket to accept or reject)

These are options to react to, not a plan. None is approved.

- **S0, floor only:** rehearse and ship the browser demo as scoped on 2026-11-06.
- **S1, floor plus browser-side upgrades:** more languages, and/or streaming on the browser path, if the research says they are quick and measurable.
- **S2, floor plus a consented memory/profile layer** keyed by something other than a real phone number (a room token or a typed synthetic number), so the privacy design is shown without telephony.
- **S3, a telephony pilot** with two consenting students, only if [01](FULL-APP-01-telephony-vendor-feasibility.md) finds a path the human can arrange in time.

## D. Conflicts and claim hazards to settle, not paper over

1. **"Shield" and "escalation" vs the never-say list.** The old map retired "shield" because the app blocks nothing. The user's area name reintroduces it. Whether the app gains any blocking or escalating action, or only the word, is [ticket 09](FULL-APP-09-bilateral-shield-escalation.md)'s question.
2. **Caller ID is not identity.** Nothing in the repo verifies who is calling. A profile keyed to a number must not be described as a verified person. If a spoofed number can pull up someone's stored memory, the design leaks it: tickets [05](FULL-APP-05-per-phone-memory-privacy.md) and [06](FULL-APP-06-caller-id-profile-lookup.md).
3. **Storage vs the dependency freeze.** Any persistence, profile store or retrieval index breaks the "no database, no new dependency" decision. Ask-first rules apply: new dependency, schema, or anything touching credentials needs the user's approval and a `DECISIONS` entry.
4. **Streaming vs the turn-based features.** Cautions, offer reading, the agreement state machine and the playback report all assume a completed turn. [Ticket 04](FULL-APP-04-continuous-streaming.md) has to say what "a turn" becomes.
5. **Unmeasured quality.** The caution classifier, the offer reader and the agreement-reply check have never been run against the live model on real speech. No ticket may treat their behaviour as proven.
6. **Spoken content reaches several model calls.** This is documented for the browser app. Memory and RAG add more places it can go.
7. **Dated vendor facts.** The 2026-11-06 telephony findings may already be out of date; tickets 01 and 02 re-verify.

## E. What this register deliberately does not say

- It does not say any of the eight areas will be built by Nov 10.
- It does not say how any area works. The matrix's "Full target" column is the user's label only.
- It does not claim phone calls are the form factor. The user has not said.
- It does not state law, vendor terms or limits as current fact.
