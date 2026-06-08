package com.engly.study.api.dto;

/**
 * Response for GET /api/study/settings and PUT /api/study/settings.
 * maxReviewsPerDay null means unlimited.
 */
public record StudySettingsResponse(
        int newCardsPerDay,
        Integer maxReviewsPerDay
) {}
