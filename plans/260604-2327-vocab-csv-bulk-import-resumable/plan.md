---
title: "Vocab CSV Bulk Import (Resumable, GB-scale, 512MB RAM)"
description: "Admin uploads a CSV (tus resumable) into one deck; Postgres-only @Async worker streams + batch-upserts with crash-resumable byte-offset checkpoints."
status: complete
priority: P2
effort: 34h
branch: main
tags: [vocab, csv-import, tus, async, postgres, flyway, angular, resumable]
created: 2026-06-04
---

# Vocab CSV Bulk Import — Implementation Plan

Admin picks a deck, uploads a CSV via **tus** (resumable upload), backend creates a
`vocab_import_job` and a **dedicated bounded `@Async` worker** streams the file
line-by-line, batch-upserts into `vocab_word` with `ON CONFLICT DO NOTHING`, and
checkpoints a **byte offset at each batch boundary in one transaction**. Crash mid-batch
rolls back fully → resume re-reads that batch cleanly (no dup, no loss). Postgres only:
no Redis, no Spring Batch. Designed for 512MB heap, forward-compatible to N instances via
a lease/heartbeat pattern.

## Architecture (2 decoupled phases joined by an immutable on-disk file)
- **Upload**: tus chunks → `/data/uploads` (persistent volume) → finalized immutable file.
- **Process**: `@Async` worker (core=1,max=1) → streaming byte-counting reader → CSV field
  parse → de-dup batch → multi-row VALUES upsert → atomic per-batch tx checkpoint.
- **Recovery**: startup re-enqueue + `@Scheduled` stale-heartbeat sweeper, lease via
  conditional `UPDATE ... WHERE status='PENDING'`.

## Phases

| # | Phase | Status | Effort | File |
|---|-------|--------|--------|------|
| 01 | DB migration Flyway V5 (tables + dedupe + unique index) | done | 2h | [phase-01](phase-01-db-migration-flyway-v5.md) |
| 02 | tus upload integration (dep, config, controller, security/multipart/tomcat) | done | 4h | [phase-02](phase-02-tus-upload-integration.md) |
| 03 | Import job domain + JDBC repository (lease, batch upsert, error insert) | done | 4h | [phase-03](phase-03-import-job-domain-and-repository.md) |
| 04 | Async CSV processor core (byte reader, batch loop, atomic checkpoint, resume) | done | 7h | [phase-04](phase-04-async-csv-processor-core.md) |
| 05 | Crash recovery (startup runner + scheduled sweeper + lease) | done | 3h | [phase-05](phase-05-crash-recovery-startup-and-sweeper.md) |
| 06 | Admin API controllers + DTOs (4 endpoints, validation, cleanup) | done | 4h | [phase-06](phase-06-admin-api-controllers-dtos.md) |
| 07 | Frontend import UI (api service, model, import page, poll, errors, routes) | done | 6h | [phase-07](phase-07-frontend-import-ui.md) |
| 08 | Tests (resume, idempotency, bad-row, memory, lease, tus, FE specs) | done | 4h | [phase-08](phase-08-tests.md) |

## Completion Notes (2026-06-08)

All phases implemented; full test suite green. During final verification (`/cook`) the
Testcontainers suite ran for the first time against live Docker and surfaced a real
**crash-recovery bug + 4 test-infra/assertion defects** — all fixed:

- **Production fix:** recovery could never reclaim stale-RUNNING jobs (`findRecoverableJobIds`
  returned them but `claim` is PENDING-only). Added `VocabImportRepository.resetStaleRunningToPending`
  and call it before `process()` in `VocabImportRecoveryService`. Code-reviewed: N-instance safe.
- **Test infra:** singleton-container pattern in `AbstractVocabImportIntegrationTest` (was per-class
  `@Testcontainers`/`@Container` → stopped container under cached Spring context → 30s HikariCP
  timeouts; AdminApiTest dropped 332s → 0.2s).
- **Test assertions corrected** to true behavior: tus POST→201, tus OPTIONS→204, resume-from-EOF
  re-reads nothing (no double-insert), errors page field `totalElements`, retry's async re-fail race.
- Added missing import-controller `@MockBean`s to `SecurityRulesTest` + `StudySecurityRulesTest`.

**Verification:** `./mvnw test -DexcludedGroups=memory` → 133 tests, 0 failures. Memory test
(capped `-Xmx200m`, 200k rows) → peak 186MB / 200MB cap. Frontend `ng build` → clean.

## Key Dependencies (order matters)
- 01 → 03 (schema before repository).
- 02 (upload) independent; needed before 06 create-job validation can confirm upload complete.
- 03 → 04 → 05 (repository → worker → recovery reuses lease/claim).
- 06 wires 02+03+04 into HTTP. 07 consumes 06+02. 08 last.
- Parallelizable: 01||02 early; 07 (FE) can start once 06 API contract frozen.

## Reports / Research
- [brainstorm](reports/brainstorm-260604-2327-vocab-csv-bulk-import-resumable.md)
- [researcher-01 tus](research/researcher-01-tus-upload-integration.md)
- [researcher-02 streaming csv/jdbc](research/researcher-02-streaming-csv-jdbc-resume.md)
- [scout-01 vocab/admin patterns](scout/scout-01-vocab-and-admin-patterns.md)

## Validation Summary

**Validated:** 2026-06-05 · **Questions asked:** 4

### Confirmed Decisions
- **Persistent volume** `/data/uploads`: CONFIRMED (PVC/volume bền). Resume-after-crash guarantee holds. Open Question #2 resolved → no PVC-setup phase needed.
- **Embedded-newline CSV constraint**: ACCEPTED. Fields must not contain embedded newlines → O(1) byte-offset seek design kept (phase-04 unchanged). Violating line → bad row. Open Question #5 resolved.
- **Field-length overflow** (term>120, meaning_vi>255, etc.): REJECT as bad row (skip + report), NO silent truncation. → phase-04 validation must length-check each field against DB column limits before batch insert.
- **Max upload size**: 1–2GB cap. → SET tus `maxUploadSize=2GB`, Tomcat `max-swallow-size`/`maxFileSize` to match (phase-02). Disk must hold ≥2GB. Lower than research's 5GB.

### Action Items
- [ ] phase-02: set tus `maxUploadSize` + Tomcat limits to **2GB** (not 5GB).
- [ ] phase-04: add explicit per-field length validation (vs DB column max) → over-length = bad row with reason; do not truncate.
- [ ] plan: Open Questions #2 and #5 now CLOSED.

## Open Questions / Assumptions (confirm before coding)
1. **tus-js-client version (FE)** — assume latest `tus-js-client@^4.x`; confirm Angular 19.2 / TS compat. BE lib locked `me.desair.tus:tus-java-server:1.0.0-3.0`.
2. **`/data/uploads` persistence** — ASSUMED a persistent volume that survives restart/redeploy. Resume-after-crash depends on the file surviving. MUST confirm k8s volume mount (README mentions OrbStack k8s; no PVC seen). If ephemeral → resume guarantee void.
3. **Disk capacity for GB files** — RAM is 512MB but disk must hold full file + temp chunks. MUST confirm server disk size; set tus `maxUploadSize` accordingly (research used 5GB).
4. **Batch size** — START 500 (safety on 512MB). Tune up to 1000 after memory profiling (phase-08 memory test). Note: multi-row VALUES with ~500 rows × ~6 params = ~3000 bind params, well under pgjdbc limit (32767).
5. **Embedded-newline CSV constraint** — LOCKED: fields MUST NOT contain embedded newlines (enables O(1) byte-offset seek). Acceptable for vocab. Documented + validated; a line failing CSV field parse → bad row, not crash. CONFIRM stakeholders accept this constraint.
6. **Testcontainers** — CONFIRMED available (`testcontainers 1.21.0`, `postgres:16-alpine`, existing `VocabAdminIntegrationTest`). Memory test uses capped `-Xmx` via surefire `argLine`.
7. **`verified` not settable via CSV** — LOCKED default false. CSV `verified` column (if present) ignored.
8. **Single instance now** — lease pattern designed but `SELECT ... FOR UPDATE SKIP LOCKED` deferred (YAGNI); conditional UPDATE lease is sufficient + forward-compatible.
