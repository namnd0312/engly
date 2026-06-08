# Phase 01 — DB Migration Flyway V5 (job + error tables, dedupe, unique index)

## Context Links
- [plan.md](plan.md) · [brainstorm](reports/brainstorm-260604-2327-vocab-csv-bulk-import-resumable.md) · [scout §8 Flyway](scout/scout-01-vocab-and-admin-patterns.md)
- Existing: `engly-be/src/main/resources/db/migration/V3__create_vocab_and_study_tables.sql`, `V4__seed_starter_decks_travel_business.sql`

## Overview
- **Date:** 2026-06-04 · **Priority:** P2 (foundational, blocks phase-03) · **Status:** pending
- Add `vocab_import_job` + `vocab_import_error` tables; dedupe existing `vocab_word` (defensive) then create `UNIQUE (deck_id, term)` enabling `ON CONFLICT` idempotency.

## Key Insights
- V4 seed verified: NO duplicate (deck_id, term) pairs exist → dedupe is defensive, will delete 0 rows but MUST stay (future-proof / other envs).
- Conventions (from V3): `bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `timestamptz NOT NULL DEFAULT now()`, FK `ON DELETE CASCADE`, explicit `CREATE INDEX idx_...`.
- `hibernate.ddl-auto: validate` → entity columns MUST match migration exactly (phase-03 entities).
- Single migration file: dedupe MUST run BEFORE `CREATE UNIQUE INDEX` (else index creation fails on dupes).

## Requirements
- **Functional:** Tables persist job progress + bad-row reports; unique index enforces per-deck term uniqueness.
- **Non-functional:** Idempotent-safe forward migration; Postgres syntax matching V3/V4; status CHECK constraint.

## Architecture
```
vocab_import_job (1) ──< vocab_import_error   (job_id FK ON DELETE CASCADE)
vocab_import_job.deck_id ──> vocab_deck(id)   (FK; no cascade-delete of jobs needed, RESTRICT default)
UNIQUE (deck_id, term) ON vocab_word          (drives ON CONFLICT DO NOTHING)
```

## Related Code Files
- **Create:** `engly-be/src/main/resources/db/migration/V5__create_vocab_import_tables.sql`
- **Modify:** none (entities added in phase-03)
- **Delete:** none

## Implementation Steps
1. Create `V5__create_vocab_import_tables.sql`.
2. `CREATE TABLE vocab_import_job`:
   - `id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
   - `deck_id bigint NOT NULL REFERENCES vocab_deck(id)`
   - `file_path varchar(500) NOT NULL`, `file_size bigint NOT NULL`
   - `status varchar(20) NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED'))`
   - `processed_offset bigint NOT NULL DEFAULT 0`
   - `processed_count bigint NOT NULL DEFAULT 0`, `inserted_count bigint NOT NULL DEFAULT 0`, `skipped_count bigint NOT NULL DEFAULT 0`, `error_count bigint NOT NULL DEFAULT 0`
   - `worker_id varchar(100) NULL`, `heartbeat_at timestamptz NULL`
   - `created_at timestamptz NOT NULL DEFAULT now()`, `updated_at timestamptz NOT NULL DEFAULT now()`
3. `CREATE INDEX idx_vocab_import_job_status ON vocab_import_job(status);` (sweeper/recovery scan).
4. `CREATE TABLE vocab_import_error`:
   - `id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
   - `job_id bigint NOT NULL REFERENCES vocab_import_job(id) ON DELETE CASCADE`
   - `line_no bigint NOT NULL`, `raw_excerpt varchar(500) NULL`, `reason varchar(255) NOT NULL`
   - `created_at timestamptz NOT NULL DEFAULT now()`
5. `CREATE INDEX idx_vocab_import_error_job ON vocab_import_error(job_id);`
6. **Dedupe BEFORE unique index** (keep MIN(id) per group):
   ```sql
   DELETE FROM vocab_word w
   USING vocab_word d
   WHERE w.deck_id = d.deck_id AND w.term = d.term AND w.id > d.id;
   ```
7. `CREATE UNIQUE INDEX uq_vocab_word_deck_term ON vocab_word(deck_id, term);`
8. Run `./mvnw -pl engly-be flyway:info` or boot app → confirm V5 applied, app boots (ddl validate passes once phase-03 entities exist; until then verify migration only).

## Todo List
- [ ] Write V5 SQL (2 tables + 2 indexes + dedupe + unique index)
- [ ] CHECK constraint on status
- [ ] Dedupe statement precedes unique index
- [ ] Boot/flyway validate clean against fresh + seeded DB
- [ ] Confirm Testcontainers run applies V5

## Success Criteria
- Flyway applies V5 on fresh DB and on a DB already seeded by V4 (no failure).
- Unique index exists; inserting duplicate (deck_id, term) raises constraint (verified by phase-08 idempotency test).

## Risk Assessment
- **Unique index fails on pre-existing dupes** → mitigated by dedupe step (step 6) ordered first.
- **`updated_at` not auto-bumped** → app sets it explicitly in checkpoint UPDATE (no DB trigger; KISS).

## Security Considerations
- `file_path` stored server-side only (never user-controlled raw path; derived from tus uploadId + storage root — validate in phase-06 to prevent path traversal).

## Next Steps
- Phase-03 entities/records mirror these exact columns. Phase-08 idempotency test asserts unique index behavior.
