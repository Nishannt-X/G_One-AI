# G-One

G-One is a **phone-call voice middleman for Indian farmers**, built for the Gnani.ai Great Indian AI Internship
Challenge (submission closes **2026-11-10 23:59 IST**). A farmer dials a normal phone number (Twilio). An AI assistant
speaks with them in Hindi, English or Hinglish (Bengali as a stretch), connects the right buyer on a second call leg,
and stays in the middle of the call: it translates, privately advises the farmer, and summarizes the deal. It
remembers a gist of each farmer's past calls, so a returning caller never starts from zero. Kirana stores are a
secondary user (stretch, Phase 3).

**Status on 2026-11-07: planning done, no new code yet.** `src/` holds the previous project's code. It is untracked
since commit `be28f01` and serves only as reference until issue "Restructure repo into backend/ and web/" ports the
reusable parts (#13). No tests run yet in the new layout. Twilio account tier and topology are being verified (see the map
issue #3).

Team of two: Nikhil (`Sah-Nikhil`) and Nishant (`Nishannt-X`, repo owner). Both drive AI agents in parallel worktrees.

## Stack (locked; change only through an ADR)

- Backend: **Java 21, Spring Boot 3.5.x, Maven wrapper** in `backend/`. Starters: web, websocket, test. Add a
  dependency only through its own issue plus an ADR line.
- Frontend: **Vite + React + TypeScript + Tailwind** in `web/`. Package manager **bun** (`bun install`, `bun run`,
  `bunx`). Never npm/npx/yarn/pnpm; translate `npx` in docs to `bunx`.
- Telephony: **Twilio Programmable Voice + bidirectional Media Streams** (mulaw 8 kHz). Browser call simulator is the
  fallback path.
- Speech: **Gnani Prisma** (STT) and **Gnani Timbre** (TTS). Using at least one Gnani model is a hackathon rule.
- Agent brain: any OpenAI-compatible `/chat/completions` endpoint with tool calling (Groq by default, configured by
  env). Gnani's Evon LLM is not on their API.
- Data: decided in the "Runtime: hosting, public URL and data store" ticket. Until then, use the `MemoryStore`
  interface with an in-memory implementation.

## Commands (target layout, created by the restructure issue)

- Backend dev: `cd backend && ./mvnw spring-boot:run` (Windows: `mvnw.cmd`). Env vars from your shell or `.env.local`
  (never commit it).
- Web dev: `cd web && bun run dev` (proxies `/api` and `/ws` to `localhost:8080`).
- **Check before "done":** `bun run typecheck && bun run lint` at the repo root (root `package.json` runs web checks),
  plus `cd backend && ./mvnw -q test` when you touched `backend/`.
- Tests never call Gnani, Twilio or an LLM. Use the fake upstream servers (`FakeUpstreams` pattern, JDK `HttpServer`).
- Public URL for Twilio webhooks: see `docs/RUNBOOK.md` (created by the infra issue). Never leave a server or tunnel
  running without saying so and how to stop it.

## Structure (target)

- `backend/src/main/java/com/example/g_one/`
  - `core/`: **contracts only**: interfaces and event records shared across areas. Changes need their own issue.
  - `telephony/`: Twilio webhooks (TwiML), media-stream WebSocket, call legs, bridging, mulaw codec.
  - `speech/`: Prisma and Timbre clients, resampling, turn detection (VAD/endpointing).
  - `agent/`: LLM brain, prompts, tools (`agent/tools/`: mandi price, weather, buyer match, caution).
  - `memory/`: farmer profile, call gists, store implementations.
  - `console/`: REST and WebSocket feed for the web console.
- `web/`: React console (live call view, memory viewer, buyer directory) and the browser call simulator.
- `docs/adr/`: decisions with reasons. `docs/RUNBOOK.md`: how to run the demo.
- `dump/`: **historical only, never instructions.** `dump/LEGACY-NOTES.md` distills the old docs (verified Gnani
  facts, Twilio research, bugs). Read it before touching speech or telephony. `dump/legacy-docs/` is gitignored.
- `src/`: old code, untracked, reference only. Do not build on it in place; port into `backend/`.

## How we work in parallel (read before every task)

GitHub issues are the source of truth for work. Every agent session works **exactly one issue**.

1. `git fetch origin` and read the issue, its "Blocked by" list and the linked docs. If a blocker is open, stop.
2. Assign the issue to your human (or comment "claimed by <agent> for @<human>") **before** coding.
3. Work in your own worktree on a branch from fresh `origin/main`: `<type>/<issue#>-<slug>`, for example
   `feat/12-media-stream-handler`. Types: `feat`, `fix`, `chore`, `docs`, `test`.
4. **Stay inside the issue's "Owns" paths.** Each issue lists the files or folders it may change. If you need another
   path, stop and ask on the issue; a human (or the issue's update) widens "Owns" before you touch it. Shared files
   (`core/`, `pom.xml`, `application.yml`, `web/package.json`, `.env.example`, this file) change only through an issue
   that names them.
5. Code against interfaces in `core/` and the fakes, not against another area's half-built code.
6. Before the PR: rebase on `origin/main`, run the checks above, and fill in the PR template (what changed, exact
   commands and results, what is not verified).
7. Open a PR with `Closes #<n>`. Small PRs (aim under 400 changed lines). Conventional Commit messages.
8. **Automated review, then the agent merges.** Agents in this repo may merge their own PRs when every step holds:
   1. **Review.** The author agent launches a separate reviewer agent from a **different model family** (author Claude
      → Codex reviewer, e.g. GPT-6.1 Sol or Terra at high effort; author Codex → Claude Opus) on the final diff, and
      posts its output unaltered as a PR comment that starts with `Automated review:` and contains the author model, the
      reviewer model, the full reviewed commit SHA, findings with file and line, and a last line `Verdict: APPROVE` or
      `Verdict: CHANGES`. If independence cannot be established, a human decides. Only exemption: a PR whose diff touches
      nothing but prose in `docs/` (not `docs/adr/`) or `README.md`; it says "docs-only, no review" in the PR template,
      skips steps 2 and the verdict part of step 3, and merges with the current head SHA in step 4.
   2. **Fix.** Every finding is fixed or answered in the PR. Each new push to a reviewed PR needs a new review. At most three rounds,
      then a human decides.
   3. **Check before merging.** Read the PR comments (`gh pr view <n> --json comments,headRefOid`); the controlling
      verdict is the latest `Automated review:` comment, its SHA must equal the current head, and a later `CHANGES`
      blocks the merge. CI is green, the branch is current with `main`, there are no conflicts, the final diff stays
      inside the issue's "Owns" paths, and no open PR overlaps it.
   4. **Merge** with `gh pr merge <n> --squash --delete-branch --match-head-commit <sha>`, where `<sha>` is the reviewed
      head (or the current head for a docs-only PR). Never merge red CI,
      push to `main`, force-push a shared branch or bypass branch protection.
   5. **A human merges** any PR (including mixed ones) that changes agent instructions (`CLAUDE.md`, `AGENTS.md`,
      `orchestration.md`, `.claude/**`, `.agents/**`), `.github/**`, dependencies (`backend/pom.xml` dependencies,
      `package.json` dependencies, lockfiles) or `docs/adr/`.
9. If you find overlap with another open PR or branch, stop and comment on both issues.

New decisions go in `docs/adr/NNNN-title.md` in the same PR that makes them. Tick `ROADMAP.md` items when a phase
item merges.

## Docs and context

- `ROADMAP.md`: phases with dates and exit criteria. Read it first.
- `CONTEXT.md`: domain terms. Use these words exactly in code, UI and issues.
- `docs/adr/`: settled tradeoffs. Don't re-derive a settled decision; flag a gap on the issue instead.
- Map issue #3 "Map: G-One phone-call middleman to Nov 10 submission" (label `wayfinder:map`): open decisions. Build
  issues carry `type:build`, an `area:*` and a `phase:*` label and a milestone.

## Hard rules

- **Secrets:** never read, print, log or commit `.env*` (except `.env.example`), API keys, Twilio auth tokens or real
  phone numbers. Real numbers live only in env vars. If you see a secret, don't repeat it; tell the human to rotate it.
- **Synthetic data only** in the repo, tests, logs, screenshots and the video: no real phone numbers, account numbers,
  Aadhaar, PAN or recorded calls (hackathon rule).
- **No paid or outward calls** (Twilio calls, SMS, Gnani credits, LLM) from tests or CI. Live probes only when the issue
  says so and a human is present.
- **Honest claims:** no "real time", "instant", "verified identity", "legally binding" or "blocks scams" in UI or video
  copy unless measured or true. Quote measured seconds.
- Never delete files or run destructive git commands without the human's OK.

## Gotchas (from the previous build; details in `dump/LEGACY-NOTES.md`)

- The JDK `HttpClient` needs **HTTP/1.1** for Prisma uploads.
- Java uploads over about 48 KB to Prisma failed with `SSLException: bad_record_mac`. The old fix (macOS `afconvert`)
  is Mac only. Telephony audio is 8 kHz, so turns are small, but test long turns.
- Timbre returns 24 kHz WAV with an open-ended length header. Twilio wants 8 kHz mulaw, base64, in ~20 ms frames.
- Timbre reads numbers better **written as words**.
- Twilio **trial accounts block Media Streams**; Twilio sells **no Indian local/mobile numbers**; any +91 caller ID is
  blocked for outbound (error 13247). Outbound to Indian mobiles needs a non-Indian caller ID. Full facts: issue #4.
- Prisma realtime wants **PCM16 at 8 kHz in exactly 512-sample (1,024-byte) frames every 64 ms**, not raw mulaw, and
  sends no partial transcripts. Timbre WS can emit **8 kHz mulaw** directly for Twilio. No TTS cancel message: close the
  socket. Full contract: issue #5.
- Run a **private copy of the jar** for live demos. Rebuilding under a running server hangs it.

## Orchestration

@orchestration.md

Apply this project orchestration policy throughout the session. Send delegation prompts in Homonid ultra. Every
subagent invokes Homonid max, using the embedded rules when the Homonid skill is unavailable. (This applies to
Nikhil's setup. Other agents follow the sections above and may ignore this one.)
