# System Architecture

## Overview

Engly is a **modular monolith** organised by feature (package-by-feature), not by layer. Delivers:
- Public irregular-verb lookup API (paginated search, detail by form)
- App-owned JWT auth (email/password sign-up; access+refresh in httpOnly cookies)
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
│   ├── SecurityConfig.java         # Stateless resource server (app JWT from cookie); CSRF
│   ├── GlobalExceptionHandler.java # HTTP error mapping (404, 409, 401 login/refresh, 400, 500). Covers verb/vocab/study/identity domains
│   └── OidcRoleMappingConfig.java  # roles claim → ROLE_* authority helper (shape-tolerant)
├── identity/                        # App-owned auth (JWT)
│   ├── api/
│   │   ├── AuthController.java       # POST /api/auth/{register,login,refresh,logout,verify-email,resend-verification}
│   │   └── SessionController.java    # GET /api/me → {sub, email, name, isAdmin}
│   ├── application/                  # AuthTokenService, UserAccountService, CookieBearerTokenResolver,
│   │                                 # EmailVerificationService, ActivationEmailSender, TokenHashing
│   ├── config/                       # JwtKeyConfig (RSA encoder/decoder), AuthProperties,
│   │                                 # EmailVerificationProperties, MailConfig
│   ├── domain/                       # UserAccount, RefreshToken, EmailVerificationToken (+ auth exceptions)
│   └── infrastructure/               # UserAccountRepository, RefreshTokenRepository, EmailVerificationTokenRepository
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
│   │   ├── StudyStatsController.java        # GET /api/study/stats/{summary,activity,forecast,accuracy}; days bounded [1,90] (400 otherwise)
│   │   └── dto/                             # ReviewRating (enum), ReviewRequest, ReviewResultResponse, StudyCardResponse, StudyDeckSummary, StudySettings*, StudySummaryResponse, ActivityPointResponse, ForecastPointResponse, AccuracyPointResponse
│   ├── application/
│   │   ├── Sm2Scheduler.java                # Pure SM-2 function: AGAIN/HARD/GOOD/EASY → Sm2Result (no I/O)
│   │   ├── Sm2Input.java                    # Record: easeFactor, intervalDays, repetitions, lapses, rating
│   │   ├── Sm2Result.java                   # Record: easeFactor, intervalDays, repetitions, lapses, state
│   │   ├── StudyClock.java                  # Centralized clock (Asia/Saigon TZ); test constructor accepts Clock
│   │   ├── ReviewService.java               # @Transactional: load/lazy-create card state → Sm2Scheduler → persist; dueOn = today+interval; writes ReviewLog row
│   │   ├── StudyDeckService.java            # listDecks with per-user new/due/learned counts
│   │   ├── StudyQueueService.java           # buildQueue: due cards first, then new up to allowance; lazy-creates NEW state rows
│   │   ├── StudySettingsService.java        # getOrCreate + update user settings (newCardsPerDay, maxReviewsPerDay)
│   │   └── StudyStatsService.java           # Aggregate review_log via native SQL; gap-fill missing dates; currentStreak helper; date bucketing Asia/Saigon TZ
│   ├── domain/
│   │   ├── UserCardState.java               # JPA entity: user_sub (OIDC sub), vocab_word_id, state, easeFactor, intervalDays, reps, lapses, dueOn
│   │   ├── UserStudySettings.java           # JPA entity: PK=user_sub, newCardsPerDay=20, maxReviewsPerDay=null
│   │   ├── CardLearningState.java           # Enum: NEW, LEARNING, REVIEW
│   │   └── ReviewLog.java                   # JPA entity: append-only history; user_sub, vocab_word_id, deck_id, rating, prev_state, new_state, interval_days, ease_factor, reviewed_at
│   └── infrastructure/
│       ├── UserCardStateJpaRepository.java  # findByUserSubAndVocabWordId, findDueCards, countIntroducedToday, findWordIdsWithStateForDeck
│       ├── UserStudySettingsJpaRepository.java
│       └── ReviewLogJpaRepository.java      # Read-only; custom native queries for stats (summary, activity, forecast, accuracy)
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
| Charting     | chart.js (direct use)             | 4        |
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

## Authentication — app-owned JWT

The app issues its OWN JWTs for email/password accounts. Access + refresh tokens are delivered as httpOnly cookies (never exposed to JS).
RBAC is sourced from local DB roles (`app_user_role`), embedded in the access JWT `roles` claim.

**Identity model.** `app_user.sub` is the canonical user key (also the study tables' `user_sub`).
Password users get a generated `local-<uuid>` sub; social (Google/Facebook) users get a generated
`sso-<uuid>` sub. A provider account is linked to an `app_user` via `app_user_identity`
(`provider`,`provider_user_id` unique). `app_user.email` is nullable (Facebook may return none).

**Implementation map**
- BE `identity/config/JwtKeyConfig.java` + `AuthProperties`: RSA `JwtEncoder`/`JwtDecoder` (keys
  from `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`, else an ephemeral dev keypair); access/refresh TTLs;
  cookie names/flags (`AUTH_COOKIE_SECURE`, SameSite=Lax).
- BE `identity/application/AuthTokenService.java`: mints the access JWT, issues/rotates opaque
  refresh tokens (SHA-256 hash persisted in `refresh_token`), writes/clears the httpOnly cookies.
- BE `identity/application/UserAccountService.java`: register / password login.
- BE `identity/api/AuthController.java`: `POST /api/auth/{register,login,refresh,logout}`.
- BE `common/SecurityConfig.java`: Stateless resource server validating the app JWT from the access cookie
  (`CookieBearerTokenResolver`), `/api/admin/**`→`hasRole('ADMIN')`, `CookieCsrfTokenRepository`.
- BE `common/OidcRoleMappingConfig.java`: shape-tolerant `roles` claim → ROLE_* helper.
- BE `identity/api/SessionController.java`: `GET /api/me` → `{sub,email,name,isAdmin}` from the JWT.
- FE `core/auth.service.ts`: `loginWithPassword()`, `register()`, `refresh()`, `logout()`; `core/auth-refresh.interceptor.ts`
  retries once via `/api/auth/refresh` on 401. XSRF-TOKEN cookie → X-XSRF-TOKEN for mutations.
- Tests: BE `AuthControllerTest`, `AuthTokenServiceTest`, `UserAccountServiceTest`, `AuthFlowIntegrationTest`, `Admin/SecurityRulesTest`.

### Social login (Google / Facebook)

Social login reuses the same app-issued cookie session — the OAuth2 handshake is the only stateful part.

- **Two ordered filter chains** (`common/SecurityConfig.java`): Chain A (`@Order(1)`,
  `securityMatcher("/oauth2/**","/login/oauth2/**")`, `IF_REQUIRED` session, CSRF off, `oauth2Login()`)
  handles only the handshake; the existing stateless `/api/**` chain (`@Order(2)`) is unchanged. Chain A
  and the provider registry exist only when a provider is configured (`@ConditionalOnExpression` on
  `GOOGLE_CLIENT_ID`/`FACEBOOK_CLIENT_ID`), so the app boots password-only when SSO is unset.
- **`identity/config/SocialLoginClientsConfig.java`**: builds the `ClientRegistrationRepository` from env
  (only non-blank providers); overrides Facebook's built-in to a current Graph API version.
- **`identity/application/SocialLoginService.java`**: resolve-or-link. Returning identity → its account;
  else **verified-both-sides** linking — a Google-verified email links to an existing account only when
  that account is itself `email_verified` (password accounts are never verified → never silently merged,
  preventing pre-account-hijacking); Facebook is never email-linked. New social users get a fresh account
  (`SocialAccountCreator`, `REQUIRES_NEW` so a concurrent first login isolates cleanly).
- **`identity/application/SsoAuthenticationSuccessHandler.java`**: resolves the user, calls
  `AuthTokenService.issueSession(...)` (same cookies as password login), drops the handshake session, and
  redirects to `app.frontend-url` (fixed server-side value — never request-derived, so no open redirect).
- FE `features/auth/social-login-buttons.component.ts` + `core/auth.service.ts#loginWithProvider()`:
  full-page navigation to `/oauth2/authorization/{provider}`; failure returns to `/login?error=sso`.
- **Dev origin note**: `engly-fe/proxy.conf.json` proxies `/oauth2` + `/login/oauth2` with
  `changeOrigin:false` so the handshake session and issued cookies stay on the SPA origin (:4200);
  provider redirect URIs must therefore be registered against that same origin.
- Tests: BE `SocialLoginServiceTest`, `SsoSuccessHandlerTest`, `SecurityOauth2ChainTest`.

```mermaid
sequenceDiagram
    participant Browser
    participant Spring (:8081)
    participant Provider as Google/Facebook

    Browser->>Spring (:8081): GET /oauth2/authorization/{provider}
    Spring (:8081)-->>Browser: 302 to provider (state in session)
    Browser->>Provider: authorize + consent
    Provider-->>Browser: 302 /login/oauth2/code/{provider}?code
    Browser->>Spring (:8081): callback (code, state)
    Note over Spring (:8081): resolve-or-link app_user → issueSession()
    Spring (:8081)-->>Browser: Set-Cookie ACCESS/REFRESH + 302 to app.frontend-url
    Browser->>Spring (:8081): GET /api/me (cookie auto-sent) → authenticated
```

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

### Email activation on registration (hard gate)

Self-service (password) sign-up no longer starts a session immediately: the account is created but
unusable until the emailed activation link is clicked. Social (SSO) accounts bypass this entirely —
the provider is the trust anchor for those.

- **Register**: `AuthController.register` creates the account, calls
  `EmailVerificationService.startVerification` (invalidates any prior token, mints a new opaque
  token, stores only its SHA-256 hash in `email_verification_token`, and — only after the
  transaction commits — dispatches the activation email), and returns **202 + `{email}`, no
  cookies**.
- **Login**: `UserAccountService.login` throws `EmailNotVerifiedException` → **403**
  `{code: "EMAIL_NOT_VERIFIED"}` once credentials are correct but the account is unactivated (unknown
  email / bad password still return the generic 401 — never an enumeration oracle).
- **Verify**: `POST /api/auth/verify-email {token}` atomically claims the token
  (`consumeIfUsable` — a single conditional `UPDATE`, immune to a concurrent-verify TOCTOU race),
  marks the account verified, and issues the session (auto-login). Rejects with 409 if the caller is
  already authenticated as someone else (login-CSRF guard — this endpoint is CSRF-exempt since it
  runs pre-cookie).
- **Resend**: `POST /api/auth/resend-verification {email}` always returns 202 (no account-existence
  enumeration); silently no-ops for an unknown/already-verified email or one still in cooldown
  (default 60s).
- **Rollback switch**: `EMAIL_VERIFICATION_ENABLED=false` reverts to the pre-feature flow —
  register auto-verifies and issues a session immediately, login is never gated. No redeploy needed.
- **Backfill**: migration `V11` sets `email_verified=true` for every pre-existing password account
  (`password_hash IS NOT NULL`), so only new sign-ups are gated — no mass lockout.
- **SSO interaction**: activating a password account (`email_verified` false→true) makes it a valid
  `SocialLoginService` link target — a user who later signs in with the same verified Google email
  lands in the same merged account (intended "verified-both-sides" UX, not an accident).
- Tests: BE `EmailVerificationServiceTest`, `AuthActivationFlowTest`; FE
  `verify-email-page.component.spec.ts`, `auth.service.spec.ts`, `register-page.component.spec.ts`,
  `login-page.component.spec.ts`.

```mermaid
sequenceDiagram
    participant Browser
    participant Spring (:8081)
    participant SMTP

    Browser->>Spring (:8081): POST /api/auth/register
    Spring (:8081)-->>Browser: 202 {email} (no cookies)
    Spring (:8081)-->>SMTP: activation email (post-commit, non-fatal on failure)
    Browser->>Spring (:8081): POST /api/auth/verify-email {token} (from emailed link)
    Spring (:8081)-->>Browser: 200 + Set-Cookie ACCESS/REFRESH (auto-login)
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

### V9 — `review_log` (append-only study history)
```sql
CREATE TABLE review_log (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_sub          varchar(100) NOT NULL,
    vocab_word_id     bigint NOT NULL REFERENCES vocab_word(id) ON DELETE CASCADE,
    deck_id           bigint NOT NULL REFERENCES vocab_deck(id) ON DELETE CASCADE,
    rating            varchar(20) NOT NULL,  -- AGAIN|HARD|GOOD|EASY
    prev_state        varchar(16) NOT NULL,  -- NEW|LEARNING|REVIEW
    new_state         varchar(16) NOT NULL,  -- NEW|LEARNING|REVIEW
    interval_days     int NOT NULL,
    ease_factor       numeric(5,3) NOT NULL,
    reviewed_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT idx_review_log_user_date UNIQUE (user_sub, reviewed_at)
);
CREATE INDEX idx_review_log_user_reviewed_at ON review_log(user_sub, reviewed_at);
CREATE INDEX idx_review_log_user_deck_reviewed_at ON review_log(user_sub, deck_id, reviewed_at);
```
**Forward-only history** — Rows are never updated or deleted; one row written per `POST /api/study/review`. Stats APIs aggregate via native SQL queries with date bucketing in `Asia/Saigon` TZ.

### V11 — `email_verification_token` (email activation on registration)
```sql
CREATE TABLE email_verification_token (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  varchar(255) NOT NULL,           -- SHA-256 of the opaque token; raw value never stored
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,                     -- NULL = unused; single-use enforced atomically in the repo
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_evt_token_hash UNIQUE (token_hash)
);
```
Backfill in the same migration: `UPDATE app_user SET email_verified = true WHERE password_hash IS
NOT NULL AND email_verified = false` — grandfathers every pre-existing password account so the new
login gate does not lock out the current user base.

### Conflict message
All `DataIntegrityViolationException` → 409 `{"message":"resource already exists"}` (generic, no SQL leak).

---

## Security Posture

| Concern         | Current State                          | Notes                                |
|-----------------|----------------------------------------|--------------------------------------|
| Authentication  | App-issued JWT (email/password) | Access+refresh as httpOnly cookies |
| Email activation | Hard gate on password sign-up — no session until the emailed link is clicked | Kill-switch `EMAIL_VERIFICATION_ENABLED`; pre-existing accounts grandfathered by V11 backfill |
| CSRF            | CookieCsrfTokenRepository (httpOnly=false) | XSRF-TOKEN cookie → X-XSRF-TOKEN header; `/api/auth/{login,register,refresh,verify-email,resend-verification}` exempt |
| Public routes   | `/api/auth/**`, `/api/verbs/**`, root/static | All permitAll   |
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
