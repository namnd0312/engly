
# Phase 05 — SSO/BFF Integration Spike

## Context Links
- [plan.md](plan.md)
- [Phase 01 (skeleton, SecurityConfig stub)](phase-01-setup-monorepo-and-skeleton.md)
- [Phase 04 (frontend)](phase-04-angular-verb-search-ui.md)
- [Brainstorm summary §4.3](../reports/brainstorm-summary-260530-2323-engly-irregular-verbs-ddd-sso.md)
- IdP (reference, do NOT copy code): `/Users/admin/Desktop/DEV/BACK_END/OPEN-GATE-APP`

## Overview
- **Date:** 2026-05-30
- **Priority:** P2 (validation spike — proves integration, does not block public MVP)
- **Effort:** 8h
- **Implementation status:** implemented (code + automated tests); live IdP round-trip pending (steps 1 & 7 — needs OPEN-GATE on :9000 + `engly` client registered)
- **Review status:** not reviewed
- **Note:** real project is Maven (`engly-be`/`engly-fe`), not gradle/`backend`/`frontend` as drafted; oauth2 client config lives in `application.yml` (no separate config class — YAGNI). `/api/**` uses a 401 entry point so the SPA stays anonymous gracefully.
- **Description:** BFF pattern. Register Engly as confidential OAuth2 client in OPEN-GATE-APP. Engly BE uses spring-boot-starter-oauth2-client + oauth2Login() (OIDC). Tokens held server-side; browser gets httpOnly session cookie only. `GET /api/me` returns identity from OidcUser (id_token claims, since IdP has NO /userinfo). Angular login/logout buttons.

## Key Insights
- IdP lacks `/userinfo` → MUST rely on `oauth2Login()` OIDC flow so Spring populates `OidcUser` from id_token. Do NOT configure a userInfoUri.
- BFF = tokens in server-side authorized-client store; NEVER in JS/localStorage/cookie payload. Browser holds only session id (httpOnly).
- Same-origin dev proxy (phase 01) keeps cookie/CSRF simple. Verb endpoints stay permitAll; only `/api/me` requires authentication.
- Re-enable CSRF here (was disabled in phase 01 stub) using CookieCsrfTokenRepository; Angular auto-sends `X-XSRF-TOKEN`.

## Requirements
**Functional**
- Engly registered as client in IdP: grants authorization_code + refresh_token; scopes `openid profile email`; redirect_uri `http://localhost:8081/login/oauth2/code/opengate`; post_logout_redirect `http://localhost:4200/`.
- Login flow: Angular Login → BE `/oauth2/authorization/opengate` → IdP (Auth Code + PKCE) → callback → session cookie set → back to Angular.
- `GET /api/me` (authenticated) → `{ sub, email, name }` from OidcUser; 401 when anonymous.
- Logout clears Engly session + redirects to post_logout_redirect.
**Non-functional**
- No tokens in browser. permitAll `/api/verbs/**`; authenticated `/api/me`. Files < 200 lines.

## Architecture
**IdP client registration** (one-time, via `POST /admin/clients`, needs ROLE_ADMIN):
```
client_id: engly  (confidential, store secret in BE env — NOT committed)
grant_types: authorization_code, refresh_token
scopes: openid profile email
redirect_uri: http://localhost:8081/login/oauth2/code/opengate
post_logout_redirect_uri: http://localhost:4200/
```
**Backend**
```
com.engly.identity
├── config/ oauth2-client-config.java   (provider "opengate": issuer/authorization/token/jwks URIs; client reg; NO user-info-uri)
└── api/   session-controller.java        (GET /api/me from OidcUser; logout helper if needed)
com.engly.common
└── security-config.java (REPLACE stub):
     - permitAll: /api/verbs/**, /, static, /oauth2/**, /login/**
     - authenticated: /api/me
     - .oauth2Login(...)  (default redirect base /oauth2/authorization/opengate)
     - .logout(...) → post_logout redirect
     - CSRF: CookieCsrfTokenRepository.withHttpOnlyFalse() (Angular reads XSRF-TOKEN cookie)
     - CORS: not needed in dev (same-origin proxy); add config bean only if FE served cross-origin
```
- `application.yml`: `spring.security.oauth2.client.registration.opengate` + `.provider.opengate` (authorization-uri `http://localhost:9000/oauth2/authorize`, token-uri `/oauth2/token`, jwk-set-uri `/oauth2/jwks`, issuer-uri if discovery available). client-secret via env.
**Frontend**
```
frontend/src/app/core/
├── auth.service.ts   (signal user; loadMe() GET /api/me — tolerate 401 → null; login(): window.location → '/oauth2/authorization/opengate'; logout(): POST /logout)
└── (app shell) login/logout buttons bound to AuthService
```
- AuthService.loadMe() on app init but MUST NOT block verb lookup (catch 401 → anonymous).

## Related Code Files
**Create**
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/identity/config/oauth2-client-config.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/identity/api/session-controller.java`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/core/auth.service.ts`
- `/Users/admin/Desktop/DEV/AI/Engly/plans/260530-2323-engly-irregular-verbs-mvp-sso/reports/sso-spike-validation-report.md` (round-trip evidence)
**Modify**
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/common/security-config.java` (replace permitAll stub → real rules + oauth2Login + CSRF cookie)
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/resources/application.yml` (oauth2 client registration/provider, secret via env)
- `/Users/admin/Desktop/DEV/AI/Engly/backend/build.gradle.kts` (add spring-boot-starter-oauth2-client)
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/app.config.ts` / app shell (login/logout buttons, AuthService init)
**Delete:** none

## Implementation Steps
1. Ensure OPEN-GATE-APP running on :9000. Obtain ROLE_ADMIN token; `POST /admin/clients` to register `engly` confidential client with grants/scopes/redirect above. Capture client_secret → BE env (NOT committed).
2. Add `spring-boot-starter-oauth2-client` to build.gradle.kts.
3. `application.yml`: configure registration `opengate` (client-id, client-secret env, scopes openid/profile/email, redirect-uri template) + provider URIs (authorization/token/jwks; issuer if `/.well-known/openid-configuration` works). Do NOT set user-info-uri.
4. Replace `SecurityConfig` stub: permitAll verbs + oauth2/login + static; authenticated `/api/me`; `.oauth2Login()`; `.logout()` with post_logout redirect; CSRF CookieCsrfTokenRepository.withHttpOnlyFalse(); re-enable CSRF (remove phase-01 disable).
5. `SessionController`: `@GetMapping("/api/me")` taking `@AuthenticationPrincipal OidcUser` → return `{sub,email,name}`; rely on security to 401 anonymous.
6. Frontend `AuthService`: `loadMe()` GET `/api/me` (catch 401 → user=null); `login()` redirect to `/oauth2/authorization/opengate`; `logout()` POST `/logout` (CSRF cookie auto). Add login/logout buttons in shell; show name when logged in.
7. Run full round-trip: click Login → IdP login → callback → `/api/me` returns identity. Verify in browser devtools: NO access/refresh token in storage/JS; only session + XSRF cookies.
8. Record evidence (screens/notes) in sso-spike-validation-report.md.

## Todo List
- [ ] Register `engly` client in OPEN-GATE-APP (POST /admin/clients) — needs live IdP + admin creds
- [x] Add oauth2-client dependency (pom.xml — Maven)
- [x] application.yml oauth2 registration/provider (no user-info-uri; secret via env; offline-safe explicit URIs)
- [x] Replace SecurityConfig stub (permitAll verbs, authenticated /api/me, oauth2Login, logout, CSRF cookie, /api/** → 401)
- [x] SessionController GET /api/me from OidcUser
- [x] AuthService + login/logout buttons (non-blocking loadMe)
- [ ] Full OAuth2 round-trip works — pending live IdP
- [x] Automated tests green: BE SecurityRulesTest (anon 401 / OIDC 200), FE auth.service + app.component specs
- [ ] Verify no tokens in browser; write validation report — pending live IdP round-trip

## Success Criteria
- Click Login → complete OAuth2 round-trip with OPEN-GATE-APP → `/api/me` returns correct sub/email/name.
- Anonymous `/api/me` → 401; verb lookup still works without login.
- Browser storage/JS contains NO access/refresh tokens (only httpOnly session + XSRF cookie).
- Logout clears session, redirects to :4200.

## Risk Assessment
- Missing `/userinfo` → use oauth2Login OIDC (claims from id_token). *Resolved.*
- IdP Boot 4.0.6 vs Engly 3.4.x interop → OIDC/OAuth2 standard, compatible. *Low.*
- redirect_uri mismatch → must match registered value EXACTLY (`/login/oauth2/code/opengate`). *Medium.*
- CSRF re-enable breaks POST /logout → CookieCsrfTokenRepository + Angular X-XSRF-TOKEN. *Low.*
- client_secret leak → env only, gitignored. *Medium.*

## Security Considerations
- BFF: tokens server-side only; httpOnly session cookie; protects against XSS token theft.
- CSRF enabled (cookie repo) for state-changing requests (logout).
- client_secret + admin token never committed; use env/secret store.
- Scope minimal: openid profile email.

## Next Steps
- Phase 06: integration test for security rules (permitAll verbs vs 401 /api/me) + document SSO flow in system-architecture.md.
- Future: extend identity context (roles, protected features) once more bounded contexts added.
