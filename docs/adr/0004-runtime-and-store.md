# 0004: Runtime, public URL and data store

- **Status:** accepted, 2026-11-08 (#10)
- **Decided by:** Nikhil (store: SQLite; other drafted defaults accepted), agent (draft)

## Context

Two students have two days (Nov 8 to Nov 10) to get a working call. Twilio, or Tone or KooKoo if #6 picks them, must
reach our backend over public `https` webhooks and a `wss` media stream on port 443.

- Twilio waits about 15 s for a webhook.
- Stream URLs cannot carry query strings, and `X-Twilio-Signature` is checked against the exact public URL.
- Memory is tiny: a few farmers, a few gists each (see #9).
- A dependency change in `backend/pom.xml` needs a human merge (CLAUDE.md step 8.5).

Both teammates test calls in parallel, and one machine runs the recorded demo.

## Options: hosting and public URL

| | Laptop + ngrok static domain | Laptop + Cloudflare Tunnel | Cloud host (Render / Railway / Fly) |
|---|---|---|---|
| Stable URL | Yes: the free plan includes one static domain per account (check the current free-plan limits in the dashboard) | Only a **named** tunnel, which needs a domain on Cloudflare DNS. A quick tunnel gets a new random URL every run | Yes |
| Setup time | About 10 min | About 30 to 60 min with a domain; quick tunnels are fast but the URL changes | 1 to 3 h: Dockerfile, build, secrets, volume |
| WebSocket / Twilio | Works; Twilio documents ngrok | Works | Works |
| Failure modes | Laptop sleep or home network. The free-plan data cap: roughly 0.15 to 0.3 GB per hour of two-leg calling, which is fine for rehearsals and the video | Same laptop risks; extra DNS setup | Free tiers sleep (a cold start misses Twilio's 15 s timeout); disk is ephemeral without a volume; redeploys interrupt calls |
| Debugging | Local logs, IDE, fast restart | Same | Remote logs only; slow loop |
| Cost | Free | Free (domain extra) | Free tier is risky; paid about $5 to $7 |

**Decision: laptop + ngrok static domain, one free ngrok account per teammate.**

- **Why:** it is the shortest setup with a URL that never changes, and it keeps a fast local debug loop.
- **Fallback:** a cloud host only if the demo laptop's network fails the Nov 8, 22:00 checkpoint.

## Options: data store

| | SQLite (`org.xerial:sqlite-jdbc`) | H2 file | JSON files | Hosted Postgres |
|---|---|---|---|---|
| New dependencies | 1, or 2 with `spring-boot-starter-jdbc` (which also triggers DataSource auto-config) | 1 | **0** (Jackson ships with `starter-web`) | 2, plus network and credentials |
| Human merge for the dependency | Yes | Yes | **No** | Yes |
| Fits the access pattern (get or put by `farmerKey`) | Yes | Yes | Yes | Overkill |
| Seed and inspect | SQL inserts or a loader | Same | **The seed is the store format.** Readable on camera and in the console | Remote |
| Risks | Native library per OS (bundled for Windows, macOS and Linux) | Version and file-lock quirks | Concurrent writes. Mitigate with a per-key lock plus write-temp-then-atomic-move | Latency at call start; outage |

**Decision: SQLite behind `MemoryStore`** (chosen by Nikhil over the drafted JSON files). One database file,
`${G_ONE_DATA_DIR}/g-one.db`, holding farmers, profile facts with provenance, call gists and deals as in
`docs/memory.md`.

- **Why:** a real database is easier to explain and inspect, and it handles concurrent writes from two call legs.
- **Dependency:** `org.xerial:sqlite-jdbc` (plus `spring-boot-starter-jdbc` if #20 uses `JdbcTemplate`). It is added
  in the #20 PR, which therefore needs a **human merge** (CLAUDE.md step 8.5).
- The synthetic seed farmer is loaded from a JSON seed file at startup when `G_ONE_MEMORY_SEED_FILE` is set.

## Decision

1. **Demo server:** one demo laptop runs a **private copy of the jar**: `backend\target\*.jar` copied to
   `C:\g-one-demo\`, so rebuilding cannot hang it.
   - The laptop is on mains power, with sleep disabled, on its most stable network. A phone hotspot is the backup.
   - The second laptop is a warm standby with its own ngrok domain.
2. **Sharing one number between two teammates:**
   - **Inbound (variant B of #8, chosen: Tone or KooKoo):** the number's webhook points at the demo laptop. Whoever needs it
     says "taking the number" in team chat, repoints it, and points it back afterwards. The current owner goes in
     `docs/RUNBOOK.md`.
   - **Outbound-only (fallback variant A):** each server sends its own `G_ONE_PUBLIC_BASE_URL` in the TwiML and
     status callback URLs when it creates a call, so both teammates can test at once without sharing.
3. **Store:** SQLite file under `G_ONE_DATA_DIR` (default `./data`, gitignored). Each teammate keeps their own
   database. The demo laptop's database is canonical and is reseeded before the recording.
4. **Console exposure:** the tunnel makes the console public, and it shows live transcripts. The console REST and WS
   endpoints require `G_ONE_ACCESS_CODE`.
5. **Running and stopping:**
   - Start: `java -jar C:\g-one-demo\g-one.jar` and `ngrok http --url=<static-domain> 8080`.
   - Stop: Ctrl+C in both windows. `docs/RUNBOOK.md` (#16) records this. Never leave either running without saying
     so.

### Environment variables (names only)

- **Gnani:** `GNANI_API_KEY`, `GNANI_BASE_URL` (optional), `GNANI_STT_MODEL`, `GNANI_TTS_MODEL`.
- **LLM:** `G_ONE_LLM_BASE_URL`, `G_ONE_LLM_API_KEY`, `G_ONE_LLM_MODEL`.
- **Public URL and access:** `G_ONE_PUBLIC_BASE_URL` (the exact `https://` origin, also used for signature checks),
  `G_ONE_ACCESS_CODE`.
- **Telephony:** `G_ONE_TELEPHONY_PROVIDER` (`twilio` | `tone` | `kookoo`).
  - Twilio: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` (or `TWILIO_API_KEY_SID` + `TWILIO_API_KEY_SECRET`),
    `TWILIO_FROM_NUMBER` (non-Indian).
  - Tone or KooKoo: provider keys, named after #6.
- **Demo numbers (env only):** `G_ONE_DEMO_FARMER_PHONE`, plus `G_ONE_BUYER_<ID>_PHONE` for each directory buyer.
- **Memory:** `G_ONE_MEMORY_HMAC_KEY` (≥ 32 random bytes, base64), `G_ONE_DATA_DIR`, `G_ONE_MEMORY_SEED_FILE`
  (optional).
- **Existing:** `G_ONE_MANDI_SNAPSHOT_FILE` or the `G_ONE_AGMARKNET_*` set.
- The ngrok auth token lives in ngrok's own config, not in the app's env.

## Consequences

- The demo depends on one laptop and its network. This is accepted for two days and mitigated by the standby laptop
  and the hotspot.
- One new dependency (`sqlite-jdbc`) and a human merge for #20. `forget_me` deletes the farmer's rows.
- Inbound calls (variant B) mean one number points at one laptop at a time, so teammates hand it over explicitly.
- `G_ONE_PUBLIC_BASE_URL` must exactly match the tunnel origin, or Twilio signature checks fail.
