# Phase 03 — Frontend Core (XSRF, Auth isAdmin, Guard, Route, Nav)

## Context Links
- Research: `research/researcher-02-frontend-admin.md` (§1 guard, §2 XSRF)
- Code: `engly-fe/.../app.config.ts`, `.../core/auth.service.ts`, `.../core/auth.service.spec.ts`,
  `.../app.routes.ts`, `.../app.component.ts`, `.../app.component.html`,
  `.../features/profile/profile.routes.ts` (lazy-route pattern to mirror)
- Depends on: phase-01 (`/api/me` returns `isAdmin`).

## Overview
- **Date:** 2026-05-31
- **Description:** Enable Angular XSRF for mutating calls, add `isAdmin` signal + `ensureLoaded()`
  promise to `AuthService`, create a functional `adminGuard`, wire a lazy `/admin` route, and show
  a conditional Admin nav link.
- **Priority:** P1 (blocks phase 04)
- **Implementation status:** complete
- **Review status:** complete

## Key Insights
- `provideHttpClient(withFetch())` currently has NO `withXsrfConfiguration` → mutating admin calls would
  fail CSRF (403). Must add it.
- `loadMe()` is fire-and-forget; a guard needs to await it → add `ensureLoaded(): Promise<void>` that
  resolves after the first `/api/me` settles (success OR error). Subsequent calls reuse the same promise.
- `UserInfo` must gain `isAdmin: boolean`; `isAdmin` exposed as a `computed` signal.
- Mirror `profile.routes.ts` lazy pattern; admin uses a guard + child routes.

## Requirements
- Functional: XSRF header auto-attached; `auth.isAdmin()` reflects `/api/me`; guard allows only
  authenticated admins, redirects others to `/verbs`; Admin nav link visible only to admins.
- Non-functional: no UI lib; scoped CSS; strict TS; guard handles the loadMe race deterministically.

## Architecture
```
app.config.ts: provideHttpClient(withFetch(), withXsrfConfiguration({cookieName:'XSRF-TOKEN', headerName:'X-XSRF-TOKEN'}))
AuthService: UserInfo{...,isAdmin}; isAdmin=computed(()=>_user()?.isAdmin ?? false);
             ensureLoaded(): Promise<void> (memoized; resolves when /api/me settles)
adminGuard (CanActivateFn, async): await ensureLoaded(); allow if isAuthenticated()&&isAdmin() else parseUrl('/verbs')
app.routes.ts: {path:'admin', canActivate:[adminGuard], loadChildren: admin.routes}
app.component: readonly isAdmin = auth.isAdmin; nav @if(isAdmin()) -> Admin link
```

## Related Code Files
**Create:**
- `engly-fe/src/app/features/admin/admin.guard.ts` (~20 lines)

**Modify:**
- `engly-fe/src/app/app.config.ts` — add `withXsrfConfiguration`.
- `engly-fe/src/app/core/auth.service.ts` — `UserInfo.isAdmin`, `isAdmin` computed, `ensureLoaded()`.
- `engly-fe/src/app/app.routes.ts` — add lazy `/admin` route with `adminGuard`.
- `engly-fe/src/app/app.component.ts` — expose `isAdmin`, add `goToAdmin()`.
- `engly-fe/src/app/app.component.html` — conditional Admin nav button.

**Delete:** none.

## Implementation Steps
1. `app.config.ts`:
   ```ts
   import { provideHttpClient, withFetch, withXsrfConfiguration } from '@angular/common/http';
   provideHttpClient(
     withFetch(),
     withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
   ),
   ```
2. `auth.service.ts`:
   - Extend interface: `export interface UserInfo { sub: string; email: string|null; name: string|null; isAdmin: boolean; }`
   - Add `readonly isAdmin = computed(() => this._user()?.isAdmin ?? false);`
   - Add memoized loader:
     ```ts
     private loadPromise?: Promise<void>;
     ensureLoaded(): Promise<void> {
       if (!this.loadPromise) {
         this.loadPromise = new Promise<void>((resolve) => {
           this.http.get<UserInfo>('/api/me', { withCredentials: true }).pipe(
             tap((u) => this._user.set(u)),
             catchError(() => { this._user.set(null); return of(null); }),
           ).subscribe(() => resolve());
         });
       }
       return this.loadPromise;
     }
     ```
   - Refactor `loadMe()` to call `ensureLoaded()` (keep void return; preserves AppComponent usage and
     existing spec which calls `loadMe()` then `expectOne('/api/me')`).
   - NOTE: existing `auth.service.spec.ts` flushes user objects without `isAdmin`; phase 05 updates those.
3. Create `admin.guard.ts`:
   ```ts
   import { inject } from '@angular/core';
   import { Router, CanActivateFn } from '@angular/router';
   import { AuthService } from '../../core/auth.service';
   export const adminGuard: CanActivateFn = async () => {
     const auth = inject(AuthService); const router = inject(Router);
     await auth.ensureLoaded();
     if (auth.isAuthenticated() && auth.isAdmin()) return true;
     return router.parseUrl('/verbs');
   };
   ```
4. `app.routes.ts` — add before the `''` redirect:
   ```ts
   {
     path: 'admin',
     canActivate: [adminGuard],
     loadChildren: () => import('./features/admin/admin.routes').then((m) => m.adminRoutes),
   },
   ```
   (Import `adminGuard` from `./features/admin/admin.guard`.)
5. `app.component.ts` — add `readonly isAdmin = this.auth.isAdmin;` and
   `goToAdmin(): void { this.router.navigate(['/admin']); }`.
6. `app.component.html` — inside `.app-auth`, when authenticated, before Profile:
   ```html
   @if (isAdmin()) {
     <button type="button" class="auth-btn" (click)="goToAdmin()">Admin</button>
   }
   ```
   (Reuse existing `.auth-btn` scoped style — no new CSS needed.)
7. Build check: `cd engly-fe && npm run build`.

## Todo List
- [ ] Add `withXsrfConfiguration` to `app.config.ts`
- [ ] Add `isAdmin` to `UserInfo` + computed signal in `AuthService`
- [ ] Add memoized `ensureLoaded()`; route `loadMe()` through it
- [ ] Create `admin.guard.ts` (async CanActivateFn)
- [ ] Wire lazy `/admin` route with guard in `app.routes.ts`
- [ ] Add conditional Admin nav button (component + html)
- [ ] `npm run build` passes

## Success Criteria
- Build passes. After admin `/api/me`, `auth.isAdmin()` true → guard allows `/admin`, Admin link shows.
- Non-admin/anonymous → guard redirects to `/verbs`, no Admin link. XSRF header present on later mutations.

## Risk Assessment
- **loadMe race** → solved by awaiting memoized `ensureLoaded()`; guard never decides before /api/me settles.
- **Existing specs break** (UserInfo now needs isAdmin) → addressed in phase 05 (update flushes).
- **XSRF cookie absence** → backend `CsrfCookieFilter` already forces the cookie on every response; same-origin via proxy.

## Security Considerations
- Guard is UX-only; real enforcement is the backend `hasRole('ADMIN')` (phase 01). Never trust FE alone.
- `withCredentials` already used; XSRF header now auto-sent for state-changing requests.

## Next Steps
- Unblocks phase 04 (admin UI consumes guard, route, XSRF, `isAdmin`).
