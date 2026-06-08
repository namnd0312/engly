# Brainstorm — Resumable 10GB CSV Vocab Import (512MB BE)

**Date:** 2026-06-04 · **Status:** Agreed · **Module:** `engly-be` vocab admin

## Problem statement

Admin needs to bulk-import vocab words from a CSV that may reach **10GB**, on a
backend with as little as **512MB RAM**, and if the import process **crashes
mid-way it must resume from the last unprocessed row** — not restart.

### Confirmed requirements (user decisions)

| Decision | Choice |
|---|---|
| Realistic max size | **Truly up to 10GB** (~40–60M rows) — full streaming machinery required |
| Resume scope | **Processing only** — upload is single-shot; if upload drops, re-upload from scratch |
| Bad/duplicate rows | **Skip bad rows + report them**; valid rows still import; no upsert → no unique constraint needed |
| Raw byte storage | **Postgres Large Object** (`pg_largeobject`) |
| Worker | **Hand-rolled streaming worker** (no Spring Batch) |

Stack: Spring Boot 3.4.5 · Java 21 · Maven · PostgreSQL · Spring Data JPA.
Admin routes already gated to `ROLE_ADMIN` by `SecurityConfig`.

## Brutal-honesty notes

- **10GB is almost certainly a YAGNI requirement** for a language app (a vocab row
  is ~150–250 bytes → 10GB ≈ 40–60M words = a multi-language dictionary). The
  *pattern* (stream-don't-buffer + atomic checkpoint) is correct at any size, so we
  build it properly; just acknowledge the headline number is generous.
- **`bytea` is unusable here** — 1GB hard cap per value + must materialize fully →
  OOM on 512MB. Large Object streams in chunks (up to 4TB), seekable. Correct call.
- Storing 10GB in Postgres **bloats DB size, WAL, and every backup** until deleted →
  mandatory cleanup of the LO on success.

## Architecture (agreed)

Two fully decoupled phases, everything in Postgres:

```
[Admin] --raw text/csv stream--> POST /api/admin/decks/{deckId}/imports
                                      | (write LO in ~MB chunks, short txns)
                                      v
                            import_job (status=PENDING, byte_offset=0, lo_oid)
                                      |
   background worker  (also re-scans for unfinished jobs ON BOOT = crash resume)
                                      |
        LO lo_lseek(byte_offset) -> parse batch of N rows -> validate each
                                      |
   ┌──────────────── ONE transaction per batch ─────────────────┐
   │ INSERT valid rows  +  INSERT bad rows -> import_error       │
   │ UPDATE import_job SET byte_offset, rows_processed/failed     │
   └─────────────────────────────────────────────────────────────┘ commit, repeat
                                      |
                         status=COMPLETED -> unlink Large Object
```

### Why it meets each constraint

- **Resume correctness:** `byte_offset` is committed *in the same transaction* as the
  row inserts → offset can never diverge from data. On restart the worker finds the
  unfinished job, `lo_lseek` to the last committed offset, continues. **Exactly-once
  at batch granularity, zero double-inserts** (no upsert needed).
- **512MB RAM:** nothing fully materialized. Upload streams in MB chunks; processing
  holds one batch (500–1000 rows) + buffers. Constant memory at any file size.
- **Crash recovery trigger:** an `ApplicationRunner`/boot hook scans `import_job` for
  `PENDING`/`PROCESSING` rows and resumes them; submit also kicks the worker.

## Key implementation gotchas (must-handle)

1. **Exact resume = BYTE offset at a RECORD boundary, not row count.** Skipping by row
   count would re-parse tens of millions of rows on every restart. A CSV record can
   contain quoted newlines, so use a **quote-aware byte-level record splitter** that
   feeds complete logical records to the field parser — this avoids decoder/buffer
   read-ahead making the LO position ambiguous. Checkpoint = byte offset at the start
   of the next uncommitted batch (a true record boundary).
2. **Cap the error table** (~first 10k `import_error` rows verbatim, then count only) —
   otherwise a mostly-bad 40M-row file writes 20M error rows.
3. **Single-instance claim:** `SELECT … FOR UPDATE SKIP LOCKED` on `import_job` so a
   restart / 2nd instance never double-processes one job.
4. **Raw body, not multipart:** read `request.getInputStream()` (`text/csv` /
   `application/octet-stream`); Spring multipart buffers to a temp file first. Raise/
   disable Tomcat limits (`server.tomcat.max-swallow-size=-1`, large
   `max-http-request-header`/timeout). LO write done in short per-chunk transactions
   (append by re-opening OID + seek-to-end) to avoid one monster transaction.
5. **Cleanup:** unlink the LO on `COMPLETED`; `vacuumlo` for orphaned OIDs from failed
   uploads.
6. **Throughput reality:** batched JDBC INSERT ~ tens of K rows/s → 40M rows ≈ 30–60 min
   plus 10GB read. `rewriteBatchedInserts=true` on the JDBC URL. Postgres `COPY` is the
   5–10× speed lever if ever needed, but complicates per-row skip — out of scope now.

## Schema additions (Flyway `V5__create_vocab_import_tables.sql`)

- `import_job(id, deck_id FK, filename, lo_oid OID, status, total_bytes, byte_offset
  BIGINT, rows_processed BIGINT, rows_failed BIGINT, error_count BIGINT,
  worker_id, created_at, updated_at)`
- `import_error(id, job_id FK, row_number BIGINT, reason, raw_excerpt)`
- Raw bytes live in `pg_largeobject` referenced by `import_job.lo_oid`.

## API surface

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/admin/decks/{deckId}/imports` | stream CSV → create LO + job, return `202 {jobId}` |
| GET | `/api/admin/imports/{jobId}` | status, rows_processed/failed, percent |
| GET | `/api/admin/imports/{jobId}/errors?page=` | the skip report (capped) |
| DELETE | `/api/admin/imports/{jobId}` | cancel + unlink LO + delete job/errors |

CSV columns: `term, meaning_vi, ipa, example_en, example_vi, part_of_speech`
(term + meaning_vi required; rest optional). Header row expected; UTF-8.

## Component breakdown (hand-rolled, <200 LOC each)

- `vocab-import-controller` — endpoints, raw-stream intake, security.
- `vocab-import-upload-service` — stream request body → Large Object (chunked txns),
  create `import_job`.
- `csv-record-byte-splitter` — quote-aware byte-level record reader (exact offsets).
- `vocab-import-worker` — boot re-scan + claim (SKIP LOCKED) + chunked
  parse/validate/insert/checkpoint loop.
- `vocab-import-row-validator` — per-row validation → valid entity or error reason.
- `vocab-import-job` / `import-error` JPA entities + repositories.

## Success criteria

- Import a >1GB CSV on a 512MB-capped JVM without OOM (constant heap).
- Kill the JVM mid-import; on restart it resumes from the last committed offset with
  **no duplicate** and **no skipped** valid rows.
- Bad rows appear in the error report; valid rows from the same file are imported.
- LO removed after `COMPLETED`; DB size returns to baseline.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Long single transaction during 10GB upload | Append LO in short per-chunk txns |
| Decoder read-ahead breaks exact offset | Byte-level quote-aware record splitter |
| Error table explosion | Cap stored errors, keep counting |
| Double-processing on restart / multi-instance | `FOR UPDATE SKIP LOCKED` job claim |
| DB/backup bloat | Unlink LO on success + `vacuumlo` for orphans |
| 10GB over-engineering (YAGNI) | Pattern is size-agnostic; accept generous cap |

## Unresolved questions

1. **Upload transport** for 10GB single-shot — plain `curl --data-binary` / fetch
   stream, or a thin chunked-append client? (Affects only the upload edge, not resume.)
2. **CSV dialect** — fixed header order vs header-name mapping? Delimiter/quote config?
3. **Concurrency** — one import at a time globally, or per-deck parallel? (Worker pool size.)
4. **Auth on long upload** — JWT expiry vs a multi-hour upload; need a streaming-friendly check.
5. **Progress granularity** — is byte-% enough, or do you want ETA / rows/sec in `GET`?
