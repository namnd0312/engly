# Phase 08 — Tests (resume, idempotency, bad-row, memory, lease, tus, FE specs)

## Context Links
- [plan.md](plan.md) · [scout test setup](scout/scout-01-vocab-and-admin-patterns.md) · all prior phases
- Existing pattern: `engly-be/src/test/java/com/engly/vocab/VocabAdminIntegrationTest.java` (`@SpringBootTest @AutoConfigureMockMvc @Testcontainers`, `postgres:16-alpine`, JWT `ROLE_ADMIN`).

## Overview
- **Date:** 2026-06-04 · **Priority:** P2 (gates merge) · **Status:** pending
- Prove the load-bearing guarantees: resume (no dup/no loss), idempotency, bad-row tolerance, constant memory, lease/recovery, tus upload. Plus FE specs. JUnit5 + Testcontainers Postgres (confirmed available) + MockMvc.

## Key Insights
- Testcontainers 1.21.0 + `postgres:16-alpine` confirmed; Flyway V1–V5 applied per container → real unique index + tables.
- Crash simulation WITHOUT killing JVM: inject a fault so `commitBatch` throws AFTER upsert but the TransactionTemplate rolls back → assert offset unchanged + zero rows. Use a spy/wrapper repo or a test-only `RuntimeException` thrown from a hook between insert and commit. Cleanest: call `upserter.upsertBatch` inside a `transactionTemplate.executeWithoutResult` that throws at the end → verify rollback (rows==0, offset==0). This tests the tx atomicity contract directly.
- Memory test: generate a large temp CSV (e.g. 200k–500k rows) to a tmp file, run processor, assert no OOM under a capped heap. Cap heap via surefire `argLine=-Xmx256m` for a tagged test OR assert `Runtime.maxMemory`-relative used heap stays bounded across batches (sample `usedHeap` mid-run, assert it doesn't grow monotonically with rows). Pragmatic: tag `@Tag("memory")`, run in a surefire execution with `-Xmx200m`; pass == no OOM + correct counts.
- Lease/recovery test: insert a job row directly with status='RUNNING', heartbeat_at = now()-5min, processed_offset mid-file → call recovery `enqueueRecoverable()` (or processor.process) → assert reclaimed (worker_id changes, status→RUNNING→COMPLETED) and final counts correct from offset.
- tus test: drive `tusService.process` via MockMvc POST(create)+PATCH(chunk)+HEAD(offset) with `application/offset+octet-stream`; assert file lands on disk + offset increments + resume after partial PATCH. Or a focused unit on the controller delegating to a real `TusFileUploadService` with a temp storage dir.

## Requirements
- Cover every locked guarantee; no fake data/mocks that bypass real DB; all green before merge.

## Related Code Files (Create)
Backend (`engly-be/src/test/java/com/engly/vocab/imports/`):
- `VocabImportResumeTest.java` — atomic-checkpoint rollback: batch upsert+offset in one tx that throws → assert rows==0 AND processed_offset unchanged; then real run → exact counts, no dup.
- `VocabImportIdempotencyTest.java` — run same CSV import twice (full processor) → inserted_count stable, `COUNT(vocab_word WHERE deck)` unchanged 2nd run, skipped_count == row count 2nd run, no duplicate (deck_id,term).
- `VocabImportBadRowTest.java` — CSV w/ malformed lines (missing term, too few cols, overflow length, blank meaning) → those recorded in vocab_import_error w/ line_no+reason, valid rows inserted, job status COMPLETED, error_count matches.
- `VocabImportMemoryTest.java` (`@Tag("memory")`) — generate large CSV → process under capped heap → no OOM, counts == generated valid rows; assert mid-run heap bounded.
- `VocabImportLeaseRecoveryTest.java` — seed stale RUNNING job → recovery reclaims via lease → completes from offset; also assert claim() returns true once / false on already-RUNNING-fresh.
- `VocabImportUploadTusTest.java` — MockMvc tus POST/PATCH/HEAD; partial chunk then resume; file finalized on disk; non-admin → 403.
- `VocabImportAdminApiTest.java` — MockMvc: create (201; bad deck 404; incomplete upload 409; invalid body 400), GET status (+percent), GET errors paged, retry FAILED→PENDING (non-FAILED→409). Mirrors `VocabAdminIntegrationTest` auth helper.
- `ByteCountingLineReaderTest.java` — unit: LF vs CRLF, multibyte UTF-8 (CJK/emoji) byte counts exact, seek(offset) resumes at correct record boundary, EOF returns null.

Frontend (`engly-fe/src/app/features/admin/`):
- `data/vocab-import-api.service.spec.ts` — HttpClientTestingModule: create/getJob/getErrors/retry URLs+payloads.
- `vocab-import-page.component.spec.ts` — decks load, poll updates job signal + stops on terminal, retry triggers re-poll, errors table renders; tus mocked.

## Implementation Steps
1. Add a small test base or reuse `VocabAdminIntegrationTest`'s container setup (`@Container static PostgreSQLContainer postgres("postgres:16-alpine")` + `@DynamicPropertySource`). Consider a shared abstract base to DRY container config across new tests.
2. Write a CSV test-fixture helper (write rows to a temp `Path`) reused across resume/idempotency/bad-row/memory.
3. Resume test: directly exercise `VocabImportRepository.commitBatch` inside a deliberately-throwing tx → assert rollback; then a clean processor run on a file, simulate "stop after batch 1" by processing with a tiny batch size and a fault after first commit, restart processor → assert total inserted == valid rows, no dup, offset monotonic.
4. Idempotency: full `processor.process` twice on same job/file (or two jobs same deck+file) → assert counts + no dup via `SELECT count(*)` and a `GROUP BY deck_id,term HAVING count(*)>1` == empty.
5. Bad-row: fixture with known bad lines → assert vocab_import_error contents + COMPLETED status.
6. Memory: surefire execution/profile with `argLine=-Xmx200m` for `@Tag("memory")`; generate ≥200k rows; assert completion + counts; sample heap.
7. Lease/recovery: insert stale RUNNING job via JdbcTemplate → `recoveryService.enqueueRecoverable()` (or synchronous claim+process) → poll job to COMPLETED → assert counts; unit `claim` twice → true then false.
8. tus + admin API MockMvc tests w/ `admin()` JWT helper.
9. FE specs w/ HttpClientTestingModule; mock tus (`jest.mock`/stub or wrap tus call behind a thin injectable for testability — recommend a `TusUploader` wrapper service so the component is testable without real XHR).
10. Run `./mvnw test` (excluding/including `memory` tag as configured) + `npx ng test --watch=false --browsers=ChromeHeadless`. ALL green.

## Todo List
- [ ] Shared Testcontainers base (DRY) or reuse existing pattern
- [ ] CSV fixture helper
- [ ] Resume / atomic-rollback test (offset unchanged + 0 rows on rollback; no dup/no loss on real resume)
- [ ] Idempotency test (twice → stable counts, no duplicate rows)
- [ ] Bad-row test (skipped+recorded, job COMPLETED, error_count matches)
- [ ] Memory test (@Tag, capped -Xmx, no OOM, correct counts)
- [ ] Lease/recovery test (stale RUNNING reclaimed + completes; claim once-true)
- [ ] tus upload MockMvc test (chunk+resume+finalize+403 non-admin)
- [ ] Admin API MockMvc test (201/404/409/400, status+percent, errors page, retry)
- [ ] ByteCountingLineReader unit (LF/CRLF/multibyte/seek/EOF)
- [ ] FE service + component specs (tus wrapped for testability)
- [ ] `./mvnw test` + `ng test` all green

## Success Criteria
- All listed tests pass against real Postgres container.
- Resume + idempotency + bad-row + lease guarantees demonstrably hold.
- Memory test completes under capped heap with no OOM.
- No fake data / no skipped failing tests.

## Risk Assessment
- **Memory test flaky / slow in CI** → tag-isolate; moderate row count (200k) balancing signal vs CI time; document run cmd.
- **Simulating crash without killing JVM** → test the tx atomicity contract (throw inside tx → rollback) — this IS the resume guarantee at the unit level; plus a restart-from-offset integration run.
- **tus MockMvc raw-body quirks** → if MockMvc struggles with `offset+octet-stream`, fall back to direct `TusFileUploadService` unit w/ a temp dir + mock servlet req/resp.
- **FE tus untestable via HttpClientTestingModule** → wrap tus in `TusUploader` injectable; spy in spec.

## Security Considerations
- Tests assert non-admin → 403 on upload + endpoints.
- Path-traversal guard covered by an admin-API test feeding a malicious uploadId → 400.

## Next Steps
- On green: code-reviewer pass, then docs-manager updates `docs/system-architecture.md`, `docs/codebase-summary.md`, `docs/project-changelog.md`, `docs/development-roadmap.md`.
