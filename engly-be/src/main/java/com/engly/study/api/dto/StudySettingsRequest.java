package com.engly.study.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for PUT /api/study/settings.
 * newCardsPerDay: >= 0, sensible upper bound 500.
 * maxReviewsPerDay: optional; null means unlimited.
 */
public record StudySettingsRequest(
        @NotNull(message = "newCardsPerDay is required")
        @Min(value = 0, message = "newCardsPerDay must be >= 0")
        @Max(value = 500, message = "newCardsPerDay must be <= 500")
        Integer newCardsPerDay,

        @Min(value = 0, message = "maxReviewsPerDay must be >= 0")
        @Max(value = 9999, message = "maxReviewsPerDay must be <= 9999")
        Integer maxReviewsPerDay
) {}
