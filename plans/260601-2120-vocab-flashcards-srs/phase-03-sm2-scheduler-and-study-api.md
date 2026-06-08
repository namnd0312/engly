# Phase 03 — SM-2 Scheduler + Study API

## Context Links
- Spec: `../reports/brainstorm-260601-2120-vocab-flashcards-srs.md` (§5 SM-2 mapping — AUTHORITATIVE, §7 study module)
- SM-2 research: `research/researcher-01-sm2-algorithm.md` (pure-function pseudocode, test cases)
- Auth research: `research/researcher-02-auth-patterns.md` (Pattern 1 — extend Chain 2, `OidcUser.getSubject()`)
- Mirror: `engly-be/src/main/java/com/engly/verb/application/VerbQueryService.java`, `common/SecurityConfig.java`
- Entities/repos from P1.

## Overview
- **Date:** 2026-06-01
- **Priority:** P1 (blocks P4 frontend study)
- **Current status:** pending
- **Brief:** Pure `sm2-scheduler` service (own file, <200 LOC, no I/O, unit-testable) + `study` module endpoints `/api/study/**` (decks, queue, review, settings). SecurityConfig: `/api/study/**` authenticated (Chain 2, before catch-all). Fixed TZ Asia/Saigon via a centralized clock/date helper.

## Key Insights
- **Brainstorm mapping WINS over research** where they differ. Use these exact rules (input = current `ease, interval, reps, lapses` + rating):
  - **Again** (fail): `reps=0`, `interval=1`, `ease=max(1.3, ease−0.20)`, `lapses++`, state→LEARNING.
  - **Hard**: `ease=max(1.3, ease−0.15)`, `interval=max(1, round(interval×1.2))`, `reps++`, state→REVIEW.
  - **Good**: `reps==0→1`, `reps==1→6`, `else round(interval×ease)`; `reps++`; ease unchanged (standard SM-2 baseline q=4 ≈ +0.0 net per brainstorm "standard"); state→REVIEW. (Note: research uses EF formula for Good; brainstorm says "standard SM-2" — implement interval per brainstorm; for EF on Good use **no change** to stay consistent with brainstorm wording, which lists EF deltas only for Again/Hard/Easy.)
  - **Easy**: `ease=ease+0.15`, `interval=round(interval×ease×1.3)` (with `interval=max(1,...)` floor), `reps++`, state→REVIEW.
  - All: enforce `ease=max(1.3, ease)`; `interval=max(1, interval)`; `due_on = today(Asia/Saigon) + interval days`.
- **TZ centralization:** one `StudyClock` helper exposing `today()` = `LocalDate.now(ZoneId.of("Asia/Saigon"))` + `now()` Instant. Inject everywhere date math happens (queue due-filter, review reschedule). Never call `LocalDate.now()` directly.
- **Lazy create:** `user_card_state` rows only exist after a card is first surfaced in a queue. Queue endpoint creates NEW-state rows for words without state, capped by `new_cards_per_day` minus already-introduced-today.
- **OidcUser principal** available under BFF session — `@AuthenticationPrincipal OidcUser user` → `user.getSubject()`.
- **Matcher ordering (critical):** add `/api/study/**` `authenticated()` in Chain 2 BEFORE `anyRequest().permitAll()`, AFTER Chain 1 `/api/admin/**` (separate chain, unaffected).

## Requirements
### Functional
- `GET /api/study/decks` → list decks with per-user counts `{deckId, name, slug, topic, newCount, dueCount, learnedCount}`.
  - `newCount` = words in deck with no `user_card_state` row (capped display optional; show raw remaining new words).
  - `dueCount` = `user_card_state` rows for user+deck where `due_on <= today` and state in (LEARNING, REVIEW).
  - `learnedCount` = rows with state=REVIEW.
- `GET /api/study/decks/{id}/queue` → today's session for deck: due review cards + up to `min(remainingNewAllowance)` new cards. Lazy-create NEW state rows for served new words. Returns ordered list of `{cardStateId, wordId, term, meaningVi, ipa, exampleEn, exampleVi, partOfSpeech, state}`.
  - `remainingNewAllowance = new_cards_per_day − (new cards already introduced today, i.e. state rows created_at::date = today)`.
- `POST /api/study/review {wordId, rating}` (rating ∈ AGAIN|HARD|GOOD|EASY) → load/lazy-create state for user+word, run `Sm2Scheduler`, persist, return updated `{wordId, state, intervalDays, dueOn, repetitions, easeFactor}`.
- `GET /api/study/settings` → `{newCardsPerDay, maxReviewsPerDay}` (create defaults row on first read).
- `PUT /api/study/settings {newCardsPerDay, maxReviewsPerDay}` → upsert.

### Non-functional
- `Sm2Scheduler` is a pure function: input immutable `Sm2Input(ease, interval, reps, lapses, rating)` → output `Sm2Result(ease, interval, reps, lapses, state)`. No repo, no clock inside (caller applies `due_on`). <200 LOC. 100% unit-testable.
- All study files <200 LOC; modularize (scheduler / queue-builder / review-service / settings-service).

## Architecture
### Packages
```
com.engly.study.application     Sm2Scheduler (pure), StudyClock,
                                StudyQueueService, ReviewService, StudySettingsService, StudyDeckService
com.engly.study.api             StudyController (or split: StudyDeckController, StudyReviewController, StudySettingsController)
com.engly.study.api.dto         StudyDeckSummary, StudyCardResponse, ReviewRequest, ReviewResultResponse,
                                StudySettingsRequest, StudySettingsResponse, ReviewRating (enum)
```
Split controllers if a single `StudyController` would exceed 200 LOC (likely — prefer 3 small controllers).
### Component interactions
Controller (reads `OidcUser.getSubject()`) → Service (`@Transactional`) → JpaRepositories + `Sm2Scheduler` + `StudyClock`.
### Data flow (review)
`POST /api/study/review` → `ReviewService.review(sub, wordId, rating)`:
1. `state = repo.findByUserSubAndVocabWordId(sub, wordId).orElseGet(() -> newCard(sub, wordId))`.
2. `result = sm2Scheduler.schedule(new Sm2Input(state.ease, state.interval, state.reps, state.lapses, rating))`.
3. `dueOn = studyClock.today().plusDays(result.interval())`.
4. `state.applyScheduling(result.state(), result.ease(), result.interval(), result.reps(), result.lapses(), dueOn, studyClock.now())`; save.
5. return `ReviewResultResponse`.

### SecurityConfig change (Chain 2 only)
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/verbs/**").permitAll()
    .requestMatchers("/oauth2/**", "/login/**").permitAll()
    .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
    .requestMatchers("/api/me", "/api/token").authenticated()
    .requestMatchers("/api/study/**").authenticated()   // ← NEW (any logged-in user)
    .anyRequest().permitAll())
```
CSRF stays on for study mutations (session chain) → SPA auto-sends `X-XSRF-TOKEN` (already wired). `POST /api/study/review` and `PUT /settings` need the XSRF header — FE interceptor handles it (P4).

## Related Code Files
### Create
- `engly-be/src/main/java/com/engly/study/application/sm2-scheduler` → `Sm2Scheduler.java` (PURE)
- `engly-be/src/main/java/com/engly/study/application/StudyClock.java`
- `engly-be/src/main/java/com/engly/study/application/StudyDeckService.java`
- `engly-be/src/main/java/com/engly/study/application/StudyQueueService.java`
- `engly-be/src/main/java/com/engly/study/application/ReviewService.java`
- `engly-be/src/main/java/com/engly/study/application/StudySettingsService.java`
- `engly-be/src/main/java/com/engly/study/api/StudyDeckController.java`
- `engly-be/src/main/java/com/engly/study/api/StudyReviewController.java`
- `engly-be/src/main/java/com/engly/study/api/StudySettingsController.java`
- `engly-be/src/main/java/com/engly/study/api/dto/ReviewRating.java` (enum AGAIN/HARD/GOOD/EASY)
- `engly-be/src/main/java/com/engly/study/api/dto/Sm2Input.java` + `Sm2Result.java` (records; in application pkg if preferred)
- `engly-be/src/main/java/com/engly/study/api/dto/StudyDeckSummary.java`
- `engly-be/src/main/java/com/engly/study/api/dto/StudyCardResponse.java`
- `engly-be/src/main/java/com/engly/study/api/dto/ReviewRequest.java`
- `engly-be/src/main/java/com/engly/study/api/dto/ReviewResultResponse.java`
- `engly-be/src/main/java/com/engly/study/api/dto/StudySettingsRequest.java`
- `engly-be/src/main/java/com/engly/study/api/dto/StudySettingsResponse.java`

### Modify
- `engly-be/src/main/java/com/engly/common/SecurityConfig.java` — add `/api/study/**` matcher (Chain 2).
- `engly-be/src/main/java/com/engly/study/infrastructure/UserCardStateJpaRepository.java` — add: count/find due by user+deck, find words-without-state per deck (JPQL or two-step), count new introduced today.
- `engly-be/src/main/java/com/engly/study/infrastructure/UserStudySettingsJpaRepository.java` — (PK String, default derived ok).
- `engly-be/src/main/java/com/engly/vocab/infrastructure/VocabWordJpaRepository.java` — add `findByDeckId` (if not from P2) + count by deck.

### Delete
- None.

## Implementation Steps
1. **StudyClock**: `@Component` with `ZoneId SAIGON = ZoneId.of("Asia/Saigon")`; `LocalDate today()`, `Instant now()`. Constructor-injectable `Clock` for test override (default `Clock.systemDefaultZone()` → but compute via `LocalDate.now(SAIGON)`).
2. **ReviewRating** enum + mapping to scheduler branches.
3. **Sm2Scheduler** pure service implementing the brainstorm mapping exactly (see Key Insights). Methods per rating or a switch. Enforce floors. Return `Sm2Result`.
4. **StudyQueueService**: build queue — query due cards (user+deck, due_on<=today, state in LEARNING/REVIEW), then new words (deck words lacking state for user), lazy-create NEW state rows up to remaining allowance, map to `StudyCardResponse`. Order: due first, then new.
5. **StudyDeckService**: per-deck counts via repo count queries.
6. **ReviewService**: as data-flow above; validate word exists (else 404 `VocabWordNotFoundException`).
7. **StudySettingsService**: `get(sub)` → find or create defaults; `update(sub, req)` upsert + `updatedAt`.
8. **Controllers** (3 small): inject `@AuthenticationPrincipal OidcUser user`; pass `user.getSubject()` to services. Zero-based paging not needed (queue is bounded). Validation on `ReviewRequest`/`StudySettingsRequest`.
9. **SecurityConfig**: add matcher; verify ordering (study before catch-all, admin chain untouched).
10. Repository queries (JPQL parameterized).
11. Compile + smoke.

## Todo List
- [ ] StudyClock (Asia/Saigon, test-injectable Clock)
- [ ] ReviewRating enum
- [ ] Sm2Scheduler pure service (brainstorm mapping, floors)
- [ ] Sm2Input / Sm2Result records
- [ ] StudyQueueService (due + lazy-create new, allowance cap)
- [ ] StudyDeckService (counts)
- [ ] ReviewService (compute + persist)
- [ ] StudySettingsService (get-or-default + upsert)
- [ ] 3 study controllers (OidcUser.getSubject())
- [ ] study DTOs
- [ ] SecurityConfig /api/study/** matcher
- [ ] Repository queue/count queries
- [ ] Compile/build check

## Success Criteria
- `./mvnw -q compile` passes.
- Logged-in user: `GET /api/study/decks` returns counts; `GET .../queue` returns ≤ limit new + due; `POST /review` reschedules and `due_on` advances; settings GET/PUT roundtrip.
- `/api/study/**` returns 401 when anonymous, 200 when session-authenticated (any role).
- Scheduler is pure (no Spring beans referenced inside) → unit-testable without context.

## Risk Assessment
- **Off-by-one due dates**: mitigated by single `StudyClock.today()` source + integration test (P06) asserting due card reappears on `due_on`.
- **New-card allowance double counting**: count introduced-today by `created_at::date = today`; ensure lazy-create timestamps are server-side (`insertable=false` default now()) — query by DB date in Saigon TZ (cast with `AT TIME ZONE`). Document TZ in query.
- **Queue N+1**: fetch words + states in batched queries, not per-card.
- **Scheduler/brainstorm vs research drift**: explicitly follow brainstorm; document divergence in code comment.

## Security Considerations
- `/api/study/**` requires authentication (Chain 2 session). User can only ever read/write their own `user_sub` (derived server-side from `OidcUser`, never from request body) — prevents IDOR.
- CSRF active for study mutations (cookie chain); SPA echoes `X-XSRF-TOKEN`.
- No admin escalation: study endpoints never check/return admin data.

## Next Steps
- Unblocks **P4** (frontend study UI). Scheduler unit tests + study integration tests in **P06**.

## Compile/Build Check
```
cd engly-be && ./mvnw -q -DskipTests compile
```
