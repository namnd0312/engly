# Phase 05 — Frontend Admin Deck/Word CRUD

## Context Links
- Spec: `../reports/brainstorm-260601-2120-vocab-flashcards-srs.md` (§7 Admin screens)
- Mirror: `engly-fe/src/app/features/admin/admin-list-page.component.ts`, `admin-form-page.component.ts`, `data/admin-verb-api.service.ts`, `data/admin-verb.model.ts`, `admin.routes.ts`
- Interceptor: `engly-fe/src/app/core/admin-bearer.interceptor.ts`
- Depends on: **P2** vocab admin API (`/api/admin/decks`, `/api/admin/vocab-words`).

## Overview
- **Date:** 2026-06-01
- **Priority:** P2
- **Current status:** pending
- **Brief:** Admin pages for decks + vocab-words mirroring existing verb-admin screens. Reuse `adminBearerInterceptor` (Bearer JWT auto-attach for `/api/admin/**`) and admin api-service pattern. Routes under `/admin`, parent already `adminGuard`-protected.

## Key Insights
- `/admin` parent route in `app.routes.ts` already `canActivate: [adminGuard]` — child routes need no extra guard.
- `adminBearerInterceptor` attaches `Authorization: Bearer <token>` to `/api/admin/**` calls; new admin api-services automatically benefit (same base path).
- Mirror exact list+form pattern: a list page (paged table, search, verified toggle, edit/delete/new) + a form page (create/edit shared). Two resources → either two list/form pairs or a tabbed admin home. **KISS: two list pages + two form pages**, linked from existing admin landing.
- Words form needs a deck selector (dropdown populated from `GET /api/admin/decks`).

## Requirements
### Functional
- Deck admin: list (paged, search by name/topic), create, edit, delete.
- Word admin: list (paged, search, filter by deck), create (deck dropdown), edit, delete, verified toggle.
- Navigation between admin sections (verbs, decks, words).

### Non-functional
- Files <200 LOC; split list/form/service/model per resource.
- Custom CSS mirroring existing admin pages.
- Specs in **P06**.

## Architecture
### Layout
```
features/admin/data/admin-deck-api.service.ts        + admin-deck.model.ts
features/admin/data/admin-vocab-word-api.service.ts  + admin-vocab-word.model.ts
features/admin/deck-admin-list-page.component.{ts,html,css}
features/admin/deck-admin-form-page.component.{ts,html,css}
features/admin/vocab-word-admin-list-page.component.{ts,html,css}
features/admin/vocab-word-admin-form-page.component.{ts,html,css}
```
### Component interactions
List page → api-service `list(q,page,size[,deckId])`; form page → `get/create/update`; toggle → `toggleVerified`. Word form loads deck options from `AdminDeckApiService.list`.
### Data flow
Mirrors verb admin: list → paged table; edit row → form (prefilled via `get(id)`); save → create/update → back to list.
### Routing (admin.routes.ts add)
```
decks                → deck-admin-list
decks/new            → deck-admin-form
decks/:id/edit       → deck-admin-form
vocab-words          → vocab-word-admin-list
vocab-words/new      → vocab-word-admin-form
vocab-words/:id/edit → vocab-word-admin-form
```

## Related Code Files
### Create
- `engly-fe/src/app/features/admin/data/admin-deck-api.service.ts`
- `engly-fe/src/app/features/admin/data/admin-deck.model.ts`
- `engly-fe/src/app/features/admin/data/admin-vocab-word-api.service.ts`
- `engly-fe/src/app/features/admin/data/admin-vocab-word.model.ts`
- `engly-fe/src/app/features/admin/deck-admin-list-page.component.ts`
- `engly-fe/src/app/features/admin/deck-admin-list-page.component.html`
- `engly-fe/src/app/features/admin/deck-admin-list-page.component.css`
- `engly-fe/src/app/features/admin/deck-admin-form-page.component.ts`
- `engly-fe/src/app/features/admin/deck-admin-form-page.component.html`
- `engly-fe/src/app/features/admin/deck-admin-form-page.component.css`
- `engly-fe/src/app/features/admin/vocab-word-admin-list-page.component.ts`
- `engly-fe/src/app/features/admin/vocab-word-admin-list-page.component.html`
- `engly-fe/src/app/features/admin/vocab-word-admin-list-page.component.css`
- `engly-fe/src/app/features/admin/vocab-word-admin-form-page.component.ts`
- `engly-fe/src/app/features/admin/vocab-word-admin-form-page.component.html`
- `engly-fe/src/app/features/admin/vocab-word-admin-form-page.component.css`

### Modify
- `engly-fe/src/app/features/admin/admin.routes.ts` — add deck + vocab-word routes.
- `engly-fe/src/app/features/admin/admin-list-page.component.{ts,html}` — add nav links to Decks / Vocab Words sections (admin landing).

### Delete
- None.

## Implementation Steps
1. **Models**: `AdminDeck`/`DeckRequest`/`DeckPageResponse`; `AdminVocabWord`/`VocabWordRequest`/`VocabWordPageResponse` matching P2 DTOs.
2. **admin-deck-api.service.ts**: list/get/create/update/delete (base `/api/admin/decks`, `withCredentials`).
3. **admin-vocab-word-api.service.ts**: list (q,page,size,deckId)/get/create/update/delete/toggleVerified (base `/api/admin/vocab-words`).
4. **deck-admin-list-page** + **deck-admin-form-page**: mirror verb list/form (search, paged table, new/edit/delete; slug field, name, description, topic).
5. **vocab-word-admin-list-page**: add deck filter dropdown + verified toggle column. **vocab-word-admin-form-page**: deck dropdown (load decks), term, meaningVi, ipa, examples, partOfSpeech, verified.
6. **admin.routes.ts**: register 6 routes.
7. **admin landing**: add links/buttons to the two new sections.
8. Build check.

## Todo List
- [ ] Deck models + api-service
- [ ] Vocab-word models + api-service
- [ ] deck-admin-list + deck-admin-form
- [ ] vocab-word-admin-list (deck filter + verified toggle)
- [ ] vocab-word-admin-form (deck dropdown)
- [ ] admin.routes.ts wiring
- [ ] admin landing nav links
- [ ] Build check (ng build)

## Success Criteria
- `npm run build` succeeds.
- Admin: create/edit/delete deck; create word under deck (dropdown); filter words by deck; toggle verified; duplicate slug surfaces 409 error message.
- Bearer token auto-attached (no 401/403 for admin).

## Risk Assessment
- **Token readiness**: `adminGuard` awaits `ensureLoaded()` which fetches token for admins → token present before any admin call (reuse existing flow).
- **Deck dropdown size**: many decks → use plain list (MVP small); pagination of dropdown not needed (YAGNI).
- **409 surfacing**: form must catch HttpErrorResponse 409 → show "slug already exists".

## Security Considerations
- All calls hit `/api/admin/**` → ROLE_ADMIN bearer chain (backend authoritative). FE guard UX-only.
- No secrets in code; token from existing AuthService.

## Next Steps
- Runs in **parallel with P4**. Feeds **P06** (FE admin specs).

## Compile/Build Check
```
cd engly-fe && npm run build
```
