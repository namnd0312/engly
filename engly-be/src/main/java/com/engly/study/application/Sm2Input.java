package com.engly.study.application;

import com.engly.study.api.dto.ReviewRating;

/**
 * Immutable input snapshot passed to Sm2Scheduler.
 * Captures the card's current SM-2 state + the user's rating so the scheduler
 * remains a pure function with no external I/O.
 *
 * @param easeFactor  current ease factor (SM-2 EF, >= 1.3)
 * @param intervalDays current interval in days (>= 0; 0 = not yet scheduled)
 * @param repetitions  number of consecutive successful repetitions
 * @param lapses       lifetime lapse count (wrong answers since creation)
 * @param rating       user's self-assessment button press
 */
public record Sm2Input(
        double easeFactor,
        int intervalDays,
        int repetitions,
        int lapses,
        ReviewRating rating
) {}
