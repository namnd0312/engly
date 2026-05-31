# Phase 05 — Tests (Backend + Frontend)

## Context Links
- BE patterns to mirror: `engly-be/.../common/SecurityRulesTest.java` (@WebMvcTest + `oidcLogin()`),
  `.../verb/VerbControllerTest.java` (@WebMvcTest controller), `.../verb/VerbApiIntegrationTest.java`
  (@SpringBootTest + Testcontainers).
- FE patterns to mirror: `engly-fe/.../core/auth.service.spec.ts`,
  `.../verb-search/verb-search-page.component.spec.ts` (HttpTestingController, fakeAsync/tick).
- Depends on: phases 01–04 (tests verify final code).

## Overview
- **Date:** 2026-05-31
- **Description:** Add backend security/CRUD/validation tests and frontend guard/form/api tests for the
  admin feature. No mocks-to-pass; real assertions.
- **Priority:** P1
- **Implementation status:** complete
- **Review status:** complete

## Key Insights
- `@WebMvcTest` slice excludes oauth2 autoconfig (see `VerbControllerTest` exclusions) and mocks
  `ClientRegistrationRepository` where `SecurityConfig` is imported (see `SecurityRulesTest`).
- `SecurityMockMvcRequestPostProcessors.oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))`
  simulates an admin; omit authorities for a non-admin authenticated user; no post-processor = anonymous.
- CSRF is on for mutations → `@WebMvcTest` MockMvc must use `.with(csrf())` on POST/PUT/DELETE/PATCH.
- FE: existing `auth.service.spec.ts` flushes `UserInfo` WITHOUT `isAdmin` → update those flushes to include
  `isAdmin:false` so the typed model stays valid.

## Requirements
- BE: anon→401, non-admin→403, admin→2xx on `/api/admin/**`; CRUD happy paths; 409 duplicate; 400 invalid;
  404 missing id; public `/api/verbs/**` still 200 anonymously.
- FE: guard allows admin / denies non-admin (redirect); form validation (required/maxlength); api service
  hits correct URLs with `withCredentials` (XSRF header behavior covered via request inspection).

## Architecture (test files)
```
BE  src/test/java/com/engly/
  verb/VerbAdminControllerTest.java     @WebMvcTest(VerbAdminController) — CRUD + validation + 404 (mock service)
  common/AdminSecurityRulesTest.java    @WebMvcTest + SecurityConfig+OidcRoleMappingConfig — 401/403/2xx on /api/admin/**
  verb/VerbAdminIntegrationTest.java    @SpringBootTest + Testcontainers — real create/update/delete/toggle + 409 duplicate
FE  src/app/
  features/admin/admin.guard.spec.ts
  features/admin/admin-form-page.component.spec.ts
  features/admin/data/admin-verb-api.service.spec.ts
  core/auth.service.spec.ts (UPDATE existing flushes to include isAdmin)
```

## Related Code Files
**Create (BE):** `VerbAdminControllerTest.java`, `AdminSecurityRulesTest.java`, `VerbAdminIntegrationTest.java`.
**Create (FE):** `admin.guard.spec.ts`, `admin-form-page.component.spec.ts`, `admin-verb-api.service.spec.ts`.
**Modify (FE):** `core/auth.service.spec.ts` (add `isAdmin` to flushed users; add isAdmin assertion).
**Delete:** none.

## Implementation Steps
1. **`AdminSecurityRulesTest`** (mirror `SecurityRulesTest`): import `SecurityConfig` +
   `OidcRoleMappingConfig`, mock `ClientRegistrationRepository` + `VerbAdminService`. Cases:
   - `GET /api/admin/verbs` anonymous → 401.
   - authenticated non-admin (`oidcLogin()` no ROLE_ADMIN) → 403.
   - admin (`oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))`) → 200.
   - `GET /api/verbs/go` anonymous → 200 (regression: public unchanged).
2. **`VerbAdminControllerTest`** (`@WebMvcTest(VerbAdminController)`, exclude SecurityAutoConfiguration +
   OAuth2ClientAutoConfiguration like `VerbControllerTest`; import `GlobalExceptionHandler`; mock
   `VerbAdminService`). Use `.with(csrf())` if security not excluded — here security IS excluded so csrf
   not enforced; prefer excluding security to test the controller logic cleanly. Cases:
   - POST valid → 201 + body has `id`.
   - POST blank baseForm → 400 (`MethodArgumentNotValidException`).
   - PUT valid → 200; PUT unknown id (service throws `VerbNotFoundException`) → 404.
   - DELETE → 204; DELETE unknown id → 404.
   - PATCH `/{id}/verified` → 200 with toggled value.
3. **`VerbAdminIntegrationTest`** (mirror `VerbApiIntegrationTest`, Testcontainers Postgres + real Flyway).
   Authenticate the `TestRestTemplate` is awkward with oauth2; instead use `MockMvc` + `@SpringBootTest`
   with `@AutoConfigureMockMvc` and `oidcLogin().authorities(ROLE_ADMIN)` + `.with(csrf())`, OR keep it
   `@WebMvcTest`-style. DECISION (KISS): use `@SpringBootTest` + `@AutoConfigureMockMvc`, drive admin
   requests with `oidcLogin(...).authorities(...)` and `csrf()`. Cases against real DB:
   - create new verb → 201; GET list contains it with id.
   - create duplicate base_form (e.g. seed "go") → **409**.
   - update → persisted; delete → 204 then 404 on re-fetch; toggle verified flips boolean.
4. **`admin.guard.spec.ts`**: provide a stub `AuthService` with `ensureLoaded()` resolving and
   `isAuthenticated`/`isAdmin` signals; assert guard returns `true` for admin, and a `UrlTree` (to
   `/verbs`) for non-admin/anonymous. Run via `TestBed.runInInjectionContext(() => adminGuard(...))`.
5. **`admin-verb-api.service.spec.ts`** (mirror auth.service.spec): `HttpTestingController`; assert
   `list/create/update/delete/toggleVerified` hit correct URL/method, `withCredentials===true`, and
   bodies. (XSRF header is added by the interceptor, not unit-visible; assert `withCredentials` + URL.)
6. **`admin-form-page.component.spec.ts`**: create-mode form invalid when baseForm empty, valid when
   filled; submit fires POST `/api/admin/verbs`; 409 response sets the duplicate error message.
7. **Update `auth.service.spec.ts`**: add `isAdmin:false` to flushed `UserInfo` objects; add a test that
   a flushed `isAdmin:true` makes `service.isAdmin()` true and `ensureLoaded()` resolves.
8. Run: BE `cd engly-be && ./mvnw test`; FE `cd engly-fe && npm test -- --watch=false --browsers=ChromeHeadless`.

## Todo List
- [ ] BE `AdminSecurityRulesTest` (401/403/200 + public regression)
- [ ] BE `VerbAdminControllerTest` (CRUD 201/200/204/404 + 400 validation + PATCH)
- [ ] BE `VerbAdminIntegrationTest` (real DB CRUD + 409 duplicate)
- [ ] FE `admin.guard.spec.ts` (allow admin / deny others)
- [ ] FE `admin-verb-api.service.spec.ts` (URLs/methods/withCredentials)
- [ ] FE `admin-form-page.component.spec.ts` (validation + 409 message)
- [ ] Update FE `auth.service.spec.ts` flushes for `isAdmin`
- [ ] `./mvnw test` green; `npm test --watch=false` green

## Success Criteria
- All BE + FE suites pass. Coverage includes: authz boundaries, every admin endpoint, duplicate (409),
  invalid body (400), missing id (404), guard allow/deny, form validation, api contract.

## Risk Assessment
- **Integration auth wiring** (oauth2 + TestRestTemplate) → use MockMvc + `oidcLogin()` + `csrf()` to avoid
  full OAuth round-trip in tests.
- **CSRF in MockMvc** → add `.with(csrf())` on mutating requests when security filter is active.
- **Existing specs** breaking from `UserInfo.isAdmin` → updated in step 7.

## Security Considerations
- Tests assert defense at the URL layer (401/403), proving FE guard is not the only gate.

## Next Steps
- On green: `code-reviewer`, then `docs-manager` (update `docs/system-architecture.md` admin section +
  `docs/codebase-summary.md` new files + changelog).
