# AGENTS.md

All project instructions live in [CLAUDE.md](CLAUDE.md). Read it fully before any task; every rule there applies to
Codex, Cursor and any other agent. The "Orchestration" section at its end is specific to one teammate's Claude setup
and can be ignored.

Short version:

- One GitHub issue per session. Claim it first. Branch `<type>/<issue#>-<slug>` from fresh `origin/main`, in your own
  worktree.
- Change only the paths the issue lists under "Owns". Contracts in `backend/.../core/` change only through their own
  issue.
- Checks before a PR: `bun run typecheck && bun run lint` at the root, and `cd backend && ./mvnw -q test` if you touched
  the backend.
- PR with `Closes #<n>` and the template filled in. Humans merge. Never push to `main`.
- No secrets, no real phone numbers, no paid calls from tests. Synthetic data only.
- Domain words: [CONTEXT.md](CONTEXT.md). Plan and dates: [ROADMAP.md](ROADMAP.md). Decisions: [docs/adr/](docs/adr/).
