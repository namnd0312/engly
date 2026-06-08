# Phase 05 — Crash Recovery (startup re-enqueue + scheduled sweeper + lease)

## Context Links
- [plan.md](plan.md) · [brainstorm §4 crash&resume](reports/brainstorm-260604-2327-vocab-csv-bulk-import-resumable.md) · phase-03 (claim/findRecoverableJobIds), phase-04 (processor)
- Depends on: phase-03, phase-04.

## Overview
- **Date:** 2026-06-04 · **Priority:** P2 · **Status:** pending
- On boot and periodically, find jobs that are PENDING or RUNNING-with-stale-heartbeat and (re)enqueue them. Lease via conditional UPDATE ensures exactly-one claim. Designed for 1 instance; forward-compatible to N.

## Key Insights
- Two triggers: (a) `CommandLineRunner`/`ApplicationRunner` at startup (recovers jobs orphaned by a crash before this boot), (b) `@Scheduled` sweeper every ~90s (recovers jobs whose worker died mid-run while app kept running, e.g. thread crash, or another dead instance).
- Re-enqueue must NOT double-process: the processor itself calls `repo.claim(...)` first; if claim fails (already RUNNING by a live worker), processor returns immediately. So enqueue is safe to call optimistically.
- Stale = `status='RUNNING' AND heartbeat_at < now() - 2min`. Heartbeat advanced by `commitBatch` each batch (phase-03) → a live long-running job keeps heartbeat fresh, won't be reclaimed.
- `@EnableScheduling` not yet in codebase (scout §9) → add it (alongside `@EnableAsync` from phase-04; can co-locate or separate config).
- Single executor (core=1,max=1) → at most one job processes at a time; queued jobs wait. Acceptable for now (YAGNI on parallelism).

## Requirements
- **Functional:** Orphaned/stale jobs get reprocessed automatically; no manual intervention; no duplicate inserts (idempotency + lease guarantee).
- **Non-functional:** Sweeper cheap (indexed status query); no thundering herd (single executor serializes).

## Architecture
```
@EnableScheduling
VocabImportRecoveryService
  ├ @EventListener(ApplicationReadyEvent) onStartup() → enqueueRecoverable()
  └ @Scheduled(fixedDelay=90s) sweep()       → enqueueRecoverable()
  enqueueRecoverable():
     for id in repo.findRecoverableJobIds():   // PENDING or RUNNING+stale
        processor.process(id)                  // @Async; processor re-claims via lease
```

## Related Code Files
- **Create:** `engly-be/.../vocab/imports/processing/VocabImportRecoveryService.java` (<90 LOC)
- **Modify:** add `@EnableScheduling` (in `AsyncConfig` from phase-04 or a small `SchedulingConfig`); `application.yml` add `import.stale-heartbeat-seconds: 120`, `import.sweeper-delay-ms: 90000`.
- **Delete:** none

## Implementation Steps
1. Enable scheduling: annotate phase-04 `AsyncConfig` with `@EnableScheduling` (or dedicated `SchedulingConfig`). KISS: reuse AsyncConfig.
2. `VocabImportRecoveryService`:
   - inject `VocabImportRepository` + `VocabImportProcessor` + workerId provider.
   - `@EventListener(ApplicationReadyEvent) public void onStartup()` → `enqueueRecoverable()` (use ApplicationReadyEvent over CommandLineRunner so DataSource/Flyway fully ready).
   - `@Scheduled(fixedDelayString="${import.sweeper-delay-ms:90000}", initialDelay=90000) public void sweep()` → `enqueueRecoverable()`.
   - `enqueueRecoverable()`: `repo.findRecoverableJobIds(staleSeconds)` → for each `processor.process(id)`; log count. Wrap per-id in try/catch so one failure doesn't abort the loop.
3. `findRecoverableJobIds` (phase-03) takes stale interval param: `status='PENDING' OR (status='RUNNING' AND heartbeat_at < now() - (? * interval '1 second'))`.
4. Confirm processor's `claim` makes re-enqueue idempotent: if a RUNNING job is still live (fresh heartbeat) it won't be in the recoverable set; if stale, claim resets it to RUNNING under new worker_id and resumes from `processed_offset`.
5. Compile; phase-08 lease/recovery test simulates stale job + asserts reclaim & completion.

## Todo List
- [ ] `@EnableScheduling` added
- [ ] Recovery service: ApplicationReadyEvent startup hook + @Scheduled sweeper
- [ ] `enqueueRecoverable` iterates recoverable ids, per-id try/catch, calls async processor
- [ ] Stale interval + sweeper delay configurable
- [ ] Confirm claim-based idempotency (no double processing of live job)
- [ ] Compile clean

## Success Criteria
- A job left RUNNING with `heartbeat_at` older than 2min is reclaimed by sweeper and completes from its last offset (phase-08).
- A PENDING job enqueued before a crash is picked up on next boot.
- A live, actively-checkpointing job is NOT reclaimed (heartbeat fresh).

## Risk Assessment
- **Re-enqueue storm if many stale jobs** → single executor serializes; queue small + CallerRunsPolicy bounds it.
- **Clock skew across instances (future N-instance)** → uses DB `now()` for both heartbeat write and stale comparison → consistent; safe.
- **Startup before file restored on disk** → if `/data/uploads` not persistent (OPEN QUESTION #2), resume fails → processor should mark FAILED if file missing (handle in phase-04 open()).

## Security Considerations
- Recovery is internal (no endpoint); no auth surface added.
- File-missing on resume → fail job gracefully, do not crash app.

## Next Steps
- Phase-06 retry endpoint sets FAILED→PENDING then can rely on sweeper OR directly enqueue.
