# System Architecture

## Overview

Engly is a **modular monolith** organised by feature (package-by-feature), not by layer. The MVP delivers a public irregular-verb lookup API backed by Postgres and an Angular SPA. SSO/BFF via an external OIDC provider (OPEN-GATE-APP) is **planned for phase 05** and not yet implemented.

---

## Package-by-Feature Layout

```
engly-be/src/main/java/com/engly/
├── EnglyApplication.java           # Spring Boot entry point
├── common/
│   ├── SecurityConfig.java         # Security filter chain (permitAll stub — phase 05 TODO)
│   └── GlobalExceptionHandler.java # Centralised HTTP error mapping
└── verb/
    ├── api/
    │   ├── VerbController.java             # REST: GET /api/verbs, /api/verbs/{baseForm}
    │   └── dto/
    │       ├── VerbResponse.java           # Single-verb API DTO (record)
    │       └── VerbSearchPageResponse.java # Paginated envelope DTO (record)
    ├── application/
    │   └── VerbQueryService.java   # Validates params, delegates to repo, maps DTOs
    ├── domain/
    │   ├── IrregularVerb.java      # JPA entity → irregular_verb table
    │   ├── VerbRepository.java     # Port interface (search, findByBaseFormIgnoreCase)
    │   └── VerbNotFoundException.java
    └── infrastructure/
        └── VerbJpaRepository.java  # Spring Data JPA impl of VerbRepository port
```

---

## Tech Stack

| Layer        | Technology                        | Version  |
|--------------|-----------------------------------|----------|
| Language     | Java                              | 21       |
| Framework    | Spring Boot                       | 3.4.5    |
| Build        | Maven (spring-boot-starter-parent)| 3.4.5    |
| Persistence  | Spring Data JPA + Hibernate       | Boot-managed |
| Database     | PostgreSQL                        | 16       |
| Migrations   | Flyway                            | Boot-managed |
| Security     | Spring Security                   | Boot-managed |
| Frontend     | Angular (standalone components)   | 19.2     |
| HTTP client  | Angular HttpClient                | 19.2     |
| Node         | Node.js                           | 24       |
| Tests (BE)   | JUnit 5, Testcontainers, Mockito  | Boot-managed / TC 1.21.0 |
| Tests (FE)   | Karma + Jasmine                   | 6.4 / 5.6 |

---

## Port Table

| Service             | Port | Status / Access                 |
|---------------------|------|-----------------------------------|
| PostgreSQL (dev)    | 5433 | docker compose (host mapped)      |
| Backend (Spring)    | 8081 | `./mvnw spring-boot:run` (dev)   |
| Frontend (Angular)  | 4200 | `npm start` (dev, proxies /api)  |
| Kubernetes (k8s) in-cluster | | |
| PostgreSQL (k8s)    | 5432 | Service `postgres` ClusterIP      |
| Backend (k8s)       | 8081 | Service `engly-be` ClusterIP      |
| Frontend (k8s)      | 80   | Service `engly-fe` LoadBalancer   |
| Frontend (k8s)      | 31485| NodePort (OrbStack external)      |
| OIDC IdP (OPEN-GATE)| 9000 | **PLANNED** — phase 05          |

---

## Request Flow — Verb Lookup

```
Browser
  │  GET /verbs/go
  ▼
Angular Router  (verb-search.routes.ts)
  │
  ▼
VerbDetailPageComponent   (reads :baseForm route param)
  │  GET /api/verbs/go   (withCredentials, proxied to :8081)
  ▼
proxy.conf.json  →  Spring Boot :8081
  │
  ▼
SecurityFilterChain  (permitAll — all requests pass)
  │
  ▼
VerbController.getByBaseForm("go")
  │
  ▼
VerbQueryService.getByBaseForm("go")
  │
  ▼
VerbJpaRepository.findByBaseFormIgnoreCase("go")
  │  SELECT … FROM irregular_verb WHERE lower(base_form)=lower('go')
  ▼
PostgreSQL  →  IrregularVerb entity
  │
  ▼
VerbQueryService  →  VerbResponse DTO
  │
  ▼
VerbController  →  200 OK { baseForm, pastSimple, pastParticiple, … }
  │
  ▼
VerbDetailPageComponent  renders detail card
```

---

## Search Flow — Paginated Query

```
VerbSearchPageComponent
  │  FormControl.valueChanges → debounceTime(300ms) → distinctUntilChanged
  │  GET /api/verbs?q=go&page=0&size=20
  ▼
VerbController.search(q, page, size)
  │
  ▼
VerbQueryService.search(q, page, size)  — validates size ∈ [1,100]
  │
  ▼
VerbJpaRepository.search(q, pageable)
  │  SELECT … WHERE lower(base_form) LIKE '%go%'
  │             OR  lower(meaning_vi) LIKE '%go%'
  ▼
VerbSearchPageResponse { content[], page, size, totalElements, totalPages }
```

---

## Kubernetes Deployment on OrbStack

Production runs on a single OrbStack K8s cluster via kubectl. All manifests in `k8s/` target namespace `engly`.

### Architecture

```
Browser
  │  :31485 (NodePort)
  ▼
engly-fe Service (type: LoadBalancer)  [nginx :80]
  │
  ├─→ requests to / → serve SPA (dist/frontend/browser/index.html)
  └─→ requests to /api/* → reverse-proxy to engly-be:8081 (nginx.conf)

engly-be Service (type: ClusterIP, :8081)  [Spring Boot]
  │  startup/readiness/liveness probes: GET /api/verbs?size=1
  ▼
postgres Service (type: ClusterIP, :5432)  [Postgres 16]
  │
  ▼
postgres PVC (engly-postgres-pvc, tied to in-cluster storage)
```

### Deployment

**One-command deploy:**
```bash
sh k8s/deploy-to-orbstack.sh
```

This script:
1. Builds both Docker images locally: `engly-be:local`, `engly-fe:local` (stored in OrbStack's shared image store).
2. Runs `kubectl apply -f k8s/*.yaml` (applies all manifests in order: namespace, postgres, backend, frontend).
3. Waits for rollouts to stabilize.

**Manifests:**
- `k8s/00-namespace.yaml` — Creates namespace `engly`
- `k8s/10-postgres-database.yaml` — Postgres Secret, PVC, Deployment, ClusterIP Service
- `k8s/20-engly-be-backend.yaml` — Backend Deployment, ClusterIP Service (env `DB_URL=jdbc:postgresql://postgres:5432/engly`, creds from Secret)
- `k8s/30-engly-fe-frontend.yaml` — Frontend Deployment, LoadBalancer Service

**Access:** OrbStack klipper-style LoadBalancer exposes via NodePort. Use `kubectl -n engly get svc engly-fe` to see EXTERNAL-IP and ports. Browser: `http://localhost:31485`.

**Teardown:**
```bash
kubectl delete namespace engly
```

---

## SSO / BFF Integration — IMPLEMENTED (Phase 05)

Phase 05 adds OAuth2 Authorization Code (+ PKCE) login via the OPEN-GATE-APP OIDC
provider, using a Backend-for-Frontend (BFF) pattern so tokens never reach the browser.
Wiring + automated tests are in place; the full live round-trip is validated once the IdP
is running on :9000 and the `engly` client is registered (see phase-05 plan steps 1 & 7).

**Implementation map**
- BE `spring-boot-starter-oauth2-client` + `application.yml` `opengate` registration/provider
  (explicit authorization/token/jwk-set URIs, no issuer-uri → offline-safe startup; secret via
  `OAUTH2_OPENGATE_CLIENT_SECRET` env, never committed).
- BE `common/SecurityConfig.java`: `oauth2Login`, `/api/me` authenticated (rest permitAll),
  `/api/**` 401 entry point (no redirect), `CookieCsrfTokenRepository` + plain request handler,
  `/logout` → `http://localhost:4200/`.
- BE `identity/api/SessionController.java`: `GET /api/me` → `{sub,email,name}` from `OidcUser`.
- FE `core/auth.service.ts`: signal `user`, non-blocking `loadMe()` (401→anonymous),
  `login()`→`/oauth2/authorization/opengate`, `logout()`→POST `/logout`.
- FE app shell: Login/Logout buttons; `loadMe()` on init.
- Tests: BE `SecurityRulesTest` (anon `/api/me`→401, OIDC user→200); FE `auth.service.spec.ts`
  + `app.component.spec.ts`.

### Intended OAuth2/BFF Flow

```mermaid
sequenceDiagram
    participant Browser
    participant Angular (FE :4200)
    participant BFF/Spring (:8081)
    participant OPEN-GATE IdP (:9000)

    Browser->>Angular (FE :4200): Navigate to /verbs
    Angular (FE :4200)->>BFF/Spring (:8081): GET /api/me (withCredentials)
    BFF/Spring (:8081)-->>Browser: 401 Unauthorized (no session)
    Browser->>BFF/Spring (:8081): GET /oauth2/authorization/open-gate
    BFF/Spring (:8081)-->>Browser: 302 → IdP /authorize?response_type=code&...
    Browser->>OPEN-GATE IdP (:9000): Authorization request
    OPEN-GATE IdP (:9000)-->>Browser: Login page
    Browser->>OPEN-GATE IdP (:9000): Submit credentials
    OPEN-GATE IdP (:9000)-->>Browser: 302 → /login/oauth2/code/open-gate?code=…
    Browser->>BFF/Spring (:8081): GET /login/oauth2/code/open-gate?code=…
    BFF/Spring (:8081)->>OPEN-GATE IdP (:9000): POST /token (code exchange)
    OPEN-GATE IdP (:9000)-->>BFF/Spring (:8081): access_token + id_token
    BFF/Spring (:8081)-->>Browser: 302 → / (Set-Cookie: SESSION=…; HttpOnly)
    Browser->>BFF/Spring (:8081): GET /api/me (Cookie: SESSION=…)
    BFF/Spring (:8081)-->>Browser: 200 { sub, email, … }
```

### Phase 05 Changes (planned)
- `SecurityConfig`: replace `permitAll` with `oauth2Login` + `CookieCsrfTokenRepository`; restrict `/api/me` to authenticated users.
- New `identity/` package with `MeController` (GET /api/me → UserInfo projection).
- Register `engly` client in OPEN-GATE-APP (client_id, redirect_uri, scopes: `openid profile email`).
- Frontend: redirect unauthenticated users to `/oauth2/authorization/open-gate`.

---

## Database Schema

```sql
CREATE TABLE irregular_verb (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base_form        varchar(64)  NOT NULL UNIQUE,
    past_simple      varchar(64)  NOT NULL,
    past_participle  varchar(64)  NOT NULL,
    past_simple_alt  varchar(64)  NULL,
    past_participle_alt varchar(64) NULL,
    ipa_base         varchar(96)  NULL,
    ipa_past         varchar(96)  NULL,
    ipa_pp           varchar(96)  NULL,
    meaning_vi       varchar(255) NOT NULL,
    verified         boolean      NOT NULL DEFAULT false,
    created_at       timestamptz  NOT NULL DEFAULT now()
);
```

Indexes: `idx_verb_lower_base` on `lower(base_form)`, `idx_verb_lower_meaning` on `lower(meaning_vi)`.

Seed: V2 migration inserts 179 AI-drafted rows (`verified=false`); human verification pending.

---

## Security Posture (Current MVP)

| Concern         | Current State                          | Notes                                |
|-----------------|----------------------------------------|--------------------------------------|
| Authentication  | OAuth2 OIDC via OPEN-GATE (BFF)         | —                                    |
| CSRF            | CookieCsrfTokenRepository (httpOnly=false) | —                                |
| Public routes   | `/api/verbs/**`, `/oauth2/**`, `/login/**`, root/static | —               |
| Protected routes| `/api/me` → authenticated (401 anon)   | —                                    |
| Token storage   | HttpOnly session cookie (tokens server-side) | —                              |
| Input validation| Size [1,100] + JPA parameterised query | Same                                 |
| Error responses | No stack traces, generic 500 message   | Same                                 |
