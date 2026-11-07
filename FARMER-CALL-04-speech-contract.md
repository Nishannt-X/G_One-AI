---
title: Adapt the speech pipeline to isolated phone legs
parent: FARMER-CALL-WAYFINDER.md
labels: [wayfinder:prototype]
status: open
assignee: null
blocked_by: [FARMER-CALL-01-telephony-gate.md]
---

## Question

What minimal end-to-end audio contract converts each carrier leg's telephony frames into Gnani Prisma STT, translates between the callers' selected languages, converts Gnani Timbre TTS back to the carrier format, and delivers it only to the opposite leg? Compare completed-turn processing with streaming only if the measured pause warrants it; include language and model compatibility probes.
