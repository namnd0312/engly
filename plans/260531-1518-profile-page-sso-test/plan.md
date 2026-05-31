---
title: "Profile Page — SSO/BFF E2E Test"
description: "Plain-text 'view my profile' page to end-to-end test existing SSO/BFF OIDC login."
status: pending
priority: P3
effort: 2h
branch: main
tags: [frontend, angular, sso, bff, oidc, e2e, k8s]
created: 2026-05-31
---

# Profile Page — SSO/BFF E2E Test

## Goal
Add a SIMPLE, plain-text "view my profile" page whose primary purpose is to end-to-end
exercise the EXISTING SSO/BFF OIDC login against the OPEN-GATE IdP. Strict YAGNI/KISS:
display only `name + email + sub` (exactly what `GET /api/me` returns today).
NO backend change, NO database, NO edit/PATCH, NO styling complexity, NO route guard.

## Scope (locked)
- Display ONLY `name`, `email`, `sub` from `GET /api/me`. Reuse `AuthService` — do not duplicate `/api/me`.
- Test env: k8s (OrbStack), NodePort `http://localhost:30090`.
- Angular code unchanged in shape, BUT **not literally "FE-only"**: a CONFIRMED k8s blocker requires editing
  `engly-fe/nginx.conf` (infra/config), and possibly `engly-be` forwarded-headers config. See Blocker below.
- Backend Java logic (`SessionController` `GET /api/me`, `SecurityConfig` PKCE) untouched.

## CONFIRMED Blocker (verified by reading files) — SSO broken on k8s NodePort without fix
- `engly-fe/nginx.conf` proxies ONLY `location /api/ { proxy_pass http://engly-be:8081; }` plus the SPA `location / { try_files ... index.html }`. It does NOT proxy `/oauth2`, `/login`, `/logout`.
- In k8s the browser hits engly-fe nginx at `http://localhost:30090`. BFF OIDC login uses `/oauth2/authorization/opengate` and callback `/login/oauth2/code/opengate` (`application.yml` redirect-uri `{baseUrl}/login/oauth2/code/opengate`). Those paths fall through to the SPA `index.html` instead of reaching engly-be → **login is broken on k8s NodePort**.
- Dev `proxy.conf.json` DOES proxy `/api,/oauth2,/login,/logout` to `:8081`, but `nginx.conf` never mirrored it.
- Fix lives in phase-01 (nginx edit) + a forwarded-headers risk/verification in phase-02.

## Phases

| # | Phase | Status | Effort | File |
|---|-------|--------|--------|------|
| 01 | FE profile page + lazy route + nav link + **nginx auth-path proxy (blocker fix)** | ✅ done (build green) | ~1h | [phase-01-fe-profile-page-route-nav.md](phase-01-fe-profile-page-route-nav.md) |
| 02 | Rebuild FE image, redeploy k8s, SSO e2e manual test (+ proxy preflight, forwarded-headers) | blocked (IdP redirect_uri :30090 not registered) | ~1h | [phase-02-rebuild-deploy-sso-e2e-test.md](phase-02-rebuild-deploy-sso-e2e-test.md) |

## Phase 01 Implementation Notes (done 2026-05-31)
- Created `engly-fe/src/app/features/profile/{profile-page.component.ts,.html,profile.routes.ts}`.
- `app.routes.ts`: added `/profile` lazy route before `''`/`**`.
- `app.component.{ts,html}`: auth-gated "Profile" header button → `Router.navigate(['/profile'])`.
  - NOTE: `RouterLink` directive could NOT be added to the eager root `AppComponent` — `ng build`
    fails `TS-998113` for any `@angular/router` directive/NgModule in its `imports` (RouterLink AND
    RouterModule both rejected), though the SAME import works in lazy `verb-detail`. Worked around with
    the `Router` service (no `imports` entry) — matches the header's existing `(click)` button pattern.
- `nginx.conf`: added `/oauth2/`, `/login/`, `/logout/` proxy blocks → `http://engly-be:8081` (blocker fix).
- `ng build` GREEN (exit 0).

## Phase 02 CONFIRMED blocker (new)
- IdP `oauth2_registered_client.redirect_uris` = ONLY `http://localhost:8081/login/oauth2/code/opengate`.
- k8s NodePort flow makes engly-be derive `redirect_uri = http://localhost:30090/login/oauth2/code/opengate`
  (nginx `proxy_set_header Host $host` → Host: localhost:30090). → IdP rejects (mismatch).
- FIX: register `http://localhost:30090/login/oauth2/code/opengate` on the OPEN-GATE client (DB update).

## Key Dependencies
- Phase 02 `blockedBy` Phase 01 (must build the page before deploying/testing it).
- SSO requires 4 services up: Postgres, engly-be, engly-fe, OPEN-GATE IdP `:8080` (+ its own Postgres).
- Reuses existing `AuthService` signals (`user`, `isAuthenticated`) — no new HTTP logic.

## Reference Material
- FE auth client: `engly-fe/src/app/core/auth.service.ts` (REUSE).
- Pattern to mirror: `engly-fe/src/app/features/verb-search/` (standalone components, signals, lazy `loadComponent`).
- Backend (read-only): `engly-be/src/main/java/com/engly/identity/api/SessionController.java`, `.../common/SecurityConfig.java`.
- Deploy: `k8s/deploy-to-orbstack.sh`, `k8s/30-engly-fe-frontend.yaml` (NodePort 30090).
- Docs present: `docs/codebase-summary.md`, `docs/system-architecture.md`. (`code-standards.md`, `project-overview-pdr.md` absent.)

## Out of Scope
Backend changes, DB persistence, profile editing, avatars, styling system, route guards, i18n, tests beyond existing patterns (optional spec only).

## Unresolved Questions
See end of phase-02.
