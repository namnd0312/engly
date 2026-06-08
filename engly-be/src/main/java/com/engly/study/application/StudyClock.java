package com.engly.study.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Centralized clock for the study module.
 * All "today" and "now" calls in queue-building and review scheduling go through here.
 * TZ fixed to Asia/Saigon so due-date math is consistent for Vietnamese users.
 *
 * Inject a fixed Clock in tests to control "today":
 *   new StudyClock(Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneId.of("Asia/Saigon")))
 */
@Component
public class StudyClock {

    static final ZoneId SAIGON = ZoneId.of("Asia/Saigon");

    private final Clock clock;

    /** Production bean: uses system clock in Saigon TZ. */
    public StudyClock() {
        this.clock = Clock.system(SAIGON);
    }

    /** Test constructor: inject a fixed or custom Clock. */
    public StudyClock(Clock clock) {
        this.clock = clock;
    }

    /** Returns today's date in Asia/Saigon timezone. Never call LocalDate.now() directly. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Returns the current instant (wall clock). Used for lastReviewedAt timestamps. */
    public Instant now() {
        return clock.instant();
    }
}
