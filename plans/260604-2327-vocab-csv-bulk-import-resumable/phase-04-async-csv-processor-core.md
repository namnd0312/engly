# Phase 04 — Async CSV Processor Core (byte reader, batch loop, atomic checkpoint, resume)

## Context Links
- [plan.md](plan.md) · [researcher-02 streaming/byte-offset trap](research/researcher-02-streaming-csv-jdbc-resume.md) · phase-03 (repository)
- Depends on: phase-03. Consumed by: phase-05 (recovery enqueues), phase-06 (create-job enqueues).

## Overview
- **Date:** 2026-06-04 · **Priority:** P2 (core engine) · **Status:** pending
- The heart: a custom **byte-counting line reader** + CSV field parse + batch loop that upserts and checkpoints a record-boundary byte offset atomically per batch, resumable via `FileChannel.seek(offset)`. Runs on a dedicated bounded `@Async` executor.

## Key Insights
- LOCKED DESIGN: checkpoint = **byte offset at batch boundary**, always pointing right after a `\n` (record boundary). Achieved by a custom reader counting exact UTF-8 bytes consumed per line. Avoids the "BufferedReader reads ahead → FileChannel.position() lies" trap from researcher-02.
- LOCKED CONSTRAINT: CSV fields MUST NOT contain embedded newlines → 1 physical line = 1 record → offset is O(1) seekable and exact. A line failing CSV field parse → bad row (skip+report), never a crash.
- Resume = `RandomAccessFile`/`FileChannel.seek(processed_offset)` then continue reading. O(1), no re-scan.
- Memory: streaming only, NEVER `List<all lines>`. Batch list bounded to batch size (~500). Plain JDBC via phase-03 (no JPA).
- Header row present → on a FRESH start (offset==0) skip & count header bytes into offset before first batch. On RESUME (offset>0) do NOT re-skip header (offset already past it).
- Use a real CSV field parser for splitting a single line (quotes, escaped commas) — Apache Commons CSV or univocity in single-line mode. Since no embedded newlines, parse line-by-line: feed each line string to the parser. KISS: `CSVFormat.DEFAULT.withHeader-less parse of one line` OR a small quote-aware splitter. Prefer commons-csv `CSVParser.parse(line, format)` per line to honor quoting.

## Requirements
- **Functional:** Given jobId, claim → open file → seek(offset) → read lines counting bytes → parse fields → validate (term+meaning_vi required) → accumulate batch → at batch size, `commitBatch` (offset = bytes consumed through last line in batch) → repeat to EOF → `markCompleted`. On uncaught error → `markFailed`.
- **Non-functional:** Constant heap regardless of file size; -Xmx ~320–384m G1GC; never wrap whole file in one tx.

## Architecture
```
@Async("csvImportExecutor") VocabImportProcessor.process(jobId)
  job = repo.findById; if !repo.claim(jobId, workerId) → return (someone else owns it)
  ByteCountingLineReader reader = open(file); reader.seek(job.processedOffset())
  if offset == 0: skip header line (advance offset by header bytes), repo checkpoint? (optional; cheap to re-skip is fine but offset must reflect it) → simplest: count header into running offset, commit nothing until first batch
  long offset = job.processedOffset(); long lineNo = ...
  loop:
    line = reader.readLine()  // returns text + exact bytes consumed (incl. line terminator)
    if null → break
    lineNo++; offset += bytesConsumed
    parse → ParsedRow or BadRow(reason)
    add to batch / errors
    if batch.size()==BATCH_SIZE: repo.commitBatch(jobId, workerId, deckId, batch, errors, offset, counters); clear
  flush remaining partial batch with final offset
  repo.markCompleted(jobId)
```

## Related Code Files
- **Create** (`engly-be/.../vocab/imports/`):
  - `processing/AsyncConfig.java` (`@EnableAsync`, `csvImportExecutor` bean core=1/max=1/queue small/CallerRunsPolicy; <50 LOC)
  - `processing/ByteCountingLineReader.java` (reads one line, returns string + exact UTF-8 byte count incl terminator; seek(offset); <140 LOC)
  - `processing/CsvRowParser.java` (parse a single line → fields; map to ParsedRow; validation; <120 LOC)
  - `processing/VocabImportProcessor.java` (`@Async` orchestration loop; ~170 LOC — split if >200)
  - `processing/ParsedRow.java` (record: term, meaningVi, ipa, exampleEn, exampleVi, partOfSpeech; <25 LOC)
  - `processing/BatchAccumulator.java` (holds current rows + pending errors + offset bookkeeping; optional, only if it keeps processor <200 LOC)
- **Modify:** `application.yml` (add `import.batch-size: 500`); pom optional commons-csv if chosen for single-line parsing.
- **Delete:** none

## Implementation Steps
1. `AsyncConfig`: `@EnableAsync`; `@Bean("csvImportExecutor")` ThreadPoolTaskExecutor core=1 max=1 queueCapacity=10 CallerRunsPolicy threadNamePrefix `csv-import-`. (Only ONE worker runs at a time → safe with single file processing + lease.)
2. `ByteCountingLineReader`:
   - wrap `FileChannel` / `RandomAccessFile` over `InputStream` with manual byte read OR a `BufferedInputStream` where we count bytes ourselves (NOT a Reader that hides bytes).
   - `seek(long offset)` → `channel.position(offset)` / `raf.seek(offset)`.
   - `readLine()` → read bytes until `\n` (0x0A), track count incl the `\n` (and a preceding `\r` if CRLF); decode the collected bytes as UTF-8 to a String; return `{text, bytesConsumed}` or null at EOF. Strip trailing `\r`/`\n` from text.
   - keep an internal small buffer; CRITICAL: bytesConsumed must equal exact file bytes for the line so cumulative offset stays a true file position.
3. `CsvRowParser.parse(line)`:
   - split line honoring quotes/escaped commas (commons-csv single-line parse, comma delimiter, UTF-8). If parse throws / wrong column count or missing required → return BadRow(reason).
   - required: `term` non-blank + `meaning_vi` non-blank → else BadRow.
   - trim to column max lengths (term 120, meaning_vi 255, ipa 120, example_* 500, part_of_speech 40) OR mark BadRow if over (DECISION: truncate silently is risky; choose BadRow on overflow to be honest — document). Recommend BadRow on overflow.
   - ignore any `verified` column (locked false).
4. `VocabImportProcessor.process(jobId)`:
   - `@Async("csvImportExecutor")`; load job; `if(!repo.claim(jobId, workerId)) return;`
   - resolve deckId, filePath; open reader; `reader.seek(job.processedOffset())`.
   - header handling: if `processedOffset()==0` read+discard header line, set `offset = headerBytes` (do not count as processed_count).
   - loop reading lines, parse, accumulate batch + errors, track running offset; at BATCH_SIZE call `repo.commitBatch(...)` with current offset + counter deltas, reset accumulators.
   - after loop, flush remaining; `repo.markCompleted(jobId)`.
   - wrap body in try/catch: on Exception → `repo.markFailed(jobId)` + log (do NOT rethrow to executor silently). NOTE: a BadRow is NOT an exception — only IO/DB/unexpected errors fail the job.
5. `workerId` = stable per-instance id (e.g. `hostname + ":" + random startup UUID`); inject from a small bean (reused by phase-05).
6. Set JVM flags doc note: `-Xmx384m -Xms256m -XX:+UseG1GC` (deployment config; out of code scope but document in deployment-guide via phase-06/docs).
7. Compile; smoke test small CSV via phase-08.

## Todo List
- [ ] `AsyncConfig` bounded executor + `@EnableAsync`
- [ ] `ByteCountingLineReader` exact UTF-8 byte counting + seek + CRLF/LF handling
- [ ] `CsvRowParser` quote-aware single-line parse + required-field + overflow→BadRow
- [ ] `VocabImportProcessor` claim → seek → header logic → batch loop → flush → complete/fail
- [ ] Header skipped only when offset==0
- [ ] workerId provider bean (shared w/ phase-05)
- [ ] batch-size config (500), files <200 LOC, compile clean

## Success Criteria
- Process small CSV → all valid rows inserted, header not inserted, counts exact.
- Kill between batches → restart resumes from `processed_offset` with zero dup / zero loss (phase-08 resume test).
- Heap stays flat on a large generated file (phase-08 memory test).
- A line with too few columns / blank term → recorded as error, job still COMPLETED.

## Risk Assessment
- **Byte miscount on CRLF or multibyte UTF-8** → reader counts raw bytes (not chars) → multibyte safe; explicit CRLF test in phase-08.
- **Embedded newline in a field** → violates locked constraint → that line parses as 1 record (may become BadRow); documented, acceptable. NOT supported by design.
- **Offset points mid-record after crash** → impossible: checkpoint only written at batch boundary == after a `\n`.
- **Executor queue overflow** → CallerRunsPolicy runs on caller thread (acceptable; single-worker design).
- **Processor >200 LOC** → extract `BatchAccumulator`.

## Security Considerations
- File read only from configured storage root resolved by jobId (no user path); validate path under root (phase-06 sets file_path safely).
- Bounded memory prevents OOM-based DoS; bad-row cap prevents error-table flooding (phase-03).

## Next Steps
- Phase-05 wires startup + sweeper to call `process(jobId)` for recoverable jobs.
- Phase-06 enqueues `process(jobId)` after creating PENDING job.
