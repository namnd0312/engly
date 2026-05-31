package com.engly.verb.api.dto;

import java.util.List;

/**
 * Paginated envelope returned by GET /api/verbs.
 */
public record VerbSearchPageResponse(
        List<VerbResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
