---
title: Decide the failed-turn behavior
parent: FARMER-CALL-WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: [FARMER-CALL-02-call-interaction.md, FARMER-CALL-04-speech-contract.md]
---

## Question

If Prisma, translation, or Timbre fails or times out after `#`, what should each caller hear, and should the buffered turn be retried or discarded? The human asked to settle this after the main call flow.

## Provisional resolution (browser demo, 2026-11-06)

The human deferred the exact behavior, so the prototype uses the smallest credible one; it is a default to confirm or change, not a settled decision. If Prisma, translation or Timbre fails or times out after Stop, the whole turn is aborted: nobody hears partial audio and no text is shown. The speaker sees which stage failed with the upstream status, and can **retry the same recording** (kept only in their browser) or **record again**; the listener sees a short "message failed, they will try again" note. A failed advisory never blocks the translated turn. Phone-call (`#`/IVR) behavior is not decided. See [DEMO.md](DEMO.md).
