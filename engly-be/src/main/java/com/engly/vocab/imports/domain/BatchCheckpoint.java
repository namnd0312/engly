package com.engly.vocab.imports.domain;

import java.util.List;

/**
 * One atomic checkpoint unit: the valid rows to upsert, the bad rows to record, and the
 * exact file byte offset reached at this batch boundary (always right after a '\n').
 * The repository commits upsert + error rows + counter/offset/heartbeat update in ONE
 * transaction — crash mid-batch rolls back everything (offset unchanged) so resume is clean.
 *
 * @param rows valid parsed rows read in this batch, BEFORE in-batch term de-dup
 *             (skipped_count is derived from rows.size() - actual inserted).
 */
public record BatchCheckpoint(
        Long jobId,
        String workerId,
        Long deckId,
        List<ParsedRow> rows,
        List<BadRow> errors,
        long newOffset
) {}
