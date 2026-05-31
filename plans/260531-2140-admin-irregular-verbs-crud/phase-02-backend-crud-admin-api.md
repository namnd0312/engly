# Phase 02 — Backend CRUD (Admin API)

## Context Links
- Research: `research/researcher-01-backend-security-jpa.md` (§5 entity mutation, exception handling)
- Code: `engly-be/.../verb/domain/IrregularVerb.java`, `.../verb/infrastructure/VerbJpaRepository.java`,
  `.../verb/domain/VerbRepository.java`, `.../verb/api/dto/VerbResponse.java`,
  `.../common/GlobalExceptionHandler.java`, `.../verb/domain/VerbNotFoundException.java`
- Depends on: phase-01 (security rule + role mapping live).

## Overview
- **Date:** 2026-05-31
- **Description:** New `verb/admin/` slice exposing `/api/admin/verbs/**` CRUD + verified toggle.
  Entity gains mutators; writes via `@Transactional` service; validation + 409/400/404 mapping.
- **Priority:** P1
- **Implementation status:** complete
- **Review status:** complete

## Key Insights
- Real column lengths: base_form/past_simple/past_participle/*_alt/ipa_* = **100**; meaning_vi = **255**.
  (Override research-02's 64/96.) `meaning_vi` is `nullable` in the entity (`@Column` has no `nullable=false`)
  but the DB schema marks it `NOT NULL` — treat as required (`@NotBlank`) to match the DB constraint.
- `VerbJpaRepository extends JpaRepository` already provides `save`, `deleteById`, `findById`, `existsById`.
  No port changes strictly required; keep mutations in the JPA repo (KISS) — call it directly from the
  admin service (read side keeps using the `VerbRepository` port).
- Admin DTO must include `id`; public `VerbResponse` must stay id-less (do not touch it).
- Duplicate `base_form` insert/update → `DataIntegrityViolationException` (unique constraint) → 409.

## Requirements
- Functional: list (paginated/search, incl. id), create (201), update full (200), hard delete (204),
  toggle verified (200). All under `/api/admin/verbs`.
- Non-functional: bean validation on body; safe error responses (no SQL leak); transactional writes.

## Architecture
```
VerbAdminController (/api/admin/verbs)
  GET    ""                -> VerbAdminService.search(q,page,size) -> Page<VerbAdminResponse>
  POST   ""        @Valid  -> create -> 201 VerbAdminResponse
  PUT    "/{id}"   @Valid  -> update -> 200 VerbAdminResponse
  DELETE "/{id}"           -> delete -> 204
  PATCH  "/{id}/verified"  -> toggle -> 200 VerbAdminResponse   (body: {verified:boolean})
VerbAdminService (@Transactional) -> VerbJpaRepository (save/findById/deleteById)
IrregularVerb.update(...) + setVerified(...) mutators
```

## Related Code Files
**Create:**
- `engly-be/.../verb/admin/VerbAdminController.java` (~70 lines)
- `engly-be/.../verb/admin/VerbAdminService.java` (~70 lines)
- `engly-be/.../verb/api/dto/VerbRequest.java` (record + jakarta.validation)
- `engly-be/.../verb/api/dto/VerbAdminResponse.java` (record incl. id)
- `engly-be/.../verb/api/dto/VerbVerifiedRequest.java` (record `{boolean verified}`) — for PATCH body

**Modify:**
- `engly-be/.../verb/domain/IrregularVerb.java` — add `update(...)` method + `setVerified(boolean)`.
- `engly-be/.../common/GlobalExceptionHandler.java` — add `DataIntegrityViolationException` → 409.

**Delete:** none. **Migration:** none (schema already present).

## Implementation Steps
1. **Entity mutators** in `IrregularVerb.java` (KISS — one update method, avoids 9 setters):
   ```java
   public void update(String baseForm, String pastSimple, String pastParticiple,
                      String pastSimpleAlt, String pastParticipleAlt,
                      String ipaBase, String ipaPast, String ipaPp,
                      String meaningVi, boolean verified) {
     this.baseForm = baseForm; this.pastSimple = pastSimple; this.pastParticiple = pastParticiple;
     this.pastSimpleAlt = pastSimpleAlt; this.pastParticipleAlt = pastParticipleAlt;
     this.ipaBase = ipaBase; this.ipaPast = ipaPast; this.ipaPp = ipaPp;
     this.meaningVi = meaningVi; this.verified = verified;
   }
   public void setVerified(boolean verified) { this.verified = verified; }
   ```
2. **`VerbRequest`** record (lengths match DB):
   ```java
   public record VerbRequest(
     @NotBlank @Size(max = 100) String baseForm,
     @NotBlank @Size(max = 100) String pastSimple,
     @NotBlank @Size(max = 100) String pastParticiple,
     @Size(max = 100) String pastSimpleAlt,
     @Size(max = 100) String pastParticipleAlt,
     @Size(max = 100) String ipaBase,
     @Size(max = 100) String ipaPast,
     @Size(max = 100) String ipaPp,
     @NotBlank @Size(max = 255) String meaningVi,
     boolean verified) {}
   ```
3. **`VerbAdminResponse`** record: `id` (Long) + all verb fields (mirror entity getters incl. createdAt optional).
   ```java
   public record VerbAdminResponse(Long id, String baseForm, String pastSimple, String pastSimpleAlt,
     String pastParticiple, String pastParticipleAlt, String ipaBase, String ipaPast, String ipaPp,
     String meaningVi, boolean verified) {}
   ```
4. **`VerbVerifiedRequest`**: `public record VerbVerifiedRequest(boolean verified) {}`.
5. **`VerbAdminService`** (`@Service @Transactional`): inject `VerbJpaRepository`.
   - `search(q,page,size)`: reuse paging validation (page>=0, size in [1,100] — copy the small guard or
     call existing `VerbQueryService` logic by duplicating the tiny check; KISS = inline guard). Use
     `repo.findAll(pageable)` when q blank, else add a JPQL search (reuse the existing `search` query —
     it returns entities, map to admin DTO). Return a `VerbSearchPageResponse`-style envelope of admin DTOs
     OR a new `VerbAdminPageResponse` record (create if needed; keep <200 lines total).
   - `create(req)`: `new IrregularVerb()`, call `update(...)`, `repo.save`, map → DTO.
   - `update(id, req)`: `repo.findById(id).orElseThrow(VerbNotFoundException)`, `update(...)`, save, map.
   - `delete(id)`: if `!repo.existsById(id)` throw `VerbNotFoundException`; else `repo.deleteById(id)`.
   - `toggleVerified(id, verified)`: find, `setVerified`, save, map.
   - Do NOT catch `DataIntegrityViolationException` here — let it bubble to the handler (step 7).
6. **`VerbAdminController`** (`@RestController @RequestMapping("/api/admin/verbs")`): delegate to service;
   `@PostMapping` returns `ResponseEntity.status(201)`; `@DeleteMapping` returns `ResponseEntity.noContent()`;
   `@Valid @RequestBody VerbRequest` on POST/PUT; `@PathVariable Long id`.
7. **Exception mapping** in `GlobalExceptionHandler` (note: `MethodArgumentNotValidException` → 400 already
   handled; just add 409):
   ```java
   @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
   public ResponseEntity<Map<String,Object>> handleConflict(DataIntegrityViolationException ex) {
     return buildResponse(HttpStatus.CONFLICT, "base_form already exists");
   }
   ```
8. Compile: `cd engly-be && ./mvnw -q -DskipTests compile`.

## Todo List
- [ ] Add `update(...)` + `setVerified` to `IrregularVerb`
- [ ] Create `VerbRequest`, `VerbAdminResponse`, `VerbVerifiedRequest` (+ page envelope if used)
- [ ] Create `VerbAdminService` (create/update/delete/toggle/search, @Transactional)
- [ ] Create `VerbAdminController` (5 endpoints, @Valid, 201/204)
- [ ] Add `DataIntegrityViolationException` → 409 in `GlobalExceptionHandler`
- [ ] `./mvnw -q -DskipTests compile` passes

## Success Criteria
- Compiles. CRUD endpoints function (verified in phase 05): create→201, update→200, delete→204,
  toggle→200, list→200 with `id`. Duplicate base_form→409, invalid body→400, missing id→404.
- Public `/api/verbs/**` + `VerbResponse` (no id) untouched.

## Risk Assessment
- **meaning_vi nullability mismatch** (entity vs DB) → enforce `@NotBlank` to honor DB NOT NULL; avoids 500.
- **File >200 lines** → keep envelope/DTOs in separate files; service stays focused.
- **Detached-entity update** → load via `findById` inside the same `@Transactional` so dirty-checking
  persists without an explicit save (still call save for clarity).

## Security Considerations
- Reachability gated entirely by phase-01 URL rule (`hasRole('ADMIN')`); controller has no extra annotations.
- Mutations are state-changing → covered by existing CSRF (CookieCsrfTokenRepository); FE must send X-XSRF-TOKEN (phase 03/04).
- 409 message generic ("base_form already exists") — no SQL/stacktrace leak.

## Next Steps
- Defines the contract consumed by phase 04 FE api service/model. Verified by phase 05 BE tests.
