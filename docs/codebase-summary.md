# Codebase Summary

**Project:** Engly — English irregular-verb learning app
**MVP status:** Verb lookup DONE; Study progress charts DONE

---

## Backend — `engly-be/` (Spring Boot 3.4.5, Java 21, Maven)

### Entry Point

| File | Purpose |
|------|---------|
| `src/main/java/com/engly/EnglyApplication.java` | `@SpringBootApplication` — bootstraps Spring context |

### `com.engly.common` — Cross-cutting concerns

| File | Purpose |
|------|---------|
| `SecurityConfig.java` | `SecurityFilterChain` — stateless resource server + CookieCsrfTokenRepository; `/api/verbs/**` permitAll (GET); `/api/me` authenticated; `/api/admin/**` hasRole('ADMIN'); `/logout` → SPA |
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

### `com.engly.vocab` — Vocabulary admin (content management)

| File | Purpose |
|------|---------|
| `admin/DeckAdminController.java` | `GET/POST/PUT/DELETE /api/admin/decks` (ROLE_ADMIN) |
| `admin/DeckAdminService.java` | @Transactional deck CRUD; slug dedup → 409 |
| `admin/VocabWordAdminController.java` | `GET/POST/PUT/DELETE /api/admin/vocab-words` + `PATCH /{id}/verified` |
| `admin/VocabWordAdminService.java` | @Transactional word CRUD; resolves deckId → 404 if missing; q OR deckId filter |
| `api/dto/` | DeckRequest/Response/PageResponse, VocabWordRequest/Response/PageResponse/VerifiedRequest |
| `domain/VocabDeck.java` | JPA entity: `vocab_deck` table; slug unique; `update()` + `setSlug()` |
| `domain/VocabWord.java` | JPA entity: `vocab_word`; `@ManyToOne(LAZY) VocabDeck deck`; `update()` + `setVerified()` |
| `domain/VocabDeckNotFoundException.java` | → 404 via GlobalExceptionHandler |
| `domain/VocabWordNotFoundException.java` | → 404 via GlobalExceptionHandler |
| `infrastructure/VocabDeckJpaRepository.java` | `findBySlug`, `search(q, pageable)` (LIKE name/topic) |
| `infrastructure/VocabWordJpaRepository.java` | `findByDeck_Id`, `search`, `countByDeck_Id`, `findIdsByDeckId` |

### `com.engly.study` — Per-user SRS study engine

| File | Purpose |
|------|---------|
| `api/StudyDeckController.java` | `GET /api/study/decks` (deck list + counts); `GET /api/study/decks/{id}/queue` (study queue) |
| `api/StudyReviewController.java` | `POST /api/study/review` — submit self-rating |
| `api/StudySettingsController.java` | `GET/PUT /api/study/settings` — new-cards-per-day limit |
| `api/StudyStatsController.java` | `GET /api/study/stats/summary` (totals), `activity?days=30` (daily buckets), `forecast?days=14` (predicted due), `accuracy?days=30` (rating dist); days bounded [1,90] |
| `api/dto/ReviewRating.java` | Enum: AGAIN, HARD, GOOD, EASY |
| `api/dto/` | ReviewRequest, ReviewResultResponse, StudyCardResponse, StudyDeckSummary, StudySettingsRequest/Response, StudySummaryResponse, ActivityPointResponse, ForecastPointResponse, AccuracyPointResponse |
| `application/Sm2Scheduler.java` | Pure SM-2 function (no I/O); brainstorm mapping wins over research |
| `application/Sm2Input.java` | Record: easeFactor, intervalDays, repetitions, lapses, rating |
| `application/Sm2Result.java` | Record: easeFactor, intervalDays, repetitions, lapses, state |
| `application/StudyClock.java` | Centralized `LocalDate today()` / `Instant now()` in Asia/Saigon TZ; `StudyClock(Clock)` for tests |
| `application/ReviewService.java` | @Transactional: lazy-create card state → Sm2Scheduler → persist; dueOn=today+interval; writes ReviewLog row |
| `application/StudyDeckService.java` | listDecks with per-user new/due/learned counts |
| `application/StudyQueueService.java` | buildQueue: due cards (LEARNING/REVIEW, dueOn≤today) + new up to allowance |
| `application/StudySettingsService.java` | getOrCreate (defaults 20/day) + update |
| `application/StudyStatsService.java` | Aggregate review_log via native SQL; gap-fill missing dates; currentStreak helper; per-user isolation |
| `domain/UserCardState.java` | JPA entity: `user_card_state`; PK=id; UNIQUE(user_sub, vocab_word_id); SM-2 fields |
| `domain/UserStudySettings.java` | JPA entity: `user_study_settings`; PK=user_sub; newCardsPerDay=20, maxReviewsPerDay=null |
| `domain/CardLearningState.java` | Enum: NEW, LEARNING, REVIEW |
| `domain/ReviewLog.java` | JPA entity: `review_log` (append-only); user_sub, vocab_word_id, deck_id, rating, prev/new_state, interval_days, ease_factor, reviewed_at |
| `infrastructure/UserCardStateJpaRepository.java` | findDueCards, countIntroducedToday (native, AT TIME ZONE 'Asia/Saigon'), findWordIdsWithStateForDeck |
| `infrastructure/UserStudySettingsJpaRepository.java` | JpaRepository |
| `infrastructure/ReviewLogJpaRepository.java` | Read-only; custom native queries for stats aggregation (summary, activity, forecast, accuracy) |

### Database Migrations — `engly-be/src/main/resources/db/migration/`

| File | Purpose |
|------|---------|
| `V1__create_irregular_verb_table.sql` | Creates `irregular_verb` table + two case-insensitive indexes |
| `V2__seed-irregular-verbs-180-rows-ai-draft-unverified.sql` | Seeds 179 irregular verbs (AI draft, `verified=false`) |
| `V3__create_vocab_and_study_tables.sql` | Creates `vocab_deck`, `vocab_word`, `user_card_state`, `user_study_settings` with FK + indexes |
| `V4__seed_starter_decks_travel_business.sql` | Seeds 2 decks × 20 words (Travel + Business EN→VI, `verified=false`) |

### Config & Build — `engly-be/`

| File | Purpose |
|------|---------|
| `src/main/resources/application.yml` | Server port 8081, datasource (env vars `DB_USER`/`DB_PASSWORD`, defaults `engly`/`engly`), JPA validate, Flyway enabled, admin roles claim name via `app.security.roles-claim-name` (default `roles`, env `ROLES_CLAIM_NAME`) |
| `pom.xml` | Maven parent: spring-boot-starter-parent 3.4.5. Dependencies: spring-boot-starter-security, spring-data-jpa, flyway. Test: Testcontainers 1.21.0, JUnit 5, Mockito |
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
| `common/SecurityRulesTest.java` | JWT resource server config tests; `/api/me` returns `isAdmin` | Security |

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
| `auth.service.ts` | Signals: `user` (readonly), computed `isAdmin` (derives from user). Methods: `loadMe()` (non-blocking, 401→anonymous), `ensureLoaded()` (memoized), `loginWithPassword()`, `logout()`→POST `/logout` |
| `auth.guard.ts` | CanActivateFn: awaits ensureLoaded() → authenticated→true; anonymous→auth.login()+false |

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
| `admin.routes.ts` | Feature routes: `/admin` (guarded by `adminGuard`) → admin list page; `/admin/create` → create form; `/admin/edit/:id` → edit form; `/admin/decks`, `/admin/decks/new`, `/admin/decks/:id/edit`; `/admin/vocab-words`, `/admin/vocab-words/new`, `/admin/vocab-words/:id/edit`; `/forbidden` |
| `admin.guard.ts` | CanActivateFn: admin→allow, authenticated non-admin→UrlTree `/forbidden`, anonymous→login |
| `admin-list-page.component.ts` | Verb admin list (search + table + verified toggle + edit/delete) |
| `admin-form-page.component.ts` | Verb create/edit form |
| `forbidden-page.component.ts` | 403 page |
| `deck-admin-list-page.component.ts` | Deck admin list: debounced search, table with edit/delete |
| `deck-admin-form-page.component.ts` | Deck create/edit form (name, slug, description, topic) |
| `vocab-word-admin-list-page.component.ts` | Vocab word list; deckId filter; verified toggle |
| `vocab-word-admin-form-page.component.ts` | Vocab word create/edit form; deck selector from GET /api/admin/decks |
| `data/admin-verb-api.service.ts` | HttpClient for `/api/admin/verbs` |
| `data/admin-deck-api.service.ts` | HttpClient for `/api/admin/decks` — list(q,page,size), get(id), create, update, delete |
| `data/admin-vocab-word-api.service.ts` | HttpClient for `/api/admin/vocab-words` — list(q,page,size,deckId?), CRUD + toggleVerified |
| `data/admin-deck.model.ts` | TS: AdminDeck, DeckRequest, DeckPageResponse |
| `data/admin-vocab-word.model.ts` | TS: AdminVocabWord, VocabWordRequest, VocabWordPageResponse |

### `src/app/features/study/` — SRS study UI

| File | Purpose |
|------|---------|
| `study.routes.ts` | Routes: `/study` → DeckListPage (guarded by authGuard), `/study/:deckId` → FlashcardSession, `/study/settings` → StudySettings |
| `deck-list-page.component.ts` | Lists decks with new/due/learned counts; Study button per deck; Settings link |
| `flashcard-session-page.component.ts` | Flashcard flip + 4 rating buttons (AGAIN/HARD/GOOD/EASY); progress bar; done state; 401→sessionExpired signal + re-login CTA |
| `study-settings-page.component.ts` | Load/save newCardsPerDay + maxReviewsPerDay (blank=unlimited) |
| `data/study-api.service.ts` | HttpClient: getDecks, getQueue(deckId), review(wordId, rating), getSettings, updateSettings. All withCredentials:true |
| `data/study.model.ts` | TS: StudyDeckSummary, StudyCard, ReviewResult, ReviewRating, StudySettings, UpdateSettingsRequest |

### `src/app/features/progress/` — Study progress visualization (lazy-loaded)

| File | Purpose |
|------|---------|
| `progress.routes.ts` | Feature route: `/progress` → ProgressDashboardPageComponent (guarded by authGuard) |
| `progress-dashboard-page.component.ts` | Per-user learning progress dashboard: KPI cards (total reviewed, current streak, accuracy, due today) + 4 charts (activity, forecast, accuracy, review distribution) |
| `data/progress-api.service.ts` | HttpClient: getSummary(), getActivity(days), getForecast(days), getAccuracy(days) → `/api/study/stats/**`; all withCredentials:true |
| `data/progress.model.ts` | TS: StudySummaryResponse, ActivityPoint, ForecastPoint, AccuracyPoint, ProgressChartData |
| `ui/chart-canvas.component.ts` | Reusable standalone Chart.js wrapper component; accepts data + config; chart.js v4 direct (no ng2-charts to avoid Angular 21 dep requirement) |

### Frontend Tests & Build

| File | Purpose |
|------|---------|
| `core/auth.service.spec.ts` | isAdmin computed signal, loadMe() non-blocking, ensureLoaded() memoization |
| `core/auth.guard.spec.ts` | authGuard: authenticated→true; anonymous→login()+false; awaits ensureLoaded() |
| `features/verb-search/verb-search-page.component.spec.ts` | HttpTestingController + fakeAsync — debounce, results render, loading/error |
| `features/verb-search/verb-detail-page.component.spec.ts` | ActivatedRoute stub — detail 200, notFound 404, error 500 |
| `features/admin/admin.guard.spec.ts` | adminGuard: admin→allow, non-admin→/forbidden, anonymous→login |
| `features/admin/data/admin-verb-api.service.spec.ts` | CRUD URLs/methods/withCredentials for /api/admin/verbs |
| `features/admin/data/admin-deck-api.service.spec.ts` | CRUD URLs/methods/withCredentials for /api/admin/decks |
| `features/admin/data/admin-vocab-word-api.service.spec.ts` | CRUD URLs/methods + deckId param + toggleVerified for /api/admin/vocab-words |
| `features/admin/admin-form-page.component.spec.ts` | Verb form: validation, submit→create/update, error |
| `features/admin/deck-admin-list-page.component.spec.ts` | Deck list: debounce, populates table, error state |
| `features/admin/vocab-word-admin-form-page.component.spec.ts` | Word form create/edit mode; deck selector load; 404 handling |
| `features/study/data/study-api.service.spec.ts` | All study API methods: URL, method, body, withCredentials |
| `features/study/deck-list-page.component.spec.ts` | Deck list renders, loading/error/empty states, study btn disabled |
| `features/study/flashcard-session-page.component.spec.ts` | Flip, rate→POST+advance, done state, 401→sessionExpired |
| `features/study/study-settings-page.component.spec.ts` | Load settings, save→PUT, null maxReviews, saving guard |
| `features/progress/data/progress-api.service.spec.ts` | API methods: getSummary, getActivity, getForecast, getAccuracy; URL, method, params, withCredentials |
| `Dockerfile` | Node 24-alpine, `ng build` → nginx:alpine serving dist/frontend/browser; nginx.conf proxies /api → engly-be:8081 |
| `proxy.conf.json` | Dev mode proxy: `/api/**` → `http://localhost:8081` |
| `package.json` | Dependencies: `@angular/core`, `@angular/router`, `chart.js` v4 (direct use, no ng2-charts dep) |

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
| Email/password authentication (app-issued JWT) | DONE |
| Admin CRUD — verbs (create/read/update/delete/toggle-verified) | DONE |
| Admin role from OIDC id_token roles claim | DONE |
| Admin UI — verbs (list, create/edit form, forbidden page) | DONE |
| XSRF protection (cookie-based) | DONE |
| Vocab deck + word admin CRUD (Flyway V3+V4) | DONE |
| Admin UI — decks + vocab words | DONE |
| SM-2 SRS study engine (Sm2Scheduler, pure function) | DONE |
| Study API (/api/study/decks, queue, review, settings) | DONE |
| Study UI (deck list, flashcard session, settings page) | DONE |
| Auth guard (authGuard CanActivateFn) | DONE |
| Mid-session 401 handling (sessionExpired signal + re-login CTA) | DONE |
| Integration tests (Testcontainers) — verbs + vocab admin + study flow | DONE |
| SM-2 unit tests (all rating paths + EF floor + rounding) | DONE |
| Security matrix tests (study 401/403, admin 401/403) | DONE |
| Frontend specs — study API service + 3 study components + 2 admin services + 2 admin pages + authGuard | DONE |
| Study progress history (review_log append-only table, Flyway V9) | DONE |
| Study stats API (/api/study/stats/{summary,activity,forecast,accuracy}, days-bounded queries) | DONE |
| Study progress charts dashboard (lazy-routed /progress, KPI cards + 4 chart.js charts, per-user isolation) | DONE |
| Progress tests (backend stats service + integration + security; frontend stats API service) | DONE |
| Kubernetes deployment (OrbStack) | DONE |
| Human verification of verb/vocab data | ONGOING (via admin UI) |
