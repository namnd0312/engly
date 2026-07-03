package com.engly.study.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for StudyClock — no mocks, no Spring context.
 * Verifies timezone logic for Asia/Saigon (UTC+7, no DST).
 */
class StudyClockTest {

    private static final ZoneId SAIGON = ZoneId.of("Asia/Saigon");

    // 2026-06-15T00:00:00Z = 2026-06-15T07:00:00+07:00 local → date is 2026-06-15
    @Test
    void today_fixedAtMidnightUtc_returnsSaigonDate() {
        Clock fixed = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), SAIGON);
        StudyClock clock = new StudyClock(fixed);

        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    // 2026-06-14T23:00:00Z = 2026-06-15T06:00:00+07:00 local → date is still 2026-06-15
    @Test
    void today_fixedOneHourBeforeSaigonMidnight_returnsNextDay() {
        // 23:00 UTC = 06:00 Saigon next day (UTC+7)
        Clock fixed = Clock.fixed(Instant.parse("2026-06-14T23:00:00Z"), SAIGON);
        StudyClock clock = new StudyClock(fixed);

        // 2026-06-14T23:00Z → 2026-06-15T06:00+07:00 → date 2026-06-15
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    // Year boundary: 2025-12-31T17:00:00Z = 2026-01-01T00:00:00+07:00 → date 2026-01-01
    @Test
    void today_yearBoundary_returnsNewYearDate() {
        Clock fixed = Clock.fixed(Instant.parse("2025-12-31T17:00:00Z"), SAIGON);
        StudyClock clock = new StudyClock(fixed);

        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    // now() must return the exact instant from the underlying clock
    @Test
    void now_fixedClock_returnsFixedInstant() {
        Instant expected = Instant.parse("2026-06-15T00:00:00Z");
        Clock fixed = Clock.fixed(expected, SAIGON);
        StudyClock clock = new StudyClock(fixed);

        assertThat(clock.now()).isEqualTo(expected);
    }
}
