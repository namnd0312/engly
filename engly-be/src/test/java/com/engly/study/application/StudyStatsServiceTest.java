package com.engly.study.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure static helpers in StudyStatsService (no Spring, no DB).
 * Covers the streak algorithm edge cases and inclusive date-range gap-fill scaffolding.
 */
class StudyStatsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 5);

    // ---- currentStreak ----

    @Test
    void streak_noReviews_isZero() {
        assertThat(StudyStatsService.currentStreak(List.of(), TODAY)).isZero();
    }

    @Test
    void streak_reviewedTodayOnly_isOne() {
        assertThat(StudyStatsService.currentStreak(List.of("2026-07-05"), TODAY)).isEqualTo(1);
    }

    @Test
    void streak_reviewedTodayAndTwoPriorConsecutiveDays_isThree() {
        List<String> dates = List.of("2026-07-05", "2026-07-04", "2026-07-03");
        assertThat(StudyStatsService.currentStreak(dates, TODAY)).isEqualTo(3);
    }

    @Test
    void streak_reviewedYesterdayNotToday_staysAlive() {
        // Newest is yesterday → streak counts from yesterday backward (not yet zeroed).
        List<String> dates = List.of("2026-07-04", "2026-07-03");
        assertThat(StudyStatsService.currentStreak(dates, TODAY)).isEqualTo(2);
    }

    @Test
    void streak_lastReviewBeforeYesterday_isZero() {
        // Newest review is 2 days ago → streak broken.
        List<String> dates = List.of("2026-07-03", "2026-07-02");
        assertThat(StudyStatsService.currentStreak(dates, TODAY)).isZero();
    }

    @Test
    void streak_gapBreaksTheCount() {
        // Reviewed today and 07-03, but 07-04 missing → only today counts.
        List<String> dates = List.of("2026-07-05", "2026-07-03", "2026-07-02");
        assertThat(StudyStatsService.currentStreak(dates, TODAY)).isEqualTo(1);
    }

    // ---- rangeInclusive ----

    @Test
    void rangeInclusive_singleDay() {
        assertThat(StudyStatsService.rangeInclusive(TODAY, TODAY)).containsExactly(TODAY);
    }

    @Test
    void rangeInclusive_spansBothEndpoints() {
        List<LocalDate> range = StudyStatsService.rangeInclusive(
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 5));
        assertThat(range).containsExactly(
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 5));
    }
}
