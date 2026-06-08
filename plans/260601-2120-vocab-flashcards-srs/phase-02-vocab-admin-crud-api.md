# Phase 02 — Vocab Admin CRUD API

## Context Links
- Spec: `../reports/brainstorm-260601-2120-vocab-flashcards-srs.md` (§7 Backend `vocab` module)
- Mirror: `engly-be/src/main/java/com/engly/verb/admin/VerbAdminController.java`, `VerbAdminService.java`, `engly-be/src/main/java/com/engly/verb/api/dto/VerbRequest.java`, `VerbAdminResponse.java`, `VerbAdminPageResponse.java`
- Exception mapping: `engly-be/src/main/java/com/engly/common/GlobalExceptionHandler.java`
- Security: Chain 1 `/api/admin/**` → ROLE_ADMIN already covers new routes (no SecurityConfig change).

## Overview
- **Date:** 2026-06-01
- **Priority:** P1 (blocks P5 frontend admin)
- **Current status:** pending
- **Brief:** Admin CRUD for decks + vocab-words under `/api/admin/decks` and `/api/admin/vocab-words`, mirroring `VerbAdminController/Service`. ROLE_ADMIN, zero-based paging, bean validation, duplicate slug → 409.

## Key Insights
- `/api/admin/**` is already locked to `ROLE_ADMIN` (stateless bearer chain) — no method-level `@PreAuthorize`, no SecurityConfig edit.
- `GlobalExceptionHandler` currently maps `DataIntegrityViolationException` to a hardcoded `"base_form already exists"`. **Must generalize** the message (e.g. `"resource already exists"` or detect constraint) so deck-slug conflicts read correctly. KISS: change to generic `"resource already exists"`.
- Reuse the exact paging guard (`MIN_SIZE=1, MAX_SIZE=100`) and `escapeLike` helper from `VerbAdminService`.
- `VerbNotFoundException` is verb-specific; create vocab/word equivalents (already in P1) and add `@ExceptionHandler` entries.

## Requirements
### Functional
- Decks: `GET /api/admin/decks` (paged, optional `q` on name/topic), `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`.
- Words: `GET /api/admin/vocab-words` (paged, optional `q` + optional `deckId` filter), `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`, `PATCH /{id}/verified`.
- Slug: generated from name on create if not supplied (KISS: require client-supplied slug, validated `@Pattern` lowercase-kebab). Duplicate → 409.
- Delete deck cascades words (DB ON DELETE CASCADE from P1).

### Non-functional
- Files <200 LOC; split controller/service per resource.
- Record DTOs; validation `@NotBlank`/`@Size`/`@Pattern`.

## Architecture
### Packages
```
com.engly.vocab.admin           DeckAdminController, DeckAdminService,
                                VocabWordAdminController, VocabWordAdminService
com.engly.vocab.api.dto         DeckRequest, DeckResponse, DeckPageResponse,
                                VocabWordRequest, VocabWordResponse, VocabWordPageResponse,
                                VocabWordVerifiedRequest
```
### Component interactions
Controller → Service (`@Transactional`) → JpaRepository (P1). Service maps entity↔record DTO via private `toDto`.
### Data flow
`POST /api/admin/decks {name, slug, description, topic}` → validate → save → 201 + DeckResponse. Duplicate slug bubbles `DataIntegrityViolationException` → 409.

## Related Code Files
### Create
- `engly-be/src/main/java/com/engly/vocab/admin/DeckAdminController.java`
- `engly-be/src/main/java/com/engly/vocab/admin/DeckAdminService.java`
- `engly-be/src/main/java/com/engly/vocab/admin/VocabWordAdminController.java`
- `engly-be/src/main/java/com/engly/vocab/admin/VocabWordAdminService.java`
- `engly-be/src/main/java/com/engly/vocab/api/dto/DeckRequest.java`
- `engly-be/src/main/java/com/engly/vocab/api/dto/DeckResponse.java`
- `engly-be/src/main/java/com/engly/vocab/api/dto/DeckPageResponse.java`
- `engly-be/src/main/java/com/engly/vocab/api/dto/VocabWordRequest.java`
- `engly-be/src/main/java/com/engly/vocab/api/dto/VocabWordResponse.java`
- `engly-be/src/main/java/com/engly/vocab/api/dto/VocabWordPageResponse.java`
- `engly-be/src/main/java/com/engly/vocab/api/dto/VocabWordVerifiedRequest.java`

### Modify
- `engly-be/src/main/java/com/engly/common/GlobalExceptionHandler.java` — generalize `DataIntegrityViolationException` message; add `@ExceptionHandler` for `VocabDeckNotFoundException` + `VocabWordNotFoundException` (→ 404).
- `engly-be/src/main/java/com/engly/vocab/infrastructure/VocabDeckJpaRepository.java` — add `search(q, pageable)` JPQL (name/topic LIKE, ESCAPE '!').
- `engly-be/src/main/java/com/engly/vocab/infrastructure/VocabWordJpaRepository.java` — add `search(q, pageable)` + `findByDeckId(deckId, pageable)`.

### Delete
- None.

## Implementation Steps
1. **DTOs**:
   - `DeckRequest(@NotBlank @Size(max=120) name, @NotBlank @Size(max=140) @Pattern(regexp="[a-z0-9-]+") slug, @Size(max=500) description, @Size(max=80) topic)`.
   - `DeckResponse(id, name, slug, description, topic)`; `DeckPageResponse(content, page, size, totalElements, totalPages)`.
   - `VocabWordRequest(@NotNull deckId, @NotBlank @Size(max=120) term, @NotBlank @Size(max=255) meaningVi, @Size(max=120) ipa, @Size(max=500) exampleEn, @Size(max=500) exampleVi, @Size(max=40) partOfSpeech, boolean verified)`.
   - `VocabWordResponse(...all fields...)`; `VocabWordPageResponse(...)`; `VocabWordVerifiedRequest(boolean verified)`.
2. **DeckAdminService** (`@Transactional`): copy `VerbAdminService` structure — `search/getById/create/update/delete`, paging guard + `escapeLike`, `findOrThrow` → `VocabDeckNotFoundException`, `apply` maps DeckRequest→entity, `toDto`.
3. **DeckAdminController** `@RequestMapping("/api/admin/decks")`: list/getById/create(201)/update/delete(204). Mirror verb controller signatures.
4. **VocabWordAdminService**: same shape + `deckId` filter branch in `search` (if `deckId != null` → `findByDeckId`, else search/findAll). Validate `deckId` exists on create/update → else `VocabDeckNotFoundException` (400/404). `toggleVerified`.
5. **VocabWordAdminController** `@RequestMapping("/api/admin/vocab-words")`: add `@RequestParam(required=false) Long deckId` to list; `PATCH /{id}/verified`.
6. **GlobalExceptionHandler**: change conflict message to generic `"resource already exists"`; add 404 handlers for the two new NotFound exceptions.
7. Repository JPQL search methods (LIKE lower + ESCAPE '!', mirror verb).
8. Compile + run existing tests (ensure verb conflict-message test, if any, updated — check `VerbAdminIntegrationTest.create_duplicateBaseForm_returns409` asserts `"base_form already exists"` → must update assertion to new generic message in P06, or keep message verb-specific via constraint-name inspection).
   - **Decision (KISS):** Keep generic `"resource already exists"`; update the verb integration test assertion in P06.

## Todo List
- [ ] Deck DTOs (request/response/page)
- [ ] VocabWord DTOs (request/response/page/verified)
- [ ] DeckAdminService + Controller
- [ ] VocabWordAdminService + Controller (deckId filter + verified toggle)
- [ ] Repository search/filter JPQL
- [ ] GlobalExceptionHandler: generic conflict msg + 2 NotFound handlers
- [ ] Compile/build check

## Success Criteria
- `./mvnw -q compile` passes.
- Manual/curl with admin bearer: create deck → 201; duplicate slug → 409; create word with bad deckId → 404; list words `?deckId=` filters; `PATCH verified` flips.
- No regression in verb admin endpoints.

## Risk Assessment
- **Conflict message coupling**: generalizing breaks verb test assertion → tracked, fixed in P06.
- **deckId validation race**: create word references deck; if deck deleted concurrently, FK violation → 409 (acceptable).
- **File size**: keep each service <200 LOC by sharing nothing across resources (DRY via mirrored pattern, not inheritance).

## Security Considerations
- All routes under `/api/admin/**` → ROLE_ADMIN bearer (Chain 1). No new auth surface.
- Input validation prevents oversized payloads; slug `@Pattern` blocks injection-y slugs.
- No SQL concat — JPQL parameterized + LIKE escaped.

## Next Steps
- Unblocks **P5** (frontend admin deck/word CRUD).

## Compile/Build Check
```
cd engly-be && ./mvnw -q -DskipTests compile
```
