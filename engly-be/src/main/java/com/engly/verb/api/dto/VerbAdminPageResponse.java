package com.engly.verb.api.dto;

import java.util.List;

/**
 * Paginated envelope returned by GET /api/admin/verbs (admin DTOs carry the id).
 */
public record VerbAdminPageResponse(
        List<VerbAdminResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
