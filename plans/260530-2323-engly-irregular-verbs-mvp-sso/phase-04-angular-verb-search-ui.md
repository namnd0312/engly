# Phase 04 — Angular Verb-Search UI

## Context Links
- [plan.md](plan.md)
- [Phase 03 (API contract)](phase-03-verb-lookup-api-backend.md)
- [Phase 01 (proxy)](phase-01-setup-monorepo-and-skeleton.md)

## Overview
- **Date:** 2026-05-30
- **Priority:** P1 (core MVP, user-facing)
- **Effort:** 7h
- **Implementation status:** complete
- **Review status:** not reviewed
- **Description:** Angular 19 standalone `verb-search` feature (lazy-loaded): debounced search box, results card/table (base/past/pp + IPA + meaning_vi), detail view. HttpClient withCredentials. Auth wiring deferred to phase 05 (lookup must NOT depend on auth).

## Key Insights
- Standalone components + signals. Lazy-load feature route. RxJS `debounceTime + distinctUntilChanged + switchMap` for search.
- Same-origin via dev proxy (phase 01) → call relative `/api/...`, no CORS handling needed.
- `withCredentials: true` set now so session cookie flows once SSO lands (phase 05) — harmless for public calls.

## Requirements
**Functional**
- Search box: type → debounced (300ms) query → results list updates.
- Results show base_form, past_simple(+alt), past_participle(+alt), IPA (base/past/pp), meaning_vi.
- Click result → detail view (route `/verbs/:baseForm`).
- Empty/no-result + loading + error states handled.
**Non-functional**
- Standalone components, signals for state. Files < 200 lines. Relative `/api` URLs. withCredentials true.

## Architecture
```
frontend/src/app/
├── app.routes.ts                 (lazy: '' → verb-search feature)
├── core/
│   └── http-with-credentials.ts  (provideHttpClient + withCredentials interceptor/config)
└── features/verb-search/
    ├── verb-search.routes.ts     (list + detail)
    ├── data/
    │   ├── verb.model.ts         (VerbResponse, VerbSearchPageResponse interfaces — match phase 03 DTO)
    │   └── verb-api.service.ts    (search(q,page,size), getByBaseForm(b))
    ├── verb-search-page.component.ts/.html  (search box + results, signals)
    └── verb-detail-page.component.ts/.html  (detail by baseForm)
```
- `verb-api.service.ts`: `httpClient.get('/api/verbs', {params})` + `get('/api/verbs/'+baseForm)`, `withCredentials:true`.
- Search component: `searchControl` (FormControl) → valueChanges pipe(debounceTime,distinctUntilChanged,switchMap → service) → results signal; loading/error signals.
- Detail component: route param → service.getByBaseForm → signal; 404 → "not found" message.

## Related Code Files
**Create**
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/data/verb.model.ts`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/data/verb-api.service.ts`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/verb-search-page.component.ts`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/verb-search-page.component.html`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/verb-detail-page.component.ts`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/verb-detail-page.component.html`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/verb-search.routes.ts`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/core/http-with-credentials.ts`
**Modify**
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/app.routes.ts` (lazy-load verb-search)
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/app.config.ts` (provideHttpClient withCredentials)
**Delete:** generated boilerplate sample component if unused.

## Implementation Steps
1. Configure `app.config.ts`: `provideHttpClient(withFetch())` + global withCredentials (interceptor or per-call); `provideRouter`.
2. `verb.model.ts`: interfaces mirroring phase 03 DTOs exactly (field names).
3. `verb-api.service.ts`: injectable, methods `search(q,page,size)` + `getByBaseForm(baseForm)` returning typed Observables; withCredentials.
4. `verb-search-page.component.ts`: FormControl + RxJS debounce pipeline → signals (results, loading, error); template renders card/table + IPA + meaning_vi; link each row to detail.
5. `verb-detail-page.component.ts`: read route param, fetch detail, render; handle 404 state.
6. `verb-search.routes.ts`: routes for list (`''`) + detail (`':baseForm'`); wire lazy in `app.routes.ts`.
7. Run `ng serve` (proxy active) with backend up → manually verify search "go" + detail.
8. `ng build` to confirm no compile errors.

## Todo List
- [x] app.config.ts HttpClient + withCredentials
- [x] verb.model.ts (match API DTO)
- [x] verb-api.service.ts (search + detail)
- [x] verb-search-page component (debounced search, signals, states)
- [x] verb-detail-page component (404 handling)
- [x] routes + lazy-load wiring
- [x] Manual verify via proxy against running backend
- [x] ng build passes

## Success Criteria
- Typing "go" shows go/went/gone + IPA + meaning_vi after debounce.
- Clicking a result opens detail route; unknown baseForm → friendly not-found.
- Loading + error states visible. No CORS errors (proxy same-origin).
- `ng build` clean; components standalone, files < 200 lines.

## Risk Assessment
- Proxy misconfig → 404/HTML instead of JSON. Verify proxy.conf.json + serve config from phase 01. *Low.*
- DTO field drift vs backend → mirror exactly; single source = phase 03 contract. *Low.*
- Over-fetching on each keystroke → debounce + distinctUntilChanged + switchMap (cancels stale). *Low.*

## Security Considerations
- No tokens in JS/storage (BFF model); only httpOnly cookie via withCredentials (matters after phase 05).
- Render meaning_vi/IPA as text (Angular auto-escapes) — no innerHTML.

## Next Steps
- Phase 05 adds AuthService + login/logout buttons (optional, non-blocking).
- Phase 06 adds component tests for search + detail.
