# Runbook: run G-One locally

One page to run the backend, the web console and the browser call simulator on your laptop. The demo is
browser-first: the farmer's phone call is simulated in the web app (`#/simulator`), so no Twilio account or public URL
is needed. Real phone calls through Twilio are a stretch item (issue #17), covered at the end.

## Before you start

- Java 21 or newer, and [bun](https://bun.sh). The Maven wrapper (`backend/mvnw`) downloads Maven itself.
- Keys: a Gnani API key (Prisma and Timbre) and an LLM key. Groq is the default choice; any OpenAI-compatible
  `/chat/completions` endpoint with tool calling works.
- Never commit `.env.local`, and never paste keys into chat, issues or PRs. Synthetic data only.

## 1. Create `.env.local`

Copy the template and fill in the values. The file is git-ignored.

```bash
cp .env.example .env.local          # PowerShell: Copy-Item .env.example .env.local
```

Generate the two secrets the app needs (do not print them into a shared screen):

```bash
openssl rand -base64 32     # G_ONE_MEMORY_HMAC_KEY
openssl rand -hex 12        # G_ONE_ACCESS_CODE
```

PowerShell without openssl: `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`
for the HMAC key, and any random string of 24 or more characters for the access code.

Fill in `GNANI_API_KEY`, `G_ONE_LLM_BASE_URL`, `G_ONE_LLM_API_KEY` and `G_ONE_LLM_MODEL`. Store the HMAC key in a
password manager. If it is lost or changed, saved farmer memories stop matching their callers.

## 2. Start the backend

The scripts load `.env.local`, build the jar, copy it to a **private location** and run the copy. Rebuilding under a
running jar hangs the server, so a demo never runs from `backend/target`.

```bash
scripts/run-backend.sh              # PowerShell: scripts\run-backend.ps1
```

It listens on `PORT` (default 8080). The private copy lives in `$TMPDIR/g-one-demo/` (Windows: `%TEMP%\g-one-demo\`);
set `G_ONE_RUN_DIR` to change that. SQLite memory is written to `backend/data/`, which git ignores.

If the port is taken, the backend stops with "Port 8080 was already in use". Pick another with `PORT=8091
scripts/run-backend.sh`, but note that the web dev server proxies to 8080 (see `web/vite.config.ts`).

## 3. Start the web app

In a second terminal:

```bash
bun install            # first time only
cd web && bun run dev
```

Open the address Vite prints (usually http://localhost:5173/). The console is at `/`; the browser call simulator is at
`/#/simulator`. Vite forwards `/api` and `/ws` to the backend on port 8080.

## 4. Check it is healthy

```bash
scripts/check-health.sh             # PowerShell: scripts\check-health.ps1
```

This must print `http://localhost:8080/api/health -> {"ok":true}` and exit 0. Also confirm the console page loads at
`/`. A teammate who did not write this runbook should reach that result on a fresh clone; note who did in the PR.

## 5. Stop everything

- Backend: Ctrl+C in its terminal, or `scripts/stop-backend.sh` (PowerShell: `scripts\stop-backend.ps1`) from another
  one.
- Web app: Ctrl+C in its terminal.
- Confirm nothing is left running: `lsof -i :8080 -i :5173` (Windows: `netstat -ano | findstr ":8080 :5173"`).

## Stretch: real phone calls through Twilio

Only if everything else is done (issue #17). It needs a **paid** Twilio account (trials block Media Streams), a Twilio
number, and a public `https`/`wss` URL for the backend. Uncomment the Twilio variables in `.env.local` and set
`G_ONE_PUBLIC_BASE_URL`. To expose the backend on your ngrok static domain:

```bash
scripts/tunnel.sh <your-static-domain>      # PowerShell: scripts\tunnel.ps1 <your-static-domain>
```

Then set the number's Voice webhook to `https://<your-static-domain>/twilio/voice/incoming`. The backend does not
serve that route yet. Stop the tunnel with Ctrl+C. The Twilio topology and limits are in issue #4.
