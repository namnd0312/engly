# Phase 06 — Tests + Docs

## Context Links
- SM-2 test cases: `research/researcher-01-sm2-algorithm.md` (§5)
- Auth test expectations: `research/researcher-02-auth-patterns.md` (matcher ordering, 401/403)
- Mirror tests: `engly-be/src/test/java/com/engly/verb/VerbAdminIntegrationTest.java`, `VerbQueryServiceTest.java`, `common/SecurityRulesTest.java`, `AdminSecurityRulesTest.java`
- FE specs mirror: `engly-fe/src/app/features/verb-search/*.spec.ts`, `core/auth.service.spec.ts`, `features/admin/admin.guard.spec.ts`

## Overview
- **Date:** 2026-06-01
- **Priority:** P2 (verification gate before merge)
- **Current status:** pending
- **Brief:** BE SM-2 unit tests (every rating path + edge), Testcontainers integration (vocab admin CRUD + full study flow + auth gating), FE Jasmine specs for study components/services + authGuard + admin pages. Update `docs/system-architecture.md` + `codebase-summary.md`.

## Key Insights
- `Sm2Scheduler` is pure → fast plain JUnit (no Spring context), assert exact ease/interval/reps/lapses per rating.
- Integration tests use Testcontainers Postgres (Flyway V1–V4) + MockMvc. Admin chain via `jwt().authorities(ROLE_ADMIN)`; study chain (session) via `oidcLogin()` postprocessor (`SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(...)` with `sub`).
- **Must update** `VerbAdminIntegrationTest.create_duplicateBaseForm_returns409` assertion: message changed to generic `"resource already exists"` in P02.
- Full study-flow integration proves the off-by-one due-date correctness (the highest-risk area).

## Requirements
### Functional (test coverage)
- **SM-2 unit** (each path): again-reset (reps→0, interval=1, EF−0.20, lapses++), hard (EF−0.15, interval×1.2 rounded, reps++), good n=0→1d, good n=1→6d, good n>2→round(interval×EF), easy-bonus (EF+0.15, interval×EF×1.3), EF floor 1.3 (never below), interval rounding (Math.round not truncate), interval floor 1.
- **Vocab admin integration**: deck create→list; duplicate slug→409 (`"resource already exists"`); word create with bad deckId→404; word list `?deckId=` filter; verified toggle; delete deck cascades words.
- **Study flow integration**: login (oidcLogin sub="test-user") → `GET /decks` counts → `GET queue` returns ≤ limit new → `POST review {wordId, GOOD}` → state persisted, due_on advances → simulate "today" past due (override `StudyClock` via injected fixed `Clock`, or seed due_on in past) → due card reappears in queue. Settings: PUT new_cards_per_day=2 → queue returns ≤2 new (limit enforced).
- **Auth gating**: `/api/study/**` anonymous → 401; authenticated non-admin → 200; admin-only `/api/admin/decks` anonymous/non-admin → 401/403.

### FE specs
- `authGuard`: anon → calls `auth.login()` returns false; authenticated → true.
- `study-api.service`: HTTP methods hit correct URLs with `withCredentials`.
- `deck-list` / `flashcard-session` / `study-settings`: render + signal behavior (flip toggle, advance index, rating dispatch).
- admin deck/word list+form: basic render + service call wiring.

## Architecture
### Test layout (BE)
```
study/Sm2SchedulerTest.java                (pure unit)
study/StudyApiIntegrationTest.java         (Testcontainers + oidcLogin)
study/StudySecurityRulesTest.java          (401/403 matrix)  — or fold into existing SecurityRulesTest
vocab/VocabAdminIntegrationTest.java       (Testcontainers + jwt ROLE_ADMIN)
```
### Test layout (FE) — colocated `.spec.ts` next to each component/service.
### StudyClock testability
Inject a fixed `Clock` in integration test config (or expose package-private setter) so "today" is deterministic; assert due_on math against Asia/Saigon.

## Related Code Files
### Create
- `engly-be/src/test/java/com/engly/study/Sm2SchedulerTest.java`
- `engly-be/src/test/java/com/engly/study/StudyApiIntegrationTest.java`
- `engly-be/src/test/java/com/engly/study/StudySecurityRulesTest.java`
- `engly-be/src/test/java/com/engly/vocab/VocabAdminIntegrationTest.java`
- `engly-fe/src/app/core/auth.guard.spec.ts`
- `engly-fe/src/app/features/study/data/study-api.service.spec.ts`
- `engly-fe/src/app/features/study/deck-list-page.component.spec.ts`
- `engly-fe/src/app/features/study/flashcard-session-page.component.spec.ts`
- `engly-fe/src/app/features/study/study-settings-page.component.spec.ts`
- `engly-fe/src/app/features/admin/data/admin-deck-api.service.spec.ts`
- `engly-fe/src/app/features/admin/data/admin-vocab-word-api.service.spec.ts`
- `engly-fe/src/app/features/admin/deck-admin-list-page.component.spec.ts`
- `engly-fe/src/app/features/admin/vocab-word-admin-form-page.component.spec.ts`

### Modify
- `engly-be/src/test/java/com/engly/verb/VerbAdminIntegrationTest.java` — update duplicate-conflict assertion to `"resource already exists"`.
- `engly-be/src/test/java/com/engly/common/SecurityRulesTest.java` (and/or `AdminSecurityRulesTest.java`) — add `/api/study/**` 401-when-anonymous case if folding security matrix here.
- `docs/system-architecture.md` — add vocab + study modules, SM-2 flow, new security matcher, TZ note.
- `docs/codebase-summary.md` — list new packages/components/endpoints/migrations.

### Delete
- None.

## Implementation Steps
1. Write `Sm2SchedulerTest` — one `@Test` per case in Requirements; use exact expected numbers (compute by hand from brainstorm mapping).
2. Write `VocabAdminIntegrationTest` mirroring `VerbAdminIntegrationTest` (jwt ROLE_ADMIN postprocessor); cover deck CRUD, slug 409, word deckId 404, deckId filter, verified toggle, cascade delete.
3. Write `StudyApiIntegrationTest`: use `oidcLogin().idToken(t -> t.subject("test-user"))`; full flow incl. due-reappear (fixed Clock) + settings limit enforcement.
4. Write/extend security test: `/api/study/**` 401 anon, 200 authed; admin routes 401/403.
5. Update `VerbAdminIntegrationTest` assertion (generic conflict message).
6. FE: `authGuard` spec; `study-api.service` HttpTestingController spec; component specs (TestBed, signal assertions); admin deck/word service + page specs.
7. Run BE `./mvnw test` and FE `npm test` — fix all failures (no skips, no fake data).
8. Update `docs/system-architecture.md` + `docs/codebase-summary.md`.
9. Final full build check.

## Todo List
- [ ] Sm2SchedulerTest (all rating paths + EF floor + rounding + interval floor)
- [ ] VocabAdminIntegrationTest (CRUD, 409, 404, filter, toggle, cascade)
- [ ] StudyApiIntegrationTest (queue→review→reschedule→due reappears + settings limit)
- [ ] Study auth gating test (401/403/200 matrix)
- [ ] Update VerbAdminIntegrationTest conflict assertion
- [ ] FE authGuard spec
- [ ] FE study api-service + 3 component specs
- [ ] FE admin deck/word service + page specs
- [ ] BE `./mvnw test` green
- [ ] FE `npm test` green
- [ ] Update docs/system-architecture.md + codebase-summary.md
- [ ] Final build check (BE + FE)

## Success Criteria
- `./mvnw test` all green (incl. updated verb test); `npm test` all green.
- SM-2 unit tests cover every rating + edge; integration proves due card reappears on/after `due_on` and new-limit enforced.
- `/api/study/**` 401 anonymous proven; admin routes 401/403 proven.
- Docs reflect new modules/endpoints/migrations.

## Risk Assessment
- **Flaky due-date test from real clock**: inject fixed `Clock` into `StudyClock` (or seed past due_on directly) — never rely on wall clock.
- **oidcLogin postprocessor wiring**: ensure `spring-security-test` on classpath (already used for `jwt()`).
- **Conflict-message coupling**: verb test updated in same phase to avoid red build.
- **FE signal timing**: use `fixture.detectChanges()` + `await fixture.whenStable()` for async API mocks.

## Security Considerations
- Tests assert IDOR protection: user only sees own card states (different `sub` → empty queue/own data).
- Auth matrix test prevents accidental SecurityConfig regression exposing `/api/study/**` or `/api/admin/**`.

## Next Steps
- Feature complete. Optional follow-ups (out of MVP): `user_review_log` + stats/streak, reverse cards, TTS.

## Compile/Build Check
```
cd engly-be && ./mvnw test
cd engly-fe && npm test -- --watch=false --browsers=ChromeHeadless
```
