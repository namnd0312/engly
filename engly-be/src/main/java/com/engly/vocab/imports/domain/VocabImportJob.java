package com.engly.vocab.imports.domain;

import java.time.Instant;

/**
 * Immutable snapshot of a vocab_import_job row (read-side projection).
 * Counters + processedOffset advance via the repository's atomic per-batch checkpoint;
 * workerId/heartbeatAt drive the lease + stale-reclaim recovery.
 */
public record VocabImportJob(
        Long id,
        Long deckId,
        String filePath,
        long fileSize,
        ImportJobStatus status,
        long processedOffset,
        long processedCount,
        long insertedCount,
        long skippedCount,
        long errorCount,
        String workerId,
        Instant heartbeatAt,
        Instant createdAt,
        Instant updatedAt
) {}
