# Phase 04 — Frontend Admin UI

## Context Links
- Research: `research/researcher-02-frontend-admin.md` (§3 api service, §4 form, §5 list/routes)
- Code (patterns to mirror): `engly-fe/.../features/verb-search/data/verb-api.service.ts`,
  `.../data/verb.model.ts`, `.../verb-search-page.component.{ts,html}` (signals + scoped CSS table),
  `.../features/profile/profile.routes.ts` (lazy route)
- Depends on: phase-02 (API contract) + phase-03 (guard/route/XSRF/auth).

## Overview
- **Date:** 2026-05-31
- **Description:** Build the admin feature UI: API service + model, list page (table with edit/delete +
  verified toggle), and a reactive create/edit form page. Match existing signal + scoped-CSS style, no UI lib.
- **Priority:** P2
- **Implementation status:** complete
- **Review status:** complete

## Key Insights
- Mirror `VerbApiService` structure (relative `/api`, `withCredentials:true`). Admin base = `/api/admin/verbs`.
- Model `VerbRequest`/`VerbAdminResponse` must match phase-02 records EXACTLY (camelCase, `id:number`,
  lengths 100/255). Public `verb.model.ts` is reused for nothing here — keep admin model separate (DRY within feature).
- Use Angular signals like `verb-search-page.component.ts` (`results`, `loading`, `error`); reactive form
  via `FormBuilder` (`ReactiveFormsModule`), control flow `@if/@for`.
- Delete uses native `confirm()` (KISS, matches research). Verified toggle = `PATCH /{id}/verified`.
- Route mode: one form component for create (`/admin/verbs/new`) and edit (`/admin/verbs/:id/edit`),
  branch on route param.

## Requirements
- Functional: list+search verbs (incl. id); create; edit (prefilled); delete (confirm); toggle verified inline.
- Non-functional: scoped CSS consistent with app (`<style>` block or `styles:[...]`); strict TS; standalone components.

## Architecture
```
features/admin/
  admin.routes.ts          (children under guarded /admin from phase 03)
    ''            -> AdminListPageComponent  (path '' = /admin)
    'verbs/new'   -> AdminFormPageComponent  (create)
    'verbs/:id/edit' -> AdminFormPageComponent (edit)
  data/admin-verb.model.ts (VerbRequest, VerbAdminResponse, page envelope)
  data/admin-verb-api.service.ts (list/create/update/delete/toggleVerified)
  admin-list-page.component.{ts,html}
  admin-form-page.component.{ts,html}
```
NOTE: phase-03 already attaches `canActivate:[adminGuard]` at the `/admin` parent route, so
`admin.routes.ts` children need no extra guard.

## Related Code Files
**Create:**
- `engly-fe/src/app/features/admin/admin.routes.ts`
- `engly-fe/src/app/features/admin/data/admin-verb.model.ts`
- `engly-fe/src/app/features/admin/data/admin-verb-api.service.ts`
- `engly-fe/src/app/features/admin/admin-list-page.component.ts`
- `engly-fe/src/app/features/admin/admin-list-page.component.html`
- `engly-fe/src/app/features/admin/admin-form-page.component.ts`
- `engly-fe/src/app/features/admin/admin-form-page.component.html`

**Modify:** none (route wiring + nav done in phase 03).
**Delete:** none.

## Implementation Steps
1. `admin-verb.model.ts`:
   ```ts
   export interface VerbRequest {
     baseForm: string; pastSimple: string; pastParticiple: string;
     pastSimpleAlt: string | null; pastParticipleAlt: string | null;
     ipaBase: string | null; ipaPast: string | null; ipaPp: string | null;
     meaningVi: string; verified: boolean;
   }
   export interface VerbAdminResponse extends VerbRequest { id: number; }
   export interface VerbAdminPageResponse {
     content: VerbAdminResponse[]; page: number; size: number;
     totalElements: number; totalPages: number;
   }
   ```
2. `admin-verb-api.service.ts` (mirror `VerbApiService`):
   ```ts
   @Injectable({ providedIn: 'root' })
   export class AdminVerbApiService {
     private readonly http = inject(HttpClient);
     private readonly baseUrl = '/api/admin/verbs';
     list(q = '', page = 0, size = 20): Observable<VerbAdminPageResponse> {
       const params = new HttpParams().set('q', q).set('page', page).set('size', size);
       return this.http.get<VerbAdminPageResponse>(this.baseUrl, { params, withCredentials: true });
     }
     get(id: number) { return this.http.get<VerbAdminResponse>(`${this.baseUrl}/${id}`, { withCredentials: true }); }
     create(req: VerbRequest) { return this.http.post<VerbAdminResponse>(this.baseUrl, req, { withCredentials: true }); }
     update(id: number, req: VerbRequest) { return this.http.put<VerbAdminResponse>(`${this.baseUrl}/${id}`, req, { withCredentials: true }); }
     delete(id: number) { return this.http.delete<void>(`${this.baseUrl}/${id}`, { withCredentials: true }); }
     toggleVerified(id: number, verified: boolean) {
       return this.http.patch<VerbAdminResponse>(`${this.baseUrl}/${id}/verified`, { verified }, { withCredentials: true });
     }
   }
   ```
   NOTE: phase-02 must expose `GET /api/admin/verbs/{id}` if the edit page loads by id. If NOT planned
   there, edit page instead reads the row from list navigation state OR re-fetches via list+filter.
   DECISION: add `GET /{id}` to phase-02 controller (cheap, `findById`) to keep edit page simple. (Track
   as a small addendum to phase 02 if not already present.)
3. `admin-list-page.component.ts`: signals `verbs`, `loading`, `error`, `q` (FormControl). On init
   `load()`; debounce search like verb-search page (`debounceTime(300)`). Methods: `onDelete(id)`
   (`if(!confirm(...)) return;` → `api.delete` → reload), `onToggle(v)` (`api.toggleVerified(v.id,!v.verified)`
   → update signal), `goNew()`/`goEdit(id)` via `Router`.
4. `admin-list-page.component.html`: search input + table with columns base/past/pp/meaning/verified +
   actions (Edit button, Delete button, verified checkbox/toggle). Use `@for (v of verbs(); track v.id)`,
   `@if (loading())`, empty state. Add `New verb` button → `/admin/verbs/new`. Scoped `<style>` mirroring
   `verb-search-page.component.html` table styling.
5. `admin-form-page.component.ts`: `ReactiveFormsModule`. Build form group with validators matching DB
   (Validators.required + maxLength(100) for base/past/pp; maxLength(100) optional for alts/ipa;
   required + maxLength(255) for meaningVi; verified boolean). Detect edit via
   `route.snapshot.paramMap.get('id')`; if present, `api.get(id)` → `form.patchValue`. `onSubmit()`:
   create vs update; on success `router.navigate(['/admin'])`; error handling: 409 →
   "base_form already exists", 400 → show `err.error?.message`, else generic.
6. `admin-form-page.component.html`: reactive form with labeled inputs, inline validation errors
   (`@if (form.get('baseForm')?.touched && form.get('baseForm')?.invalid)`), submit button disabled when
   `form.invalid || submitting`, server-error banner. Scoped `<style>`.
7. `admin.routes.ts`:
   ```ts
   export const adminRoutes: Routes = [
     { path: '', loadComponent: () => import('./admin-list-page.component').then(m => m.AdminListPageComponent) },
     { path: 'verbs/new', loadComponent: () => import('./admin-form-page.component').then(m => m.AdminFormPageComponent) },
     { path: 'verbs/:id/edit', loadComponent: () => import('./admin-form-page.component').then(m => m.AdminFormPageComponent) },
   ];
   ```
8. Build check: `cd engly-fe && npm run build`.

## Todo List
- [ ] Create `admin-verb.model.ts` (matches phase-02 records, lengths 100/255)
- [ ] Create `admin-verb-api.service.ts` (list/get/create/update/delete/toggleVerified)
- [ ] Create list page (.ts/.html) — table, search, delete confirm, verified toggle, New button
- [ ] Create form page (.ts/.html) — create/edit, validators, 409/400 handling
- [ ] Create `admin.routes.ts` (3 child routes)
- [ ] Confirm phase-02 exposes `GET /api/admin/verbs/{id}` (add if missing)
- [ ] `npm run build` passes

## Success Criteria
- Build passes. Admin can list/search, create (form→list), edit (prefilled→save), delete (confirm),
  toggle verified inline; 409/400 surfaced as readable messages. Styling consistent with app.

## Risk Assessment
- **Model drift vs backend** → keep field names/lengths identical to phase-02 DTOs; verified by phase-05 FE specs.
- **Files >200 lines** → split list/form into .ts + .html (already separate); keep logic lean.
- **Edit-by-id needs GET /{id}** → add to phase-02 (step 2 note) to avoid fragile nav-state passing.

## Security Considerations
- All calls `withCredentials:true`; XSRF header auto-attached (phase 03). Page only reachable through
  guarded route; backend still authorizes every request.

## Next Steps
- Verified by phase 05 FE specs (guard, form validation, api XSRF/contract).
