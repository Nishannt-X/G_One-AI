---
title: Plan the full G-One application for November 10
labels: [wayfinder:map]
status: open
tracker: local-markdown
created: 2026-11-07
supersedes: FARMER-CALL-WAYFINDER.md (for future scope only; history stands)
---

## Destination

A decision-complete specification of the **full G-One application**, plus one integrated route that ships as much of it as can honestly be built and verified by **2026-11-10**. The user wants the full application by that date "or as close as possible". "Close as possible" means the route ticket draws a cut line; nothing past the line is promised. Reaching the destination means no open design question remains and every capability has a named status (full target, Nov 10 slice, or after Nov 10). **This map plans. It builds nothing.**

## Notes

- **Why a new map.** The user superseded the Nov 10 browser-demo scope on 2026-11-07. The [browser-demo map](FARMER-CALL-WAYFINDER.md) is marked superseded for future scope and otherwise untouched: its decisions are history, not errors. The browser demo stays as the **fallback floor** (built, partly verified; see [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md) and [DEMO](DEMO.md)).
- **Read the register first.** [FULL-APP-REQUIREMENTS](FULL-APP-REQUIREMENTS.md) separates what the user has approved from what is carried over from the earlier effort, what is built, and what is unresolved. It also keeps the full-target vs Nov 10 slice table. **Every Nov 10 slice in it is undecided until the route ticket resolves.**
- **Planning only.** No feature code, no services, no external API calls, no accounts, no credentials. Do not read `.env*`, `application.properties`, keys, tokens or access codes. Secrets only ever arrive through a fresh secure prompt, never in chat. This charting session made no external call.
- **Research tickets are created but not fired.** The skill says to start research subagents at charting time. This session was docs-only and the user did not ask for subagents, so [Telephony path and vendor feasibility](FULL-APP-01-telephony-vendor-feasibility.md) and [Gnani realtime streaming and language facts](FULL-APP-02-gnani-streaming-and-language-facts.md) are open and unclaimed.
- **Claims discipline.** Until a ticket here replaces specific rows, the **Say / never say** table in [FARMER-CALL-07](FARMER-CALL-07-nov10-architecture-scope.md) governs every public word. It bans "shield", "intercepts", "blocks", "verified identity", "real time" and "phone call" for the browser build. [Bilateral shield escalation](FULL-APP-09-bilateral-shield-escalation.md) and [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md) are where those rows may change, and only with measured evidence.
- **Synthetic data.** The [Gnani challenge rules](https://www.gnani.ai/internship) require a working Gnani app and a public video. Use synthetic names, numbers, prices and recordings in the repo and the demo. The register's privacy hazards (spoofed caller ID reading someone's memory, remembered text fed to prompts) are reasons to decide these tickets before any storage exists.
- **Vendor and legal facts are dated claims, not settled facts.** Anything about Twilio, Vonage, Exotel, Plivo, Gnani limits or law that appears in this map family comes from earlier repo docs written 2026-11-06 or from the ticket author's reading. The two research tickets must re-verify with dated sources. Law is unresearched and nothing here is legal advice.
- **Inherited open tickets, not edited.** [FARMER-CALL-04](FARMER-CALL-04-speech-contract.md), [FARMER-CALL-05](FARMER-CALL-05-account-probe.md) and the provisional [FARMER-CALL-06](FARMER-CALL-06-failure-behavior.md) were written for the old map. Their questions are folded into the new tickets that cite them. They stay as written.
- **Carried-forward human choices still open:** where WhatsApp numbers come from, and a stale-price fallback ([old map fog](FARMER-CALL-WAYFINDER.md)). The route ticket decides whether they matter for the slice.
- **Schedule reality.** Today is 2026-11-07, three days from the deadline. The handoff suggested a feature freeze after Nov 8 under the old scope. Ten decision tickets cannot all be resolved one after another and then built in the time left. The frontier is the two research tickets. Recommended order for the human: run both research tickets now, and consider opening the route ticket early in triage mode (see its text) so the cut line is set before the lower-value decisions are spent on.
- **Roles and git** follow `Streamliner_ai_tools` (`AGENTS.md`, `roles/ROSTER.md`). This is a docs-only branch. The user owns every PR and merge. No PR comments.
- The 2026-11-07 handoff (`HANDOFF-2026-11-07.md`) lives on branch `docs/project-handoff-2026-11-07`, not yet merged into `main`, so it is cited by name rather than linked.

## Decisions so far

<!-- Index of closed tickets. Empty at charting: no ticket is resolved. -->

_None. Every ticket below is open._

Direction, not a ticket: the user's scope change of 2026-11-07 is recorded in [DECISIONS](DECISIONS.md) and the register.

## Frontier and blocking (index of open tickets)

Unblocked and unclaimed (the frontier):

- [Telephony path and vendor feasibility](FULL-APP-01-telephony-vendor-feasibility.md) (research)
- [Gnani realtime streaming and language facts](FULL-APP-02-gnani-streaming-and-language-facts.md) (research)

Blocked:

- [Language scope and selection](FULL-APP-03-language.md) (grilling), waits on 02
- [Continuous streaming on both legs](FULL-APP-04-continuous-streaming.md) (grilling), waits on 01 and 02
- [Per-phone memory and privacy](FULL-APP-05-per-phone-memory-privacy.md) (grilling), waits on 01
- [Caller-ID profile and lookup](FULL-APP-06-caller-id-profile-lookup.md) (grilling), waits on 01 and 05
- [Advisory RAG](FULL-APP-07-advisory-rag.md) (grilling), waits on 03 and 05
- [Consent to match](FULL-APP-08-consent-to-match.md) (grilling), waits on 05 and 06
- [Bilateral shield escalation](FULL-APP-09-bilateral-shield-escalation.md) (grilling), waits on 04 and 05
- [Integrated Nov 10 route](FULL-APP-10-integrated-nov10-route.md) (grilling), waits on all of 01 to 09

Each ticket's `blocked_by` front matter is the source of truth; this list is a convenience copy.

## Not yet specified

- **Build slices and acceptance runs.** They exist only once the route ticket draws the cut line. They are build work, outside this map; the route ticket may emit them as hand-off items.
- **A measured evaluation set for cautions and any escalation** (live model, Hindi, Tamil, Kannada, evasion). The suspected question: what sample size and pass bar earns any claim stronger than "courtesy"? It may graduate from the shield ticket.
- **Account tasks for the human** (a paid telephony account, Gnani credit and concurrency check, WhatsApp Sandbox). They become Task tickets only if the research tickets say a path needs them.
- **Legal and regulatory check** (recording consent, data protection, calling rules). Unresearched. It may graduate from the privacy or telephony tickets.
- **Public video framing and wording for the full app**, after the cut line exists.
- **Load and concurrency limits.** Rooms live in memory on one instance today. Whether the full app needs more is not yet sharp.

## Out of scope

- **Building any of it** (code, services, accounts, tunnels). Planning only; build follows once the route is clear.
- **Legally binding contracts, "smart contracts" and payment flows.** Carried from the earlier map. The user's 2026-11-07 instruction does not mention them, so they stay out unless the user redraws the destination. The agreement stays nonbinding.
- **Post-Nov-10 scale-out, multi-instance hosting and business-grade carrier onboarding beyond what the Nov 10 slice needs.** The route ticket may name them as "after Nov 10" and nothing more.
