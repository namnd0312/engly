package com.engly.study.api.dto;

import com.engly.study.domain.CardLearningState;

import java.time.LocalDate;

/**
 * Response from POST /api/study/review — the updated SM-2 state after one review.
 * dueOn: next review date in Asia/Saigon TZ (today + intervalDays).
 */
public record ReviewResultResponse(
        Long wordId,
        CardLearningState state,
        int intervalDays,
        LocalDate dueOn,
        int repetitions,
        double easeFactor,
        int lapses
) {}
