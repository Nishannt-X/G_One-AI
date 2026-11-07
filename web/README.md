# G-One web

The G-One console (and, later, the call simulator). Vite + React + TypeScript + Tailwind v4, managed with bun.

```sh
bun install          # once, at the repo root (bun workspace)
cd web && bun run dev   # http://localhost:5173, proxies /api and /ws to the backend on :8080
bun run build        # type-check and build to web/dist
```

From the repo root, `bun run typecheck && bun run lint` runs this package's checks.
