# Phase 01 — FE Profile Page + Lazy Route + Nav Link

## Context Links
- Overview: [plan.md](plan.md)
- Reuse: `engly-fe/src/app/core/auth.service.ts` (`user`, `isAuthenticated` signals; `login()`).
- Mirror pattern: `engly-fe/src/app/features/verb-search/verb-detail-page.component.ts` + `.html`, `verb-search.routes.ts`.
- Header to extend: `engly-fe/src/app/app.component.ts` + `app.component.html`.
- App routes: `engly-fe/src/app/app.routes.ts`.
- **Blocker fix:** `engly-fe/nginx.conf` (k8s reverse proxy) — currently proxies only `/api/`; must also proxy the OAuth paths.
- Reference for paths to proxy: `engly-fe/proxy.conf.json` (dev) proxies `/api,/oauth2,/login,/logout`; `engly-be/src/main/resources/application.yml` redirect-uri `{baseUrl}/login/oauth2/code/opengate`.

## Overview
- **Priority:** P3
- **Status:** pending
- **Description:** New lazy-loaded standalone `ProfilePageComponent` that renders the current user's
  `name`, `email`, `sub` as plain text from `AuthService` signals. Add `/profile` lazy route and a
  "Profile" header nav link visible only when authenticated. No backend change.

## Key Insights
- **CONFIRMED BLOCKER (must fix here):** `engly-fe/nginx.conf` proxies ONLY `location /api/` to `http://engly-be:8081` and otherwise SPA-falls-back to `index.html`. It does NOT proxy `/oauth2`, `/login`, `/logout`. On k8s NodePort (`http://localhost:30090`) the BFF login start (`/oauth2/authorization/opengate`) and callback (`/login/oauth2/code/opengate`) would hit the SPA fallback instead of engly-be → **login is broken**. Dev works only because `proxy.conf.json` (ng serve) proxies those paths; nginx never mirrored it. The page itself is fine — SSO simply cannot complete without this nginx change. This makes the task NOT purely Angular: one infra/config file (`nginx.conf`) must change too.
- `AuthService` already exposes everything needed: `user` (signal `UserInfo|null`), `isAuthenticated` (computed), `login()`. DO NOT call `/api/me` directly — read the signals.
- `AppComponent.ngOnInit()` already calls `auth.loadMe()` once at app start, so the signal is populated before the user reaches `/profile`. The profile component does NOT need to fetch.
- Codebase convention: standalone components, Angular signals, inline `<style>` block inside the template HTML, `RouterLink` for in-app nav, kebab-case filenames, files < 200 lines.
- KISS: component reads the auth signal itself for the "not authenticated" branch — NO `CanActivate` guard.

## Requirements
**Functional**
- Authenticated: show three labelled plain-text rows — Name, Email, Sub (subject identifier).
- Null `name`/`email` (backend may return null) → render a neutral placeholder (e.g. `—`).
- Not authenticated: short message + "Login" button calling `AuthService.login()`.
- Header shows a "Profile" link (routerLink `/profile`) only when `isAuthenticated()` is true.

**Non-functional**
- Each new file < 200 lines. No new dependencies. Frontend build must stay green (`ng build`).
- No styling system; minimal inline CSS consistent with existing header aesthetic is acceptable.

## Architecture
```
Header "Profile" link (app.component.html, @if isAuthenticated)
        │ routerLink="/profile"
        ▼
app.routes.ts  ──lazy── profile.routes.ts ──loadComponent── ProfilePageComponent
        │
        ▼ reads signals (no HTTP)
AuthService.user() / isAuthenticated()  ◄── already loaded by AppComponent.loadMe()
```
Data flow: AppComponent boot → `loadMe()` → `GET /api/me` (cookie) → `user` signal set → ProfilePageComponent renders signal values.

## Related Code Files
**Create**
- `engly-fe/src/app/features/profile/profile-page.component.ts` — standalone component; injects `AuthService`; exposes `user`, `isAuthenticated`; `login()` passthrough.
- `engly-fe/src/app/features/profile/profile-page.component.html` — `@if (isAuthenticated())` plain-text rows else login prompt; inline `<style>`.
- `engly-fe/src/app/features/profile/profile.routes.ts` — `profileRoutes` with `path: ''` → `loadComponent` of `ProfilePageComponent`.
- (Optional, mirrors pattern) `engly-fe/src/app/features/profile/profile-page.component.spec.ts` — basic render test for both branches.

**Modify**
- `engly-fe/src/app/app.routes.ts` — add `{ path: 'profile', loadChildren: () => import('./features/profile/profile.routes').then(m => m.profileRoutes) }` BEFORE the `''` and `**` redirects.
- `engly-fe/src/app/app.component.ts` — add `RouterLink` to `imports`.
- `engly-fe/src/app/app.component.html` — add `@if (isAuthenticated())` Profile link in `.app-auth`.
- **`engly-fe/nginx.conf` (REQUIRED blocker fix — real code change beyond Angular)** — add `location /oauth2/`, `location /login/`, `location /logout/` blocks that `proxy_pass http://engly-be:8081;`, mirroring the existing `/api/` block including its proxy headers (`Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto`). See step 9.

**Delete** — none.

## Implementation Steps
1. Create `engly-fe/src/app/features/profile/` directory.
2. `profile-page.component.ts`: standalone, `selector: app-profile-page`, `imports: [RouterLink]`, `templateUrl`. Inject `AuthService`; expose `readonly user = auth.user;`, `readonly isAuthenticated = auth.isAuthenticated;`, `login() { this.auth.login(); }`.
3. `profile-page.component.html`:
   - `@if (isAuthenticated()) { ... }` block with three rows reading `user()?.name`, `user()?.email`, `user()?.sub`, using `|| '—'` for null name/email.
   - `@else { <p>You are not signed in.</p> <button (click)="login()">Login</button> }`.
   - Append a minimal inline `<style>` block (label/value layout) consistent with header.
4. `profile.routes.ts`: export `profileRoutes: Routes` with single `{ path: '', loadComponent: () => import('./profile-page.component').then(m => m.ProfilePageComponent) }`.
5. `app.routes.ts`: insert the `profile` lazy route entry before the empty-path redirect.
6. `app.component.ts`: import `RouterLink` from `@angular/router`, add to `imports` array.
7. `app.component.html`: inside `.app-auth`, in the `@if (isAuthenticated())` branch, add `<a routerLink="/profile" class="app-user">Profile</a>` (before name/Logout).
8. Compile check: `cd engly-fe && npm run build` (or `ng build`) → must succeed with no errors.
9. **Blocker fix — `engly-fe/nginx.conf`:** add three new `location` blocks ABOVE the SPA `location /` so the OAuth/BFF paths reach the backend instead of the SPA fallback. Mirror the existing `/api/` block exactly (same `proxy_pass http://engly-be:8081;` + `proxy_http_version 1.1;` + the four `proxy_set_header` lines `Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto`):
   - `location /oauth2/ { ... proxy_pass http://engly-be:8081; ... }`
   - `location /login/ { ... }`  (covers callback `/login/oauth2/code/opengate`)
   - `location /logout/ { ... }`
   Keep DRY: identical proxy settings to `/api/`. No `ng build` impact (nginx is runtime config, not bundled); validated at deploy time in phase-02.
   - **Forwarded-headers caveat (see phase-02 risk):** the existing block sets `Host $host` + `X-Forwarded-Proto $scheme`. For the OAuth callback, engly-be derives `{baseUrl}` for `redirect_uri` from the incoming request, so these headers must reflect the browser-facing origin `http://localhost:30090`. Mirror them on the new blocks; phase-02 verifies the resulting redirect_uri.

## Todo List
- [ ] Create `features/profile/` dir
- [ ] `profile-page.component.ts`
- [ ] `profile-page.component.html` (both branches + inline style)
- [ ] `profile.routes.ts`
- [ ] Add `/profile` lazy route in `app.routes.ts`
- [ ] Add `RouterLink` import in `app.component.ts`
- [ ] Add Profile nav link in `app.component.html` (auth-gated)
- [ ] (Optional) `profile-page.component.spec.ts`
- [ ] `ng build` green
- [ ] **`nginx.conf`: add `/oauth2/`, `/login/`, `/logout/` proxy blocks (mirror `/api/`)** — REQUIRED blocker fix

## Success Criteria
- `ng build` succeeds; no TS/template errors.
- Navigating to `/profile` while authenticated shows Name/Email/Sub plain text.
- While anonymous, `/profile` shows the login prompt + working Login button.
- "Profile" link appears in header only when authenticated.
- `nginx.conf` contains `location /oauth2/`, `/login/`, `/logout/` proxying to `http://engly-be:8081` with the same headers as `/api/` (verified end-to-end in phase-02 preflight).

## Risk Assessment
- **Route order:** placing `/profile` after `**` catch-all would shadow it. Mitigation: insert before `''` and `**`.
- **Null fields:** `name`/`email` can be null from `/api/me`. Mitigation: `|| '—'` fallback.
- **Signal timing:** direct deep-link to `/profile` before `loadMe()` resolves could briefly show the anonymous branch. Acceptable (KISS); resolves on next change detection when signal updates.

## Security Considerations
- No new endpoint, no token handling — identity stays in the httpOnly session cookie; JS never sees tokens.
- `sub`/`email` are already exposed by the existing header; displaying them on `/profile` adds no new exposure.
- No route guard by design; backend `/api/me` remains the real authority (returns 401 → anonymous).

## Next Steps
Proceed to phase-02 (rebuild FE image, redeploy, SSO e2e manual test).
