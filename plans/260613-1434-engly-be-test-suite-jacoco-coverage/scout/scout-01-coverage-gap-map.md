# Engly Backend Test Coverage Gap Map

**Generated:** 2026-06-13 | **Scope:** Full breadth inventory of test coverage by module and class layer

---

## STEP 1: Existing Test Inventory

### Test Files by Module

| Test File | Count | Primary Classes Covered | Test Level |
|-----------|-------|------------------------|------------|
| **Common** | 3 | SecurityConfig, OidcRoleMappingConfig, GlobalExceptionHandler | @WebMvcTest (security rules), @SpringBootTest (not unit) |
| SecurityRulesTest.java | - | GlobalExceptionHandler (indirect via @RestControllerAdvice) | @WebMvcTest |
| AdminSecurityRulesTest.java | - | SecurityConfig (Chain 1 RBAC) | @WebMvcTest |
| OidcRoleMappingConfigTest.java | - | OidcRoleMappingConfig | @WebMvcTest |
| **Verb** | 5 | VerbAdminService, VerbQueryService, VerbAdminController, VerbController | @SpringBootTest+Testcontainers, @WebMvcTest, unit |
| VerbAdminIntegrationTest | - | VerbAdminService, VerbAdminController | @SpringBootTest+Testcontainers |
| VerbApiIntegrationTest | - | VerbController, VerbQueryService | @SpringBootTest+Testcontainers |
| VerbAdminControllerTest | - | VerbAdminController (partial; security-focused) | @WebMvcTest |
| VerbControllerTest | - | VerbController (partial; security-focused) | @WebMvcTest |
| VerbQueryServiceTest.java | - | VerbQueryService (unit) | Unit (@ExtendWith Mockito) |
| **Study** | 3 | ReviewService, StudyDeckService, StudyQueueService, StudySettingsService, StudyClock | @SpringBootTest+Testcontainers, @WebMvcTest |
| StudyApiIntegrationTest.java | - | ReviewService, StudyDeckService, StudyQueueService, StudySettingsService (via controllers) | @SpringBootTest+Testcontainers |
| StudySecurityRulesTest.java | - | Study endpoints (security only) | @WebMvcTest (mocked services) |
| Sm2SchedulerTest.java | - | Sm2Scheduler (pure unit) | Unit (no Spring) |
| **Vocab** | 8 | DeckAdminService, VocabWordAdminService, DeckAdminController, VocabWordAdminController | @SpringBootTest+Testcontainers, @WebMvcTest |
| VocabAdminIntegrationTest.java | - | DeckAdminService, VocabWordAdminService (via controllers) | @SpringBootTest+Testcontainers |
| VocabImportAdminApiTest | - | VocabImportAdminController | @SpringBootTest+Testcontainers |
| VocabImportBadRowTest | - | CsvRowParser, error handling | @SpringBootTest+Testcontainers |
| VocabImportIdempotencyTest | - | VocabImportProcessor (idempotent upsert) | @SpringBootTest+Testcontainers |
| VocabImportLeaseRecoveryTest | - | VocabImportRecoveryService, lease recovery | @SpringBootTest+Testcontainers |
| VocabImportResumeTest | - | VocabImportProcessor (resume after crash) | @SpringBootTest+Testcontainers |
| VocabImportMemoryTest | - | VocabImportProcessor (memory bounds) | @SpringBootTest+Testcontainers |
| VocabImportUploadTusTest | - | VocabImportUploadController (TUS protocol) | @SpringBootTest+Testcontainers |
| **Identity** | 0 | SessionController | NONE |
| **Application Root** | 1 | EnglyApplication | EnglyApplicationTests (@SpringBootTest context load) |

### Reusable Test Infrastructure

- **AbstractVocabImportIntegrationTest.java** — Singleton PostgreSQL container, @SpringBootTest + @AutoConfigureMockMvc, DynamicPropertyRegistry binding. Provides:
  - `admin()` RequestPostProcessor (JWT with ROLE_ADMIN)
  - `pollUntilTerminal()` helper for async job polling
  
- **VocabImportCsvFixture.java** — Helper class to materialize CSV test files. Provides:
  - `writeCsv()` — writes typed CsvRow list to temp file
  - `writeCsvRaw()` — writes raw CSV lines for custom bad-row scenarios
  - `generateValidRows()`, `generateBadRowScenarios()` — data generators

---

## STEP 2: Coverage Classification by Module

### Module: Common

| Class | Layer | Current Coverage | Testability Level | Notes |
|-------|-------|------------------|-------------------|-------|
| **GlobalExceptionHandler** | Infrastructure | THIN | HIGH | Covered indirectly in integration tests (exception paths); no dedicated unit tests for all @ExceptionHandler mappings |
| **SecurityConfig** | Infrastructure | COVERED | HIGH | Chain 1 (JWT/admin), Chain 2 (session) tested in AdminSecurityRulesTest + SecurityRulesTest + StudySecurityRulesTest |
| **OidcRoleMappingConfig** | Infrastructure | THIN | HIGH | Tested via OidcRoleMappingConfigTest but only role extraction logic; used transitively in security chains |
| SessionController | Identity.API | UNCOVERED | HIGH | No dedicated tests; endpoints /api/me, /api/token untested |
| EnglyApplication | Root | MINIMAL | LOW | Context load test only (EnglyApplicationTests) |

### Module: Verb

| Class | Layer | Current Coverage | Testability Level | Notes |
|-------|-------|------------------|-------------------|-------|
| VerbQueryService | Application | COVERED | HIGH | Full unit test (VerbQueryServiceTest): search variants, paging validation, LIKE escaping, getByBaseForm, 404 path |
| VerbAdminService | Application | COVERED | HIGH | Integration test (VerbAdminIntegrationTest): CRUD, duplicate base_form → 409, search, verified toggle |
| VerbController | API | THIN | HIGH | VerbApiIntegrationTest: happy path only; missing error/auth edge cases |
| VerbAdminController | API | THIN | HIGH | VerbAdminControllerTest: security-focused only; full CRUD path tested in integration |
| IrregularVerb | Domain | MINIMAL | LOW | Entity (JPA); tested implicitly via service/repo tests |
| VerbNotFoundException | Domain | COVERED | HIGH | Thrown and caught in tests; mapped to 404 in GlobalExceptionHandler |
| VerbJpaRepository | Infrastructure | MINIMAL | LOW | Repository interface; tested implicitly via integration tests |
| VerbRepository | Domain | MINIMAL | LOW | Domain interface; no direct tests |

### Module: Study

| Class | Layer | Current Coverage | Testability Level | Notes |
|-------|-------|------------------|-------------------|-------|
| **ReviewService** | Application | THIN | HIGH | StudyApiIntegrationTest covers GOOD/AGAIN ratings; missing HARD, EASY, 404 on bad wordId, state-transition edge cases |
| **StudyQueueService** | Application | THIN | HIGH | StudyApiIntegrationTest: queue building with due cards + new cards + limits; missing edge cases (deck not found, no words, exhausted allowance) |
| **StudyDeckService** | Application | THIN | HIGH | StudyApiIntegrationTest: lists decks with counts; missing individual deck summary computation tests |
| **StudySettingsService** | Application | THIN | HIGH | StudyApiIntegrationTest: lazy-create defaults, update, get; missing field validation, maxReviewsPerDay logic |
| **StudyClock** | Application | COVERED | HIGH | Fixed-clock injected in StudyApiIntegrationTest; deterministic "today" in Saigon TZ; could benefit from explicit unit test |
| Sm2Scheduler | Application | COVERED | HIGH | Sm2SchedulerTest: pure unit tests for all 4 rating paths, EF/interval floors, rounding, state transitions |
| StudyReviewController | API | THIN | HIGH | Covered via StudyApiIntegrationTest; CSRF token handling present but not explicitly tested |
| StudyDeckController | API | THIN | HIGH | Covered via StudyApiIntegrationTest |
| StudySettingsController | API | THIN | HIGH | Covered via StudyApiIntegrationTest |
| UserCardState | Domain | MINIMAL | LOW | Entity (JPA); lazy-created in StudyQueueService; state transitions tested implicitly |
| UserStudySettings | Domain | MINIMAL | LOW | Entity (JPA); defaults + update methods tested implicitly |
| CardLearningState | Domain | MINIMAL | LOW | Enum; tested implicitly via state checks in ReviewService/StudyQueueService |
| UserCardStateJpaRepository | Infrastructure | MINIMAL | LOW | Custom queries (findDueCards, countIntroducedToday, etc.); tested implicitly via integration tests |
| UserStudySettingsJpaRepository | Infrastructure | MINIMAL | LOW | Tested implicitly |

### Module: Vocab (Admin + API)

| Class | Layer | Current Coverage | Testability Level | Notes |
|-------|-------|------------------|-------------------|-------|
| **DeckAdminService** | Application | COVERED | HIGH | VocabAdminIntegrationTest: CRUD, search, duplicate slug → 409, pagination validation, LIKE escaping |
| **VocabWordAdminService** | Application | COVERED | HIGH | VocabAdminIntegrationTest: CRUD, deck resolution, deckId filter, verified toggle, pagination |
| DeckAdminController | API | THIN | HIGH | Integration tested; no dedicated unit tests |
| VocabWordAdminController | API | THIN | HIGH | Integration tested; no dedicated unit tests |
| VocabDeck | Domain | MINIMAL | LOW | Entity (JPA); tested implicitly |
| VocabWord | Domain | MINIMAL | LOW | Entity (JPA); tested implicitly |
| VocabDeckNotFoundException | Domain | COVERED | HIGH | Thrown in services; mapped to 404 |
| VocabWordNotFoundException | Domain | COVERED | HIGH | Thrown in services; mapped to 404 |
| VocabDeckJpaRepository | Infrastructure | MINIMAL | LOW | Tested implicitly |
| VocabWordJpaRepository | Infrastructure | MINIMAL | LOW | Tested implicitly |

### Module: Vocab.Imports

| Class | Layer | Current Coverage | Testability Level | Notes |
|-------|-------|------------------|-------------------|-------|
| VocabImportAdminService | Application | COVERED | HIGH | VocabImportAdminApiTest: job creation, retrieval, pagination |
| VocabImportProcessor | Processing | COVERED | HIGH | VocabImportBadRowTest, IdempotencyTest, ResumeTest, MemoryTest: CSV parsing, error tracking, resumption, memory bounds |
| VocabImportRecoveryService | Processing | COVERED | HIGH | LeaseRecoveryTest: orphaned job cleanup, lease expiry |
| VocabImportAdminController | API | THIN | HIGH | Integration covered; controller-specific error/auth edge cases untested |
| VocabImportUploadController | API | COVERED | HIGH | VocabImportUploadTusTest: TUS protocol (PATCH, HEAD, OPTIONS), multipart upload |
| ByteCountingLineReader | Processing | COVERED | HIGH | ByteCountingLineReaderTest: byte tracking across line boundaries |
| CsvRowParser | Processing | COVERED | HIGH | VocabImportBadRowTest: field parsing, validation, error messages |
| VocabImportJob | Domain | MINIMAL | LOW | Entity (JPA); status transitions tested implicitly via processor |
| ImportJobStatus | Domain | MINIMAL | LOW | Enum (NEW, IN_PROGRESS, COMPLETED, FAILED); tested implicitly |
| VocabImportError | Domain | MINIMAL | LOW | Value object; tested implicitly |
| BadRow, ParsedRow, BatchCheckpoint | Domain | MINIMAL | LOW | Value objects; tested implicitly |
| VocabImportJobNotFoundException | Domain | COVERED | HIGH | Thrown and caught in tests |
| VocabImportConflictException | Domain | COVERED | HIGH | Mapped to 409 in GlobalExceptionHandler |
| VocabImportRepository | Infrastructure | MINIMAL | LOW | Tested implicitly |
| VocabWordBatchUpserter | Infrastructure | THIN | HIGH | Upsert logic tested implicitly via processor idempotency tests |

---

## STEP 3: KNOWN-GAP Classes — Concrete Test Cases

### 1. SessionController
**File:** `com.engly.identity.api.SessionController`  
**Current:** UNCOVERED (0 tests)  
**Recommended Level:** @WebMvcTest (session-based BFF endpoints) + integration  

#### Concrete Test Cases Needed

**Happy Path:**
- GET /api/me with authenticated OIDC user → returns UserInfoResponse with sub, email, name, isAdmin=false
- GET /api/me with authenticated OIDC user + ROLE_ADMIN → returns isAdmin=true
- GET /api/token with authenticated OIDC user → returns TokenResponse with id_token JWT string
- GET /api/token with authenticated OIDC user → token value is a valid JWT (claims extractable)

**Error / Boundary Cases:**
- GET /api/me with anonymous request → 401 (HttpStatusEntryPoint for /api/**)
- GET /api/token with anonymous request → 401
- GET /api/me with expired/invalid session → 401
- GET /api/me + OIDC principal without email field → graceful handling (null or default)
- GET /api/me + OIDC principal without FullName → graceful handling

**Auth Rules:**
- Both endpoints require @AuthenticationPrincipal OidcUser (session-based Chain 2) — no bearer JWT needed
- Verify 401 for unauthenticated, not 403 (no admin bypass)

---

### 2. GlobalExceptionHandler
**File:** `com.engly.common.GlobalExceptionHandler`  
**Current:** THIN (covered indirectly in integration tests; no dedicated unit tests)  
**Recommended Level:** Unit tests + @RestControllerAdvice integration tests

#### Concrete Test Cases Needed

**VerbNotFoundException → 404:**
- Exception thrown with message "Verb not found: go" → response status 404, body contains message
- Verify timestamp, status, error, message fields in response

**VocabDeckNotFoundException → 404:**
- Deck not found → 404 response

**VocabWordNotFoundException → 404:**
- Word not found → 404 response

**VocabImportJobNotFoundException → 404:**
- Job not found → 404 response

**VocabImportConflictException → 409:**
- Upload not complete / job not in FAILED state → 409 Conflict
- Response message: "resource already exists" or domain-specific message

**IllegalArgumentException → 400:**
- Page validation error (page < 0) → 400 Bad Request
- Size validation error (size out of [1,100]) → 400
- Message includes "page must be >= 0" / "size must be between"

**MethodArgumentNotValidException → 400:**
- POST with invalid @Valid field (e.g., @NotBlank empty string) → 400
- Error message mentions field name + validation rule
- Only first error returned (findFirst())

**ConstraintViolationException → 400:**
- @Validated service method with @Min/@Max constraints violated → 400
- Leaf parameter name extracted (not full method path with "verbQueryService.search.size:")
- Message includes parameter name + constraint message

**DataIntegrityViolationException → 409:**
- Unique constraint violation (duplicate base_form, slug, etc.) → 409
- Generic message "resource already exists" (no SQL leak)
- log.warn() called server-side with actual cause

**Unhandled Exception → 500:**
- Generic Exception path → 500 Internal Server Error
- Generic message "An unexpected error occurred" (no stack trace)
- Full exception logged via log.error()

**Response Format:**
- All responses have timestamp (ISO 8601 Instant), status (HTTP code), error (reason phrase), message

---

### 3. ReviewService
**File:** `com.engly.study.application.ReviewService`  
**Current:** THIN (StudyApiIntegrationTest covers GOOD/AGAIN; missing HARD, EASY, edge cases)  
**Recommended Level:** @SpringBootTest+Testcontainers integration (SM-2 state machine)

#### Concrete Test Cases Needed

**Happy Path — All Rating Types:**
- Review new card (interval=0, reps=0) with GOOD → state=REVIEW, interval=1, dueOn=today+1
- Review new card with AGAIN → state=LEARNING, interval=1, dueOn=today+1, lapses=1, ef=2.3
- Review learning card (reps=1) with GOOD → state=REVIEW, interval=6, dueOn=today+6
- Review learning card with HARD → state=REVIEW, interval=round(6*1.2)=7, dueOn=today+7, ef=2.35
- Review review card (reps≥2) with GOOD → state=REVIEW, interval=round(10*2.5), dueOn=future
- Review card with EASY → state=REVIEW, interval expanded (>= input), ef+=0.15
- Review card with EASY multiple times → EF grows (capped at practical limits in Sm2Scheduler)

**Lazy-Create Card State:**
- First review of a word (no UserCardState row) → creates NEW state row, then applies scheduling
- Created state has createdAt=DB.now(), state after review is not NEW
- Second review of same word → updates existing row (not duplicated)

**Error Cases:**
- Review with non-existent wordId → VocabWordNotFoundException → 404
- Review request validation: missing wordId → MethodArgumentNotValidException → 400
- Review request validation: invalid rating enum → malformed request → 400

**Boundary/Edge Cases:**
- Review same card twice in same transaction → both reviews persisted sequentially (dueOn values differ)
- EF floor = 1.3: AGAIN on ef=1.5 → ef=1.3 (not 1.3)
- Interval floor = 1: HARD on interval=1 → round(1*1.2)=1 (not 0)
- Rounding: interval=10.4 * ef=2.5 = 26.0 → no rounding error, stays 26

**Clock Integration:**
- dueOn = today(Saigon) + interval (never UTC, always Saigon TZ)
- lastReviewedAt = now() (wall clock Instant)

---

### 4. StudyQueueService
**File:** `com.engly.study.application.StudyQueueService`  
**Current:** THIN (integration test covers basic queue building; missing edge cases)  
**Recommended Level:** @SpringBootTest+Testcontainers + unit tests with mocked repos

#### Concrete Test Cases Needed

**Happy Path:**
- Queue for deck with due cards + new cards → returns [due cards, new cards up to limit]
- Queue respects newCardsPerDay setting (default 20) → returns ≤ 20 new cards
- Queue for user with zero study history → all cards are NEW state
- Queue after review → due cards (dueOn <= today) reappear; dueOn > today cards do not

**Due Cards Logic:**
- Card with state=LEARNING, dueOn <= today → in queue
- Card with state=REVIEW, dueOn <= today → in queue
- Card with state=NEW → never in due queue (not in ACTIVE_STATES)
- Card with dueOn = today + 1 → NOT in queue (not yet due)
- Multiple due cards → all returned (no daily limit on due cards)

**New Cards Logic:**
- User with 0 cards introduced today + limit=20 → up to 20 new cards from deck
- User with 15 cards introduced today + limit=20 → up to 5 new cards
- User with 20 cards introduced today + limit=20 → 0 new cards (allowance=0)
- New cards are lazy-created (UserCardState.newCard() rows inserted)
- New cards have state=NEW, createdAt=now(), dueOn=null (or defaults)
- New cards returned in StudyCardResponse with state="NEW"

**Boundary Cases:**
- Empty deck (no words) → queue returns empty list (no new cards, no due cards)
- Deck with 5 words, all studied with dueOn > today, limit=20 → returns only the 5 cards (as NEW if new allowance remains)
- User studied word, allowance=0, no due cards → queue returns empty
- Deck not found → VocabDeckNotFoundException → 404

**N+1 Prevention:**
- Batch fetch words by IDs (single query for due + new word data, not per-card query)
- Verify in integration test: check query count via test output or assert card data is populated

**Settings Integration:**
- When StudySettingsService.getOrCreate() is called, queue respects the returned newCardsPerDay
- If user has no settings row yet → lazy-create defaults (20 new/day)

---

### 5. StudyDeckService
**File:** `com.engly.study.application.StudyDeckService`  
**Current:** THIN (lists decks in integration test; missing individual deck summary computation)  
**Recommended Level:** @SpringBootTest+Testcontainers

#### Concrete Test Cases Needed

**Happy Path:**
- listDecks(userSub) returns all decks with per-user SRS counts
- Each StudyDeckSummary has: deckId, name, slug, topic, newCount, dueCount, learnedCount
- newCount = total words in deck - words with state row for user
- dueCount = state rows where (state IN [LEARNING, REVIEW] AND dueOn <= today)
- learnedCount = state rows where state=REVIEW (all cards that have graduated)

**Per-Deck Counting Edge Cases:**
- Empty deck → newCount=0, dueCount=0, learnedCount=0
- Deck with 10 words, user has no states → newCount=10, dueCount=0, learnedCount=0
- Deck with 10 words, user has 8 states (5 NEW, 2 REVIEW, 1 LEARNING):
  - newCount = 10 - 8 = 2
  - dueCount = 1 (if LEARNING/REVIEW's dueOn <= today)
  - learnedCount = 2 (both REVIEW states)
- LEARNING card with dueOn > today → NOT in dueCount
- NEW state card → never in dueCount or learnedCount (not REVIEW)
- Duplicate deck rows or user states → no duplicates in counts (distinct query)

**Clock Integration:**
- dueCount uses today(Saigon TZ) from StudyClock, not LocalDate.now()

**Transactional Behavior:**
- @Transactional(readOnly=true) → no writes
- Concurrent user accessing same deck → counts accurate (no stale reads due to session isolation)

---

### 6. StudySettingsService
**File:** `com.engly.study.application.StudySettingsService`  
**Current:** THIN (lazy-create + update tested in integration; missing validation, field logic)  
**Recommended Level:** @SpringBootTest+Testcontainers

#### Concrete Test Cases Needed

**Happy Path:**
- getOrCreate(userSub) with no existing row → creates UserStudySettings.defaults(userSub), returns defaults (newCardsPerDay=20, maxReviewsPerDay=null)
- getOrCreate(userSub) with existing row → returns row values
- update(userSub, {newCardsPerDay=5, maxReviewsPerDay=100}) → saves, returns StudySettingsResponse with updated values
- update(userSub, {newCardsPerDay=30, maxReviewsPerDay=null}) → allows newCardsPerDay > 20 default (no hardcoded max)
- update sets updatedAt = Instant.now() app-side
- update with non-existent userSub → creates new row with requested values (not found → orElseGet → defaults then update)

**Boundary Cases:**
- getOrCreate → returns defaults: newCardsPerDay=20 (is this hard-coded default?)
- update with newCardsPerDay=0 → allowed or validation error?
- update with newCardsPerDay=10000 → allowed or cap?
- update with maxReviewsPerDay=0 → allowed (means no reviews)?
- update with maxReviewsPerDay=null → interpreted as unlimited (no cap)
- update same user twice → second update overwrites first (updatedAt is newer)

**Schema/Persistence:**
- UserStudySettings table has columns: userSub (PK), newCardsPerDay, maxReviewsPerDay, createdAt, updatedAt
- updatedAt is bumped on every update (bumpUpdatedAt() method)

---

### 7. StudyClock
**File:** `com.engly.study.application.StudyClock`  
**Current:** COVERED (used in StudyApiIntegrationTest with fixed clock; could benefit from explicit unit test)  
**Recommended Level:** Unit test (pure, no Spring context)

#### Concrete Test Cases Needed

**Production Bean:**
- Constructor StudyClock() → uses Clock.system(SAIGON)
- today() → returns LocalDate in Asia/Saigon TZ (never UTC)
- now() → returns Instant (wall clock)

**Test Constructor:**
- StudyClock(fixedClock) → uses provided Clock
- today() with fixed instant 2026-06-15T00:00:00Z in SAIGON → returns 2026-06-15
- today() with fixed instant 2026-06-14T23:00:00Z in SAIGON (1 hour before midnight) → returns 2026-06-14
- now() returns instant from clock (reproducible with fixed clock)

**Timezone Verification:**
- today() in SAIGON = today() should be 7 hours ahead of UTC (ICT = UTC+7)
- Instant 2026-06-15T00:00:00Z in SAIGON → 2026-06-15 07:00:00 local → date is still 2026-06-15

**Edge Cases:**
- Year boundary: fixed instant 2025-12-31T17:00:00Z in SAIGON → 2026-01-01 00:00:00 local → today() is 2026-01-01
- Daylight saving: SAIGON has no DST → always UTC+7

---

### 8. DeckAdminService
**File:** `com.engly.vocab.admin.DeckAdminService`  
**Current:** COVERED (integration test covers CRUD, search, validation)  
**Recommended Level:** Can add targeted unit tests for search/paging logic

#### Concrete Test Cases Needed (Gaps from Current Tests)

**Additional Unit Tests (avoid repo mocking where integration covers):**
- LIKE wildcard escaping: search("test%", 0, 20) → escaped to "test!%" before query
- LIKE underscore escaping: search("test_deck", 0, 20) → escaped to "test!_deck"
- LIKE escape char itself: search("test!file", 0, 20) → escaped to "test!!file"
- Null/empty q routing: search(null, 0, 20) → uses findAll(), not search()
- Blank q routing: search("   ", 0, 20) → uses findAll()

**Edge Cases (from reading code):**
- create() sets slug immutably (cannot be changed in update()) → verify slug is not in update() method call
- update() only modifies name, description, topic (schema constraint check)
- Paginate with size=100 (max) → succeeds
- Paginate with size=101 → IllegalArgumentException "size must be between 1 and 100"
- Paginate with page=0 (valid minimum) → succeeds
- Paginate with page=-1 → IllegalArgumentException "page must be >= 0"
- delete(deckId) with non-existent id → VocabDeckNotFoundException → 404
- Cascade: delete deck → deletes all child vocab_words (DB ON DELETE CASCADE)

---

### 9. VocabWordAdminService
**File:** `com.engly.vocab.admin.VocabWordAdminService`  
**Current:** COVERED (integration test covers CRUD, deck resolution, filtering)  
**Recommended Level:** Can add unit tests for search/paging/filtering logic

#### Concrete Test Cases Needed (Gaps from Current Tests)

**Search Logic (4 branches):**
- search(q="apple", deckId=null, 0, 20) → global search (LIKE term OR meaningVi)
- search(q=null, deckId=1, 0, 20) → filter by deck only
- search(q="apple", deckId=1, 0, 20) → combined deck+term search (M3 fix)
- search(q=null, deckId=null, 0, 20) → all words paginated
- search(q="   ", deckId=1, 0, 20) → blank q with deckId → filter by deck only
- search(q="apple", deckId=999, 0, 20) → deckId exists but no words → empty result (no 404; filtering succeeds with 0 rows)

**LIKE Escaping:**
- search("test%wildcard", null, 0, 20) → % escaped, treated literally
- search("test_underscore", null, 0, 20) → _ escaped
- search("test!exclaim", null, 0, 20) → ! escaped

**Pagination:**
- size=100 (max) → succeeds
- size=101 → IllegalArgumentException
- size=0 → IllegalArgumentException
- page=-1 → IllegalArgumentException

**Deck Resolution:**
- create(wordReq with deckId=1) where deck exists → word created, associated to deck
- create(wordReq with deckId=999) where deck does not exist → VocabDeckNotFoundException → 404
- update(wordId=1, wordReq with deckId=2) → word reassigned to deck 2 (deckId is mutable)
- update(wordId=1, wordReq with deckId=999) where deck does not exist → VocabDeckNotFoundException → 404

**Verified Flag:**
- create(wordReq with verified=true) → word.verified=true persisted
- toggleVerified(wordId=1, verified=true) → updates only verified flag, leaves other fields intact
- toggleVerified(wordId=999, verified=true) → VocabWordNotFoundException → 404

**Error Cases:**
- delete(wordId=999) → VocabWordNotFoundException → 404
- getById(wordId=999) → VocabWordNotFoundException → 404

---

## STEP 4: SecurityConfig Endpoint → Role Rules & Exception Mappings

### SecurityConfig: Endpoint Authorization Rules

**Chain 1 (Order 1) — Stateless OAuth2 Resource Server:**
- **Matcher:** `/api/admin/**`
- **Rule:** `anyRequest().hasRole("ADMIN")`
- **Auth Method:** Bearer JWT with `roles` claim containing "ROLE_ADMIN"
- **No Token:** 401 (BearerTokenAuthenticationEntryPoint)
- **Token without ROLE_ADMIN:** 403 (AccessDeniedException)
- **Session Creation:** STATELESS (no cookies)
- **CSRF:** Disabled (bearer auth is CSRF-safe)

**Chain 2 (Order 2) — Session + OAuth2 Login (BFF):**
- **Matcher:** Everything else (default)

| Endpoint Pattern | Authorization | Entry Point |
|------------------|---------------|------------|
| `/api/verbs/**` | permitAll | none |
| `/oauth2/**`, `/login/**` | permitAll | none |
| `/`, `/index.html`, `/favicon.ico` | permitAll | none |
| `/api/me`, `/api/token` | authenticated | 401 (HttpStatusEntryPoint for /api/**) |
| `/api/study/**` | authenticated | 401 (HttpStatusEntryPoint for /api/**) |
| `/api/admin/**` | hasRole("ADMIN") | 401 from Chain 1 (BearerTokenEntryPoint) |
| All other `/api/**` | permitAll (catch-all) | 401 (HttpStatusEntryPoint) |
| All other routes | permitAll | 302 redirect to OAuth2 login |

### GlobalExceptionHandler: Exception → HTTP Status Mappings

| Exception Class | HTTP Status | Message | Handler Method |
|-----------------|-------------|---------|-----------------|
| VerbNotFoundException | 404 Not Found | Custom message from exception | handleVerbNotFound() |
| VocabDeckNotFoundException | 404 Not Found | Custom message | handleDeckNotFound() |
| VocabWordNotFoundException | 404 Not Found | Custom message | handleWordNotFound() |
| VocabImportJobNotFoundException | 404 Not Found | Custom message | handleImportJobNotFound() |
| VocabImportConflictException | 409 Conflict | Custom message | handleImportConflict() |
| IllegalArgumentException | 400 Bad Request | Custom message from exception | handleIllegalArgument() |
| MethodArgumentNotValidException | 400 Bad Request | "field: constraint message" (first error only) | handleMethodArgNotValid() |
| ConstraintViolationException | 400 Bad Request | "leafParamName: message" (first violation only) | handleConstraintViolation() |
| DataIntegrityViolationException | 409 Conflict | Generic "resource already exists" (no SQL leak) | handleConflict() |
| Exception (generic catch-all) | 500 Internal Server Error | Generic "An unexpected error occurred" | handleGeneric() |

**Response Structure (all exceptions):**
```json
{
  "timestamp": "2026-06-13T12:34:56.789Z",
  "status": 404,
  "error": "Not Found",
  "message": "Custom message from exception"
}
```

---

## STEP 5: Excluded Classes (DTOs, Records, Config, Entities)

### Pure DTOs / Value Objects (No Test Coverage Needed)

| Class | Reason | Location |
|-------|--------|----------|
| VerbResponse | DTO record | com.engly.verb.api.dto |
| VerbAdminResponse | DTO record | com.engly.verb.api.dto |
| VerbRequest | DTO record with @Valid | com.engly.verb.api.dto |
| VerbSearchPageResponse | DTO record | com.engly.verb.api.dto |
| VerbAdminPageResponse | DTO record | com.engly.verb.api.dto |
| VerbVerifiedRequest | DTO record | com.engly.verb.api.dto |
| ReviewRequest | DTO record | com.engly.study.api.dto |
| ReviewRating | DTO enum | com.engly.study.api.dto |
| ReviewResultResponse | DTO record | com.engly.study.api.dto |
| StudyCardResponse | DTO record | com.engly.study.api.dto |
| StudyDeckSummary | DTO record | com.engly.study.api.dto |
| StudySettingsRequest | DTO record | com.engly.study.api.dto |
| StudySettingsResponse | DTO record | com.engly.study.api.dto |
| DeckRequest | DTO record | com.engly.vocab.api.dto |
| DeckResponse | DTO record | com.engly.vocab.api.dto |
| DeckPageResponse | DTO record | com.engly.vocab.api.dto |
| VocabWordRequest | DTO record | com.engly.vocab.api.dto |
| VocabWordResponse | DTO record | com.engly.vocab.api.dto |
| VocabWordPageResponse | DTO record | com.engly.vocab.api.dto |
| VocabWordVerifiedRequest | DTO record | com.engly.vocab.api.dto |
| Sm2Input | DTO record | com.engly.study.application |
| Sm2Result | DTO record | com.engly.study.application |
| CreateVocabImportRequest | DTO record | com.engly.vocab.imports.api.dto |
| VocabImportCreatedResponse | DTO record | com.engly.vocab.imports.api.dto |
| VocabImportJobResponse | DTO record | com.engly.vocab.imports.api.dto |
| VocabImportErrorResponse | DTO record | com.engly.vocab.imports.api.dto |
| VocabImportErrorPageResponse | DTO record | com.engly.vocab.imports.api.dto |
| BadRow | DTO record | com.engly.vocab.imports.domain |
| ParsedRow | DTO record | com.engly.vocab.imports.domain |
| BatchCheckpoint | DTO record | com.engly.vocab.imports.domain |
| CsvParseResult | DTO record | com.engly.vocab.imports.processing |

### Configuration / Infrastructure Classes (Config-Level Only)

| Class | Reason | Location |
|-------|--------|----------|
| AsyncConfig | Configuration (thread pools) | com.engly.vocab.imports.processing |
| TusUploadConfig | Configuration (TUS protocol) | com.engly.vocab.imports.upload |
| EnglyApplication | Root bootstrap; covered by context load test | com.engly |

### JPA Entities (Tested Implicitly via Repo Tests)

| Class | Reason | Location |
|-------|--------|----------|
| IrregularVerb | JPA entity; state tested implicitly via VerbQueryService/VerbAdminService | com.engly.verb.domain |
| VocabDeck | JPA entity; state tested implicitly via DeckAdminService | com.engly.vocab.domain |
| VocabWord | JPA entity; state tested implicitly via VocabWordAdminService | com.engly.vocab.domain |
| UserCardState | JPA entity; state transitions tested implicitly via ReviewService/StudyQueueService | com.engly.study.domain |
| UserStudySettings | JPA entity; state tested implicitly via StudySettingsService | com.engly.study.domain |
| VocabImportJob | JPA entity; status tested implicitly via VocabImportProcessor | com.engly.vocab.imports.domain |

### Enums (Tested Implicitly via Service/Controller Tests)

| Class | Reason | Location |
|-------|--------|----------|
| CardLearningState | Enum (NEW, LEARNING, REVIEW); tested implicitly via ReviewService state transitions | com.engly.study.domain |
| ImportJobStatus | Enum (NEW, IN_PROGRESS, COMPLETED, FAILED); tested implicitly via VocabImportProcessor | com.engly.vocab.imports.domain |

### Repository Interfaces (Tested Implicitly via Service Integration Tests)

| Class | Reason | Location |
|-------|--------|----------|
| VerbRepository | Domain interface; queries tested implicitly via VerbQueryService | com.engly.verb.domain |
| VerbJpaRepository | JPA implementation; tested implicitly via VerbAdminService/VerbQueryService | com.engly.verb.infrastructure |
| VocabDeckJpaRepository | JPA implementation; tested implicitly via DeckAdminService/StudyDeckService | com.engly.vocab.infrastructure |
| VocabWordJpaRepository | JPA implementation; tested implicitly via VocabWordAdminService/StudyQueueService | com.engly.vocab.infrastructure |
| UserCardStateJpaRepository | JPA implementation with custom queries; tested implicitly via ReviewService/StudyQueueService/StudyDeckService | com.engly.study.infrastructure |
| UserStudySettingsJpaRepository | JPA implementation; tested implicitly via StudySettingsService/StudyQueueService | com.engly.study.infrastructure |
| VocabImportRepository | JPA implementation; tested implicitly via VocabImportProcessor/VocabImportAdminService | com.engly.vocab.imports.infrastructure |

---

## STEP 6: Coverage Summary Table

| Class | Module | Layer | Current Coverage | Recommended Test Level | New Test File Name | Priority |
|-------|--------|-------|------------------|----------------------|-------------------|----------|
| SessionController | Identity | API | UNCOVERED | @WebMvcTest + @SpringBootTest | SessionControllerTest.java | HIGH |
| GlobalExceptionHandler | Common | Infrastructure | THIN | Unit + @RestControllerAdvice | GlobalExceptionHandlerTest.java | HIGH |
| ReviewService | Study | Application | THIN | @SpringBootTest+Testcontainers | ReviewServiceIntegrationTest.java | HIGH |
| StudyQueueService | Study | Application | THIN | @SpringBootTest+Testcontainers | StudyQueueServiceIntegrationTest.java | HIGH |
| StudyDeckService | Study | Application | THIN | @SpringBootTest+Testcontainers | StudyDeckServiceIntegrationTest.java | HIGH |
| StudySettingsService | Study | Application | THIN | @SpringBootTest+Testcontainers | StudySettingsServiceIntegrationTest.java | HIGH |
| StudyClock | Study | Application | COVERED | Unit test (explicit) | StudyClockTest.java | MEDIUM |
| DeckAdminService | Vocab | Application | COVERED | Unit (search/paging logic) | DeckAdminServiceTest.java | MEDIUM |
| VocabWordAdminService | Vocab | Application | COVERED | Unit (search/paging logic) | VocabWordAdminServiceTest.java | MEDIUM |
| VerbQueryService | Verb | Application | COVERED | Already COVERED (VerbQueryServiceTest.java) | - | - |
| VerbAdminService | Verb | Application | COVERED | Already COVERED (VerbAdminIntegrationTest.java) | - | - |
| Sm2Scheduler | Study | Application | COVERED | Already COVERED (Sm2SchedulerTest.java) | - | - |
| SecurityConfig | Common | Infrastructure | COVERED | Already COVERED (AdminSecurityRulesTest, SecurityRulesTest, StudySecurityRulesTest) | - | - |

---

## Summary

**Total Main Classes Audited:** 80+ (controllers, services, domain, infrastructure, DTOs)

**Current Test Coverage:**
- **COVERED:** 12 classes (Sm2Scheduler, VerbQueryService, VerbAdminService, SecurityConfig, etc.)
- **THIN:** 15 classes (ReviewService, StudyQueueService, StudyDeckService, StudySettingsService, controllers, services with integration coverage but missing edge cases)
- **UNCOVERED:** 1 class (SessionController)
- **EXCLUDED:** 52+ (DTOs, records, entities, repositories, enums, config)

**High-Priority Test Gaps:**
1. **SessionController** (identity.api) — 0 tests; 8 test cases needed
2. **GlobalExceptionHandler** (common) — indirect coverage only; 10+ test cases needed
3. **ReviewService** (study.application) — basic GOOD/AGAIN only; 8+ additional test cases needed
4. **StudyQueueService** (study.application) — basic queue building; 10+ edge case tests needed
5. **StudyDeckService** (study.application) — listing only; 8+ per-deck summary tests needed
6. **StudySettingsService** (study.application) — CRUD + lazy-create covered; 8+ boundary tests needed

**Reusable Test Infrastructure:**
- AbstractVocabImportIntegrationTest: Singleton PostgreSQL container, admin() JWT helper, pollUntilTerminal()
- VocabImportCsvFixture: CSV file generators, CsvRow builder
- Both available for new study/vocab tests requiring integration

**Estimated Effort:**
- SessionController: 1 test file, ~50 lines (3 test methods)
- GlobalExceptionHandler: 1 test file, ~150 lines (10 test methods)
- Review/Queue/Deck/Settings Services: 4 test files, ~600 lines total (50+ test methods combined)
- Dto/Entity tests: EXCLUDED (tested implicitly via integration tests)

