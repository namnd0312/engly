package com.engly.study.api.dto;

import java.time.LocalDate;

/**
 * One upcoming day in the due-forecast chart (GET /api/study/stats/forecast).
 * Gap-filled across the forecast window.
 *
 * @param date     the upcoming day (Asia/Saigon)
 * @param dueCount LEARNING/REVIEW cards scheduled due that day
 */
public record ForecastPointResponse(LocalDate date, long dueCount) {
}
