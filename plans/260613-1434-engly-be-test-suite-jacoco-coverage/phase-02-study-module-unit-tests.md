# Phase 02 — Study Module Unit Tests

## Context Links
- Scout cases: `scout/scout-01-coverage-gap-map.md` §§3–7 (Review/Queue/Deck/Settings/Clock)
- Test strategy: `research/researcher-02-spring-test-strategy.md` §§4,5 (Mockito, parameterized)
- Existing pattern to copy: `engly-be/src/test/java/com/engly/verb/VerbQueryServiceTest.java`
- Existing integration coverage (do NOT duplicate): `engly-be/src/test/java/com/engly/study/StudyApiIntegrationTest.java`, `Sm2SchedulerTest.java`

## Overview
- **Date:** 2026-06-13
- **Description:** Fast Mockito unit tests for the four study application services + an explicit `StudyClock` unit test. Targets THIN classes; mock repos + `Sm2Scheduler`; inject fixed-instant `StudyClock`. Covers branch-heavy logic (allowance math, due-filter routing, lazy-create, settings update) that integration tests touch only on happy path.
- **Priority:** P1
- **Implementation status:** pending
- **Review status:** pending

## Key Insights
- `Sm2Input`/`Sm2Result` are **package-private** in `com.engly.study.application` → unit tests that construct/stub them MUST be in package `com.engly.study.application` (not `com.engly.study`). Place these files under `src/test/java/com/engly/study/application/`.
- `ReviewService.review()` only needs `Sm2Scheduler` MOCKED (return a canned `Sm2Result`) to assert the wiring `dueOn = studyClock.today() + intervalDays` and the persist/lazy-create path — SM-2 math itself is already covered by `Sm2SchedulerTest`. DRY: do NOT re-test the algorithm here.
- `StudyClock(Clock fixed)` constructor exists for deterministic tests — pure unit, zero Spring.
- Entities (`UserCardState`, `UserStudySettings`) have factory methods (`newCard`, `defaults`) and behavior methods (`applyScheduling`, `update`, `bumpUpdatedAt`) — build real instances, no reflection needed for most; use the `VerbQueryServiceTest` reflection helper only if a getter has no public setter.
- `StudyQueueService` allowance: `allowance = max(0, newCardsPerDay - introducedToday)` — the high-value branches (allowance 0, partial, full; deck-not-found; due+new ordering) are best as mocked unit tests.

## Requirements
**Functional** — cover per-class cases below; tests genuinely pass (no mock-as-cheat).
**Non-functional** — each file < 200 LOC; pure Mockito (`@ExtendWith(MockitoExtension.class)`), no Spring context.

## Architecture
`@ExtendWith(MockitoExtension.class)` + `@Mock` repos/scheduler + constructor-injected service under test. `StudyClock` built with `Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneId.of("Asia/Saigon"))`.

## Related Code Files
**Create** (all under `src/test/java/com/engly/study/application/`):
| Test file | Target class |
|-----------|--------------|
| `ReviewServiceTest.java` | `com.engly.study.application.ReviewService` |
| `StudyQueueServiceTest.java` | `com.engly.study.application.StudyQueueService` |
| `StudyDeckServiceTest.java` | `com.engly.study.application.StudyDeckService` |
| `StudySettingsServiceTest.java` | `com.engly.study.application.StudySettingsService` |
| `StudyClockTest.java` | `com.engly.study.application.StudyClock` |

**Modify / Delete** — none. (Do NOT touch `StudyApiIntegrationTest`; it stays as e2e coverage.)

## Implementation Steps
1. **ReviewServiceTest** — mock `UserCardStateJpaRepository`, `VocabWordJpaRepository`, `Sm2Scheduler`; real `StudyClock(fixed)`. Cases:
   - unknown wordId (`existsById`→false) → `VocabWordNotFoundException`.
   - lazy-create: `findByUserSubAndVocabWordId`→empty → `save(newCard(...))` invoked; then scheduling applied.
   - existing state path: returns existing, `save` called once for update.
   - `dueOn` wiring: scheduler returns intervalDays=6 → assert `dueOn == 2026-06-15 + 6` and `state.applyScheduling(...)` receives `studyClock.now()`.
   - response mapping: returned `ReviewResultResponse` fields mirror state after apply.
2. **StudyQueueServiceTest** — mock 4 repos + `StudyClock(fixed)`. Cases:
   - deck not found (`existsById`→false) → `VocabDeckNotFoundException`.
   - due cards mapped: `findDueCards` returns 2 states; `findAllById` returns matching words → 2 responses, correct order (due first).
   - allowance full: settings 20, introducedToday 0, deck has 3 unseen → 3 NEW lazy-created + saved.
   - allowance partial: introducedToday 15, limit 20 → at most 5 new (`limit(allowance)`).
   - allowance zero: introducedToday ≥ limit → no new cards saved.
   - settings lazy-create: `settingsRepo.findById`→empty → `save(defaults)` invoked.
   - empty deck: no due, no new word ids → empty list.
   - due word missing from `findAllById` (null map entry) → skipped (no NPE).
3. **StudyDeckServiceTest** — mock `VocabDeckJpaRepository`, `VocabWordJpaRepository`, `UserCardStateJpaRepository` + `StudyClock(fixed)`. Cases:
   - listDecks maps each deck via count queries → `StudyDeckSummary` fields populated.
   - `newCount = max(0, total - withState)`; assert floor at 0 when withState > total.
   - dueCount + learnedCount come from the right repo methods (verify args incl. `ACTIVE_STATES`, `today`, `REVIEW`).
   - empty deck list → empty summaries.
4. **StudySettingsServiceTest** — mock `UserStudySettingsJpaRepository`. Cases:
   - getOrCreate first time: findById empty → `save(defaults)`; response `newCardsPerDay=20`, `maxReviewsPerDay=null`.
   - getOrCreate existing: returns stored values, no save.
   - update existing: `update(...)` + `bumpUpdatedAt` + `save`; response reflects new values.
   - update no-row: findById empty → defaults then update applied then save (created with requested values).
5. **StudyClockTest** — pure unit, no mocks. Cases (from scout §7):
   - `today()` with fixed `2026-06-15T00:00:00Z` in Saigon → `2026-06-15` (UTC+7, no DST).
   - boundary `2026-06-14T23:00:00Z` Saigon → still next-day logic verified (06:00 local 06-15 → 2026-06-15).
   - year boundary `2025-12-31T17:00:00Z` → `2026-01-01` local.
   - `now()` returns the fixed instant.
6. Compile: `cd engly-be && ./mvnw -o test-compile` (or `./mvnw test -Dtest='Study*Test,ReviewServiceTest'`).

## Todo List
- [ ] `ReviewServiceTest` (5+ cases)
- [ ] `StudyQueueServiceTest` (8 cases)
- [ ] `StudyDeckServiceTest` (4 cases)
- [ ] `StudySettingsServiceTest` (4 cases)
- [ ] `StudyClockTest` (4 cases)
- [ ] All compile + pass; each file < 200 LOC

## Success Criteria
- All 5 files pass via `./mvnw test`.
- `study.application` package LINE coverage materially up vs phase-01 baseline (target: each of the 4 services + clock > 85% line in JaCoCo HTML).
- No duplication of `Sm2Scheduler` algorithm assertions (DRY w/ `Sm2SchedulerTest`).

## Risk Assessment
- **Package-private record access** → put files in `...study.application`; if misplaced, `Sm2Input`/`Sm2Result` won't resolve. Mitigation: explicit package note above.
- **Over-mocking entity behavior** (mocking `UserCardState`) hides real logic. Mitigation: use real entities via factory methods; mock only repos + scheduler.
- **Stubbing strictness** (Mockito `UnnecessaryStubbingException`) on branch tests. Mitigation: use `lenient()` only where a shared `@BeforeEach` stub is unused by some cases, else stub per-test.

## Security Considerations
- None — pure unit tests, no auth/DB. RBAC covered in phase-04/05.

## Next Steps
- Independent of phases 03/04. Feeds phase-06 coverage. Residual study e2e edge cases (if any) handled in phase-05.
