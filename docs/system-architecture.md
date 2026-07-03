# System Architecture

## Overview

Engly is a **modular monolith** organised by feature (package-by-feature), not by layer. Delivers:
- Public irregular-verb lookup API (paginated search, detail by form)
- App-owned JWT auth (email/password sign-up; access+refresh in httpOnly cookies); OPEN-GATE SSO optional
- Admin-only CRUD for verbs (create/read/update/hard-delete + verified toggle)
- Role-based access control (admin role from local DB roles, embedded in the app JWT)
- Full Angular SPA with search, detail, admin pages (lazy-loaded, guarded)
- Kubernetes deployment on OrbStack

---

## Package-by-Feature Layout

```
engly-be/src/main/java/com/engly/
├── EnglyApplication.java           # Spring Boot entry point
├── common/
│   ├── SecurityConfig.java         # Chain 1 (OPEN-GATE SSO /oauth2,/login) + Chain 2 (stateless resource server, app JWT from cookie); CSRF
│   ├── GlobalExceptionHandler.java # HTTP error mapping (404, 409, 401 login/refresh, 400, 500). Covers verb/vocab/study/identity domains
│   └── OidcRoleMappingConfig.java  # roles claim → ROLE_* authority helper (shape-tolerant)
├── identity/                        # App-owned auth (JWT) + OPEN-GATE SSO
│   ├── api/
│   │   ├── AuthController.java       # POST /api/auth/{register,login,refresh,logout}
│   │   └── SessionController.java    # GET /api/me → {sub, email, name, isAdmin}
│   ├── application/                  # AuthTokenService, UserAccountService, OpenGateSsoSuccessHandler, CookieBearerTokenResolver
│   ├── config/                       # JwtKeyConfig (RSA encoder/decoder), AuthProperties
│   ├── domain/                       # UserAccount, RefreshToken (+ auth exceptions)
│   └── infrastructure/               # UserAccountRepository, RefreshTokenRepository
├── vocab/                           # Vocabulary admin (content management)
│   ├── admin/
│   │   ├── DeckAdminController.java         # /api/admin/decks CRUD (ROLE_ADMIN)
│   │   ├── DeckAdminService.java            # @Transactional deck CRUD
│   │   ├── VocabWordAdminController.java    # /api/admin/vocab-words CRUD + PATCH /verified
│   │   └── VocabWordAdminService.java       # @Transactional word CRUD; deckId→404
│   ├── api/dto/                             # DeckRequest/Response/Page, VocabWordRequest/Response/Page
│   ├── domain/
│   │   ├── VocabDeck.java                   # JPA entity: id, name, slug (unique), description, topic
│   │   ├── VocabWord.java                   # JPA entity: @ManyToOne VocabDeck (lazy), term, meaningVi, ipa, examples, verified
│   │   ├── VocabDeckNotFoundException.java  # → 404
│   │   └── VocabWordNotFoundException.java  # → 404
│   └── infrastructure/
│       ├── VocabDeckJpaRepository.java      # findBySlug, search(q, pageable)
│       └── VocabWordJpaRepository.java      # findByDeck_Id, search, countByDeck_Id, findIdsByDeckId
├── study/                           # Per-user SRS study engine
│   ├── api/
│   │   ├── StudyDeckController.java         # GET /api/study/decks, GET /api/study/decks/{id}/queue
│   │   ├── StudyReviewController.java       # POST /api/study/review
│   │   ├── StudySettingsController.java     # GET/PUT /api/study/settings
│   │   └── dto/                             # ReviewRating (enum), ReviewRequest, ReviewResultResponse, StudyCardResponse, StudyDeckSummary, StudySettings*
│   ├── application/
│   │   ├── Sm2Scheduler.java                # Pure SM-2 function: AGAIN/HARD/GOOD/EASY → Sm2Result (no I/O)
│   │   ├── Sm2Input.java                    # Record: easeFactor, intervalDays, repetitions, lapses, rating
│   │   ├── Sm2Result.java                   # Record: easeFactor, intervalDays, repetitions, lapses, state
│   │   ├── StudyClock.java                  # Centralized clock (Asia/Saigon TZ); test constructor accepts Clock
│   │   ├── ReviewService.java               # @Transactional: load/lazy-create card state → Sm2Scheduler → persist; dueOn = today+interval
│   │   ├── StudyDeckService.java            # listDecks with per-user new/due/learned counts
│   │   ├── StudyQueueService.java           # buildQueue: due cards first, then new up to allowance; lazy-creates NEW state rows
│   │   └── StudySettingsService.java        # getOrCreate + update user settings (newCardsPerDay, maxReviewsPerDay)
│   ├── domain/
│   │   ├── UserCardState.java               # JPA entity: user_sub (OIDC sub), vocab_word_id, state, easeFactor, intervalDays, reps, lapses, dueOn
│   │   ├── UserStudySettings.java           # JPA entity: PK=user_sub, newCardsPerDay=20, maxReviewsPerDay=null
│   │   └── CardLearningState.java           # Enum: NEW, LEARNING, REVIEW
│   └── infrastructure/
│       ├── UserCardStateJpaRepository.java  # findByUserSubAndVocabWordId, findDueCards, countIntroducedToday, findWordIdsWithStateForDeck
│       └── UserStudySettingsJpaRepository.java
└── verb/
    ├── api/
    │   ├── VerbController.java             # Public: GET /api/verbs, /api/verbs/{baseForm}
    │   └── dto/
    │       ├── VerbResponse.java           # Single-verb DTO (record)
    │       └── VerbSearchPageResponse.java # Paginated envelope DTO (record)
    ├── admin/
    │   ├── VerbAdminController.java        # Admin: GET/POST/PUT/DELETE/PATCH /api/admin/verbs/**
    │   ├── VerbAdminService.java           # @Transactional admin mutations
    │   └── dto/
    │       ├── VerbRequest.java            # Create/update DTO (bean validation)
    │       ├── VerbAdminResponse.java      # Admin response DTO (incl. numeric id)
    │       ├── VerbVerifiedRequest.java    # PATCH /verified body
    │       └── VerbAdminPageResponse.java  # Paginated response envelope
    ├── application/
    │   └── VerbQueryService.java   # Read-only service; validates params, delegates to repo
    ├── domain/
    │   ├── IrregularVerb.java      # JPA entity; update(...) + setVerified mutators
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

## Request Flow — Public Verb Lookup

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
SecurityFilterChain  (permitAll for /api/verbs/**)
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

## Request Flow — Admin CRUD

```
Browser (authenticated admin)
  │  PUT /api/admin/verbs/1   (X-XSRF-TOKEN header, from XSRF-TOKEN cookie)
  ▼
Angular AdminFormPageComponent
  │
  ▼
SecurityFilterChain  (hasRole('ADMIN') for /api/admin/**)
  │ ← extracts OIDC id_token roles claim via OidcRoleMappingConfig
  │ ← maps to ROLE_ADMIN authority
  ▼
VerbAdminController.update(id, request)
  │
  ▼
VerbAdminService.update(id, request)  @Transactional
  │
  ▼
VerbJpaRepository.findById(id)  →  IrregularVerb
  │
  ▼
entity.update(request)  →  mutate fields
  │
  ▼
VerbJpaRepository.save(entity)
  │  UPDATE irregular_verb SET … WHERE id = ?
  ▼
PostgreSQL
  │
  ▼
VerbAdminResponse DTO  →  200 OK
  │
  ▼
AdminFormPageComponent  updates list
```

Non-admins or anonymous hitting `/api/admin/**` receive 403 (authenticated non-admin) or 401 (anonymous).

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

## Authentication — app-owned JWT + OPEN-GATE SSO

The app issues its OWN JWTs for email/password accounts; OPEN-GATE is one SSO option, not the
token issuer. Access + refresh tokens are delivered as httpOnly cookies (never exposed to JS).
RBAC is sourced from local DB roles (`app_user_role`), embedded in the access JWT `roles` claim.

**Identity model.** `app_user.sub` is the canonical user key (also the study tables' `user_sub`).
Password users get a generated `local-<uuid>` sub; SSO users link by email and keep the existing
sub (OPEN-GATE's sub for brand-new SSO accounts), so prior study progress stays attached.

**Implementation map**
- BE `identity/config/JwtKeyConfig.java` + `AuthProperties`: RSA `JwtEncoder`/`JwtDecoder` (keys
  from `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`, else an ephemeral dev keypair); access/refresh TTLs;
  cookie names/flags (`AUTH_COOKIE_SECURE`, SameSite=Lax).
- BE `identity/application/AuthTokenService.java`: mints the access JWT, issues/rotates opaque
  refresh tokens (SHA-256 hash persisted in `refresh_token`), writes/clears the httpOnly cookies.
- BE `identity/application/UserAccountService.java`: register / password login / SSO provision.
- BE `identity/api/AuthController.java`: `POST /api/auth/{register,login,refresh,logout}`.
- BE `identity/application/OpenGateSsoSuccessHandler.java`: on OPEN-GATE login, provision/link the
  local user and issue the app's own cookies, then redirect to the SPA.
- BE `common/SecurityConfig.java`: Chain 1 (`/oauth2/**`,`/login/**`) = OPEN-GATE SSO handshake;
  Chain 2 (stateless) = resource server validating the app JWT from the access cookie
  (`CookieBearerTokenResolver`), `/api/admin/**`→`hasRole('ADMIN')`, `CookieCsrfTokenRepository`.
- BE `common/OidcRoleMappingConfig.java`: shape-tolerant `roles` claim → ROLE_* helper.
- BE `identity/api/SessionController.java`: `GET /api/me` → `{sub,email,name,isAdmin}` from the JWT.
- FE `core/auth.service.ts`: `loginWithPassword()`, `register()`, `loginWithOpenGate()` (→
  `/oauth2/authorization/opengate`), `refresh()`, `logout()`; `core/auth-refresh.interceptor.ts`
  retries once via `/api/auth/refresh` on 401. XSRF-TOKEN cookie → X-XSRF-TOKEN for mutations.
- Tests: BE `AuthControllerTest`, `AuthTokenServiceTest`, `UserAccountServiceTest`,
  `OpenGateSsoSuccessHandlerTest`, `AuthFlowIntegrationTest`, `Admin/SecurityRulesTest`.

### Password login flow (app-issued JWT)

```mermaid
sequenceDiagram
    participant Browser
    participant Angular (FE)
    participant Spring (:8081)

    Browser->>Angular (FE): Submit login form
    Angular (FE)->>Spring (:8081): POST /api/auth/login {email,password}
    Spring (:8081)-->>Browser: 200 + Set-Cookie ACCESS_TOKEN, REFRESH_TOKEN (httpOnly)
    Angular (FE)->>Spring (:8081): GET /api/me (cookie auto-sent)
    Spring (:8081)-->>Browser: 200 { sub, email, name, isAdmin }
    Note over Angular (FE),Spring (:8081): On 401, interceptor POSTs /api/auth/refresh once, then retries
```

### SSO flow (OPEN-GATE as one option)

```mermaid
sequenceDiagram
    participant Browser
    participant Spring (:8081)
    participant OPEN-GATE IdP

    Browser->>Spring (:8081): GET /oauth2/authorization/opengate
    Spring (:8081)-->>Browser: 302 → IdP /authorize (PKCE)
    Browser->>OPEN-GATE IdP: Authorize + login
    OPEN-GATE IdP-->>Browser: 302 → /login/oauth2/code/opengate?code=…
    Browser->>Spring (:8081): callback (code exchange, id_token verified via JWKS)
    Note over Spring (:8081): SsoSuccessHandler: provision/link local user by email
    Spring (:8081)-->>Browser: 302 → SPA + Set-Cookie ACCESS_TOKEN/REFRESH_TOKEN (app JWT)
```

### Admin CRUD Implementation (completed)
- `SecurityConfig`: stateless resource server + `CookieCsrfTokenRepository`, `/api/admin/**` guarded by `hasRole('ADMIN')`.
- Admin role from the app JWT `roles` claim (sourced from `app_user_role`).
- `VerbAdminController` + `VerbAdminService`: CRUD + PATCH /verified endpoint.
- `SessionController.me()`: returns `isAdmin` boolean.
- Frontend: `withXsrfConfiguration`, `AuthService.isAdmin` signal, admin guard → `/forbidden` on non-admin.
- Admin feature: list (search + table + verified toggle), create/edit form, ForbiddenPageComponent.

---

## Database Schema

### V1 — `irregular_verb`
```sql
CREATE TABLE irregular_verb (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base_form        varchar(100) NOT NULL UNIQUE,
    ...
    meaning_vi       varchar(255) NOT NULL,
    verified         boolean      NOT NULL DEFAULT false,
    created_at       timestamptz  NOT NULL DEFAULT now()
);
```
Seed: V2 inserts 179 AI-drafted rows (`verified=false`).

### V3 — Vocabulary + Study tables (added Phase 1)
```sql
CREATE TABLE vocab_deck (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        varchar(120) NOT NULL,
    slug        varchar(140) NOT NULL,
    description varchar(500),
    topic       varchar(80),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_vocab_deck_slug UNIQUE (slug)
);

CREATE TABLE vocab_word (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deck_id      bigint NOT NULL REFERENCES vocab_deck(id) ON DELETE CASCADE,
    term         varchar(120) NOT NULL,
    meaning_vi   varchar(255) NOT NULL,
    ipa          varchar(120),
    example_en   varchar(500),
    example_vi   varchar(500),
    part_of_speech varchar(40),
    verified     boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_card_state (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_sub      varchar(100) NOT NULL,  -- OIDC subject claim
    vocab_word_id bigint NOT NULL,
    state         varchar(16) NOT NULL DEFAULT 'NEW',  -- NEW|LEARNING|REVIEW
    ease_factor   numeric(5,3) NOT NULL DEFAULT 2.5,
    interval_days int NOT NULL DEFAULT 0,
    repetitions   int NOT NULL DEFAULT 0,
    lapses        int NOT NULL DEFAULT 0,
    due_on        date,
    last_reviewed_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_word UNIQUE (user_sub, vocab_word_id)
);

CREATE TABLE user_study_settings (
    user_sub          varchar(100) PRIMARY KEY,
    new_cards_per_day int NOT NULL DEFAULT 20,
    max_reviews_per_day int,
    updated_at        timestamptz NOT NULL DEFAULT now()
);
```
Seed: V4 inserts 2 decks (Travel, Business) × 20 words each (`verified=false`), Asia/Saigon authored.

### Conflict message
All `DataIntegrityViolationException` → 409 `{"message":"resource already exists"}` (generic, no SQL leak).

---

## Security Posture

| Concern         | Current State                          | Notes                                |
|-----------------|----------------------------------------|--------------------------------------|
| Authentication  | App-issued JWT (email/password); OPEN-GATE SSO optional | Access+refresh as httpOnly cookies |
| CSRF            | CookieCsrfTokenRepository (httpOnly=false) | XSRF-TOKEN cookie → X-XSRF-TOKEN header; `/api/auth/{login,register,refresh}` exempt |
| Public routes   | `/api/auth/**`, `/api/verbs/**`, `/oauth2/**`, `/login/**`, root/static | All permitAll   |
| Protected routes| `/api/me` → authenticated (401 anon), `/api/admin/**` → admin (403 non-admin), `/api/study/**` → authenticated (401 anon) | Role-based |
| Admin role      | App JWT `roles` claim, sourced from `app_user_role` | Local DB is the RBAC source |
| Study routes    | Resource-server chain — any authenticated user; user_sub derived from `Jwt.getSubject()` (no IDOR) | CSRF required for POST/PUT |
| Token storage   | httpOnly cookies; access JWT (~15m) + rotating refresh (hash stored, raw only in cookie) | RSA-signed local key |
| Input validation| Search size [1,100]; bean validation on all admin/study DTOs | Parameterised SQL; LIKE escape |
| Error responses | No stack traces, generic 500; 409 "resource already exists" on any unique constraint violation | GlobalExceptionHandler covers all domains |
| Frontend XSS    | Angular standalone components, no external template injection | Trusted templates |

## SM-2 Scheduling (Sm2Scheduler — pure function)

| Rating | EF change     | Interval                     | Reps   | State    |
|--------|---------------|------------------------------|--------|----------|
| AGAIN  | max(1.3, ef−0.20) | 1                        | reset 0 | LEARNING |
| HARD   | max(1.3, ef−0.15) | max(1, round(interval×1.2)) | reps++ | REVIEW   |
| GOOD   | unchanged     | reps=0→1; reps=1→6; else round(interval×ef) | reps++ | REVIEW |
| EASY   | ef+0.15       | max(1, round(interval×ef×1.3)) | reps++ | REVIEW |

EF floor: 1.3. New card EF: 2.5. dueOn = today(Asia/Saigon) + intervalDays. Clock centralised in `StudyClock` (fixed-Clock constructor for tests).
