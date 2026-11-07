---
title: Integrated Nov 10 route
parent: FULL-APP-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: []
informed_by: [FULL-APP-01-telephony-vendor-feasibility.md, FULL-APP-02-gnani-streaming-and-language-facts.md, FULL-APP-03-language.md, FULL-APP-04-continuous-streaming.md, FULL-APP-05-per-phone-memory-privacy.md, FULL-APP-06-caller-id-profile-lookup.md, FULL-APP-07-advisory-rag.md, FULL-APP-08-consent-to-match.md, FULL-APP-09-bilateral-shield-escalation.md]
closes_after: [FULL-APP-01-telephony-vendor-feasibility.md, FULL-APP-02-gnani-streaming-and-language-facts.md, FULL-APP-03-language.md, FULL-APP-04-continuous-streaming.md, FULL-APP-05-per-phone-memory-privacy.md, FULL-APP-06-caller-id-profile-lookup.md, FULL-APP-07-advisory-rag.md, FULL-APP-08-consent-to-match.md, FULL-APP-09-bilateral-shield-escalation.md]
---

## Question

Given what tickets 01 to 09 decide (or, in triage mode, what is known so far), and a deadline of 2026-11-10, what is the single ordered route: which capabilities ship (the cut line), in what build order, behind what checkpoints, what fallback stays deployable, what acceptance runs prove each shipped capability, what the public video and submission may say, and what is explicitly after Nov 10?

## Type and mode

Grilling (HITL). The cut line is the user's decision. The agent presents the dependency picture, the evidence status of each capability and a recommendation; the user chooses.

## Early triage mode (recommended given the calendar)

Today is 2026-11-07; the old plan froze features after Nov 8. Nothing blocks this ticket: it can be opened **at any time**, with whatever the other tickets have decided so far, to set a **provisional** cut line and a provisional build order. The other tickets keep running in parallel with a human in the loop. Triage decides what is *promised for Nov 10*. It does not remove a capability from the full target and it does not declare any ticket a blocker: the user-approved requirements ([register items 5 to 7](FULL-APP-REQUIREMENTS.md#a-approved-by-the-user-for-this-effort-user-now)) stay in the full target, and a capability that falls below the cut line is "after Nov 10, nothing promised", with its decision ticket still open. A provisional cut is revised as the other tickets resolve. This ticket is **closed** only when all tickets in `closes_after` are closed and the route is final.

## Known

- Built and partly verified: the two-phone browser demo (the floor). Other pieces exist as fakes-only or unverified code. See the [register](FULL-APP-REQUIREMENTS.md#c-capability-matrix-full-target-today-nov-10-slice).
- Previous plan and truth table: [FARMER-CALL-07](../FARMER-CALL-07-nov10-architecture-scope.md). Previous route steps there were: restart the live app with fresh secrets; agreement against fakes; Sandbox sender; freeze after Nov 8; Nov 9 three scripted runs on two phones from a private jar copy. Those steps belong to the superseded scope and are history; this ticket decides what is reused.
- Operating rules from the handoff (`HANDOFF-2026-11-07.md`, on branch `docs/project-handoff-2026-11-07`): secrets only through a fresh secure prompt; run a private copy of the jar; stop before restart because rooms vanish; no demo process or tunnel was running on 2026-11-07; the user owns every PR and merge.
- Test suite: [DEMO](../DEMO.md#run-locally) states 162 tests; the handoff did not rerun it and did not reconcile the count.

## Sub-questions

1. **Cut line.** For every capability in the register, in or out for Nov 10, with its target evidence status: *built*, *tested against fakes*, *verified on real phones*, or *after Nov 10*. Reject "in" for anything that cannot be verified by a named run.
2. **The floor.** How does the browser demo stay deployable at every checkpoint? Candidate mechanisms to weigh: a separate branch, configuration switches, additive endpoints. Which checkpoint triggers the fallback decision?
3. **Order and slices.** Dependency order of the in-line capabilities, each slice PR-sized with a verify check. These are hand-offs for the Builder, not built here.
4. **Human tasks and dates.** Accounts, credentials via secure prompt, phones, joining demo numbers to a sandbox, restarts. Each with an owner and a date, flagged as ask-first/paid/outward-facing.
5. **Parallelism.** What can run in parallel branches on disjoint files under the orchestrator-only rule, and what must be serial?
6. **Acceptance.** For each shipped capability: the scripted run, how many consecutive passes, on which phones and browsers, and what a pass looks like. Include iOS Safari if it will be filmed.
7. **Evidence labels.** The wording the video may use for each capability given its evidence status. This ticket **owns the final say/never-say table** for the full app. Rows may change only for capabilities that reached the stated evidence status, using sentences the user has approved. The existing table stands until then.
8. **New dependencies and decisions.** Every storage, retrieval or telephony component implies a `DECISIONS` entry and user approval first. List them in order.
9. **Risks and kill switches.** Top risks, a dated checkpoint for each, and the action when it trips. Include credit exhaustion, an account that is not ready in time, undocumented-endpoint drift and iOS audio behaviour.
10. **After Nov 10.** Name what is deferred, and say it will not be claimed.
11. **Carried human choices.** WhatsApp number source and the stale-price fallback: decide, or mark them out of the slice.

## Constraints

- Planning only. The route lists work; it does not start it.
- Nothing is promised that is not scheduled with a verifying run.
- The user owns PRs and merges. No PR comments from agents.

## Done when

A cut-line table (capability, full target, Nov 10 status, evidence status, run that proves it), the ordered route with checkpoints and the fallback trigger, the human task list with dates, the acceptance scripts, the risk table with kill switches, the final say/never-say table, and the list of `DECISIONS` entries required. After this, the map is complete and building can begin.
