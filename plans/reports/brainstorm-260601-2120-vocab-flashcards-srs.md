# Brainstorm — Vocabulary Learning + Flashcards + Spaced Repetition (SRS)

**Date:** 2026-06-01 · **Project:** Engly · **Status:** Agreed, ready for `/plan`

---

## 1. Problem Statement

Add a feature to **learn new words** and **review words** via flashcards, practiced with the **Spaced Repetition** method. Current app is irregular-verbs dictionary only (read-only lookup + admin CRUD); no progress/review/SRS anything. Greenfield.

## 2. Locked Decisions (from discovery)

| Topic | Decision |
|-------|----------|
| Content source | **New general vocabulary table** (any word, not just verbs) |
| SRS algorithm | **SM-2** (Anki classic) |
| Review interaction | **Self-rated recall** — Again / Hard / Good / Easy |
| Progress storage | **Login required, backend per-user**, keyed on OIDC `sub` |
| Content owner | **Admin-curated, shared** (mirror existing verb admin CRUD) |
| Organization | **By deck/topic** (e.g. Travel, Business, IELTS) |
| Daily limit | **User-adjustable** → requires per-user settings table |

## 3. Current Architecture (baseline to build on)

- **BE:** Spring Boot 3.4.5, layered (domain/application/api), Flyway (`V<N>__*.sql`), Postgres, Testcontainers + JUnit5. Auth = stateless OIDC, **no User table** — identity from `sub` claim. `GlobalExceptionHandler`, record DTOs, zero-based paging.
- **FE:** Angular 19 standalone, signals + RxJS, custom component-scoped CSS, BFF OAuth2 + httpOnly cookie, `adminBearerInterceptor`, `adminGuard`. No state lib.

## 4. Data Model (new — Flyway V3+)

```
vocab_deck         id, name, slug(uniq), description, topic, created_at
vocab_word         id, deck_id FK, term, meaning_vi, ipa(null),
                   example_en(null), example_vi(null), part_of_speech(null),
                   verified bool default false, created_at
user_card_state    id, user_sub, vocab_word_id FK,
                   state(NEW|LEARNING|REVIEW), ease_factor default 2.5,
                   interval_days, repetitions, lapses,
                   due_on(date), last_reviewed_at, created_at
                   UNIQUE(user_sub, vocab_word_id)
user_study_settings  user_sub PK, new_cards_per_day default 20,
                     max_reviews_per_day(null=∞), updated_at
user_review_log    id, user_sub, vocab_word_id, rating,
                   prev_interval, new_interval, reviewed_at   -- OPTIONAL (analytics/streak)
```

**Identity key decision:** store OIDC `sub` **string directly** as `user_sub` (KISS/YAGNI) — no `user_account` table. Trade-off: if IdP `sub` ever changes, history orphans. Acceptable for now; a `user_account(sub uniq)` table can be added later without breaking the model.

## 5. SM-2 Mapping (4-button)

Computed **on backend** (authoritative). On each review of a card with `(ease, interval, reps)`:
- **Again** (fail): reps=0, interval=1d (learning), ease = max(1.3, ease−0.20), lapses++
- **Hard:** ease = max(1.3, ease−0.15), interval = max(1, round(interval×1.2))
- **Good:** reps==0 → 1d; reps==1 → 6d; else round(interval×ease); reps++
- **Easy:** ease += 0.15; interval = round(interval×ease×1.3); reps++
- `due_on = today + interval_days`. New cards introduced up to `new_cards_per_day`.

## 6. Approaches Evaluated

### A. Backend-authoritative SRS — **RECOMMENDED**
Backend owns scheduling + SM-2; FE is thin (render card, send rating).
- ✅ Single source of truth, cross-device consistent, can't be cheated/desynced, testable in isolation (Testcontainers), matches existing layered pattern.
- ⚠️ Slightly more BE endpoints. Network round-trip per rating (trivial).

### B. Frontend-computed SRS, backend persists blob
FE runs SM-2, posts resulting state.
- ✅ Fewer BE endpoints, offline-friendlier.
- ❌ Logic in TS (harder to unit-test the scheduler vs JUnit), cross-device drift risk, business rule lives in client = anti-pattern for authoritative progress. **Rejected.**

### C. Extend `verb` module vs new `vocab`/`study` module
- New **`vocab`** module (admin CRUD content) + **`study`** module (per-user SRS) mirrors existing `verb` layering. Keeps verbs untouched, DRY via shared conventions. **Chosen.** (Reusing verb table was rejected at discovery — content scope = general vocab.)

## 7. Recommended Solution (API + UI)

**Backend — `vocab` module (admin, mirrors verb admin):**
- `GET/POST/PUT/DELETE /api/admin/decks`, `.../decks/{id}` (+ `PATCH verified` if needed)
- `GET/POST/PUT/DELETE /api/admin/vocab-words` (filter by deck)

**Backend — `study` module (authenticated user):**
- `GET  /api/study/decks` → decks + per-user counts (new / due / learned)
- `GET  /api/study/decks/{id}/queue` → today's session: due reviews + new (≤ limit)
- `POST /api/study/review` `{wordId, rating}` → compute SM-2, persist, return updated state
- `GET/PUT /api/study/settings` → daily limits
- `GET  /api/study/stats` (optional) → due count / streak

**Frontend — new `features/study` (+ admin deck/word screens):**
- New **`authGuard`** (auth-only, generalizes existing `adminGuard`).
- Routes: `/study` (deck list w/ progress), `/study/:deckId` (flashcard session), `/study/settings`.
- Components: `deck-list`, `flashcard-session` (flip animation + 4 rating buttons + progress bar), `study-settings`. Signals for queue/index/flip state; reuse api-service + custom CSS patterns.
- Admin: `decks` + `vocab-words` CRUD pages mirroring existing verb-admin (reuse `adminBearerInterceptor`).
- Header nav: add **"Học từ vựng"** entry.

## 8. Implementation Considerations & Risks

- **Auth surface change:** first auth-required-but-not-admin area → need generic `authGuard` (FE) + `/api/study/**` requires authenticated (BE `SecurityConfig`).
- **Timezone:** `due_on` is a date → compute "today" in a fixed server TZ (Asia/Saigon) to avoid off-by-one. Document it.
- **Empty/edge:** no due cards → "all done" state; deck with 0 words; first-ever card creation (lazy-create `user_card_state` on first see).
- **Seed data:** ship 1–2 starter decks (Flyway seed, `verified=false` like verbs) so feature is demoable.
- **Scope creep guard (YAGNI):** no audio TTS, no reverse cards, no leech detection, no FSRS, no import/export in MVP. `user_review_log` optional — include only if streak/stats wanted now.
- **Modularization:** keep files <200 LOC; SM-2 in its own `sm2-scheduler` service (pure, unit-tested).

## 9. Success Metrics / Validation

- User logs in → picks deck → studies new cards → rates → cards reschedule per SM-2; due cards reappear on/after `due_on`.
- Settings change to new-cards/day takes effect next session.
- Backend SM-2 unit tests (each rating path) + integration tests (Testcontainers) green.
- Cross-device: same user sees consistent due queue.

## 10. Next Steps

1. Run `/plan` with this summary → phased plan (migrations → vocab admin → study/SRS API → FE study UI → tests/docs).
2. Confirm starter deck topics + seed word count.

## 11. Unresolved Questions

1. **`user_review_log` in MVP?** Needed only if you want streaks/stats now. Default: skip until stats feature requested.
2. **Starter decks:** which topics + how many seed words each (e.g. 2 decks × 20 words)?
3. **Card direction:** front = `term` → back = `meaning_vi` (recommended). Want reverse (meaning→term) too? (MVP: one direction.)
4. **Daily-limit default value** before user customizes (proposed: 20 new/day, reviews unlimited).
