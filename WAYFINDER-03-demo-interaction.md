---
title: Define the two-person demo interaction
parent: WAYFINDER.md
labels: [wayfinder:grilling]
status: open
assignee: null
blocked_by: []
---

## Question

How should the two people join as A/B, choose their spoken and listening languages later, take turns, see transcript and translation, and hear translated audio? What visible failure or retry behavior is required for a credible public demo?

## Resolution

Provisional (2026-11-06), implemented in the prototype: one person creates a room and shares a join link; the creator picks Farmer or Buyer and the joiner gets the other role (the farmer–buyer map supersedes plain A/B). Each person picks a language from a configured list and may change it between turns. Tap Start/Stop (auto-stop before 30 s). Both screens show transcript and translation; only the listener hears the voice, with a manual play button if autoplay is blocked. Failure behavior is in [FARMER-CALL-06](FARMER-CALL-06-failure-behavior.md). Still to confirm on real phones and with the human: the public language pair and the wording of the UI.

## Interview notes

- Position the hackathon version as a general two-person language bridge; use a commerce exchange as one demo example.
- Both phones show the source transcript and translated text. Only the listener hears the translated voice.
- Each speaker taps Start and Stop for a turn; processing begins after Stop.
- One person creates a room and shares a join link. Each participant chooses their own language; the first public language pair is still undecided.
- Still to decide: playback permission and what happens when a turn fails.
