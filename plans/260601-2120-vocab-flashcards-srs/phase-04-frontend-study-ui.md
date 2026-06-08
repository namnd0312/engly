# Phase 04 — Frontend Study UI

## Context Links
- Spec: `../reports/brainstorm-260601-2120-vocab-flashcards-srs.md` (§7 Frontend `features/study`)
- Auth research: `research/researcher-02-auth-patterns.md` (generic `authGuard`)
- Mirror: `engly-fe/src/app/features/verb-search/**`, `core/auth.service.ts`, `features/admin/admin.guard.ts`, `app.routes.ts`, `app.component.html`
- Depends on: **P3** study API (`/api/study/**`).

## Overview
- **Date:** 2026-06-01
- **Priority:** P1 (user-facing core)
- **Current status:** pending
- **Brief:** New `features/study` area: deck-list (progress counts), flashcard-session (flip animation + 4 rating buttons + progress bar; signals for queue/index/flip), study-settings. New generic functional `authGuard` (reuses `auth.ensureLoaded()`/`isAuthenticated`, redirects anon to OAuth2). Header nav "Học từ vựng". Study api-service + models mirroring verb-api patterns (`withCredentials:true`).

## Key Insights
- `authGuard` = `adminGuard` minus the role check (anon → `auth.login()`; authenticated → allow). Place generically (e.g. `core/auth.guard.ts`) so future areas reuse it.
- Mutations (`POST /review`, `PUT /settings`) go through the **session chain** → need `X-XSRF-TOKEN` (global XSRF interceptor in `app.config.ts` auto-attaches from cookie). Confirm interceptor covers `/api/study`.
- Flip + buttons: card state via signals (`queue`, `index`, `flipped`); on rating → call API, advance index, reset flip. "All done" empty state when queue empty.
- Custom component-scoped CSS, indigo `#6366f1` accent / dark `#1a1a2e`, mirroring existing components (no UI lib).
- Routes lazy-loaded via `study.routes.ts`, guarded at parent.

## Requirements
### Functional
- `/study` deck list: cards per deck showing new/due/learned counts + "Study" button (disabled if 0 new + 0 due).
- `/study/:deckId` flashcard session: fetch queue; show term (front); flip to reveal meaning/ipa/example; 4 buttons Again/Hard/Good/Easy → submit rating, advance; progress bar (index/total); completion screen.
- `/study/settings`: form for newCardsPerDay (int>=0) + maxReviewsPerDay (nullable/blank=unlimited); save → PUT.
- Anonymous visiting `/study/**` → redirected to OAuth2 login.

### Non-functional
- Files <200 LOC; split components + api-service + models.
- Signals + RxJS; standalone components; OnPush implicit.
- Jasmine specs authored in **P06** (component shells testable).

## Architecture
### Layout
```
core/auth.guard.ts                         authGuard (generic CanActivateFn)
features/study/study.routes.ts
features/study/data/study-api.service.ts
features/study/data/study.model.ts         StudyDeckSummary, StudyCard, ReviewResult, StudySettings, ReviewRating
features/study/deck-list-page.component.{ts,html,css}
features/study/flashcard-session-page.component.{ts,html,css}
features/study/study-settings-page.component.{ts,html,css}
```
### Component interactions
Pages inject `StudyApiService` (HttpClient, `withCredentials`). Session component owns signals: `queue = signal<StudyCard[]>([])`, `index = signal(0)`, `flipped = signal(false)`, `done = computed(...)`. Rating click → `api.review({wordId, rating})` → on success advance.
### Data flow
`GET /api/study/decks` → deck list. Select deck → `GET /api/study/decks/{id}/queue` → session. Each rating → `POST /api/study/review`. Settings page → `GET/PUT /api/study/settings`.
### Routing (app.routes.ts add)
```ts
{ path: 'study', canActivate: [authGuard],
  loadChildren: () => import('./features/study/study.routes').then(m => m.studyRoutes) }
```
`study.routes.ts`: `''`→deck-list, `settings`→settings, `:deckId`→flashcard-session.

## Related Code Files
### Create
- `engly-fe/src/app/core/auth.guard.ts`
- `engly-fe/src/app/features/study/study.routes.ts`
- `engly-fe/src/app/features/study/data/study-api.service.ts`
- `engly-fe/src/app/features/study/data/study.model.ts`
- `engly-fe/src/app/features/study/deck-list-page.component.ts`
- `engly-fe/src/app/features/study/deck-list-page.component.html`
- `engly-fe/src/app/features/study/deck-list-page.component.css`
- `engly-fe/src/app/features/study/flashcard-session-page.component.ts`
- `engly-fe/src/app/features/study/flashcard-session-page.component.html`
- `engly-fe/src/app/features/study/flashcard-session-page.component.css`
- `engly-fe/src/app/features/study/study-settings-page.component.ts`
- `engly-fe/src/app/features/study/study-settings-page.component.html`
- `engly-fe/src/app/features/study/study-settings-page.component.css`

### Modify
- `engly-fe/src/app/app.routes.ts` — add `/study` route (authGuard, lazy children).
- `engly-fe/src/app/app.component.html` — add "Học từ vựng" nav button (visible when `isAuthenticated()`), wire `goToStudy()`.
- `engly-fe/src/app/app.component.ts` — add `goToStudy()` navigation method.
- `engly-fe/src/app/app.config.ts` — verify global XSRF interceptor covers `/api/study` mutations (likely no change; confirm only).

### Delete
- None.

## Implementation Steps
1. **authGuard** (`core/auth.guard.ts`): `await auth.ensureLoaded(); if (auth.isAuthenticated()) return true; auth.login(); return false;`.
2. **study.model.ts**: interfaces matching P3 DTOs + `type ReviewRating = 'AGAIN'|'HARD'|'GOOD'|'EASY'`.
3. **study-api.service.ts**: `getDecks()`, `getQueue(deckId)`, `review(wordId, rating)`, `getSettings()`, `updateSettings(req)` — all `withCredentials:true`, base `/api/study`.
4. **deck-list-page**: load decks on init; render cards with counts; Study button → `router.navigate(['/study', deckId])`; settings link.
5. **flashcard-session-page**: read `deckId` param; load queue; signals queue/index/flipped; template shows front/back with flip CSS (3D transform or simple class toggle), progress bar `index+1 / total`, 4 buttons; on rating call api then advance; completion state + "back to decks".
6. **study-settings-page**: reactive form/signal-backed inputs; load current; save → PUT; success/error feedback.
7. **Routing**: add `/study` to `app.routes.ts` + create `study.routes.ts` children.
8. Header nav + `goToStudy()`.
9. Confirm XSRF interceptor coverage; build.

## Todo List
- [ ] authGuard (core)
- [ ] study.model + study-api.service
- [ ] deck-list-page component (+html/css)
- [ ] flashcard-session-page component (flip + 4 buttons + progress + done state)
- [ ] study-settings-page component
- [ ] study.routes.ts + app.routes.ts wiring
- [ ] Header "Học từ vựng" nav + goToStudy()
- [ ] Verify XSRF interceptor for /api/study
- [ ] Build check (ng build)

## Success Criteria
- `npm run build` (or `ng build`) succeeds, no TS errors.
- Logged-in user: deck list shows counts; session flips, rates, advances, reaches done; settings persist.
- Anonymous → `/study` triggers OAuth2 login redirect.
- Review POST carries XSRF token (no 403).

## Risk Assessment
- **XSRF on study POST**: if interceptor scoped only to certain paths, study mutations 403 → verify/extend interceptor.
- **Session expiry mid-session**: review POST → 401; FE should surface "session expired, please log in" (catch 401 → `auth.login()`).
- **Empty queue**: render "all done / no cards due" instead of blank.
- **Flip animation perf**: pure CSS transform, no heavy JS.

## Security Considerations
- No tokens in JS (BFF cookie). `withCredentials:true` everywhere.
- authGuard is UX-only; backend `/api/study/**` authenticated() is the real gate.
- Never send `user_sub` from client — server derives it.

## Next Steps
- Runs in **parallel with P5**. Feeds **P06** (FE specs).

## Compile/Build Check
```
cd engly-fe && npm run build
```
