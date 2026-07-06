---
title: Study Progress Charts Dashboard
description: >-
  Per-user learning/review progress dashboard: new review_log table +
  /api/study/stats aggregation API + Angular /progress page with chart.js
  charts.
status: completed
priority: P2
branch: main
tags:
  - backend
  - frontend
  - study
  - srs
  - charts
  - angular
  - spring-boot
blockedBy: []
blocks: []
created: '2026-07-05T09:30:05.555Z'
createdBy: 'ck:plan'
source: skill
---

# Study Progress Charts Dashboard

## Overview

Add a per-user progress dashboard visualizing SRS study/review history as charts.
Extends the existing `study` SRS module (verbs untouched). Login required; keyed on
JWT `sub` string (no user FK), consistent with `user_card_state`.

**Two data sources feed the charts:**
- **Snapshot** (immediate, from existing `user_card_state`): mastery breakdown, due
  forecast, new-cards-per-day (from immutable `created_at`), per-deck progress.
- **Time-series** (forward-only, NEW `review_log` table written per review): reviews
  per day, study streak, rating/accuracy trend. History starts empty at launch and
  accrues going forward — cannot be backfilled (per-review history was never recorded).

## Locked Decisions (do not re-litigate)
- **Hybrid data model**: add append-only `review_log` (V9) + write one row per review
  in `ReviewService.review()`; snapshot charts derive from `user_card_state`.
- **`review_log` denormalizes `deck_id`** (card state has no deck column) to allow
  per-deck time-series without joins.
- **Charting**: `chart.js` used **directly** via a small reusable `ChartCanvasComponent`.
  (ng2-charts@10 needs Angular 21; the Angular-19 line ng2-charts@8 drags in `@angular/cdk` —
  so chart.js-direct was chosen to keep the app dep-light. Decision confirmed with user mid-build.)
- **Route**: new lazy `features/progress/` at `/progress`, `canActivate: [authGuard]`,
  mirroring `/study`. Header nav link added.
- **Auth**: `@AuthenticationPrincipal Jwt user` → `user.getSubject()`; `userSub` derived
  server-side only (never from request body). Stats endpoints sit under `/api/study/stats/**`
  → inherit `.authenticated()` from `SecurityConfig` (no security change needed).
- **Timezone**: all date bucketing in **Asia/Saigon** via existing `StudyClock` +
  `AT TIME ZONE 'Asia/Saigon'` native-query precedent (repo lines 84–110).

## YAGNI Fence
NO: cross-user/leaderboard stats, CSV/PDF export, configurable date ranges beyond a fixed
default (30d activity / 14d forecast), retention-curve modeling (FSRS), backfill of history,
real-time updates, caching layer, per-word drilldown.

## Phases

| Phase | Name | Status | Description |
|-------|------|--------|-------------|
| 1 | [Backend Review Log](./phase-01-backend-review-log.md) | Completed | V9 `review_log` + write one row per review in `ReviewService`. |
| 2 | [Backend Stats API](./phase-02-backend-stats-api.md) | Completed | `/api/study/stats` summary/activity/forecast/accuracy; Saigon bucketing; streak. |
| 3 | [Frontend Data + Charting Deps](./phase-03-frontend-data-charting-deps.md) | Completed | chart.js (direct); `features/progress/` service+models+route+nav. |
| 4 | [Frontend Dashboard + Charts](./phase-04-frontend-dashboard-charts.md) | Completed | KPI cards + donut/line/bar/stacked charts via ChartCanvasComponent. |
| 5 | [Tests + Docs](./phase-05-tests-docs.md) | Completed | Unit/integration/security tests (BE 285, FE 163 green) + docs. |

## Dependency Chain
```
P1 ──► P2 ──► P3 ──► P4 ──► P5
```
- P2 needs P1 (`review_log` read model).
- P3 needs P2 DTO contracts (endpoint shapes). P4 needs P3 (service + models + deps).
- P5 after P4. P1 also updates the existing `ReviewService` test (now writes a log row).

## Acceptance Criteria
- Logged-in user opens `/progress`, sees: mastery donut, reviews-vs-new activity chart
  (30d), due-forecast bar (14d), rating/accuracy chart, and KPI cards (streak, total,
  reviewed today, due today), all populated (snapshot charts non-empty immediately).
- Each review appends exactly one `review_log` row (verified by integration test).
- `/api/study/stats/**` returns 401 anonymous, 200 authenticated; only own `sub` data.
- `./mvnw test` and `ng test` green.

## Key Dependencies
- BE: Spring Boot 3.4.5, Java 21, Flyway (**next migration = V9**), Postgres, Testcontainers.
- FE: Angular 19 standalone + signals; **add** `chart.js` + `ng2-charts`; custom CSS
  (indigo `#6366f1` / dark `#1a1a2e`); proxy `/api` → :8081; cookie auth + XSRF (existing).

## Resolved Questions
1. Streak counts **graded reviews only** (from `review_log`); new-card introductions alone do
   not extend the streak. Implemented + unit-tested in `StudyStatsServiceTest`.
2. Activity chart **overlays** "reviews/day" (review_log, forward-only) with "new cards/day"
   (created_at, real history) — mitigates the empty-launch look. Implemented.
