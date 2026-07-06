package com.engly.study.api.dto;

import java.time.LocalDate;

/**
 * One day in the rating/accuracy chart (GET /api/study/stats/accuracy).
 * Per-day counts of each self-rating from review_log; gap-filled with zeros.
 * Overall accuracy % (e.g. (good+easy)/total) is computed client-side.
 *
 * @param date  the day (Asia/Saigon)
 * @param again reviews rated AGAIN (forgot)
 * @param hard  reviews rated HARD
 * @param good  reviews rated GOOD
 * @param easy  reviews rated EASY
 */
public record AccuracyPointResponse(LocalDate date, long again, long hard, long good, long easy) {
}
