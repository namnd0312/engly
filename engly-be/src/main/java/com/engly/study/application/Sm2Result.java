package com.engly.study.application;

import com.engly.study.domain.CardLearningState;

/**
 * Immutable output from Sm2Scheduler — the new SM-2 state after applying one review.
 * Caller (ReviewService) is responsible for setting dueOn = today + intervalDays.
 *
 * @param easeFactor  updated ease factor (floor-enforced to >= 1.3)
 * @param intervalDays new interval in days (floor-enforced to >= 1)
 * @param repetitions  updated consecutive-success count
 * @param lapses       updated lapse count
 * @param state        new CardLearningState (LEARNING or REVIEW)
 */
public record Sm2Result(
        double easeFactor,
        int intervalDays,
        int repetitions,
        int lapses,
        CardLearningState state
) {}
