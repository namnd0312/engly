# Phase 06 — Admin API Controllers + DTOs (4 endpoints, validation, cleanup)

## Context Links
- [plan.md](plan.md) · [scout §3 controller / §4 DTO / §7 exceptions](scout/scout-01-vocab-and-admin-patterns.md) · [researcher-01 getUploadInfo](research/researcher-01-tus-upload-integration.md)
- Depends on: phase-02 (tus getUploadInfo), phase-03 (repo), phase-04 (processor enqueue). Modify: `GlobalExceptionHandler.java`.

## Overview
- **Date:** 2026-06-04 · **Priority:** P2 · **Status:** pending
- HTTP surface for import: create job from completed upload, poll status, page errors, retry FAILED. Records + Jakarta validation, mirroring `VocabWordAdminController`. Plus a `@Scheduled` temp-file cleanup.

## Key Insights
- Mirror existing admin controller conventions: `@RestController @RequestMapping("/api/admin/vocab-imports")`, records for DTOs, `@Valid`, `ResponseEntity`, 201 on create with body.
- Create-job validates: (a) deck exists (`VocabDeckJpaRepository.existsById` → else `VocabDeckNotFoundException` 404); (b) upload complete via `tusService.getUploadInfo(uploadId)` (uploadedBytes==totalBytes) → else 400/409; (c) resolve `file_path` under storage root (path-traversal guard).
- Retry only when status==FAILED → reset to PENDING (clear worker_id/heartbeat, KEEP processed_offset so it resumes, not restarts) + enqueue. If not FAILED → 409.
- `percent = file_size>0 ? processed_offset/file_size*100 : 0`.
- Exception mapping reuses GlobalExceptionHandler; add handler for `VocabImportJobNotFoundException`→404 and `UploadNotCompleteException`/IllegalState→409 if new types introduced (prefer reuse: deck-not-found 404 exists; IllegalArgumentException→400; for "upload not complete" / "not failed" use a 409-mapped exception or IllegalStateException→409).

## Requirements
- **Functional:** 4 endpoints below; all ROLE_ADMIN (Chain 1). Cleanup deletes finalized files for COMPLETED jobs + stale orphan uploads.
- **Non-functional:** Fast (status = single-row read); errors paginated; no large payloads.

## API
```
POST   /api/admin/vocab-imports            {deckId, uploadId}        → 201 {jobId, status}
GET    /api/admin/vocab-imports/{id}        → 200 ImportJobResponse (status, counts, percent, fileSize, createdAt)
GET    /api/admin/vocab-imports/{id}/errors?page=0&size=20 → 200 ImportErrorPageResponse
POST   /api/admin/vocab-imports/{id}/retry  → 200 {jobId, status:PENDING}  (409 if not FAILED)
```

## Related Code Files
- **Create** (`engly-be/.../vocab/imports/`):
  - `admin/VocabImportAdminController.java` (4 endpoints; <140 LOC)
  - `admin/VocabImportAdminService.java` (`@Service`; orchestrates validate→create→enqueue, status, errors, retry; ~160 LOC — split if >200)
  - `api/dto/CreateVocabImportRequest.java` (record: `@NotNull Long deckId`, `@NotBlank String uploadId`)
  - `api/dto/VocabImportJobResponse.java` (record: id, deckId, status, fileSize, processedCount, insertedCount, skippedCount, errorCount, percent, createdAt)
  - `api/dto/VocabImportErrorResponse.java` (record: lineNo, rawExcerpt, reason)
  - `api/dto/VocabImportErrorPageResponse.java` (record: content, page, size, totalElements, totalPages — mirror `VocabWordPageResponse`)
  - `processing/UploadCleanupScheduledTask.java` (`@Scheduled` hourly; <60 LOC)
  - (maybe) `domain/UploadNotCompleteException.java` (mapped 409) — or reuse IllegalStateException
- **Modify:** `common/GlobalExceptionHandler.java` (+ `VocabImportJobNotFoundException`→404, + 409 for upload-not-complete / retry-not-allowed)
- **Delete:** none

## Implementation Steps
1. DTO records (validation on request only).
2. `VocabImportAdminService.create(req)`:
   - `if(!deckRepo.existsById(deckId)) throw new VocabDeckNotFoundException(...)`.
   - `UploadInfo info = tusService.getUploadInfo(req.uploadId())`; if null or `uploadedBytes != totalBytes` → throw 409 (upload not complete).
   - resolve `filePath = storageRoot.resolve(uploadId).normalize()`; assert `filePath.startsWith(storageRoot)` (path-traversal guard) else 400.
   - `long jobId = repo.insertJob(deckId, filePath, totalBytes)` (status PENDING).
   - `processor.process(jobId)` (async enqueue).
   - return `{jobId, PENDING}` 201.
3. `getStatus(id)` → `repo.findById(id).orElseThrow(VocabImportJobNotFoundException)` → map to response w/ percent.
4. `getErrors(id, page, size)` → validate job exists; `repo.pageErrors` + `countErrors` → page response. Clamp size (1..100) like existing service.
5. `retry(id)`:
   - load job; `if(status != FAILED) throw 409`; `repo.resetToPending(id)` (status PENDING, worker_id NULL, heartbeat NULL, KEEP processed_offset + counts so resume not restart); `processor.process(id)`; return PENDING.
6. `VocabImportAdminController` thin delegation to service; statuses 201/200; `@Valid` on create.
7. `UploadCleanupScheduledTask`: `@Scheduled(fixedRate=3600000, initialDelay=600000)` → delete files for COMPLETED jobs older than N hours + `tusService.cleanup(24h)` for abandoned uploads. Keep FAILED/RUNNING/PENDING files (needed for resume/retry).
8. `GlobalExceptionHandler`: add mappings.
9. Compile `./mvnw -pl engly-be -am compile`.

## Todo List
- [ ] DTO records + validation
- [ ] Service: create (deck-exists + upload-complete + path guard + insert + enqueue)
- [ ] Service: status (+percent), errors (paged, size-clamped), retry (FAILED-only, keep offset)
- [ ] Controller 4 endpoints, 201/200/409/404 mapping
- [ ] Cleanup @Scheduled (only COMPLETED/abandoned; never RUNNING/FAILED/PENDING)
- [ ] GlobalExceptionHandler new mappings
- [ ] Files <200 LOC, compile clean

## Success Criteria
- Create with valid deck+completed upload → 201 + job runs.
- Bad deckId → 404; incomplete/unknown uploadId → 409; malformed body → 400.
- GET status reflects live counts + percent; GET errors paginates.
- Retry on FAILED → resumes (offset preserved); retry on non-FAILED → 409.

## Risk Assessment
- **Double-enqueue (create then sweeper)** → processor.claim idempotency absorbs it.
- **Path traversal via uploadId** → normalize + startsWith(storageRoot) guard.
- **Cleanup deletes an in-progress file** → cleanup filters to COMPLETED-only + heartbeat-stale-aware; never touch active.
- **Retry restarts from 0** → explicitly preserve processed_offset; document.

## Security Considerations
- All endpoints ROLE_ADMIN (Chain 1, stateless Bearer, CSRF off).
- No raw file path exposed in responses.
- Path-traversal guard on resolved file path.

## Next Steps
- Phase-07 FE consumes these exact DTO shapes. Phase-08 MockMvc tests cover all status codes. docs-manager updates system-architecture + changelog.
