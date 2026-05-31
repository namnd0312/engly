# Code Review — Engly Irregular-Verbs MVP
**Date:** 2026-05-31  
**Reviewer:** code-reviewer subagent  
**Verdict:** PASS (merge-ready for public MVP; SSO phase deferred as expected)  
**Score:** 8.0 / 10

---

## Scope

| Layer | Files reviewed | LOC |
|---|---|---|
| Backend domain | `IrregularVerb`, `VerbRepository`, `VerbNotFoundException` | 70+22+12 |
| Backend infra | `VerbJpaRepository` | 36 |
| Backend app | `VerbQueryService` | 94 |
| Backend API | `VerbController`, `VerbResponse`, `VerbSearchPageResponse` | 49+18+14 |
| Backend common | `SecurityConfig`, `GlobalExceptionHandler` | 32+60 |
| Backend resources | `application.yml`, `V1__create…`, `V2__seed…` | 17+26+189 |
| Backend build | `build.gradle.kts` | 54 |
| Backend tests | `VerbQueryServiceTest`, `VerbControllerTest`, `VerbApiIntegrationTest`, `SecurityRulesTest` | 149+116+117+67 |
| Frontend feature | `verb-api.service.ts`, `verb.model.ts`, `verb-search-page.component.ts/html`, `verb-detail-page.component.ts/html`, `verb-search.routes.ts` | ~550 |
| Frontend core | `http-session-cookie-config.ts`, `app.config.ts`, `app.routes.ts`, `app.component.html` | ~50 |

All files are **under 200 lines**. No file-size violations.

---

## Overall Assessment

The codebase is clean, well-layered (FLAT verb module respected), and architecturally sound for a greenfield MVP. Security stub is clearly marked transient with TODO markers. Tests are real assertions, not vacuous. Main gaps: no server-side logging in `GlobalExceptionHandler`, a subscription leak in `VerbDetailPageComponent`, and a minor LIKE-wildcard correctness concern.

---

## Critical Issues

None.

---

## High Priority Findings

### H1 — `GlobalExceptionHandler`: generic exception swallowed silently (no logging)
**File:** `backend/src/main/java/com/engly/common/GlobalExceptionHandler.java:46-49`

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
    // Log internally but never expose internal details to client
    return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
}
```

The comment says "log internally" but there is no logger declared or called. Any unhandled exception (e.g., Hibernate error, timeout, NPE) is silently dropped — invisible in prod logs. This makes debugging production failures impossible.

**Fix:** Add SLF4J logger and `log.error("Unhandled exception", ex)` before the return.

---

### H2 — `VerbDetailPageComponent`: subscription never unsubscribed
**File:** `frontend/src/app/features/verb-search/verb-detail-page.component.ts:26-48`

`ngOnInit` subscribes to `route.paramMap.pipe(switchMap(...))` but the component implements no `OnDestroy`. While Angular's router does destroy `ActivatedRoute` on navigation, Angular best-practice requires explicit cleanup to prevent leaks in edge cases (e.g., dynamic insertion, testing).

**Fix (preferred — uses Angular 16+ API):**
```typescript
import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/rxjs-interop';

// inside class, replace subscription:
private readonly destroyRef = inject(DestroyRef);

ngOnInit(): void {
  this.route.paramMap.pipe(
    switchMap(...),
    takeUntilDestroyed(this.destroyRef)
  ).subscribe(...);
}
```

---

## Medium Priority Findings

### M1 — LIKE wildcard injection in search query (JPQL, not SQL-injection, but correctness issue)
**File:** `backend/src/main/java/com/engly/verb/infrastructure/VerbJpaRepository.java:25-31`

Spring Data JPA **does not escape `%` or `_` wildcards** inside `@Param`-bound JPQL LIKE expressions. A user query of `%` returns all verbs (identical to empty query). A query of `_` matches any single character. This is a **correctness/UX** issue (not a security hole since the endpoint is public read-only), but it can cause confusing result counts.

**Fix:** Escape in `VerbQueryService.search()` before passing to repo:
```java
String escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
resultPage = verbRepository.search(escaped, pageable);
```
Then update the JPQL to add `ESCAPE '\\'`.

### M2 — Empty-query path uses LIKE-based search instead of `findAll`
**File:** `backend/src/main/java/com/engly/verb/application/VerbQueryService.java:42-43`

When `q` is blank, the code calls `verbRepository.search("", pageable)` which executes `WHERE lower(baseForm) LIKE '%' OR lower(meaningVi) LIKE '%'` — a full-table scan using `lower()` functions. At 179 rows this is harmless, but as the table grows this bypasses the functional indexes on `lower(base_form)` and `lower(meaning_vi)`. The efficient path would be `findAll(pageable)`.

**Fix:**
```java
if (q == null || q.isBlank()) {
    resultPage = ((org.springframework.data.jpa.repository.JpaRepository<IrregularVerb, Long>) verbRepository)
        .findAll(pageable);
} else { ... }
```
Or declare `Page<IrregularVerb> findAll(Pageable pageable)` on `VerbRepository` port and call that.

### M3 — `ConstraintViolationException` handler leaks Hibernate validation message
**File:** `backend/src/main/java/com/engly/common/GlobalExceptionHandler.java:40-43`

`ex.getMessage()` from `jakarta.validation.ConstraintViolationException` can include internal field paths (e.g., `verbQueryService.search.size: must be between 1 and 100`). It exposes method names and service internals in the HTTP response.

**Fix:** Extract only constraint messages without the method path:
```java
String message = ex.getConstraintViolations().stream()
    .map(cv -> cv.getPropertyPath().toString().replaceAll(".*\\.", "") + ": " + cv.getMessage())
    .findFirst().orElse("Validation failed");
```

### M4 — `http-session-cookie-config.ts` is dead code (never imported)
**File:** `frontend/src/app/core/http-session-cookie-config.ts`

Exports a string constant that is not imported anywhere. Violates YAGNI. The comment is useful as developer intent documentation but the file should either be a `.md` note or simply deleted until phase-05 actually needs it.

**Fix:** Delete the file; the comment in `verb-api.service.ts` already documents the rationale.

### M5 — No Angular router wildcard (`**`) catch-all route
**Files:** `frontend/src/app/app.routes.ts`, `frontend/src/app/features/verb-search/verb-search.routes.ts`

Navigating to an unknown path (e.g., `/foo/bar/baz`) causes the Angular router to silently render nothing — no 404 page, no error state. This is especially noticeable when URL-copying verb detail links.

**Fix (minimal):** Add to `app.routes.ts`:
```typescript
{ path: '**', redirectTo: 'verbs' }
```

### M6 — `meaningVi` nullability mismatch across layers
- DB schema (`V1`): `meaning_vi varchar(255) NOT NULL`
- Java DTO `VerbResponse`: `String meaningVi` (implicitly nullable in Java)
- TypeScript model `verb.model.ts:11`: `meaningVi: string | null`

The TS model is unnecessarily permissive; the DB guarantees non-null. This causes the UI to defensively render `??  '—'` for a value that will never be null, adding noise.

**Fix:** Change `verb.model.ts` to `meaningVi: string` (non-nullable). Update templates to drop `?? '—'` for this field only.

---

## Low Priority Findings

### L1 — `IrregularVerb` entity: `createdAt` has no `@PrePersist` or `@CreationTimestamp`
**File:** `backend/src/main/java/com/engly/verb/domain/IrregularVerb.java:51-52`

The DB column `DEFAULT now()` handles inserts correctly as long as JPA doesn't try to insert `null` explicitly. With `nullable = false` and no `@Column(insertable = false)`, Hibernate will attempt to insert `null` for `createdAt` on a programmatic save — which will fail. This is only relevant if the application ever writes verbs programmatically (currently only Flyway seed does it), but the field should be protected.

**Fix:** Add `@Column(name = "created_at", nullable = false, updatable = false, insertable = false)` or add `@CreationTimestamp`.

### L2 — `build.gradle.kts`: Testcontainers system-property override is fragile
**File:** `backend/build.gradle.kts:47-53`

Passing `systemProperty("api.version", "1.44")` as a JVM property to override the docker-java shaded config is undocumented and may silently break with future TC or OrbStack versions. The `resolutionStrategy` version pin to `1.21.0` is appropriate, but the `api.version` system property is not a documented TC API.

Risk is low (dev-env only, well-commented), acceptable for MVP. Suggest revisiting when TC ≥ 1.22 ships.

### L3 — `VerbSearchPageComponent`: `loading()` state is `false` initially but shows `empty-msg` on first render
**File:** `frontend/src/app/features/verb-search/verb-search-page.component.html:22-24`

On initial render (before `ngOnInit` completes), `loading()=false`, `error()=null`, `results().length=0`. The template shows the "No verbs found" message for a brief flash before the initial request fires. The `debounceTime(300)` means the flash lasts at least 300ms.

**Fix:** Initialize `loading` signal to `true`:
```typescript
readonly loading = signal(true);
```

### L4 — No `@Column(insertable=false)` guard on `createdAt` (overlaps L1)
See L1 above.

### L5 — `VerbApiIntegrationTest`: `search_emptyQuery_returnsSeedCount` checks `>= 179` but V2 has exactly 179 rows
**File:** `backend/src/test/java/com/engly/verb/VerbApiIntegrationTest.java:72-73`

The comment says "179 rows" but the assertion allows any count >= 179. This is intentionally permissive but the comment is slightly inconsistent (`"180-row seed"` in V2 filename vs `179` in test comment). Minor documentation inconsistency.

---

## Nit-level Observations

- **N1** `verb-search-page.component.html` has `<style>` inline (178 lines total including CSS). Consider extracting CSS to `.component.css` for consistency and to keep HTML under 100 lines — current size is within the 200-line limit but at the edge.
- **N2** `VerbQueryServiceTest.makeVerb()` uses reflection (`setField`) to set private fields — acceptable for unit tests but a constructor or builder on the entity would be cleaner long-term.
- **N3** `VerbController` uses `int` for `page`/`size` — passing a non-integer string (e.g., `?page=abc`) returns a 400 from Spring automatically. No issue, just documenting coverage.
- **N4** `application.yml` default password `${DB_PASSWORD:engly}` is a weak default. Acceptable for local dev; ensure it is not used in any cloud environment.

---

## Positive Observations

- Parameterized JPQL throughout — no string concatenation, no SQL injection risk.
- `VerbResponse`/`VerbSearchPageResponse` are records; `id` and `createdAt` excluded from API — correct DTO hygiene.
- `@Transactional(readOnly = true)` on `VerbQueryService` — correct.
- `SecurityConfig` CSRF-disabled marked clearly as transient with two TODO markers referencing phase-05. SSO deferral is documented.
- `GlobalExceptionHandler.handleGeneric` returns a generic message with no stack trace — correct.
- `switchMap` used in both Angular components — HTTP requests properly cancelled on rapid input.
- `withCredentials: true` per-call is SSO-ready without a premature interceptor (YAGNI).
- Tests are real assertions — integration test checks actual seed data (`went`, `đi`) not just HTTP status.
- All non-Java files use kebab-case; Java files use PascalCase — naming conventions respected.
- `build.gradle.kts` version-override is well-commented with the specific OrbStack/TC issue explained.
- `@for (verb of results(); track verb.baseForm)` uses proper identity tracking.

---

## Recommended Actions (prioritized)

1. **[High]** Add SLF4J logger + `log.error("Unhandled exception", ex)` in `GlobalExceptionHandler.handleGeneric` — without this, prod debugging is blind.
2. **[High]** Add `takeUntilDestroyed(this.destroyRef)` in `VerbDetailPageComponent.ngOnInit`.
3. **[Medium]** Escape `%` and `_` in `VerbQueryService.search()` before passing to LIKE query.
4. **[Medium]** Delete `http-session-cookie-config.ts` (dead code).
5. **[Medium]** Add `{ path: '**', redirectTo: 'verbs' }` wildcard route to `app.routes.ts`.
6. **[Medium]** Fix `ConstraintViolationException` handler to not expose method path in message.
7. **[Low]** Initialize `loading` signal to `true` in `VerbSearchPageComponent` to eliminate empty-state flash.
8. **[Low]** Add `insertable = false` or `@CreationTimestamp` to `IrregularVerb.createdAt`.
9. **[Low]** Tighten `meaningVi` type to `string` (non-nullable) in `verb.model.ts`.

---

## Metrics

| Metric | Value |
|---|---|
| Files reviewed | 28 |
| LOC analyzed | ~1,500 |
| Files > 200 lines | 0 |
| Critical findings | 0 |
| High findings | 2 |
| Medium findings | 6 |
| Low findings | 5 |
| Nit findings | 4 |
| Backend tests | 24/24 pass |
| Frontend tests | 13/13 pass |
| DTO entity leakage | None (id, createdAt excluded) |
| Parameterized queries | Confirmed |
| Secrets committed | None (env-var substitution used) |
| Stack traces in API | None (generic handler correct) |
| CSRF-disabled marked transient | Yes (2 TODO markers) |

---

## Verdict

**PASS — merge-ready for public MVP.**  
SSO/phase-05 deferral is intentional and well-marked. Items H1 (no logging) and H2 (subscription leak) should be fixed before the first production deployment. Items M1–M6 are recommended before the next feature phase.

---

## Unresolved Questions

1. Is `meaning_vi` intended to remain `NOT NULL` going forward, or will future verbs have unknown meanings? (Affects whether TS model tightening in M6 is safe.)
2. The V2 filename says `180-rows` but the integration test asserts `>= 179`. Which is canonical? (V2 file has 179 data rows — filename is inaccurate.)
3. Will `ConstraintViolationException` (M3) ever be reachable from the public `GET /api/verbs` endpoints, or only from future write endpoints? If only write, M3 priority drops to Low.
