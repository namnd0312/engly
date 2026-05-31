# Phase 06 — Testing + Documentation

## Context Links
- [plan.md](plan.md)
- [Phase 03 (API)](phase-03-verb-lookup-api-backend.md)
- [Phase 04 (UI)](phase-04-angular-verb-search-ui.md)
- [Phase 05 (SSO)](phase-05-sso-bff-integration-spike.md)

## Overview
- **Date:** 2026-05-30
- **Priority:** P2
- **Effort:** 6h
- **Implementation status:** pending
- **Review status:** not reviewed
- **Description:** Backend integration tests (Testcontainers Postgres) over real schema+seed, security-rules tests, frontend component tests, and `docs/` (system-architecture, codebase-summary, deployment-guide). Consolidates correctness + onboarding.

## Key Insights
- Unit tests already in phase 03; this phase adds REAL-DB integration (Testcontainers) so Flyway migrations + queries verified end-to-end.
- Security test: assert `/api/verbs/**` permitAll vs `/api/me` 401 anonymous — cheap guard against regressions.
- Docs were placeholders since phase 01 — fill now with actual implemented architecture.

## Requirements
**Functional**
- Backend: integration test boots context + Testcontainers Postgres, runs Flyway, hits `/api/verbs?q=go` + `/api/verbs/go` + 404; security test for permitAll vs authenticated.
- Frontend: component tests for search (debounce → service called, results render) + detail (404 state).
- Docs: system-architecture.md, codebase-summary.md, deployment-guide.md complete + accurate.
**Non-functional**
- All tests pass (no skips/mocks-to-cheat). CI-friendly (Testcontainers needs Docker). Files < 200 lines.

## Architecture
**Backend tests**
```
backend/src/test/java/com/engly/
├── verb/verb-api-integration-test.java   (@SpringBootTest + @Testcontainers PostgreSQLContainer; Flyway auto; real queries)
└── common/security-rules-test.java        (MockMvc: /api/verbs permitAll 200; /api/me anonymous 401)
```
- Testcontainers PostgreSQLContainer("postgres:16"); dynamic datasource props; Flyway runs V1+V2 (seed) → assert search/detail.
- Security-rules test may stub oauth2Login or assert 401 redirect/status for /api/me without auth.
**Frontend tests**
```
frontend/src/app/features/verb-search/
├── verb-search-page.component.spec.ts   (fakeAsync debounce; HttpTestingController; results render)
└── verb-detail-page.component.spec.ts    (404 → not-found state)
```
**Docs**
```
docs/
├── system-architecture.md   (modular monolith, package-by-feature, BFF/SSO flow diagram, ports)
├── codebase-summary.md       (module map verb/identity/common + frontend features)
└── deployment-guide.md       (run order: Postgres → IdP → BE → FE; env vars; client registration)
```

## Related Code Files
**Create**
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/test/java/com/engly/verb/verb-api-integration-test.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/test/java/com/engly/common/security-rules-test.java`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/verb-search-page.component.spec.ts`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/src/app/features/verb-search/verb-detail-page.component.spec.ts`
**Modify**
- `/Users/admin/Desktop/DEV/AI/Engly/backend/build.gradle.kts` (testcontainers + junit-jupiter, spring-security-test)
- `/Users/admin/Desktop/DEV/AI/Engly/docs/system-architecture.md` (placeholder → full)
- `/Users/admin/Desktop/DEV/AI/Engly/docs/codebase-summary.md` (placeholder → full)
- `/Users/admin/Desktop/DEV/AI/Engly/docs/deployment-guide.md` (placeholder → full)
**Delete:** none

## Implementation Steps
1. Add test deps: `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`, `org.springframework.security:spring-security-test`.
2. `verb-api-integration-test`: @SpringBootTest(webEnvironment=RANDOM_PORT) + @Testcontainers; `@DynamicPropertySource` for datasource; let Flyway apply V1+V2; TestRestTemplate/MockMvc assert search "go"→go/went/gone, detail, 404.
3. `security-rules-test`: MockMvc — `/api/verbs/go` → 200 (permitAll); `/api/me` no auth → 401; with `@WithMockUser`/mock OidcUser → 200 identity (optional).
4. Frontend specs: HttpTestingController; search spec uses fakeAsync+tick to flush debounce, expect GET `/api/verbs`, render results; detail spec simulates 404 → not-found UI.
5. Run `./gradlew test` (Docker required) + `ng test --watch=false` → all green; fix failures (do NOT skip).
6. Write docs: system-architecture (modules, BFF/SSO sequence via mermaid, port table 9000/8081/4200/5433), codebase-summary (module/file map), deployment-guide (run order, env vars EN_GLY datasource + OAuth client_secret, IdP client registration steps).
7. Update `plan.md` + phase statuses; note any follow-ups.

## Todo List
- [ ] Add testcontainers + security-test deps
- [ ] Verb API integration test (real Postgres + Flyway)
- [ ] Security-rules test (permitAll verbs / 401 /api/me)
- [ ] Frontend search + detail component specs
- [ ] gradlew test + ng test all green
- [ ] docs/system-architecture.md complete (with SSO diagram)
- [ ] docs/codebase-summary.md complete
- [ ] docs/deployment-guide.md complete

## Success Criteria
- Backend integration + security tests pass against real Postgres (Testcontainers), Flyway seed verified.
- Frontend component tests pass (debounce, render, 404).
- Three docs accurate, reflect implemented architecture + run instructions.
- No skipped/cheated tests.

## Risk Assessment
- Testcontainers needs Docker in CI/dev → document prerequisite; allow `@DisabledIfDockerUnavailable` fallback only if truly needed (avoid hiding failures). *Medium.*
- oauth2Login security test complexity → keep to status-code assertions; mock OidcUser optional. *Low.*
- Flyway seed UTF-8 in test container → matches dev encoding. *Low.*

## Security Considerations
- Security-rules test is the regression guard for auth boundaries (public verbs vs protected /api/me).
- deployment-guide must instruct env-based secrets (no secrets in repo).

## Next Steps
- After green + docs: delegate to code-reviewer (per primary workflow) before merge.
- Future bounded contexts (vocabulary, SRS) reuse this test+docs pattern.
