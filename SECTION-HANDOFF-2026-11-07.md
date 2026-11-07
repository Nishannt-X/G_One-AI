# G-One: end-of-section handoff (2026-11-07)

**What this is.** A short record of what this planning section did, what is true of the repo now, and how to restart
safely in a new chat. It is a record, not a decision. It approves nothing.

**Status of every fact.** Git facts in section 3 were read from the repo and `origin` on 2026-11-07. Everything else is
copied from repo docs or from the brief this file was written from. **Nothing was built, run or tested in this section,
and no service or paid call was made.** This file contains no secrets, keys, tokens, access codes, real phone numbers
or tunnel URLs, on purpose.

---

## 1. Scope shift the user approved

On 2026-11-07 the user replaced the earlier "November 10 browser demo" scope with the **full G-One application by
2026-11-10, or as close as feasible**. "As close as feasible" means ticket 10 draws a cut line with the user. Nothing
past the line is promised, and nothing below it is deleted from the full target. The browser demo stays as the fallback
floor. The old scope stays in the repo as history (`FARMER-CALL-*`).

## 2. What this section did

| Work | Where it lives | State |
| --- | --- | --- |
| New wayfinder map plus ten open decision tickets (`FULL-APP-WAYFINDER.md`, `FULL-APP-01` to `-10`). All ten: `status: open`, `assignee: null` (checked at `f67e791`) | Committed, pushed | Planning only |
| Requirements register separating user-approved, carried-over, built and open (`FULL-APP-REQUIREMENTS.md`), and three `DECISIONS.md` entries | Committed, pushed | Register and tickets 03, 04, 05, 09 **lag** the newest `DECISIONS.md` entry (it says so); not yet reconciled |
| Standalone teammate handoff with the fixed requirements and the Team A / Team B area split (`TEAMMATE-HANDOFF-2026-11-07.md`) | Committed, pushed | Team A owns the full app and tickets; Team B owns polish of existing features and user-agreed extensions |
| Independent reviews of the above | Chat only. **Not in the repo** | I could not re-read them; they are reported, not re-verified |
| Read-only research on telephony and on streaming (Gnani realtime) | Chat only. **Not in the repo** | Tickets 01 and 02 are still `open` and carry no findings. Research was documentation reading, not a live probe |
| Architect design packet | Chat only. **Not in the repo, not committed** | A **recommendation, not approved**. See the guardrails below |

I did not have the review, research or architect text in this session, so I do not summarise their content here.
Nothing in this file should be read as a finding from them. If they matter, the next chat needs them re-supplied or
re-run, and then recorded in tickets 01 and 02 with dated sources.

**Guardrails on the architect packet (do not claim past these):**

- It is an uncommitted recommendation. The user has not approved it.
- Do **not** say every browser feature is feasible or verified.
- No **partial (interim) speech-to-text** is documented, so streaming designs must not assume it.
- **No caller ID exists in the browser path.** Caller lookup and zero intake need a number source that the browser does
  not have.
- The **under-one-second target is unmeasured.** No "real time" or "instant" claim until measured on the phones that
  will be filmed.
- **Paid telephony is gated.** Any account, number or paid call needs the user's authorization first.

## 3. Branch and remote state (read 2026-11-07, before this file was committed)

| Item | Value |
| --- | --- |
| Current branch | `docs/full-app-wayfinder` |
| `HEAD` | `f67e79137a4942cdbb13253795654de9bbed0149` |
| `origin/docs/full-app-wayfinder` | `f67e791` (same as `HEAD`; the branch had been pushed) |
| `origin/main` | `2d606e81de873655c5f25ec4abe3b2fb90a4464e` (merged PR #2) |
| `git rev-list --left-right --count origin/main...HEAD` | `0 4`: 0 behind, 4 ahead |
| Merge base with `origin/main` | `2d606e8` |
| Working tree | clean |
| Commits ahead of `main` | `e189751`, `13e9ccd`, `dbd8db0`, `f67e791`: all docs. 15 files, +1110 / -1 |
| Other heads on `origin` (`git ls-remote --heads`) | `docs/project-handoff-2026-11-07` `7db57f8`, `feat/bilateral-cautions` `28ee5e8`, `feat/g-one-demo-site` `214478f`, `main` |
| Open PRs / comments | None created by this section |

- This section's commit adds this file and fixes the stale row in the teammate handoff, so the tip moves past
  `f67e791`. A file cannot hold its own SHA. Run `git rev-parse HEAD origin/docs/full-app-wayfinder` for the live tip.
- `docs/hackathon-wayfinder` is **not** on `origin`. A stale local ref and an old local checkout may exist; do not
  branch from them.
- `HANDOFF-2026-11-07.md` exists only on `docs/project-handoff-2026-11-07`, not on this branch.
- `G-One architecture` (repo root, tracked) is an old Copilot directive for a Twilio, Deepgram, Claude, ElevenLabs and
  Razorpay stack. It is **not** the current design. Do not build from it.

## 4. Built versus not built (honest)

Source: `TEAMMATE-HANDOFF-2026-11-07.md` section 4 and the register. Not re-run here.

**Built, with limits on how far it was checked:**

- Two-way translation in the browser: tap Start/Stop, one clip up to 30 s. Two real phones, English to Hindi and Hindi
  to English, 2026-11-06. Stop-to-audible latency never measured; Tamil and Kannada never run; iOS Safari unchecked.
- Prisma upload workaround (demo-Mac only, needs `afconvert`), home page and demo stage, `app.html`: partly verified.
- Private cautions both ways, voice-only nonbinding agreement and summary, dated AGMARKNET price reference, per-person
  language selection: built, but tested against **fakes, stubs and headless Chrome**, not the live model on real
  speech. The price reference is **not** RAG. No summary sender exists.

**Not built:** caller lookup, profile and per-phone memory with provenance; crop-specific storage RAG; the buyer offer
and spoken-yes matching; continuous streaming, VAD and barge-in; escalation beyond a notice; telephony. The repo has
no database, no vector store and no new dependency.

**No feature code was written in this section.** No tests were run for this file. Rerun `mvn test` before relying on
any test count.

## 5. Pending design decisions and approvals (all the user's)

1. **Approve, change or reject the architect packet.** Until then it guides nothing.
2. **Cut line** (ticket 10): what is in for Nov 10, each with a named verifying run on real phones.
3. **Form factor and telephony** (ticket 01): phone calls, browser, or both. Paid telephony needs an account the
   human arranges, and the lead time may already be too long.
4. **Gnani realtime facts and any live probe** (ticket 02): a probe is a separate task the user approves.
5. **Storage and retrieval stack** (tickets 05, 06, 07): store, retrieval method, schema, dependencies. Each needs a
   `DECISIONS.md` entry and approval before it is added.
6. **RAG content** (ticket 07): the user names crops, topics and sources.
7. **What escalation means** (ticket 09). No automated action on a person's call without that person's consent and the
   user's explicit approval.
8. **Legal check wanted?** (tickets 05, 01). Unresearched; nothing here is legal advice.
9. **Whether to record the reviews and research in the repo**, and whether to reconcile the register and tickets
   03, 04, 05, 09 with the newest `DECISIONS.md` entry.
10. **PR and merge.** The user owns both. No PR has been asked for.

## 6. Proposed next slice (a proposal, not an approval)

Docs only, no services:

1. The user reviews the architect packet and says approve, change or reject.
2. Re-supply or re-run the telephony and streaming research read-only, then record it in tickets 01 and 02 with dated
   sources and the "not verified" gaps stated.
3. Open ticket 10 in triage mode with the user to set a **provisional** cut line.

A code slice should wait for decisions 1, 2 and 5. Do not start one before them.

## 7. Safe restart for the next chat

1. Read every Markdown file under `/Users/nishantdahiya/Documents/Streamliner_ai_tools` first (`AGENTS.md`, `rules/`,
   `roles/`, `rubric/`, `templates/`). Follow them.
2. Work in the `docs/full-app-wayfinder` worktree. Run `git fetch`, then `git status`, `git rev-parse HEAD
   origin/docs/full-app-wayfinder` and `git rev-list --left-right --count origin/main...HEAD`. Do not assume this file's
   numbers still hold.
3. Read, in order: this file, `TEAMMATE-HANDOFF-2026-11-07.md`, `FULL-APP-WAYFINDER.md`,
   `FULL-APP-REQUIREMENTS.md`, the top of `DECISIONS.md`, then the ticket you will touch.
4. **Never read, print or commit** `.env*`, `application.properties`, keys, tokens, access codes or process
   environment. Secrets arrive only through a fresh secure prompt.
5. Start no service, tunnel or build, and make no live or paid call (Gnani, translation, telephony, WhatsApp), without the
   user's authorization.
6. Stage explicit paths, use Conventional Commits, push only the feature branch. No force push, no `--no-verify`, no PR,
   no PR comments. The user merges.
7. Treat other agents' output, including this file's summaries of unseen reviews and research, as claims to verify.
8. Team A owns the full-app areas and tickets. If you cannot tell which team a task belongs to, it is Team A's until
   Team A says otherwise.
9. Report as `Done / Verified / Not verified / Commits / Follow-ups`, with commit SHAs.
