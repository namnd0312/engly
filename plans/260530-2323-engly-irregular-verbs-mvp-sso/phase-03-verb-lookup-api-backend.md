# Phase 03 — Verb Lookup API (Backend)

## Context Links
- [plan.md](plan.md)
- [Phase 02](phase-02-database-schema-and-verb-seed-data.md)
- [Brainstorm summary](../reports/brainstorm-summary-260530-2323-engly-irregular-verbs-ddd-sso.md)

## Overview
- **Date:** 2026-05-30
- **Priority:** P1 (core MVP)
- **Effort:** 6h
- **Implementation status:** pending
- **Review status:** not reviewed
- **Description:** Implement public verb-lookup API in `verb/` bounded context (FLAT: controller→service→repo). Search + paginate + detail, validation, error handling, unit tests.

## Key Insights
- Module kept flat — NO aggregate/value-object ceremony (KISS/YAGNI). Entity = JPA `IrregularVerb`; port `VerbRepository`; impl is Spring Data JPA repo.
- Search = case-insensitive match base_form OR meaning_vi, paginated. Detail by base_form (path = unique natural key, lowercased).
- Return DTOs, not entities, to keep API contract stable (decouples persistence).

## Requirements
**Functional**
- `GET /api/verbs?q=&page=&size=` → paginated results; q optional (empty → all, paged). Match base_form/meaning_vi (case-insensitive, prefix/contains).
- `GET /api/verbs/{baseForm}` → single verb detail; 404 if missing.
**Non-functional**
- Public (permitAll — enforced in phase 05). p95 < 200ms for seeded dataset. Files < 200 lines. Default page size 20, max 100.

## Architecture
```
com.engly.verb
├── api/
│   ├── verb-controller.java        (VerbController: GET list + detail)
│   └── dto/ verb-response.java, verb-search-page-response.java (records)
├── application/
│   └── verb-query-service.java     (VerbQueryService: search, getByBaseForm, entity→DTO map)
├── domain/
│   ├── irregular-verb.java         (@Entity IrregularVerb)
│   └── verb-repository.java        (port interface: search(q,pageable), findByBaseForm)
└── infrastructure/
    └── verb-jpa-repository.java     (Spring Data JPA impl of port + @Query)
```
- `VerbRepository` port (domain) → `VerbJpaRepository extends JpaRepository<IrregularVerb,Long>` (infra) implementing port via derived/@Query (`lower(base_form) like` + `lower(meaning_vi) like`).
- Controller returns `Page`-mapped `VerbSearchPageResponse{ content, page, size, totalElements, totalPages }`.
- Errors: `GlobalExceptionHandler` (in common/) → 404 NotFound for missing baseForm, 400 for invalid paging params.

## Related Code Files
**Create**
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/verb/domain/irregular-verb.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/verb/domain/verb-repository.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/verb/infrastructure/verb-jpa-repository.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/verb/application/verb-query-service.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/verb/api/verb-controller.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/verb/api/dto/verb-response.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/verb/api/dto/verb-search-page-response.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/common/global-exception-handler.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/test/java/com/engly/verb/verb-query-service-test.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/test/java/com/engly/verb/verb-controller-test.java` (@WebMvcTest)
**Modify:** none (SecurityConfig stub already permitAll; tightened in phase 05)

## Implementation Steps
1. `IrregularVerb` @Entity mapping all columns from phase 02 (incl. alternates, ipa*, meaning_vi, verified).
2. `VerbRepository` port: `Page<IrregularVerb> search(String q, Pageable p)`, `Optional<IrregularVerb> findByBaseFormIgnoreCase(String b)`.
3. `VerbJpaRepository` (infra) extends JpaRepository + implements port; `@Query` with `lower(base_form) like lower(concat('%',:q,'%')) or lower(meaning_vi) like ...`; empty/null q → return all paged.
4. `VerbQueryService`: validate page≥0, 1≤size≤100; call repo; map entity→`VerbResponse`; build `VerbSearchPageResponse`; throw `VerbNotFoundException` when detail absent.
5. `VerbController`: `@GetMapping("/api/verbs")` (q default "", page 0, size 20), `@GetMapping("/api/verbs/{baseForm}")`.
6. `GlobalExceptionHandler` (@RestControllerAdvice): map VerbNotFound→404, constraint/illegal-arg→400, generic→500 with safe body `{timestamp,status,error,message}`.
7. Unit tests: service (search/paging/not-found mapping with mocked repo) + controller (@WebMvcTest, mocked service, asserts JSON + status codes).
8. Compile + run tests: `./gradlew test`.

## Todo List
- [ ] IrregularVerb entity
- [ ] VerbRepository port + JPA impl (search + findByBaseForm)
- [ ] VerbQueryService (search, detail, paging validation, DTO map)
- [ ] DTO records (VerbResponse, VerbSearchPageResponse)
- [ ] VerbController (list + detail)
- [ ] GlobalExceptionHandler (404/400/500)
- [ ] Unit tests (service + controller) green
- [ ] gradlew build + test pass

## Success Criteria
- `GET /api/verbs?q=go` returns go/went/gone + IPA + meaning_vi in paged envelope.
- `GET /api/verbs/go` returns detail; `GET /api/verbs/zzz` → 404.
- Invalid size (>100 or <1) → 400.
- All unit tests pass; files < 200 lines.

## Risk Assessment
- LIKE search slow at scale → fine for ~180 rows; lower() index from phase 02. Defer trigram. *Low.*
- Entity leakage to API → enforced DTO mapping. *Low.*
- baseForm path case sensitivity → use findByBaseFormIgnoreCase + lowercase path. *Low.*

## Security Considerations
- Public endpoints; no auth. Still validate/clamp paging to prevent abuse (size cap 100).
- Use parameterized JPA queries (no string-concat SQL) → no injection.
- Error handler returns generic messages (no stack traces / SQL to client).

## Next Steps
- Phase 04 consumes this exact contract (envelope shape, field names).
- Phase 05 enforces permitAll on `/api/verbs/**` in real SecurityConfig.
- Phase 06 adds Testcontainers integration tests over real Postgres.

