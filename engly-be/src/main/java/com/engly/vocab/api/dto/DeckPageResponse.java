package com.engly.vocab.api.dto;

import java.util.List;

/**
 * Paginated envelope returned by GET /api/admin/decks.
 * Mirrors VerbAdminPageResponse shape for frontend consistency.
 */
public record DeckPageResponse(
        List<DeckResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
