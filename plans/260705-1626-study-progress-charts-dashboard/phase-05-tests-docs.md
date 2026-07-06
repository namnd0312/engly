---
phase: 5
title: Tests + Docs
status: completed
effort: 5h
---

# Phase 5: Tests + Docs

## Overview
Test the new write path + stats API + dashboard, fix the review-log ripple in existing tests,
and update docs. Do not weaken existing tests to pass.

## Requirements
### Backend tests
- **`StudyStatsService` unit** (pure logic, mocked repos):
  - Streak: no reviews → 0; reviewed today only → 1; reviewed today + prior consecutive days → N;
    gap yesterday, none today → 0; reviewed yesterday not today → still counts (alive).
  - Gap-fill: query returns sparse days → output has one entry per day in range, zeros filled,
    date-ordered ascending.
- **`ReviewService` unit** (existing test — now depends on `ReviewLogJpaRepository`): update
  mock wiring; assert exactly one `ReviewLog` saved with prev≠new state on a NEW→LEARNING review,
  correct rating/deckId/interval/ease.
- **Integration (Testcontainers)**: `POST /api/study/review` inserts one `review_log` row; then
  `GET /api/study/stats/summary` reflects `reviewedToday=1`, correct state counts; `activity`
  shows today's review bucketed to Asia/Saigon date; a 23:00-Saigon review buckets to the right day.
- **Security**: `/api/study/stats/**` → 401 anonymous, 200 authenticated; user A cannot see user B's
  data (sub from JWT). `days` > 90 → 400.
### Frontend tests
- `progress-api.service.spec.ts`: `HttpTestingController`; each method hits the right URL
  (`/api/study/stats/...` with `?days=`), method GET, `withCredentials` true; `flush` sample.
- `progress-dashboard-page.component.spec.ts`: mock `ProgressApiService` (return `of(...)`),
  assert signals populate, loading→false, and error path sets error signal.
- Chart child components: minimal spec (create + `@Input` set → no throw). Keep light.
### Docs
- `docs/codebase-summary.md`: add `review_log` table + `study/stats` API + `features/progress`.
- `docs/system-architecture.md`: note the append-only review log + stats aggregation + chart stack.
- README "Tests" counts if it enumerates specs (currently "24 tests" / "13 specs") — update numbers.

## Related Code Files
### Create
- `engly-be/src/test/java/com/engly/study/application/StudyStatsServiceTest.java`
- `engly-be/src/test/java/com/engly/study/StudyStatsIntegrationTest.java` (or extend existing study IT)
- `engly-fe/src/app/features/progress/data/progress-api.service.spec.ts`
- `engly-fe/src/app/features/progress/progress-dashboard-page.component.spec.ts`
- (optional light) `engly-fe/src/app/features/progress/charts/*.spec.ts`
### Modify
- Existing `ReviewService` test (add `ReviewLogJpaRepository` mock; assert log write).
- `engly-be/src/test/java/com/engly/study/StudySecurityRulesTest.java` — add `/api/study/stats/**`
  auth assertions (mirror existing study matcher tests).
- `docs/codebase-summary.md`, `docs/system-architecture.md`, `README.md` (test counts).

## Implementation Steps
1. `StudyStatsServiceTest` (streak + gap-fill matrix).
2. Fix `ReviewService` test ripple; assert log write.
3. Integration test: review → log row → stats reflect it; TZ bucket assertion.
4. Security rule test for stats endpoints.
5. FE service spec + dashboard component spec (+ light chart specs).
6. `cd engly-be && ./mvnw test` ; `cd engly-fe && npx ng test --watch=false --browsers=ChromeHeadless`.
7. Docs update; verify claims/counts match reality.

## Success Criteria
- [ ] `./mvnw test` green (incl. updated ReviewService + new stats tests).
- [ ] `ng test` green (incl. progress specs).
- [ ] Streak + gap-fill edge cases covered.
- [ ] Integration proves one-log-per-review + correct Asia/Saigon bucketing.
- [ ] `/api/study/stats/**` security asserted (401/200, no cross-user leak).
- [ ] Docs updated + accurate.

## Risk Assessment
- **Flaky TZ tests**: use `StudyClock` with an injectable fixed `Clock`; assert on Saigon-local
  dates, never machine-local `LocalDate.now()`.
- **Testcontainers/Docker required**: same precedent as existing study IT — no new infra.
- **Chart component specs brittle** (canvas/chart.js in headless): keep to create + input smoke;
  don't assert rendered pixels.
