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
| `SecurityConfig.java` | `SecurityFilterChain` bean — **permitAll stub** (CSRF disabled). TODO phase-05: add OAuth2 + CookieCsrf |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice` — maps `VerbNotFoundException→404`, `IllegalArgumentException→400`, generic→500. No stack traces exposed |

### `com.engly.verb` — Verb bounded context (package-by-feature)

#### `verb/api/`

| File | Purpose |
|------|---------|
| `VerbController.java` | REST endpoints: `GET /api/verbs` (paginated search) + `GET /api/verbs/{baseForm}` (detail). Delegates entirely to `VerbQueryService` |
| `dto/VerbResponse.java` | Java record — API DTO for one verb (baseForm, pastSimple, pastParticiple, alts, IPA ×3, meaningVi, verified) |
| `dto/VerbSearchPageResponse.java` | Java record — pagination envelope (content, page, size, totalElements, totalPages) |

#### `verb/application/`

| File | Purpose |
|------|---------|
| `VerbQueryService.java` | Validates `page≥0`, `size∈[1,100]`; calls `VerbRepository.search` or `findByBaseFormIgnoreCase`; maps entities→DTOs. `@Transactional(readOnly=true)` |

#### `verb/domain/`

| File | Purpose |
|------|---------|
| `IrregularVerb.java` | JPA `@Entity` → `irregular_verb` table. Getters only, no setters (immutable after Flyway seed) |
| `VerbRepository.java` | Port interface: `search(q, pageable)` + `findByBaseFormIgnoreCase(baseForm)` |
| `VerbNotFoundException.java` | Domain exception → 404 via `GlobalExceptionHandler` |

#### `verb/infrastructure/`

| File | Purpose |
|------|---------|
| `VerbJpaRepository.java` | `JpaRepository` + `VerbRepository` impl. JPQL `search` query: `lower(base_form) LIKE %q%` OR `lower(meaning_vi) LIKE %q%`. Derived `findByBaseFormIgnoreCase` |

### Database Migrations — `engly-be/src/main/resources/db/migration/`

| File | Purpose |
|------|---------|
| `V1__create_irregular_verb_table.sql` | Creates `irregular_verb` table + two case-insensitive indexes |
| `V2__seed-irregular-verbs-180-rows-ai-draft-unverified.sql` | Seeds 179 irregular verbs (AI draft, `verified=false`) |

### Config & Build — `engly-be/`

| File | Purpose |
|------|---------|
| `src/main/resources/application.yml` | Server port 8081, datasource (env vars `DB_USER`/`DB_PASSWORD`, defaults `engly`/`engly`), JPA validate, Flyway enabled |
| `pom.xml` | Maven parent: spring-boot-starter-parent 3.4.5. Testcontainers 1.21.0, maven-surefire-plugin with system property `api.version=1.44` and env `TESTCONTAINERS_RYUK_DISABLED=true` |
| `Dockerfile` | Multi-stage Maven build → eclipse-temurin:21-jre, port 8081 |

### Tests — `engly-be/src/test/java/com/engly/`

| File | Purpose | Type |
|------|---------|------|
| `EnglyApplicationTests.java` | Context loads with Testcontainers Postgres (Flyway V1+V2 run) | Smoke / integration |
| `verb/VerbQueryServiceTest.java` | Unit tests for `VerbQueryService` (Mockito) — pagination validation, mapping, exceptions | Unit |
| `verb/VerbControllerTest.java` | `@WebMvcTest` — HTTP layer tests (search/detail 200, 404, 400) — mocks service | Web layer |
| `verb/VerbApiIntegrationTest.java` | `@SpringBootTest` + Testcontainers Postgres — real Flyway seed, real SQL queries, full stack | Integration |
| `common/SecurityRulesTest.java` | `@WebMvcTest` — asserts `permitAll` lets anonymous requests reach `/api/verbs/**`; TODO stub for phase-05 `/api/me→401` | Security |

---

## Frontend — `engly-fe/` (Angular 19.2, Node 24, standalone components)

### Root

| File | Purpose |
|------|---------|
| `src/main.ts` | Bootstraps Angular app with `provideHttpClient`, `provideRouter` |
| `src/app/app.routes.ts` | Top-level routes — lazy-loads `verb-search.routes` |
| `src/app/app.component.ts` | Root component with `<router-outlet>` |
| `proxy.conf.json` | Dev proxy: `/api/**` → `http://localhost:8081` |
| `angular.json` | Workspace config (build + test targets) |
| `tsconfig.json` / `tsconfig.spec.json` | TypeScript config (strict mode) |

### `src/app/features/verb-search/` — Verb feature module

| File | Purpose |
|------|---------|
| `verb-search.routes.ts` | Feature routes: `/` → `VerbSearchPageComponent`, `/verbs/:baseForm` → `VerbDetailPageComponent` |
| `verb-search-page.component.ts` | Search page: `FormControl` with `debounceTime(300)` + `distinctUntilChanged` → `VerbApiService.search`. Signals: `results`, `loading`, `error`, `totalElements` |
| `verb-search-page.component.html` | Search input + results table with `@for` / `@if` control flow. Inline styles |
| `verb-detail-page.component.ts` | Detail page: reads `:baseForm` from `ActivatedRoute.paramMap`, calls `VerbApiService.getByBaseForm`. Signals: `verb`, `loading`, `notFound`, `error` |
| `verb-detail-page.component.html` | Detail card with all verb fields; not-found + error states. Inline styles |
| `data/verb-api.service.ts` | `HttpClient` wrapper for `/api/verbs` — `search(q, page, size)` + `getByBaseForm(baseForm)`. `withCredentials:true` ready for SSO cookies |
| `data/verb.model.ts` | TypeScript interfaces: `VerbResponse`, `VerbSearchPageResponse` (mirrors backend DTOs) |

### Frontend Tests & Build

| File | Purpose |
|------|---------|
| `src/features/verb-search/verb-search-page.component.spec.ts` | `HttpTestingController` + `fakeAsync/tick` — debounce timing, results render, loading/error/empty states |
| `src/features/verb-search/verb-detail-page.component.spec.ts` | `ActivatedRoute` stub — detail render on 200, `notFound` signal + UI on 404, `error` signal on 500 |
| `Dockerfile` | Node 22-alpine, `ng build` → nginx:alpine serving `dist/frontend/browser`; `nginx.conf` proxies `/api` → `http://engly-be:8081`, SPA fallback |
| `nginx.conf` | Reverse proxy: `/api/**` → engly-be:8081; all others → index.html (SPA mode); no CORS (single origin in k8s) |
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
| Verb lookup API (GET /api/verbs, /api/verbs/:base) | DONE |
| Postgres schema + 179-row seed (Flyway V1+V2) | DONE |
| Angular search + detail pages | DONE |
| Integration tests (Testcontainers) | DONE |
| Security rules tests | DONE |
| Frontend component tests | DONE |
| Kubernetes deployment (OrbStack) | DONE |
| SSO / BFF (OAuth2 + OPEN-GATE, /api/me) | DEFERRED — phase 05 |
| Human verification of verb data | PENDING |
