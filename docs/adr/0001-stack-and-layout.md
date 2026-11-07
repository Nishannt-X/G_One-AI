# 0001: Stack and repo layout

- **Status:** accepted, 2026-11-07
- **Decided by:** Nikhil (stack choice), agent (layout)

## Context

Three days remain before the 2026-11-10 deadline. The previous project left a Java 21 / Spring Boot backend whose Gnani
Prisma and Timbre clients ran on real phones, plus fake-upstream test servers. Two people and several AI agents will
work at once, so areas must live in separate folders.

## Decision

- Backend stays **Java 21 + Spring Boot 3.5.x (Maven wrapper)** in `backend/`. Reusable code from the old `src/` is
  ported (speech clients, HTTP/1.1 helper, WAV helper, AGMARKNET source, caution wording, voice-confirmation state
  machine, fake upstreams). Room/turn code for the browser bridge is not ported.
- Frontend is **Vite + React + TypeScript + Tailwind** in `web/`, managed with **bun**. Spring serves the built files in
  production; Vite proxies `/api` and `/ws` in development.
- A root `package.json` exposes `typecheck`, `lint` and `test` so the team's standard check
  (`bun run typecheck && bun run lint`) works from the root.
- Backend packages map 1:1 to areas: `core` (contracts), `telephony`, `speech`, `agent`, `memory`, `console`. Each build
  issue owns one area's folder.
- The agent's LLM is any OpenAI-compatible chat endpoint with tool calling, chosen by env vars (Groq by default for
  speed). Gnani's Evon is not available on their API.

## Consequences

- Rewriting the Gnani clients is avoided, and the HTTP/1.1 and WAV lessons carry over.
- Two toolchains (Maven and bun). CI runs both.
- Changes to `core/` are the main collision risk, so they go through dedicated issues only.
