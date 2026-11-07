# ROADMAP

Destination: a **working phone-call middleman shown in a public video**, posted on LinkedIn and X, and submitted
through Gnani's form before **2026-11-10 23:59 IST**. All times below are IST.

Work is tracked as GitHub issues. Each phase is a milestone, and each line below is an issue. Tick a line when its PR
merges. Open decisions live on the map issue "Map: G-One phone-call middleman to Nov 10 submission" (#3).

## What wins here (read once)

- **Internship shortlist = verified engagement** (reactions, comments, reshares) on our nominated LinkedIn and X posts,
  counted at the closing cutoff, then a skills interview. So the **post goes live by Nov 9, 22:00**, not on Nov 10.
  Every extra hour live is extra engagement. Bought engagement, bots and engagement swaps are disqualifying.
- **Awards** (₹50k each, a team can win only one, judged on category criteria):
  - **Real Deal** (most useful real-world problem, farmers or kirana): our primary fit.
  - **Noise Canceller** (messy telephonic audio): only reachable if the real Twilio phone path works. Strong secondary.
  - **Two-Person Army** (best team of two): we are eligible automatically. Build quality and the split of work decide it.
  - **Demo Day Drop** (best 60-second video): cut a 60 s version of the video regardless.
  - **Mixed Vibes** (Hinglish/Benglish): reachable if code-mixed speech works well on calls.
  - **Crowd Favourite**: public votes, same engagement work as the shortlist.
  - Not ours: Main Character (regional/low-resource language), Solo Ninja, Small Town (depends on college location).
- Hard rules: working app on at least one Gnani model, public video, synthetic data only.

## Lanes (who works on what)

Each person owns one lane and drives agents on its issues, in the order listed. An issue is takeable when its "Blocked
by" issues are closed. Assignees on GitHub follow the lane owner. `area:*` labels map to lanes. Board with a Lane column:
[G-One to Nov 10](https://github.com/users/Sah-Nikhil/projects/2).

| Lane | Areas | Build issues, in order | Decisions it drives |
| --- | --- | --- | --- |
| **Call path** (unassigned; starts after the provider pick in #6, except #16 and #18) | `telephony`, `speech`, `infra` | #16 runtime, #17 Twilio inbound + stream, #18 speech adapter, #25 buyer leg, #33 Bengali | #34 telephony alternatives, #6 go/no-go, #7 Gnani probe, #10 runtime |
| **Brain and memory** (Nikhil, `Sah-Nikhil`) | `core`, `agent`, `memory` | #14 contracts, #23 call pipeline, #19 agent brain, #20 memory, #24 buyers, #26 mediation, #27 deal summary | #8 call flow, #9 memory |
| **Console and launch** (Nishant, `Nishannt-X`) | `web`, `launch` | #15 CI, #21 console v1, #22 simulator, #28 console v2, #29 rehearse, #30 video, #31 posts, #32 submission | #11 demo and post plan, #12 cut line |

#13 (restructure) comes before all lanes. Owners decided 2026-11-07: Nikhil drives Brain and memory, Nishant drives
Console and launch, each with agents in worktrees off `main`. Call path is picked up once #6 names the telephony
provider; #16 (runtime) and #18 (Gnani speech adapter) do not depend on the provider and can start earlier by whoever
has free agents. Both share the video and posts.

## Phase 0: Foundation (milestone P0, due Nov 8, 13:00)

Exit: `backend/` and `web/` build, CI is green on a PR, contracts are merged, decision tickets that gate Phase 1 are
closed.

- [x] #13 Restructure repo into backend/ and web/
- [ ] #14 Define core contracts and fakes
- [ ] #15 CI: backend tests and web checks on every PR
- [ ] #16 Runtime: public URL, env template and runbook
- Decisions (map #3): #4 Twilio topology research, #5 Gnani telephony research, #6 Twilio account go/no-go,
  #10 runtime and data store, #9 farmer memory. Also open now: #8 call flow, #11 demo and post plan, #12 cut line.

## Phase 1: Single-leg call loop (milestone P1, due Nov 9, 09:00)

Exit: a farmer dials the number from a real phone, speaks Hindi or Hinglish, and hears useful spoken answers (mandi
price, weather) with turn detection and barge-in. Calling again, the assistant greets them with what it remembers. The
console shows the call live. The call simulator runs the same pipeline in a browser.

- [ ] #17 Twilio inbound call and media stream handler
- [ ] #18 Telephony speech adapter: mulaw, Prisma, Timbre, turn detection
- [ ] #19 Agent brain v1 with mandi price and weather tools
- [ ] #20 Farmer memory v1: profile, call gist, load at call start
- [ ] #21 Console v1: live call view
- [ ] #22 Call simulator (fallback floor)
- [ ] #23 Call pipeline: wire legs, speech and brain with barge-in

## Phase 2: Middleman (milestone P2, due Nov 9, 18:00)

Exit: in one scripted call the farmer asks to sell, says yes to connecting, the assistant dials a buyer leg, both talk
through the assistant in different languages, the farmer gets a private price whisper and a scam caution, and the call
ends with a voice-confirmed deal summary saved to memory.

- [ ] #24 Buyer directory and find-buyers tool
- [ ] #25 Outbound buyer leg and bridge
- [ ] #26 Mediation: per-turn translation and whispers
- [ ] #27 Deal summary with spoken confirmation
- [ ] #28 Console v2: two-leg view and deal card

**Feature freeze Nov 9, 18:00.** After this, only fixes.

## Phase 3: Launch (milestone P3, due Nov 10, 20:00)

- [ ] #29 Rehearse the demo script three times and record measured latency (Nov 9, 18:00 to 19:30)
- [ ] #30 Record and edit the video: 60 s cut plus a 2 to 3 min version (Nov 9, 19:30 to 21:30)
- [ ] #31 LinkedIn and X posts live, tagging Gnani AI with #GreatIndianAIInternshipChallenge #GnaniAI (by Nov 9, 22:00)
- [ ] #32 README, contribution statement, submission form (by Nov 10, 20:00; deadline 23:59)
- Stretch, only after the post is live: #33 Bengali/Benglish, latency tuning, kirana mode.

## Checkpoints

| When (IST) | Check | If it fails |
| --- | --- | --- |
| Nov 8, 10:00 | Twilio account go/no-go done | Switch the demo to the call simulator plus a single real leg if possible (map ticket "Cut line") |
| Nov 8, 22:00 | One real phone turn: speak, hear an answer | Fall back to Gnani REST with our own VAD; drop barge-in |
| Nov 9, 09:00 | Phase 1 exit met | Cut buyer leg to a scripted second phone on the simulator |
| Nov 9, 18:00 | Phase 2 exit met | Film what works; say the limits honestly |
| Nov 9, 22:00 | Posts live | Non-negotiable |
