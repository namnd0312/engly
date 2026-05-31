---
title: "Admin-only CRUD for Irregular Verbs"
description: "IdP-role-gated admin CRUD + verified toggle over irregular verbs; separate /api/admin namespace, Angular admin page."
status: complete
priority: P2
effort: 13h
branch: main
tags: [backend, frontend, security, rbac, crud, oidc, angular, spring-boot]
created: 2026-05-31
---

# Admin-only CRUD for Irregular Verbs

Add full CRUD (create/read/update/hard-delete) + a `verified` toggle for irregular verbs,
restricted to admins. Admin role comes from an OPEN-GATE OIDC id_token claim mapped to a
`ROLE_ADMIN` authority (no DB users/roles). A new `/api/admin/verbs/**` namespace carries all
mutations; the public read-only `/api/verbs/**` stays untouched. Angular gets a lazy `/admin`
page guarded by `isAdmin`.

## Architecture (locked)
- BE: new `verb/admin/` slice — `VerbAdminController` (`/api/admin/verbs/**`), `VerbAdminService`
  (`@Transactional` writes), `VerbRequest` + `VerbAdminResponse` records, validation, exception
  mapping (409/400/404). `IrregularVerb` gains an `update(...)` mutator + setter for `verified`.
- Security: URL rule `/api/admin/**` → `hasRole('ADMIN')`; `/api/verbs/**` stays permitAll (GET).
  New `OidcRoleMappingConfig` (`OidcUserService` bean) maps configurable roles claim → authorities.
  `/api/me` extended with `isAdmin`.
- FE: `withXsrfConfiguration` added; `AuthService` gains `isAdmin` signal + `ensureLoaded()`;
  new lazy `features/admin/` (guard, routes, api service, model, list + form pages); conditional
  Admin nav link.

## Phases
| # | Phase | File | Status | Effort |
|---|-------|------|--------|--------|
| 01 | Backend security / RBAC (OIDC role map + URL rules + /api/me isAdmin) | [phase-01-backend-security-rbac.md](phase-01-backend-security-rbac.md) | complete | 3h |
| 02 | Backend CRUD (entity mutation, service, controller, DTOs, validation, 409/400) | [phase-02-backend-crud-admin-api.md](phase-02-backend-crud-admin-api.md) | complete | 4h |
| 03 | Frontend core (XSRF, auth isAdmin + ensureLoaded, guard, route, nav) | [phase-03-frontend-core-auth-guard.md](phase-03-frontend-core-auth-guard.md) | complete | 2h |
| 04 | Frontend admin UI (api service + model, list page, form page) | [phase-04-frontend-admin-ui.md](phase-04-frontend-admin-ui.md) | complete | 3h |
| 05 | Tests (BE security/CRUD/409/400 + FE guard/form/XSRF) | [phase-05-tests-backend-frontend.md](phase-05-tests-backend-frontend.md) | complete | 1h |

## Key Dependencies
- Phase 02 depends on 01 (security URL rule + role mapping must exist before admin endpoints are reachable).
- Phase 03 depends on 01 (`/api/me` must return `isAdmin`).
- Phase 04 depends on 02 (admin API contract) + 03 (guard/route/XSRF/auth).
- Phase 05 depends on 01–04 (verifies final code).

## Cross-cutting facts (verified against real code)
- DB column lengths (system-architecture.md + `IrregularVerb.java`): base_form/past_simple/
  past_participle/*_alt/ipa_* = **100**; meaning_vi = **255**. (research-02's 64/96 is wrong — use these.)
- `oauth2Login` + PKCE + `CookieCsrfTokenRepository` + `CsrfCookieFilter` already wired in
  `SecurityConfig.java`; `/api/me` already authenticated via `SessionController`.
- Registration id is `opengate`; FE login URL `/oauth2/authorization/opengate`.
- Hard delete only; `irregular_verb` table already exists (Flyway V1+V2). No new migration expected.
- Use numeric `id` (Long) as the admin identifier (base_form is editable).

## Success Criteria (whole feature)
- `./mvnw -q -DskipTests compile` passes; `./mvnw test` green (BE).
- `npm run build` + `npm test -- --watch=false` green (FE).
- Anonymous/non-admin → 401/403 on `/api/admin/**`; admin → 2xx. Public GET `/api/verbs/**` unchanged.
- Admin can create/edit/delete/toggle-verified from the UI; non-admins never see `/admin` or the nav link.

## Validation Summary

**Validated:** 2026-05-31 · **Questions asked:** 4

### Confirmed Decisions
- **Roles claim name = `roles`** — user confirmed. `app.security.roles-claim-name` default `roles` stays; tolerant `extractRoles` still handles array-vs-string shape (shape itself unconfirmed → still inspect a real id_token). Downgrades Unresolved Q1 from "name+shape unknown" to "shape unknown".
- **No admin fallback / allowlist** — authority derives SOLELY from the OIDC roles claim. Do NOT add `admin-emails` config. Admin login is testable only once OPEN-GATE emits the role (see Q2 below).
- **Verified toggle = dedicated `PATCH /api/admin/verbs/{id}/verified`** — confirmed (matches phase-02). Keep inline list toggle.
- **Non-admin → 403 page** (CHANGE): authenticated non-admin hitting `/admin` sees a "Không có quyền" (403 forbidden) page, NOT a silent redirect. Anonymous users still get sent to login.

### Action Items (revise phase files before/during impl)
- [ ] **Phase 03**: add a standalone `forbidden-page.component` (+ `/forbidden` route, or display via the admin route). `adminGuard` returns `UrlTree` to `/forbidden` for authenticated-non-admin; triggers `login()` for anonymous. Update guard success-criteria/tests accordingly.
- [ ] **Phase 05**: add FE guard test case = authenticated-non-admin → routed to `/forbidden` (not `/verbs`).
- [ ] **Phase 01**: `roles-claim-name` default `roles` confirmed; keep the "inspect real id_token to confirm shape" deferred step (shape still unverified).

## Unresolved Questions
1. **OPEN-GATE roles claim SHAPE (claim name now confirmed = `roles`).** Name resolved via
   validation. Still unknown: array-of-strings vs space/comma string vs nested object, and casing
   (`admin` vs `ADMIN`). Tolerant `extractRoles` + ROLE_/uppercase normalization covers array & string;
   a **real id_token should still be decoded after a login** to confirm shape — if nested/object-shaped,
   `OidcRoleMappingConfig.extractRoles` needs a small tweak.
2. **How an admin is granted the role in OPEN-GATE.** Mechanism to assign the admin
   role/group/claim to a user in the OPEN-GATE IdP is unknown (admin console? client scope/mapper?).
   Needed to actually test an admin login end-to-end (phase 01 step "live verify" + phase 05 manual check).
3. Whether OPEN-GATE will emit the roles claim under the current requested scopes (`openid profile
   email`) or requires an extra scope (e.g. `roles`). If extra scope needed, add to `application.yml`.
