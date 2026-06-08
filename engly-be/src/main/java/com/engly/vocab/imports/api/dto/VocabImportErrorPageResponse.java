package com.engly.vocab.imports.api.dto;

import java.util.List;

/** Paginated envelope for GET /api/admin/vocab-imports/{id}/errors. Mirrors VocabWordPageResponse. */
public record VocabImportErrorPageResponse(
        List<VocabImportErrorResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
