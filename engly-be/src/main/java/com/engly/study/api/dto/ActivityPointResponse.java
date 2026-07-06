package com.engly.study.api.dto;

import java.time.LocalDate;

/**
 * One day in the activity chart (GET /api/study/stats/activity).
 * Gap-filled: every day in the window is present, zero when no activity.
 *
 * @param date     the day (Asia/Saigon)
 * @param reviews  graded reviews that day (from review_log — forward-only history)
 * @param newCards new cards introduced that day (from user_card_state.created_at — has real history)
 */
public record ActivityPointResponse(LocalDate date, long reviews, long newCards) {
}
