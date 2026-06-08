package com.engly.vocab.imports.domain;

/**
 * A CSV line that failed parse/validation — skipped, not inserted, and reported.
 * Accumulated per batch then persisted (capped) as vocab_import_error rows in the same tx.
 */
public record BadRow(
        long lineNo,
        String rawExcerpt,
        String reason
) {}
