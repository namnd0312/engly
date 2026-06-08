package com.engly.vocab.imports.api.dto;

/** One bad-row entry in the paged errors view. */
public record VocabImportErrorResponse(
        long lineNo,
        String rawExcerpt,
        String reason
) {}
