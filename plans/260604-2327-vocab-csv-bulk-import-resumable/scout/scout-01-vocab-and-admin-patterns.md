# Scout Report: Vocab & Admin Patterns for CSV Import Feature
**Date:** 2026-06-04 | **Codebase:** Engly (Spring Boot 3.4.5, Angular 19.2, PostgreSQL)

---

## 1. Backend File Structure & Package Layout

**Backend root:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly`

### Vocab module tree:
```
com/engly/vocab/
├── domain/
│   ├── VocabWord.java               (JPA entity, @Entity @Table("vocab_word"))
│   ├── VocabDeck.java               (JPA entity, @Entity @Table("vocab_deck"))
│   ├── VocabWordNotFoundException.java
│   └── VocabDeckNotFoundException.java
├── admin/
│   ├── VocabWordAdminController.java    (@RestController, /api/admin/vocab-words)
│   ├── VocabWordAdminService.java       (@Service, @Transactional)
│   ├── DeckAdminController.java         (@RestController, /api/admin/decks)
│   └── DeckAdminService.java            (@Service)
├── infrastructure/
│   ├── VocabWordJpaRepository.java      (extends JpaRepository)
│   └── VocabDeckJpaRepository.java      (extends JpaRepository)
└── api/dto/
    ├── VocabWordRequest.java           (record, validation annotations)
    ├── VocabWordResponse.java          (record)
    ├── VocabWordPageResponse.java      (pagination envelope)
    ├── DeckRequest.java
    ├── DeckResponse.java
    ├── DeckPageResponse.java
    └── VocabWordVerifiedRequest.java
```

---

## 2. Entity Structure: VocabWord & VocabDeck

### VocabWord.java
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/domain/VocabWord.java`

**Key fields & annotations:**
```java
@Entity @Table(name = "vocab_word")
private Long id;                          // @Id @GeneratedValue(IDENTITY)
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "deck_id", nullable = false)
private VocabDeck deck;                   // Owning side, FK: vocab_deck(id) ON DELETE CASCADE

@Column(name = "term", nullable = false, length = 120)
private String term;

@Column(name = "meaning_vi", nullable = false, length = 255)
private String meaningVi;

@Column(name = "ipa", length = 120)       // Optional
private String ipa;

@Column(name = "example_en", length = 500)
@Column(name = "example_vi", length = 500)
@Column(name = "part_of_speech", length = 40)  // Optional

@Column(name = "verified", nullable = false)   // Default false
private boolean verified;

@Column(name = "created_at", insertable = false, updatable = false)
private Instant createdAt;                // DB DEFAULT now()
```

**Mutators:** `update(...)`, `setVerified(...)`, `setDeck(...)`

### VocabDeck.java
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/domain/VocabDeck.java`

**Key fields:**
```java
private Long id;                          // @Id @GeneratedValue(IDENTITY)
@Column(nullable = false, length = 120)
private String name;

@Column(nullable = false, unique = true, length = 140)
private String slug;                      // Immutable after creation

@Column(length = 500)
private String description;

@Column(length = 80)
private String topic;

@Column(insertable = false, updatable = false)
private Instant createdAt;
```

---

## 3. VocabWordAdminController & VocabWordAdminService

### VocabWordAdminController.java
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/admin/VocabWordAdminController.java`

**Routing & method signatures:**
```java
@RestController @RequestMapping("/api/admin/vocab-words")

@GetMapping
ResponseEntity<VocabWordPageResponse> list(
    @RequestParam(defaultValue = "") String q,
    @RequestParam(required = false) Long deckId,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size)

@GetMapping("/{id}")
ResponseEntity<VocabWordResponse> getById(@PathVariable Long id)

@PostMapping
ResponseEntity<VocabWordResponse> create(@Valid @RequestBody VocabWordRequest req)

@PutMapping("/{id}")
ResponseEntity<VocabWordResponse> update(@PathVariable Long id, @Valid @RequestBody VocabWordRequest req)

@DeleteMapping("/{id}")
ResponseEntity<Void> delete(@PathVariable Long id)

@PatchMapping("/{id}/verified")
ResponseEntity<VocabWordResponse> toggleVerified(@PathVariable Long id, @RequestBody VocabWordVerifiedRequest req)
```

**Security:** All `/api/admin/**` enforced by **Chain 1** security config (stateless JWT, ROLE_ADMIN).

### VocabWordAdminService.java
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/admin/VocabWordAdminService.java`

**Key patterns:**
- `@Service @Transactional` (writes), `@Transactional(readOnly=true)` (reads)
- deckId validation: `resolveDeck(Long deckId)` → throws `VocabDeckNotFoundException(404)`
- Search logic: branching on `q` & `deckId` presence → calls repo methods
- DTO conversion: `toDto(VocabWord)` → maps to `VocabWordResponse`
- Pagination: min=1, max=100 (validated)
- LIKE escape: `escapeLike(String)` matches DB ESCAPE '!' clause

---

## 4. DTOs & Validation Patterns

### VocabWordRequest.java
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/api/dto/VocabWordRequest.java`

```java
public record VocabWordRequest(
    @NotNull Long deckId,
    @NotBlank @Size(max = 120) String term,
    @NotBlank @Size(max = 255) String meaningVi,
    @Size(max = 120) String ipa,
    @Size(max = 500) String exampleEn,
    @Size(max = 500) String exampleVi,
    @Size(max = 40) String partOfSpeech,
    boolean verified
) {}
```

**Pattern:** Records with Jakarta validation annotations (`@NotNull`, `@NotBlank`, `@Size`).

### VocabWordResponse.java
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/api/dto/VocabWordResponse.java`

```java
public record VocabWordResponse(
    Long id, Long deckId, String term, String meaningVi,
    String ipa, String exampleEn, String exampleVi,
    String partOfSpeech, boolean verified
) {}
```

**DTO location:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/api/dto/`

---

## 5. Repository Pattern: Spring Data JPA Only

### VocabWordJpaRepository.java
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/infrastructure/VocabWordJpaRepository.java`

**Extends:** `JpaRepository<VocabWord, Long>`

**Custom query methods:**
```java
@EntityGraph(attributePaths = {"deck"})
Page<VocabWord> findByDeck_Id(Long deckId, Pageable pageable);

@Query("SELECT w.id FROM VocabWord w WHERE w.deck.id = :deckId")
List<Long> findIdsByDeckId(@Param("deckId") Long deckId);

long countByDeck_Id(Long deckId);

@EntityGraph(attributePaths = {"deck"})
@Query("SELECT w FROM VocabWord w WHERE lower(w.term) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
       OR lower(w.meaningVi) LIKE lower(concat('%', :q, '%')) ESCAPE '!'")
Page<VocabWord> search(@Param("q") String q, Pageable pageable);

@EntityGraph(attributePaths = {"deck"})
@Query("SELECT w FROM VocabWord w WHERE w.deck.id = :deckId
       AND (lower(w.term) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
           OR lower(w.meaningVi) LIKE lower(concat('%', :q, '%')) ESCAPE '!')")
Page<VocabWord> searchByDeck(@Param("deckId") Long deckId, @Param("q") String q, Pageable pageable);
```

**Pattern:** No JdbcTemplate/NamedParameterJdbcTemplate used in vocab module. Pure Spring Data JPA with `@Query` and `@EntityGraph` for N+1 prevention.

**DataSource/JDBC availability:** Standard Spring Boot DataSource auto-config available via `application.yml` but not explicitly wired in vocab layer.

---

## 6. Security Config: /api/admin/** Chain

### SecurityConfig.java
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/common/SecurityConfig.java`

**Chain 1 (adminApiSecurityFilterChain) — Order(1):**
```java
.securityMatcher("/api/admin/**")
.authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
.oauth2ResourceServer(oauth2 -> oauth2
    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
.csrf(csrf -> csrf.disable());
```

**JWT roles extraction:** `rolesClaimName: roles` (from OIDC id_token) → mapped to `ROLE_ADMIN`.

**CSRF:** Disabled for stateless API (Bearer auth).

**Multipart config:** Not explicitly set in SecurityConfig. Standard Spring Boot multipart auto-config (`max-file-size`, `max-request-size` from `application.yml`).

---

## 7. GlobalExceptionHandler: Exception → HTTP Status Mapping

**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/common/GlobalExceptionHandler.java`

**Key mappings:**
```java
@ExceptionHandler(VocabWordNotFoundException.class) → 404
@ExceptionHandler(VocabDeckNotFoundException.class) → 404
@ExceptionHandler(IllegalArgumentException.class) → 400
@ExceptionHandler(MethodArgumentNotValidException.class) → 400 (validation errors)
@ExceptionHandler(DataIntegrityViolationException.class) → 409 ("resource already exists")
@ExceptionHandler(Exception.class) → 500 (logs full stack, returns generic message)
```

**Response format:**
```json
{
  "timestamp": "2026-06-04T...",
  "status": 400,
  "error": "Bad Request",
  "message": "..."
}
```

---

## 8. Flyway Migrations: Schema & Naming Conventions

### V3__create_vocab_and_study_tables.sql
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/resources/db/migration/V3__create_vocab_and_study_tables.sql`

**vocab_deck schema:**
```sql
CREATE TABLE vocab_deck (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        varchar(120) NOT NULL,
    slug        varchar(140) NOT NULL UNIQUE,
    description varchar(500) NULL,
    topic       varchar(80) NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
```

**vocab_word schema:**
```sql
CREATE TABLE vocab_word (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deck_id         bigint NOT NULL REFERENCES vocab_deck(id) ON DELETE CASCADE,
    term            varchar(120) NOT NULL,
    meaning_vi      varchar(255) NOT NULL,
    ipa             varchar(120) NULL,
    example_en      varchar(500) NULL,
    example_vi      varchar(500) NULL,
    part_of_speech  varchar(40) NULL,
    verified        boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_vocab_word_deck ON vocab_word(deck_id);
```

**Postgres-specific:** `GENERATED ALWAYS AS IDENTITY`, `timestamptz`, `ON DELETE CASCADE`, indexed FK.

### V4__seed_starter_decks_travel_business.sql
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/resources/db/migration/V4__seed_starter_decks_travel_business.sql`

**Seeding pattern:** FK resolution via sub-select on slug:
```sql
INSERT INTO vocab_word (deck_id, term, meaning_vi, ...)
VALUES ((SELECT id FROM vocab_deck WHERE slug = 'travel'), ...)
```

---

## 9. Application Config: JDBC URL, Datasource, Flyway, Async/Scheduling

### application.yml
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/resources/application.yml`

**Datasource:**
```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5433/engly}
    username: ${DB_USER:engly}
    password: ${DB_PASSWORD:engly}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
```

**async/scheduling:** No `@EnableAsync`, `@EnableScheduling`, `@Async`, or `@Scheduled` annotations found in codebase. Not used.

**Multipart (inferred defaults):** Standard Spring Boot — not explicitly configured.

---

## 10. Frontend Structure & Admin Routes

### Frontend root: `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app`

**Admin feature tree:**
```
features/admin/
├── admin.routes.ts                    (adminRoutes definition)
├── admin.guard.ts                     (CanActivateFn: ROLE_ADMIN check)
├── admin-list-page.component.ts       (main hub)
├── admin-form-page.component.ts       (verb form)
├── vocab-word-admin-list-page.component.ts
├── vocab-word-admin-form-page.component.ts
├── deck-admin-list-page.component.ts
├── deck-admin-form-page.component.ts
├── forbidden-page.component.ts
├── data/
│   ├── admin-vocab-word-api.service.ts
│   ├── admin-vocab-word.model.ts
│   ├── admin-deck-api.service.ts
│   ├── admin-deck.model.ts
│   └── (verb counterparts)
└── (*.html templates)
```

---

## 11. Admin API Service Pattern

### admin-vocab-word-api.service.ts
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/features/admin/data/admin-vocab-word-api.service.ts`

```typescript
@Injectable({ providedIn: 'root' })
export class AdminVocabWordApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/vocab-words';

  list(q = '', page = 0, size = 20, deckId?: number): Observable<VocabWordPageResponse>
  get(id: number): Observable<AdminVocabWord>
  create(req: VocabWordRequest): Observable<AdminVocabWord>
  update(id: number, req: VocabWordRequest): Observable<AdminVocabWord>
  delete(id: number): Observable<void>
  toggleVerified(id: number, verified: boolean): Observable<AdminVocabWord>
```

**Auth:** All calls set `{ withCredentials: true }` (httpOnly session cookie); `adminBearerInterceptor` auto-attaches Bearer JWT token.

---

## 12. Admin Routes & Guards

### admin.routes.ts
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/features/admin/admin.routes.ts`

**Route definitions:**
```typescript
{
  path: 'vocab-words',
  loadComponent: () => import('./vocab-word-admin-list-page.component').then(...)
},
{
  path: 'vocab-words/new',
  loadComponent: () => import('./vocab-word-admin-form-page.component').then(...)
},
{
  path: 'vocab-words/:id/edit',
  loadComponent: () => import('./vocab-word-admin-form-page.component').then(...)
}
```

### admin.guard.ts
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/features/admin/admin.guard.ts`

```typescript
export const adminGuard: CanActivateFn = async (): Promise<boolean> => {
  const auth = inject(AuthService);
  await auth.ensureLoaded();
  return auth.isAdmin();
};
```

Redirects to `forbidden` if not admin.

---

## 13. Auth Interceptor & Token Handling

### admin-bearer.interceptor.ts
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/core/admin-bearer.interceptor.ts`

```typescript
export const adminBearerInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.url.startsWith('/api/admin')) {
    const token = inject(AuthService).token();
    if (token) {
      req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
    }
  }
  return next(req);
};
```

**Token lifecycle:** `AuthService.ensureLoaded()` → fetches `/api/token` (if admin) → stores in `_token` signal.

### proxy.conf.json
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/proxy.conf.json`

```json
{
  "/api": {
    "target": "http://localhost:8081",
    "secure": false,
    "changeOrigin": true
  }
}
```

dev-server proxies all `/api/**` requests to backend :8081.

---

## 14. Form Component Pattern: vocab-word-admin-form-page.component.ts

**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/features/admin/vocab-word-admin-form-page.component.ts`

**Key patterns:**
- `signal()` for state: `editId`, `submitting`, `serverError`, `decks`
- Reactive form: `FormBuilder.nonNullable.group()`
- Load decks on init: `deckApi.list()` → populate dropdown
- Conditional load: if `:id` param present → edit mode (fetch & patch form)
- Submit: toggle between create/update based on `editId`
- Error handling: 404 → deck not found, other → generic message
- Success: navigate back to list

---

## 15. List Component Pattern: vocab-word-admin-list-page.component.ts

**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/features/admin/vocab-word-admin-list-page.component.ts`

**Key patterns:**
- `signal()` for state: `words`, `decks`, `loading`, `error`, `totalElements`
- Debounced search: `searchControl.valueChanges` → debounceTime(300) → distinctUntilChanged() → switchMap()
- Deck filter dropdown: `deckFilterControl` → `onDeckFilterChange()` → reload
- Delete with confirm dialog
- Toggle verified (optimistic update via `signal.update()`)
- Error handling: catchError + display error signal
- Navigation: goNew(), goEdit(), goBack()

---

## 16. Angular Version & Dependencies

**package.json** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/package.json`

```json
{
  "@angular/common": "^19.2.0",
  "@angular/core": "^19.2.0",
  "@angular/forms": "^19.2.0",
  "@angular/platform-browser": "^19.2.0",
  "@angular/router": "^19.2.0",
  "rxjs": "~7.8.0"
}
```

**Standalone components:** Yes (no NgModules).
**Router:** Standalone routing (Routes) with lazy-loading & guards.
**Forms:** ReactiveFormsModule + Validators.

---

## 17. Model Interfaces

### admin-vocab-word.model.ts
**Path:** `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/features/admin/data/admin-vocab-word.model.ts`

```typescript
export interface VocabWordRequest {
  deckId: number;
  term: string;
  meaningVi: string;
  ipa?: string | null;
  exampleEn?: string | null;
  exampleVi?: string | null;
  partOfSpeech?: string | null;
}

export interface AdminVocabWord {
  id: number; deckId: number; term: string; meaningVi: string;
  ipa: string | null; exampleEn: string | null;
  exampleVi: string | null; partOfSpeech: string | null;
  verified: boolean; createdAt: string;
}

export interface VocabWordPageResponse {
  content: AdminVocabWord[];
  page: number; size: number; totalElements: number; totalPages: number;
}
```

---

## Maven & Build Config

**pom.xml** `/Users/admin/Desktop/DEV/AI/Engly/engly-be/pom.xml`

**Key dependencies:**
- Spring Boot 3.4.5 (parent)
- spring-boot-starter-web, -data-jpa, -validation, -security, -oauth2-client, -oauth2-resource-server
- flyway-core, flyway-database-postgresql
- postgresql driver
- testcontainers (test)

**Java version:** 21

---

## Summary Table: Key Absolute Paths

| Item | Path |
|------|------|
| VocabWord entity | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/domain/VocabWord.java` |
| VocabDeck entity | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/domain/VocabDeck.java` |
| VocabWordAdminController | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/admin/VocabWordAdminController.java` |
| VocabWordAdminService | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/admin/VocabWordAdminService.java` |
| VocabWordRequest DTO | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/api/dto/VocabWordRequest.java` |
| VocabWordJpaRepository | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/vocab/infrastructure/VocabWordJpaRepository.java` |
| SecurityConfig | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/common/SecurityConfig.java` |
| GlobalExceptionHandler | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/java/com/engly/common/GlobalExceptionHandler.java` |
| V3 vocab schema | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/resources/db/migration/V3__create_vocab_and_study_tables.sql` |
| V4 vocab seed | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/resources/db/migration/V4__seed_starter_decks_travel_business.sql` |
| application.yml | `/Users/admin/Desktop/DEV/AI/Engly/engly-be/src/main/resources/application.yml` |
| admin-vocab-word-api.service.ts | `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/features/admin/data/admin-vocab-word-api.service.ts` |
| admin.routes.ts | `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/features/admin/admin.routes.ts` |
| admin-bearer.interceptor.ts | `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/src/app/core/admin-bearer.interceptor.ts` |
| proxy.conf.json | `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/proxy.conf.json` |
| package.json | `/Users/admin/Desktop/DEV/AI/Engly/engly-fe/package.json` |
