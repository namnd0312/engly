package com.engly.study.api.dto;

/**
 * Snapshot totals for the progress dashboard KPI cards (GET /api/study/stats/summary).
 *
 * @param totalCards        all card-state rows for the user (across decks)
 * @param newCount          cards in NEW state
 * @param learningCount     cards in LEARNING state
 * @param reviewCount       cards in REVIEW state
 * @param totalReviews      lifetime graded reviews (review_log row count)
 * @param reviewedToday     graded reviews today (Asia/Saigon)
 * @param dueToday          LEARNING/REVIEW cards due on or before today
 * @param currentStreakDays consecutive days (ending today or yesterday) with ≥1 graded review
 */
public record StudySummaryResponse(
        long totalCards,
        long newCount,
        long learningCount,
        long reviewCount,
        long totalReviews,
        long reviewedToday,
        long dueToday,
        int currentStreakDays) {
}
