# Phase 03 — Import Job Domain + JDBC Repository (lease, batch upsert, error insert)

## Context Links
- [plan.md](plan.md) · [researcher-02 jdbc/upsert](research/researcher-02-streaming-csv-jdbc-resume.md) · [scout §5 repo / §8 schema](scout/scout-01-vocab-and-admin-patterns.md)
- Depends on: phase-01 (tables). Consumed by: phase-04 (worker), phase-05 (recovery), phase-06 (API).

## Overview
- **Date:** 2026-06-04 · **Priority:** P2 · **Status:** pending
- Domain records for job + error; a **plain-JDBC** repository (NamedParameter/JdbcTemplate) doing lease-claim, atomic per-batch checkpoint, multi-row upsert, capped error insert. **No JPA** on the import write path (OOM avoidance).

## Key Insights
- LOCKED: FORBID `JpaRepository.saveAll` for vocab_word import — persistence-context bloat = OOM at 512MB. Vocab module elsewhere uses JPA; import path is the deliberate exception → use `JdbcTemplate`.
- Multi-row single VALUES statement per batch: `updateCount` = rows actually inserted (ON CONFLICT skips don't count) → `inserted += updateCount`, `skipped += (batchSize - updateCount)`. Accurate AND fast.
- Do NOT rely on `reWriteBatchedInserts` (does not merge ON CONFLICT). Build one statement w/ N value tuples directly.
- De-dup batch in-memory by `term` BEFORE insert (last-wins) → avoids same-statement ON CONFLICT self-conflict.
- Lease claim = conditional UPDATE; rows-affected==1 means this worker owns the job.
- `DataSource` auto-configured already; just inject `JdbcTemplate` (Boot autoconfigures it) + a `TransactionTemplate`.

## Requirements
- **Functional:** create job; claim (lease); atomic checkpoint (upsert + errors + counters + offset in 1 tx); fetch job; page errors; find recoverable jobs; reset FAILED→PENDING.
- **Non-functional:** O(1) heap per batch; bounded SQL param count (~500 rows × 6 cols ≈ 3000 < 32767).

## Architecture
```
VocabImportJob (record)         maps vocab_import_job
VocabImportError (record)       maps vocab_import_error
ImportJobStatus (enum)          PENDING/RUNNING/COMPLETED/FAILED
VocabImportRepository           JdbcTemplate + TransactionTemplate
  ├ insertJob(deckId,path,size) → returns id (PENDING)
  ├ claim(jobId,workerId)       → boolean (conditional UPDATE)
  ├ commitBatch(checkpoint)     → ONE tx: upsert words + insert errors(capped) + UPDATE counters+offset+heartbeat
  ├ heartbeat(jobId,workerId)   → UPDATE heartbeat_at (optional mid-batch keepalive)
  ├ markCompleted/markFailed
  ├ findById / pageErrors(jobId,page,size) / countErrors
  └ findRecoverableJobIds()     → PENDING OR (RUNNING AND heartbeat_at < now()-interval)
```

## Related Code Files
- **Create** (`engly-be/.../vocab/imports/`):
  - `domain/VocabImportJob.java` (record, <40 LOC)
  - `domain/VocabImportError.java` (record, <25 LOC)
  - `domain/ImportJobStatus.java` (enum, <15 LOC)
  - `domain/VocabImportJobNotFoundException.java` (<15 LOC; mapped 404 in phase-06)
  - `infrastructure/VocabImportRepository.java` (JdbcTemplate-based, ~180 LOC — split if >200)
  - `infrastructure/VocabWordBatchUpserter.java` (builds multi-row VALUES SQL + binds; <120 LOC) — extracted to keep repo <200 and DRY
- **Modify:** none (JdbcTemplate/TransactionTemplate auto-available; add `TransactionTemplate` @Bean only if not present — Spring provides PlatformTransactionManager, wrap in `new TransactionTemplate(txManager)`).
- **Delete:** none

## Implementation Steps
1. Records + enum mirroring V5 columns (snake→camel). `VocabImportJob` includes all counters + offset + worker_id + heartbeat_at + status + file_path/size.
2. `VocabWordBatchUpserter.buildSql(int rowCount)` → `INSERT INTO vocab_word (deck_id,term,meaning_vi,ipa,example_en,example_vi,part_of_speech,verified) VALUES (?,?,?,?,?,?,?,false),(...) ON CONFLICT (deck_id,term) DO NOTHING`. `verified` literal false (not param). Return SQL; bind via `PreparedStatementSetter`.
3. `upsertBatch(deckId, List<ParsedRow> rows)`:
   - de-dup by `term` in-memory (LinkedHashMap keyed term).
   - if empty → return 0.
   - `jdbcTemplate.update(buildSql(rows.size()), ps -> bind all)` → returns insertedCount.
4. `commitBatch(BatchCheckpoint cp)` via `TransactionTemplate.executeWithoutResult`:
   - `int inserted = upserter.upsertBatch(deckId, cp.rows())`
   - insert errors (only up to remaining cap; always pass error delta for counters): `jdbcTemplate.batchUpdate("INSERT INTO vocab_import_error(job_id,line_no,raw_excerpt,reason) VALUES (?,?,?,?)", ...)` capped at 10k stored total (track stored count; skip inserts beyond cap but still increment error_count).
   - `UPDATE vocab_import_job SET processed_offset=?, processed_count=processed_count+?, inserted_count=inserted_count+?, skipped_count=skipped_count+?, error_count=error_count+?, heartbeat_at=now(), updated_at=now() WHERE id=? AND worker_id=?`.
   - all in one tx → crash mid-batch rolls back everything incl. offset.
5. `claim(jobId, workerId)`: `UPDATE vocab_import_job SET worker_id=?, status='RUNNING', heartbeat_at=now(), updated_at=now() WHERE id=? AND status='PENDING'` → return `rowsAffected == 1`.
6. `markCompleted` (status='COMPLETED'), `markFailed` (status='FAILED', clear worker_id).
7. `findRecoverableJobIds()`: `SELECT id FROM vocab_import_job WHERE status='PENDING' OR (status='RUNNING' AND heartbeat_at < now() - interval '2 minutes')`.
8. `pageErrors` + `countErrors` for phase-06 errors endpoint.
9. RowMappers for job + error. Compile.

## Todo List
- [ ] Records + enum + not-found exception
- [ ] `VocabWordBatchUpserter` multi-row VALUES builder + binder, in-batch term de-dup
- [ ] `commitBatch` atomic tx (upsert + capped errors + counters + offset + heartbeat)
- [ ] `claim` conditional-UPDATE lease returns boolean
- [ ] `findRecoverableJobIds`, `markCompleted/Failed`, `findById`, `pageErrors/countErrors`
- [ ] TransactionTemplate wired
- [ ] Files <200 LOC each; compile clean

## Success Criteria
- `commitBatch` either fully commits (offset advances, counters match) or fully rolls back (offset unchanged) — proven by phase-08 resume test.
- `claim` returns true exactly once for a PENDING job under concurrent calls (rows-affected semantics).
- inserted+skipped+error per batch == rows read in that batch.

## Risk Assessment
- **In-batch duplicate terms** → de-dup before insert; counted as skipped.
- **Error cap accounting drift** → always increment `error_count` by full delta; only the `vocab_import_error` row inserts are capped at 10k.
- **Param-count explosion at batch 1000** → 1000×7≈7000 < 32767, safe; documented.
- **TransactionTemplate missing bean** → construct from injected `PlatformTransactionManager`.

## Security Considerations
- `raw_excerpt` truncated to 500 chars (matches column) to bound storage; never executed.
- Parameterized SQL only (no string concat of user values; only tuple-count is concatenated).

## Next Steps
- Phase-04 worker calls `claim` then loops `commitBatch`; phase-05 recovery reuses `findRecoverableJobIds`+`claim`; phase-06 uses `insertJob`/`findById`/`pageErrors`/reset.
