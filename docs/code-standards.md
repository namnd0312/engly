# Code Standards

Derived from actual codebase patterns as of 2026-08-20. Evidence via file paths and line references.

---

## Backend Architecture — Package-by-Feature Layout

**Pattern:** `com.engly.<feature>.<layer>`  
**Observed in:** `com.engly.verb`, `com.engly.vocab`, `com.engly.study`, `com.engly.identity`, `com.engly.vocab.imports`

Each feature module organizes by layer:

| Layer | Package | Purpose | Example |
|-------|---------|---------|---------|
| API | `<feature>.api` | REST controllers, request/response DTOs | `verb/api/VerbController.java`, `verb/api/dto/VerbRequest.java` |
| Application | `<feature>.application` | Business logic, service classes, stateless orchestration | `study/application/Sm2Scheduler.java`, `identity/application/EmailVerificationService.java` |
| Domain | `<feature>.domain` | JPA entities, domain exceptions, ports (interfaces) | `verb/domain/IrregularVerb.java`, `verb/domain/VerbNotFoundException.java` |
| Infrastructure | `<feature>.infrastructure` | JpaRepository implementations, data access adapters | `verb/infrastructure/VerbJpaRepository.java` |
| Config | `<feature>.config` | `@ConfigurationProperties` classes, bean factories | `identity/config/JwtKeyConfig.java`, `identity/config/AuthProperties.java` |

**Note:** `com.engly.common` holds cross-cutting concerns (SecurityConfig, GlobalExceptionHandler, OidcRoleMappingConfig) — use for shared, not feature-specific code.

---

## Java Naming & DTO Conventions

### Records for DTOs

**Pattern:** Use Java `record` (final, immutable, hashCode/equals auto-generated)  
**Evidence:** `verb/api/dto/VerbResponse.java`, `identity/api/dto/LoginRequest.java`, `study/api/dto/ReviewRequest.java`

```java
// Example: com.engly.identity.api.dto.LoginRequest (line 2-5)
public record LoginRequest(
    String email,
    String password
) {}
```

Bean validation annotations:

```java
@Email(message = "Invalid email")
String email;

@NotBlank(message = "Password required")
String password;

@Size(min=1, max=100, message = "Field must be 1-100 chars")
String term;
```

### Service Classes

**Pattern:** `@Service` with transactional methods; method names follow domain intent

```java
// Example: AuthTokenService.java
public class AuthTokenService {
    public JwtToken issueAccessToken(UserAccount user) { ... }  // Domain intent: issue token
    public RefreshToken rotateRefreshToken(UserAccount user) { ... }
}
```

### Exception Classes

**Pattern:** One domain exception per feature, extends `RuntimeException`, caught by `GlobalExceptionHandler`

**Location:** `<feature>.domain` package  
**Evidence:** `verb/domain/VerbNotFoundException.java`, `identity/domain/InvalidLoginException.java`, `vocab/imports/domain/VocabImportConflictException.java`

```java
// com.engly.verb.domain.VerbNotFoundException
public class VerbNotFoundException extends RuntimeException {
    public VerbNotFoundException(String message) {
        super(message);
    }
}
```

**GlobalExceptionHandler mapping** (com/engly/common/GlobalExceptionHandler.java):
- `VerbNotFoundException` → HTTP 404
- `InvalidLoginException` → HTTP 401
- `EmailAlreadyExistsException` → HTTP 409
- `DataIntegrityViolationException` → HTTP 409
- `IllegalArgumentException` → HTTP 400
- Other RuntimeException → HTTP 500 (no stack trace)

---

## Flyway Migration Conventions

**Location:** `engly-be/src/main/resources/db/migration/`  
**Naming:** `V<n>__snake_case_description.sql` (double underscore, snake_case)

| Version | Pattern | Notes |
|---------|---------|-------|
| V1-V4 | Schema + seed (schema-only or mixed) | Initial tables; AI-drafted seed data marked `verified=false` |
| V5+ | Schema evolution + feature-specific tables | Forward-only (no rollback); each migration is idempotent (CREATE TABLE IF NOT EXISTS, INSERT ... ON CONFLICT) |

**Seed data convention:** AI-drafted seeds label `verified=false` in a comment + in the table's `verified` column (e.g., V2, V4, V8). No human annotation required in commit.

**Observation:** All migrations use forward-only pattern; no DOWN scripts (Flyway validateOnMigrate=true enforces immutability).

---

## Test Layering

**Location:** `engly-be/src/test/java/com/engly/`  
**Framework:** JUnit 5, Mockito, Testcontainers 1.21.0 (Postgres)

### Test Types

| Type | Annotation | Purpose | Location | Example |
|------|-----------|---------|----------|---------|
| Smoke | `@SpringBootTest` + Testcontainers | Context loads, Flyway runs, DB ready | Root test | EnglyApplicationTests.java |
| Unit | None (plain @Test) | Service logic in isolation; mock I/O | `<feature>/` | Sm2SchedulerTest.java (no mocks; pure logic test) |
| Service | `@SpringBootTest` + mock repos | Service behavior; some infrastructure isolation | `<feature>/` | AuthTokenServiceTest, EmailVerificationServiceTest |
| Web Layer | `@WebMvcTest` | Controller request/response; no DB | `<feature>/` | VerbControllerTest, AuthControllerTest |
| Security | `@WebMvcTest` with MockMvc | Auth rules (401, 403, permit); no business logic | `<feature>/` | AdminSecurityRulesTest, AuthSecurityRulesTest |
| Integration | `@SpringBootTest` + Testcontainers | Real Flyway, real DB, real transactions | `<feature>/` | VerbApiIntegrationTest, StudyFlowIntegrationTest |

**Testcontainers config:** Uses Postgres image (auto-downloaded); runs migrations V1-V11 on startup; no mock data (seed data from migrations only).

**No fakes/mocks in integration tests:** Real database, real Flyway, real transactions — tests must pass with actual behavior, not stubbed.

**Test naming:** `*Test.java` for unit; `*IntegrationTest.java` for Testcontainers; `*SecurityRulesTest.java` for auth matrix tests.

**Coverage:** JaCoCo configured in pom.xml (maven-jacoco-plugin); visible in GitHub Actions if CI runs `mvn test`.

---

## Frontend — Angular 19.2 Conventions

### File Naming

**Pattern:** Kebab-case with role suffix

| Pattern | Example | Purpose |
|---------|---------|---------|
| `*-page.component.ts` | `login-page.component.ts`, `study-settings-page.component.ts` | Routed page component |
| `*-page.component.html` | `login-page.component.html` | Template for page component |
| `*-page.component.spec.ts` | `login-page.component.spec.ts` | Jasmine tests (collocated) |
| `*-api.service.ts` | `verb-api.service.ts`, `study-api.service.ts` | HttpClient service |
| `*.model.ts` | `verb.model.ts`, `study.model.ts` | TS interfaces / type definitions |
| `*.routes.ts` | `verb-search.routes.ts`, `admin.routes.ts` | Feature routing configuration |
| `*.guard.ts` | `auth.guard.ts`, `admin.guard.ts` | CanActivateFn route guards |

**Evidence:** `engly-fe/src/app/features/` (all feature directories follow this pattern)

### Standalone Components

**Pattern:** `@Component({ standalone: true, imports: [...] })`  
**Evidence:** All feature components in `engly-fe/src/app/features/` are standalone

```typescript
@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, HttpClientModule],
  template: `...`
})
export class LoginPageComponent { ... }
```

### Routing Structure

**Root routes:** `app.routes.ts` with lazy-loaded features

```typescript
// app.routes.ts
export const routes: Routes = [
  { path: '', loadChildren: () => import('./features/verb-search/verb-search.routes').then(m => m.VERB_SEARCH_ROUTES) },
  { path: 'auth', loadChildren: () => import('./features/auth/auth.routes').then(m => m.AUTH_ROUTES), canActivate: [unauthGuard] },
  { path: 'admin', canActivate: [adminGuard], loadChildren: () => import('./features/admin/admin.routes').then(m => m.ADMIN_ROUTES) },
  { path: 'study', canActivate: [authGuard], loadChildren: () => import('./features/study/study.routes').then(m => m.STUDY_ROUTES) },
  { path: 'progress', canActivate: [authGuard], loadChildren: () => import('./features/progress/progress.routes').then(m => m.PROGRESS_ROUTES) },
];
```

**Feature routes:** Each feature defines its own routes exported from `<feature>.routes.ts`

### API Services

**Pattern:** `HttpClient` with `withCredentials: true` (for cookies)

```typescript
// study-api.service.ts (evidence: engly-fe/src/app/features/study/data/study-api.service.ts)
@Injectable({ providedIn: 'root' })
export class StudyApiService {
  constructor(private http: HttpClient) {}

  getDecks() {
    return this.http.get<StudyDeckSummary[]>('/api/study/decks', {
      withCredentials: true
    });
  }
}
```

### Test Colocation

**Pattern:** `*.spec.ts` files live next to source in same directory

**Framework:** Karma + Jasmine  
**Evidence:** `features/auth/login-page.component.spec.ts`, `features/study/study-api.service.spec.ts`

**Setup:** `ng test` runs all `**/*.spec.ts` files; HttpTestingController for API mocking

---

## Configuration Conventions

### Spring Boot Config

**Pattern:** `@ConfigurationProperties(prefix="app.<feature>")` classes in `<feature>.config` package

```java
// Example: identity/config/AuthProperties.java
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    private String jwtSecret;
    private int accessTokenExpiry;  // minutes
    private int refreshTokenExpiry; // days
}
```

**Environment override:** Properties in `application.yml` can be overridden by env vars (all caps, `_` separators):
```yaml
app:
  auth:
    jwt-secret: ${JWT_SECRET:dev-key}
    access-token-expiry: ${JWT_ACCESS_EXPIRY:5}
    refresh-token-expiry: ${JWT_REFRESH_EXPIRY:7}
```

**Evidence:** `engly-be/src/main/resources/application.yml`, env vars used in Kubernetes manifests (`k8s/20-engly-be-backend.yaml`)

---

## File Size & Modularization

**Guidance:** Keep code files under 200 lines for optimal context management (stated in project CLAUDE.md)

**Strategy if file exceeds 200 lines:**
1. Extract service classes to separate modules
2. Extract DTOs to separate files
3. Split large repositories into focused adapters
4. Create utility/helper classes for repeated logic

**Observed patterns:**
- DTOs: Always separate files (e.g., `verb/api/dto/VerbRequest.java`, `verb/api/dto/VerbResponse.java`)
- Services: Separate per domain responsibility (e.g., `AuthTokenService`, `EmailVerificationService`, `SocialLoginService`)
- Repositories: One interface per domain entity, separate JPA implementation

---

## Code Quality Expectations

| Aspect | Standard |
|--------|----------|
| Null handling | Use `Optional` in API; null-check in domain logic; `@Nullable` annotation for potentially null values |
| Error messages | Clear, actionable; no stack traces in API responses (GlobalExceptionHandler removes them) |
| Logging | INFO level for business events (logins, imports); DEBUG for details; use SLF4J |
| Transactions | `@Transactional` on service methods that write; `readOnly=true` for queries; scope wisely (not on controller) |
| Security | No hardcoded secrets; use `@ConfigurationProperties` + env vars; validate input in DTOs |
| Idempotency | Migrations use `IF NOT EXISTS` and `ON CONFLICT DO NOTHING`; replay-safe |
| Immutability | Records for DTOs; `final` for utility classes; avoid mutable static state |

---

## Cross-Cutting Example: Adding a New Feature

**To add a new feature (e.g., `com.engly.flashcards`):**

1. Create package structure under `engly-be/src/main/java/com/engly/flashcards/`:
   - `api/` (controllers, DTOs)
   - `application/` (services)
   - `domain/` (entities, exceptions, ports)
   - `infrastructure/` (repositories)
   - `config/` (config beans if needed)

2. Write DTOs as records in `api/dto/`.

3. Create domain entity in `domain/` and port interface for data access.

4. Implement repository in `infrastructure/` extending JpaRepository.

5. Create service in `application/` with business logic.

6. Create controller in `api/` with `@RestController`, route to service.

7. Add domain exception in `domain/` if needed (GlobalExceptionHandler catches it).

8. Create Flyway migration: `V<n>__create_flashcards_table.sql` (forward-only, idempotent).

9. Write tests:
   - Unit test for service logic (`FlashcardServiceTest.java`)
   - Web layer test for controller (`FlashcardControllerTest.java`, `@WebMvcTest`)
   - Integration test with Testcontainers (`FlashcardIntegrationTest.java`, `@SpringBootTest`)
   - Security rules test if endpoint is admin-only (`FlashcardSecurityRulesTest.java`)

10. Frontend: Create feature in `engly-fe/src/app/features/flashcards/` with same layer structure (pages, services, models, routes).
