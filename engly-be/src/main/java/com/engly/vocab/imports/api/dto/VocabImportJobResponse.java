package com.engly.vocab.imports.api.dto;

import java.time.Instant;

/**
 * Status projection for GET /api/admin/vocab-imports/{id}. `percent` is derived from
 * processedOffset/fileSize (0–100). No raw server file path is exposed.
 */
public record VocabImportJobResponse(
        Long id,
        Long deckId,
        String status,
        long fileSize,
        long processedCount,
        long insertedCount,
        long skippedCount,
        long errorCount,
        int percent,
        Instant createdAt
) {}
