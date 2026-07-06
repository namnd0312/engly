---
phase: 2
title: Backend Stats API
status: completed
effort: 6h
---

# Phase 2: Backend Stats API

## Overview
Read-only aggregation API under `/api/study/stats/**` feeding the dashboard. Snapshot stats
from `user_card_state`; time-series from `review_log`. All date math in Asia/Saigon. No
`SecurityConfig` change — the existing `/api/study/**` → `.authenticated()` matcher covers it.

## Requirements
### Functional — endpoints (all GET, current user derived from JWT `sub`)
- `GET /api/study/stats/summary` → `{ totalCards, newCount, learningCount, reviewCount,
  totalReviews, reviewedToday, dueToday, currentStreakDays }`.
- `GET /api/study/stats/activity?days=30` → ordered list `[{ date, reviews, newCards }]`,
  one entry per day for the last `days` (gap-filled with zeros). `reviews` from `review_log`;
  `newCards` from `user_card_state.created_at` (has real history).
- `GET /api/study/stats/forecast?days=14` → `[{ date, dueCount }]` for the next `days` days
  from `user_card_state.due_on` (states LEARNING/REVIEW), gap-filled.
- `GET /api/study/stats/accuracy?days=30` → `[{ date, again, hard, good, easy }]` per-day
  rating counts from `review_log`, gap-filled. (Overall accuracy % computed client-side.)
### Non-functional
- `days` validated: clamp/`@Min(1) @Max(90)` (default 30 activity / 14 forecast). Reject > 90.
- Gap-filling in Java (generate contiguous date range in Asia/Saigon, left-join to a
  `Map<LocalDate, ...>` from the query). Native aggregation queries return only non-zero days.
- Files < 200 LOC; split service if needed (`StudyStatsService` orchestrates; helper for
  date-range gap-fill, e.g. `StatsDateRange`).

## Architecture
### Packages
```
com.engly.study.application   StudyStatsService, StatsDateRange (gap-fill helper)
com.engly.study.api           StudyStatsController  (/api/study/stats)
com.engly.study.api.dto       StudySummaryResponse, ActivityPointResponse,
                              ForecastPointResponse, AccuracyPointResponse  (records)
```
### Repository queries (native, Asia/Saigon bucketing — follow repo L84–110 precedent)
Add to `ReviewLogJpaRepository`:
- `reviewsPerDay(userSub, fromInclusive)` →
  `SELECT (reviewed_at AT TIME ZONE 'Asia/Saigon')::date AS d, COUNT(*) FROM review_log
   WHERE user_sub=:sub AND reviewed_at >= :from GROUP BY d` → `List<Object[]>` (date, long)
  or an interface projection `{ LocalDate getD(); long getCount(); }`.
- `ratingsPerDay(userSub, fromInclusive)` → same bucket, `GROUP BY d, rating` → (date, rating, count).
- `distinctReviewDates(userSub)` → `SELECT DISTINCT (reviewed_at AT TIME ZONE 'Asia/Saigon')::date
   FROM review_log WHERE user_sub=:sub ORDER BY 1 DESC` → for streak.
- `long count()` scoped: `countByUserSub(userSub)` → totalReviews.

Add to `UserCardStateJpaRepository`:
- `newCardsPerDay(userSub, fromInclusive)` →
  `(created_at AT TIME ZONE 'Asia/Saigon')::date` bucket, COUNT, GROUP BY.
- `duePerDay(userSub, fromInclusive, toInclusive, states)` → group by `due_on` (already a date).
- `countByUserSubGroupedByState` OR reuse three `countByUserSubAndState(userSub, state)` calls
  (JPQL, deck-independent — note existing counts are per-deck; add global variants).
- Reuse existing `countReviewedTodayForUser(sub, today)`.

### Streak computation (`StudyStatsService`)
From `distinctReviewDates` (desc): let `today = studyClock.today()`. If the newest date is not
`today` or `today-1`, streak = 0. Else walk consecutive days backward counting until a gap.
(Anchor: streak stays "alive" if reviewed today OR yesterday, so an in-progress day before a
review doesn't zero it.) Pure function over `List<LocalDate>` → unit-testable in P5.

### Component flow
Controller (`@AuthenticationPrincipal Jwt user`, `user.getSubject()`) → `StudyStatsService`
(`@Transactional(readOnly = true)`) → repos + `StudyClock` + `StatsDateRange`. Returns records.

## Related Code Files
### Create
- `engly-be/src/main/java/com/engly/study/api/StudyStatsController.java`
- `engly-be/src/main/java/com/engly/study/application/StudyStatsService.java`
- `engly-be/src/main/java/com/engly/study/application/StatsDateRange.java` (gap-fill helper; pure)
- `engly-be/src/main/java/com/engly/study/api/dto/StudySummaryResponse.java`
- `engly-be/src/main/java/com/engly/study/api/dto/ActivityPointResponse.java`
- `engly-be/src/main/java/com/engly/study/api/dto/ForecastPointResponse.java`
- `engly-be/src/main/java/com/engly/study/api/dto/AccuracyPointResponse.java`
### Modify
- `engly-be/src/main/java/com/engly/study/infrastructure/ReviewLogJpaRepository.java` — add
  `reviewsPerDay`, `ratingsPerDay`, `distinctReviewDates`, `countByUserSub`.
- `engly-be/src/main/java/com/engly/study/infrastructure/UserCardStateJpaRepository.java` — add
  `newCardsPerDay`, `duePerDay`, global state counts, (reuse `countReviewedTodayForUser`).
### Delete
- None.

## Implementation Steps
1. DTO records (4).
2. Repo queries (native for TZ bucketing; parameterize; return projections or `Object[]`).
3. `StatsDateRange` helper: `List<LocalDate> range(from, to)` + `fill(range, Map, zeroSupplier)`.
4. `StudyStatsService`: implement summary, activity, forecast, accuracy, streak. Compute totals
   via global state counts; `reviewedToday`/`dueToday` reuse existing today-scoped counts.
5. `StudyStatsController`: 4 GET endpoints; `@Valid`/`@Min/@Max` on `days` query param (default values).
6. `./mvnw -q -DskipTests compile`.

## Success Criteria
- [ ] `./mvnw -q -DskipTests compile` passes.
- [ ] Each endpoint returns gap-filled, date-ordered data for the authenticated user only.
- [ ] `summary` state counts + `dueToday` match `user_card_state`; `reviewedToday` matches
      `review_log`/`last_reviewed_at`.
- [ ] Anonymous request → 401; other users' data never returned (sub from JWT only).
- [ ] `days` > 90 rejected (400).

## Risk Assessment
- **Empty history at launch**: activity/accuracy sparse until `review_log` accrues — expected
  (locked decision). `newCards` line still shows real history from `created_at`.
- **TZ off-by-one**: reuse the exact `AT TIME ZONE 'Asia/Saigon'` cast; bucket range built from
  `StudyClock.today()`. P5 asserts a review at 23:00 Saigon buckets to the correct local date.
- **N+1 / heavy scans**: indexed by `(user_sub, reviewed_at)` (P1). Aggregations are single
  grouped queries, not per-day loops.
- **Streak edge cases**: no reviews → 0; reviewed today only → 1; gap yesterday but reviewed
  today → 1. Covered by pure-function unit tests (P5).
