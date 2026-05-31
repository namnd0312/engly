# Codebase Summary

**Project:** Engly — English irregular-verb learning app
**MVP status:** Verb lookup DONE; SSO/BFF DEFERRED (phase 05)

---

## Backend — `engly-be/` (Spring Boot 3.4.5, Java 21, Maven)

### Entry Point

| File | Purpose |
|------|---------|
| `src/main/java/com/engly/EnglyApplication.java` | `@SpringBootApplication` — bootstraps Spring context |

### `com.engly.common` — Cross-cutting concerns

| File | Purpose |
|------|---------|
| `SecurityConfig.java` | `SecurityFilterChain` — oauth2Login + CookieCsrfTokenRepository; `/api/verbs/**` permitAll (GET); `/api/me` authenticated; `/api/admin/**` hasRole('ADMIN'); `/logout` → SPA |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice` — maps `VerbNotFoundException→404`, `DataIntegrityViolationException→409`, `IllegalArgumentException→400`, generic→500. No stack traces |
| `OidcRoleMappingConfig.java` | `OidcUserService` bean — extracts id_token roles claim (configurable name via `app.security.roles-claim-name`, env `ROLES_CLAIM_NAME`, default `roles`) → ROLE_* authorities |

### `com.engly.identity` — Identity / session management

| File | Purpose |
|------|---------|
| `api/SessionController.java` | `GET /api/me` (authenticated) → `{sub, email, name, isAdmin}` from `OidcUser` + role mapping |

### `com.engly.verb` — Verb bounded context (package-by-feature)

#### `verb/api/` — Public read-only endpoints

| File | Purpose |
|------|---------|
| `VerbController.java` | REST: `GET /api/verbs` (paginated search, permitAll) + `GET /api/verbs/{baseForm}` (detail, permitAll) |
| `dto/VerbResponse.java` | Record — public DTO (baseForm, pastSimple, pastParticiple, alts, IPA ×3, meaningVi, verified) |
| `dto/VerbSearchPageResponse.java` | Record — pagination envelope (content, page, size, totalElements, totalPages) |

#### `verb/admin/` — Admin-only CRUD endpoints

| File | Purpose |
|------|---------|
| `VerbAdminController.java` | `hasRole('ADMIN')` endpoints: `GET /api/admin/verbs` (list + search), `GET /api/admin/verbs/{id}` (detail), `POST /api/admin/verbs` (create→201), `PUT /api/admin/verbs/{id}` (update→200), `DELETE /api/admin/verbs/{id}` (hard delete→204), `PATCH /api/admin/verbs/{id}/verified` (toggle verified→200) |
| `VerbAdminService.java` | `@Transactional` write operations; CRUD + verified toggle; validation + error mapping |
| `dto/VerbRequest.java` | Record — create/update payload (bean validation: base_form/past_simple/past_participle/alts=100 chars, meaning_vi=255) |
| `dto/VerbAdminResponse.java` | Record — admin response (incl. numeric id) |
| `dto/VerbVerifiedRequest.java` | Record — PATCH verified body |
| `dto/VerbAdminPageResponse.java` | Record — paginated admin response |

#### `verb/application/`

| File | Purpose |
|------|---------|
| `VerbQueryService.java` | Read-only service; validates `page≥0`, `size∈[1,100]`; searches & maps DTOs. `@Transactional(readOnly=true)` |

#### `verb/domain/`

| File | Purpose |
|------|---------|
| `IrregularVerb.java` | JPA `@Entity` → `irregular_verb` table. Getters + mutable `update(VerbRequest)` + `setVerified(boolean)` |
| `VerbRepository.java` | Port interface: `search(q, pageable)` + `findByBaseFormIgnoreCase(baseForm)` |
| `VerbNotFoundException.java` | Domain exception → 404 |

#### `verb/infrastructure/`

| File | Purpose |
|------|---------|
| `VerbJpaRepository.java` | `JpaRepository` + `VerbRepository` impl. JPQL search; case-insensitive base_form/meaning_vi matching |

### Database Migrations — `engly-be/src/main/resources/db/migration/`

| File | Purpose |
|------|---------|
| `V1__create_irregular_verb_table.sql` | Creates `irregular_verb` table + two case-insensitive indexes |
| `V2__seed-irregular-verbs-180-rows-ai-draft-unverified.sql` | Seeds 179 irregular verbs (AI draft, `verified=false`) |

### Config & Build — `engly-be/`

| File | Purpose |
|------|---------|
| `src/main/resources/application.yml` | Server port 8081, datasource (env vars `DB_USER`/`DB_PASSWORD`, defaults `engly`/`engly`), JPA validate, Flyway enabled, OAuth2 opengate client registration/provider (secret via `OAUTH2_OPENGATE_CLIENT_SECRET` env), admin roles claim name via `app.security.roles-claim-name` (default `roles`, env `ROLES_CLAIM_NAME`) |
| `pom.xml` | Maven parent: spring-boot-starter-parent 3.4.5. Dependencies: spring-boot-starter-oauth2-client, spring-boot-starter-security, spring-data-jpa, flyway. Test: Testcontainers 1.21.0, JUnit 5, Mockito |
| `Dockerfile` | Multi-stage Maven build → eclipse-temurin:21-jre, port 8081 |

### Tests — `engly-be/src/test/java/com/engly/`

| File | Purpose | Type |
|------|---------|------|
| `EnglyApplicationTests.java` | Context loads with Testcontainers Postgres (Flyway V1+V2) | Smoke / integration |
| `verb/VerbQueryServiceTest.java` | `VerbQueryService` unit tests (Mockito) — pagination, mapping, exceptions | Unit |
| `verb/VerbControllerTest.java` | `@WebMvcTest` — public `/api/verbs` endpoints (200, 404, 400) | Web layer |
| `verb/VerbApiIntegrationTest.java` | `@SpringBootTest` + Testcontainers — real Flyway seed + queries; public CRUD regression | Integration |
| `verb/admin/AdminSecurityRulesTest.java` | `@WebMvcTest` — `/api/admin/**` auth rules: anon→401, non-admin→403, admin→200; public `/api/verbs/**` unchanged | Security |
| `verb/admin/VerbAdminControllerTest.java` | `@WebMvcTest` admin endpoints — CRUD returns correct codes; bean validation errors→400 | Web layer |
| `verb/admin/VerbAdminIntegrationTest.java` | `@SpringBootTest` + Testcontainers — real CRUD + duplicate base_form→409; verified toggle | Integration |
| `common/SecurityRulesTest.java` | OAuth2/OIDC config tests; `/api/me` returns `isAdmin` | Security |

---

## Frontend — `engly-fe/` (Angular 19.2, Node 24, standalone components)

### Root

| File | Purpose |
|------|---------|
| `src/main.ts` | Bootstraps Angular with `provideHttpClient` (withXsrfConfiguration), `provideRouter` |
| `src/app/app.routes.ts` | Top-level routes — lazy-loads verb-search.routes + admin.routes; app init: AuthService.ensureLoaded() |
| `src/app/app.component.ts` | Root component; login/logout buttons; Admin nav link (shown if isAdmin); router-outlet |
| `src/app/app.component.html` | Nav + router-outlet. Admin link conditional on isAdmin signal |
| `proxy.conf.json` | Dev proxy: `/api/**` → `http://localhost:8081` |
| `nginx.conf` | Prod: `/api/**` → engly-be:8081; all others → index.html (SPA); no CORS (same origin in k8s) |
| `angular.json` | Build + test targets |
| `tsconfig.json` | Strict mode; path aliases |

### `src/app/core/` — App-wide services

| File | Purpose |
|------|---------|
| `auth.service.ts` | Signals: `user` (readonly), computed `isAdmin` (derives from user). Methods: `loadMe()` (non-blocking, 401→anonymous), `ensureLoaded()` (memoized), `login()`→`/oauth2/authorization/opengate`, `logout()`→POST `/logout` |

### `src/app/features/verb-search/` — Public verb lookup

| File | Purpose |
|------|---------|
| `verb-search.routes.ts` | Feature routes: `/` → `VerbSearchPageComponent`, `/verbs/:baseForm` → `VerbDetailPageComponent` |
| `verb-search-page.component.ts` | Search page: FormControl + debounceTime(300) → VerbApiService.search. Signals: results, loading, error, totalElements |
| `verb-search-page.component.html` | Search input + results table. Inline styles |
| `verb-detail-page.component.ts` | Detail page: reads :baseForm from route; calls VerbApiService.getByBaseForm. Signals: verb, loading, notFound, error |
| `verb-detail-page.component.html` | Detail card with verb fields; not-found + error states. Inline styles |
| `data/verb-api.service.ts` | HttpClient for `/api/verbs` — search(q, page, size) + getByBaseForm(baseForm). withCredentials:true |
| `data/verb.model.ts` | TS interfaces: VerbResponse, VerbSearchPageResponse |

### `src/app/features/admin/` — Admin-only CRUD

| File | Purpose |
|------|---------|
| `admin.routes.ts` | Feature routes: `/admin` (guarded by `adminGuard`) → admin list page; `/admin/create` → create form; `/admin/edit/:id` → edit form; `/forbidden` → ForbiddenPageComponent |
| `admin.guard.ts` | CanActivateFn: admin→allow, authenticated non-admin→UrlTree to `/forbidden`, anonymous→login. Uses AuthService.ensureLoaded() |
| `admin-list-page.component.ts` | Search + pagination table. Inline verified toggle (PATCH). Edit/delete buttons. Signals: verbs, loading, error |
| `admin-list-page.component.html` | Searchable table with actions. Inline styles |
| `admin-form-page.component.ts` | Create/edit form. Reactive FormGroup: baseForm, pastSimple, pastParticiple, alts, IPA ×3, meaningVi, verified. Signals: loading, error, success |
| `admin-form-page.component.html` | Form with validation messages. Inline styles |
| `forbidden-page.component.ts` | 403 page (authenticated non-admin) |
| `forbidden-page.component.html` | "Không có quyền" message |
| `data/admin-verb-api.service.ts` | HttpClient for `/api/admin/verbs` — list(q, page, size), getById(id), create(req), update(id, req), delete(id), setVerified(id, verified). XSRF headers auto-applied |
| `data/admin-verb.model.ts` | TS interfaces: VerbAdminResponse, VerbRequest, VerbAdminPageResponse |

### Frontend Tests & Build

| File | Purpose |
|------|---------|
| `src/core/auth.service.spec.ts` | isAdmin computed signal, loadMe() non-blocking, ensureLoaded() memoization |
| `src/features/verb-search/verb-search-page.component.spec.ts` | HttpTestingController + fakeAsync — debounce timing, results render, loading/error states |
| `src/features/verb-search/verb-detail-page.component.spec.ts` | ActivatedRoute stub — detail 200, notFound signal on 404, error on 500 |
| `src/features/admin/admin.guard.spec.ts` | Guard: admin→allow, non-admin→`/forbidden`, anonymous→login; ensureLoaded() awaited |
| `src/features/admin/admin-verb-api.service.spec.ts` | Mocked `/api/admin/verbs` CRUD; XSRF headers; list(q,page,size), create/update/delete/setVerified |
| `src/features/admin/admin-form-page.component.spec.ts` | Form validation, submit → create/update, error handling |
| `src/features/admin/forbidden-page.component.spec.ts` | Component renders "Không có quyền" message |
| `Dockerfile` | Node 24-alpine, `ng build` → nginx:alpine serving dist/frontend/browser; nginx.conf proxies /api → engly-be:8081 |
| `proxy.conf.json` | Dev mode proxy: `/api/**` → `http://localhost:8081` |

---

---

## Kubernetes Manifests — `k8s/`

| File | Purpose |
|------|---------|
| `00-namespace.yaml` | Namespace `engly` |
| `10-postgres-database.yaml` | Secret `engly-db-secret` (DB_USER, DB_PASSWORD), PVC `engly-postgres-pvc`, Deployment + Service `postgres` (ClusterIP :5432) |
| `20-engly-be-backend.yaml` | Deployment (env from Secret, liveness/readiness/startup probes on `GET /api/verbs?size=1`) + Service `engly-be` (ClusterIP :8081) |
| `30-engly-fe-frontend.yaml` | Deployment (nginx serving SPA + proxying /api) + Service `engly-fe` (type: LoadBalancer, exposed via NodePort 31485 on OrbStack) |
| `deploy-to-orbstack.sh` | One-command script: builds images (`engly-be:local`, `engly-fe:local`) into OrbStack store, applies manifests, waits for rollouts |

**Images:** Built locally into OrbStack's shared image store (no external registry). `imagePullPolicy: IfNotPresent`.

---

## Current Implementation Status

| Feature | Status |
|---------|--------|
| Public verb lookup API (GET /api/verbs, /api/verbs/:base) | DONE |
| Postgres schema + 179-row seed (Flyway V1+V2) | DONE |
| Angular search + detail pages | DONE |
| OAuth2/OIDC login (BFF pattern) | DONE |
| Admin CRUD (create/read/update/delete/toggle-verified) | DONE |
| Admin role from OIDC id_token roles claim | DONE |
| Admin UI (list, create/edit form, forbidden page) | DONE |
| XSRF protection (cookie-based) | DONE |
| Integration tests (Testcontainers) | DONE |
| Admin security rules tests | DONE |
| Frontend component tests (auth, guard, forms) | DONE |
| Kubernetes deployment (OrbStack) | DONE |
| Human verification of verb data | ONGOING (via admin UI) |
