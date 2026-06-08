package com.engly.study.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /api/study/review.
 * wordId: the vocab_word.id being reviewed.
 * rating: one of AGAIN | HARD | GOOD | EASY.
 */
public record ReviewRequest(
        @NotNull(message = "wordId is required")
        Long wordId,

        @NotNull(message = "rating is required")
        ReviewRating rating
) {}
