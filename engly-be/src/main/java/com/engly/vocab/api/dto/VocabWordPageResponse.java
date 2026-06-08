package com.engly.vocab.api.dto;

import java.util.List;

/**
 * Paginated envelope returned by GET /api/admin/vocab-words.
 * Mirrors DeckPageResponse / VerbAdminPageResponse shape.
 */
public record VocabWordPageResponse(
        List<VocabWordResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
