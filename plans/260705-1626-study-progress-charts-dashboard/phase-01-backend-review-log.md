---
phase: 1
title: Backend Review Log
status: completed
effort: 4h
---

# Phase 1: Backend Review Log

## Overview
Add an append-only `review_log` table (V9) and write exactly one row per review inside
`ReviewService.review()`. This is the forward-only history source for time-series charts.
Verbs untouched; follows `study` module layering and the no-user-FK (`user_sub`) convention.

## Requirements
### Functional
- Every graded review persists one immutable `review_log` row with: user, word, deck,
  rating, prev/new state, resulting interval + ease, and `reviewed_at` timestamp.
- Existing review behavior (SM-2 scheduling, response) unchanged.
### Non-functional
- No blocking of the review response on log failure semantics — log write is in the same
  `@Transactional` as the state update (atomic: both commit or both roll back).
- All new files < 200 LOC. No FK to a user table (`user_sub varchar(100)`), matching `user_card_state`.

## Architecture
### V9 migration — `review_log`
```sql
CREATE TABLE IF NOT EXISTS review_log (
    id            bigint          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_sub      varchar(100)    NOT NULL,
    vocab_word_id bigint          NOT NULL REFERENCES vocab_word(id) ON DELETE CASCADE,
    deck_id       bigint          NOT NULL REFERENCES vocab_deck(id) ON DELETE CASCADE, -- denormalized
    rating        varchar(8)      NOT NULL,   -- AGAIN | HARD | GOOD | EASY
    prev_state    varchar(16)     NOT NULL,   -- state BEFORE this review
    new_state     varchar(16)     NOT NULL,   -- state AFTER this review
    interval_days int             NOT NULL,   -- resulting interval
    ease_factor   double precision NOT NULL,  -- resulting ease
    reviewed_at   timestamptz     NOT NULL DEFAULT now()
);
-- Time-series aggregation index (user + time)
CREATE INDEX idx_review_log_user_time ON review_log(user_sub, reviewed_at);
-- Per-deck time-series
CREATE INDEX idx_review_log_user_deck_time ON review_log(user_sub, deck_id, reviewed_at);
```
`ON DELETE CASCADE` keeps logs consistent if a word/deck is removed (matches `user_card_state`).

### Component interactions
`ReviewService.review(sub, wordId, rating)` (existing) → after `applyScheduling` + `save(state)`:
capture `prevState` (read state BEFORE scheduling), resolve `deckId` from the word, build a
`ReviewLog`, `reviewLogRepo.save(log)`. `reviewed_at` from `StudyClock.now()` (or DB default).

Resolve `deckId`: the review path already loads/uses the `VocabWord` (for the 404 check). Reuse
that word's `deck.getId()` — no extra query. If current code does not already hold the word,
add a single `vocabWordRepo.findById` (it validates existence anyway).

## Related Code Files
### Create
- `engly-be/src/main/resources/db/migration/V9__create_review_log_table.sql`
- `engly-be/src/main/java/com/engly/study/domain/ReviewLog.java` (entity; static factory `record(...)`)
- `engly-be/src/main/java/com/engly/study/infrastructure/ReviewLogJpaRepository.java`
  (empty body for P1 — extends `JpaRepository<ReviewLog, Long>`; P2 adds aggregation queries)
### Modify
- `engly-be/src/main/java/com/engly/study/application/ReviewService.java` — capture `prevState`
  before scheduling; after saving state, append a `ReviewLog` row. Inject `ReviewLogJpaRepository`.
### Delete
- None.

## Implementation Steps
1. Write V9 migration exactly as above. Verify next-version = V9 (latest is V8).
2. `ReviewLog` entity: fields mirror columns; `createdBy` not needed. `reviewed_at`
   `insertable=false updatable=false` with DB default now() **OR** set explicitly from
   `StudyClock.now()` — pick explicit-set for testability (fixed clock in tests). Use `@Enumerated`
   only if you model rating/state as enums; storing as `String` is fine (rating from `ReviewRating.name()`,
   states from `CardLearningState.name()`). Add a static factory `of(sub, word, deck, rating, prev, new, interval, ease, at)`.
3. `ReviewLogJpaRepository extends JpaRepository<ReviewLog, Long>` (no custom methods yet).
4. In `ReviewService.review()`: read `prevState = state.getState()` BEFORE running the scheduler;
   after `cardStateRepo.save(state)`, build+save the log (rating, prevState, `state.getState()` as new,
   `state.getIntervalDays()`, `state.getEaseFactor()`, `deckId`, `studyClock.now()`).
5. `./mvnw -q -DskipTests compile`.

## Success Criteria
- [ ] `./mvnw -q -DskipTests compile` passes.
- [ ] V9 applies cleanly on a fresh DB (Testcontainers boot in P5 confirms).
- [ ] A single `POST /api/study/review` inserts exactly one `review_log` row with correct
      prev/new state and deck_id (asserted in P5 integration test).
- [ ] Existing review response contract unchanged.

## Risk Assessment
- **prev_state capture order**: must read state BEFORE `applyScheduling` mutates the managed
  entity. Capture into a local first. Mitigation: unit test asserts prev≠new on a NEW→LEARNING review.
- **deck_id resolution cost**: reuse already-loaded word; avoid an extra query / N+1.
- **Existing ReviewService test breaks** (now depends on `ReviewLogJpaRepository`): update its
  mock/wiring in P5 (or here if the test is co-located and fast).
