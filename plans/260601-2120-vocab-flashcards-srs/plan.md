---
title: "Vocabulary Learning + Flashcards + Spaced Repetition (SRS)"
description: "Admin-curated vocab decks + per-user SM-2 flashcard study (backend-authoritative), Angular study UI + admin CRUD."
status: reviewed
priority: P2
effort: 34h
branch: main
tags: [backend, frontend, srs, vocabulary, angular, spring-boot]
created: 2026-06-01
---

# Vocabulary Learning + Flashcards + Spaced Repetition (SRS)

Add a vocabulary-learning feature: admin-curated decks/words + per-user flashcard study driven by **SM-2** (computed backend-side, authoritative). Login required; per-user progress keyed on OIDC `sub` (string, no user table). User-adjustable daily new-card limit. Mirrors existing `verb` module layering (BE) and `verb-search` / `admin` patterns (FE).

**Authoritative spec:** `../reports/brainstorm-260601-2120-vocab-flashcards-srs.md`
**Research:** `research/researcher-01-sm2-algorithm.md`, `research/researcher-02-auth-patterns.md`

## Locked Decisions (do not re-litigate)
- New `vocab` (content, admin) + `study` (per-user SRS) BE modules; verbs untouched.
- SM-2 backend-authoritative. 4 buttons: Again / Hard / Good / Easy. Mapping per brainstorm (Again=reset+EF−0.20; Hard=EF−0.15 & interval×1.2; Good=standard SM-2; Easy=EF+0.15 & interval bonus). Brainstorm WINS over research where they differ.
- `user_sub` = OIDC `sub` string directly via `@AuthenticationPrincipal OidcUser.getSubject()` (BFF cookie session). No `user_account` table.
- Fixed TZ **Asia/Saigon** for `due_on` / "today" math; centralize in a clock/date helper.
- Defaults: skip `user_review_log` (MVP); seed 2 decks × 20 words (Travel, Business; `verified=false`); term→meaning direction only; 20 new/day, reviews unlimited.

## YAGNI Fence
NO: TTS, reverse cards, leech/suspend, FSRS, import/export, `user_review_log`, stats/streak endpoints.

## Phases

| # | Phase | Status | Description |
|---|-------|--------|-------------|
| 01 | [Database migrations + entities](phase-01-database-migrations-and-entities.md) | complete | Flyway V3 (4 tables) + V4 seed (2×20 words); JPA entities mirroring `IrregularVerb`. |
| 02 | [Vocab admin CRUD API](phase-02-vocab-admin-crud-api.md) | complete | `/api/admin/decks` + `/api/admin/vocab-words` CRUD, ROLE_ADMIN, paging, validation, slug→409. |
| 03 | [SM-2 scheduler + study API](phase-03-sm2-scheduler-and-study-api.md) | complete | Pure `sm2-scheduler` + `/api/study/**` (decks/queue/review/settings); SecurityConfig matcher; TZ helper. |
| 04 | [Frontend study UI](phase-04-frontend-study-ui.md) | complete | `features/study`: deck-list, flashcard-session (flip + 4 buttons), settings; generic `authGuard`; header nav. |
| 05 | [Frontend admin deck/word CRUD](phase-05-frontend-admin-deck-word-crud.md) | complete | Admin pages for decks + vocab-words mirroring verb-admin (adminBearerInterceptor). |
| 06 | [Tests + docs](phase-06-tests-and-docs.md) | complete | SM-2 unit tests, Testcontainers integration, FE Jasmine specs, docs update. |

## Dependency Chain
```
P1 ──► P2 ──► P5 ┐
  └──► P3 ──► P4 ┴──► P6
```
- P3 needs P1 entities; P2 needs P1 entities.
- P4 needs P3 (study API). P5 needs P2 (vocab admin API).
- **P4 and P5 run in parallel** once their BE deps land.
- P6 (tests/docs) after P4 + P5.

## Key Dependencies
- BE: Spring Boot 3.4.5, Java 21, Flyway (NEXT migration = **V3**, seed = **V4**), Postgres, Testcontainers + JUnit5, Spring Security OIDC.
- FE: Angular 19 standalone, signals + RxJS, `adminBearerInterceptor`, global XSRF interceptor, custom CSS (indigo `#6366f1` / dark `#1a1a2e`).

## Validation Summary

**Validated:** 2026-06-01 · **Questions asked:** 4

### Confirmed Decisions
- **Seed words:** AI-generated 40 EN→VI pairs (Travel + Business), `verified=false` — matches plan default. No blocker.
- **Duplicate slug→409:** reuse shared `GlobalExceptionHandler`, generalize conflict message to "resource already exists"; update 1 existing verb test assertion in P6. Confirmed.
- **Mid-session 401:** keep progress, show "session expired" + re-login button; un-submitted rating is lost (no silent redirect). Confirmed → P4 error path.
- **Entity mapping:** ⚠️ **CHANGED from plan** — use `@ManyToOne` JPA relationship `VocabWord → VocabDeck` (planner had chosen flat `Long deckId`).

### Action Items (revise before/during P1)
- [ ] **P1:** `VocabWord` entity uses `@ManyToOne(fetch = LAZY) @JoinColumn(name="deck_id") VocabDeck deck` instead of flat `Long deckId`. Keep FK column `deck_id` + index unchanged (migration V3 unaffected).
- [ ] **P1/P2/P3 ripple:** repositories query via `deck` relationship (e.g. `findByDeckId`); DTOs still expose flat `deckId` (map `word.getDeck().getId()`); guard against N+1 in `queue`/admin list (use `@EntityGraph` or explicit fetch join where listing words). Add this note to phase-01 + phase-02 architecture sections.
- [ ] **P4:** implement mid-session 401 handler in study api-service / flashcard-session error path (preserve current card, re-login CTA).

## Unresolved Questions
1. OPEN-GATE `sub` claim guaranteed string UUID (not composite)? Assumed yes (`getSubject()`); if composite, `user_sub` column length 100 may need raise.
2. IdP session timeout value unknown — affects how often mid-review 401 occurs (handling now confirmed regardless).
