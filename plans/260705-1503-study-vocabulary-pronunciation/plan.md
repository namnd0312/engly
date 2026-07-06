---
title: Vocabulary Pronunciation (Speaker Icon + Web Speech API)
description: >-
  Speaker icon on flashcards; click pronounces the English term via browser TTS.
  Frontend-only, zero backend change.
status: completed
priority: P2
branch: main
tags:
  - frontend
  - study
  - accessibility
  - tts
blockedBy: []
blocks: []
created: '2026-07-05T08:05:39.825Z'
createdBy: 'ck:plan'
source: skill
---

# Vocabulary Pronunciation (Speaker Icon + Web Speech API)

## Overview

Add a speaker icon to the flashcard study session. Clicking it speaks the English
`term` aloud using the browser Web Speech API (`window.speechSynthesis`). No audio
files, no network, no backend change — the backend already supplies `term` + `ipa`.

**Scope:** frontend only (`engly-fe`). Angular 19, standalone components, plain CSS,
inline-SVG icons — matching existing repo conventions (verified: no icon lib, no SCSS,
no existing TTS code).

**Approach chosen:** Web Speech API over cloud/pre-generated audio (YAGNI — no cost,
no storage, no `audio_url` column/migration, works offline).

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Pronunciation Service](./phase-01-pronunciation-service.md) | Completed |
| 2 | [Wire Speaker Button + Tests](./phase-02-wire-speaker-button-tests.md) | Completed |

## Dependencies

- Phase 2 depends on Phase 1 (uses `PronunciationService`).
- No cross-plan dependencies.

## Acceptance Criteria

- Speaker button visible next to the term on the flashcard front face.
- Click pronounces the English word in an English accent.
- Rapid repeat clicks cancel the prior utterance (no overlap/queue buildup).
- Button hidden (or disabled) when the browser lacks `speechSynthesis` support.
- Accessible: `type="button"`, Vietnamese `aria-label`, keyboard-activatable.
- Unit tests pass for service + component; existing study tests still green.

## Design Decisions (confirmed via validation)

- **Voice/accent:** `en-US`, pick first available English voice. Hardcoded, not user-configurable (YAGNI).
- **Icon placement:** front face next to `.card-term` only. Back-face IPA line deferred.
- **Unsupported browsers:** hide the button entirely (no disabled/dead control).
- **a11y label:** `aria-label="Phát âm"`.

## Validation Log

### Session 1 — 2026-07-05

**Verification Results (Light tier — Fact Checker)**
- Claims checked: 8 (file paths, symbols, conventions)
- Verified: 8 | Failed: 0 | Unverified: 0
- Confirmed: `core/auth.service.ts` pattern; `flashcard-session-page.component.{ts,html,css,spec}`;
  `currentCard()`/`flipped()` signals + `inject()` usage; `.card-term` front-face render;
  Angular 19 standalone + plain CSS + no icon lib; backend `ipa` present, no audio field.

**Interview decisions (all confirm existing plan defaults — no phase changes required):**
- Accent → `en-US`.
- Icon placement → front face only.
- No-TTS behavior → hide the button.

**Whole-Plan Consistency Sweep:** No plan content changed (decisions matched defaults).
Re-read `plan.md` + both phase files — no stale terms, no contradictions. Consistent.

**Recommendation:** Proceed to implementation. `Failed: 0` — plan is implementation-eligible.
