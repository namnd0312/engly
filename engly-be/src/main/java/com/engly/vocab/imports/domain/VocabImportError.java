package com.engly.vocab.imports.domain;

import java.time.Instant;

/**
 * A recorded bad-row from a CSV import (one vocab_import_error row).
 * line_no is 1-based physical line in the uploaded file; reason is a human-readable cause.
 */
public record VocabImportError(
        Long id,
        Long jobId,
        long lineNo,
        String rawExcerpt,
        String reason,
        Instant createdAt
) {}
