# 0003: Parallel work through issues, worktrees and owned paths

- **Status:** accepted, 2026-11-07
- **Decided by:** Nikhil

## Context

Two people and their AI agents work at the same time. Merges must not break each other's work.

## Decision

- **One issue = one branch = one worktree = one PR.** Branch name `<type>/<issue#>-<slug>` from fresh `origin/main`.
- Every build issue lists the paths it **owns**. Agents change only those. Shared files (`core/`, `pom.xml`,
  `application.yml`, `web/package.json`, `.env.example`, `CLAUDE.md`) change only through issues that name them.
- **Contracts first:** Phase 0 merges interfaces and fakes in `core/`, then Phase 1 areas build in parallel against
  them.
- Issue dependencies use GitHub's native "blocked by" links, so the frontier is visible on the board.
- Phases are milestones with due dates. Labels: `type:build`, `area:*`, `phase:*`; decision tickets use `wayfinder:*`.
- PRs need green CI and a filled template. **Agents may squash-merge their own PRs** after an automated review by an
  independent agent from a different model family approves the exact head commit (amended 2026-11-07 by Nikhil). The
  procedure, the docs-only exemption and what still needs a human (agent instructions, `.github/**`, dependencies,
  ADRs) are in CLAUDE.md, "How we work in parallel", step 8. Agents never push to `main`.
- GitHub Discussions are off (only the repo owner can enable them), so feature ideas start as issues labelled
  `idea`.

## Consequences

Reviews cost a second agent run per PR but replace waiting for a human. Some idle time while Phase 0 lands, in exchange for few merge conflicts later. A contract change mid-phase costs a
dedicated issue and a rebase for everyone.
