package com.engly.study;

import com.engly.study.application.Sm2Input;
import com.engly.study.application.Sm2Result;
import com.engly.study.application.Sm2Scheduler;
import com.engly.study.api.dto.ReviewRating;
import com.engly.study.domain.CardLearningState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Pure JUnit 5 unit tests for Sm2Scheduler — no Spring context, no I/O.
 * Covers every rating path plus edge cases (EF floor, interval floor, rounding).
 * Expected values computed from the authoritative brainstorm mapping:
 *   AGAIN: reps=0, interval=1, ef=max(1.3, ef-0.20), lapses++, state=LEARNING
 *   HARD:  ef=max(1.3, ef-0.15), interval=max(1, round(interval*1.2)), reps++
 *   GOOD:  reps==0→1; reps==1→6; else round(interval*ef); reps++; ef unchanged
 *   EASY:  ef+=0.15; interval=max(1, round(interval*ef*1.3)); reps++
 *   EF floor: 1.3; new-card EF: 2.5.
 */
class Sm2SchedulerTest {

    private Sm2Scheduler scheduler;

    /** Default new-card state: EF=2.5, interval=0, reps=0, lapses=0. */
    private static final double DEFAULT_EF = 2.5;

    @BeforeEach
    void setUp() {
        scheduler = new Sm2Scheduler();
    }

    // -------------------------------------------------------------------------
    // AGAIN — reset path
    // -------------------------------------------------------------------------

    @Test
    void again_resetsRepsAndInterval_and_penalizesEf() {
        // EF 2.5 → 2.5 - 0.20 = 2.30
        Sm2Input input = new Sm2Input(DEFAULT_EF, 10, 3, 0, ReviewRating.AGAIN);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.repetitions()).isEqualTo(0);
        assertThat(result.intervalDays()).isEqualTo(1);
        assertThat(result.easeFactor()).isCloseTo(2.30, within(0.001));
        assertThat(result.lapses()).isEqualTo(1);
        assertThat(result.state()).isEqualTo(CardLearningState.LEARNING);
    }

    @Test
    void again_efFloor_doesNotGoBelowOnePointThree() {
        // EF already at 1.3 → should stay 1.3, not drop to 1.1
        Sm2Input input = new Sm2Input(1.3, 1, 0, 2, ReviewRating.AGAIN);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.easeFactor()).isCloseTo(1.3, within(0.001));
        assertThat(result.lapses()).isEqualTo(3);
        assertThat(result.state()).isEqualTo(CardLearningState.LEARNING);
    }

    @Test
    void again_onNewCard_interval1_lapsesBumped() {
        // New card: interval=0, treated as effectiveInterval=1 but for AGAIN always returns 1
        Sm2Input input = new Sm2Input(DEFAULT_EF, 0, 0, 0, ReviewRating.AGAIN);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(1);
        assertThat(result.repetitions()).isEqualTo(0);
        assertThat(result.lapses()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // HARD — modest growth path
    // -------------------------------------------------------------------------

    @Test
    void hard_penalizesEfAndGrowsIntervalBy1point2() {
        // EF 2.5 → 2.35; interval 10 → round(10*1.2)=12
        Sm2Input input = new Sm2Input(DEFAULT_EF, 10, 3, 0, ReviewRating.HARD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.easeFactor()).isCloseTo(2.35, within(0.001));
        assertThat(result.intervalDays()).isEqualTo(12);
        assertThat(result.repetitions()).isEqualTo(4);
        assertThat(result.lapses()).isEqualTo(0);
        assertThat(result.state()).isEqualTo(CardLearningState.REVIEW);
    }

    @Test
    void hard_efFloor_doesNotGoBelowOnePointThree() {
        // EF 1.35 - 0.15 = 1.20 → clamped to 1.3
        Sm2Input input = new Sm2Input(1.35, 5, 2, 1, ReviewRating.HARD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.easeFactor()).isCloseTo(1.3, within(0.001));
        assertThat(result.intervalDays()).isEqualTo(6); // round(5*1.2)=6
    }

    @Test
    void hard_intervalFloor_atLeastOne() {
        // New card: interval=0, effectiveInterval=1 → round(1*1.2)=1
        Sm2Input input = new Sm2Input(DEFAULT_EF, 0, 0, 0, ReviewRating.HARD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void hard_intervalRoundingCorrect() {
        // interval=3, round(3*1.2)=round(3.6)=4
        Sm2Input input = new Sm2Input(DEFAULT_EF, 3, 1, 0, ReviewRating.HARD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(4);
    }

    // -------------------------------------------------------------------------
    // GOOD — standard SM-2 progression
    // -------------------------------------------------------------------------

    @Test
    void good_repsZero_gives1DayInterval() {
        Sm2Input input = new Sm2Input(DEFAULT_EF, 0, 0, 0, ReviewRating.GOOD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(1);
        assertThat(result.repetitions()).isEqualTo(1);
        assertThat(result.easeFactor()).isCloseTo(DEFAULT_EF, within(0.001)); // unchanged
        assertThat(result.state()).isEqualTo(CardLearningState.REVIEW);
    }

    @Test
    void good_repsOne_gives6DayInterval() {
        Sm2Input input = new Sm2Input(DEFAULT_EF, 1, 1, 0, ReviewRating.GOOD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(6);
        assertThat(result.repetitions()).isEqualTo(2);
        assertThat(result.easeFactor()).isCloseTo(DEFAULT_EF, within(0.001));
    }

    @Test
    void good_repsGreaterThanOne_usesIntervalTimesEf() {
        // reps=2, interval=6, ef=2.5 → round(6*2.5)=15
        Sm2Input input = new Sm2Input(DEFAULT_EF, 6, 2, 0, ReviewRating.GOOD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(15);
        assertThat(result.repetitions()).isEqualTo(3);
        assertThat(result.easeFactor()).isCloseTo(DEFAULT_EF, within(0.001));
    }

    @Test
    void good_repsGreaterThanOne_roundingIsCorrect() {
        // interval=4, ef=2.5 → round(4*2.5)=round(10.0)=10
        Sm2Input input = new Sm2Input(DEFAULT_EF, 4, 3, 0, ReviewRating.GOOD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(10);
    }

    @Test
    void good_repsGreaterThanOne_withOddRounding() {
        // interval=7, ef=1.5 → round(7*1.5)=round(10.5)=11 (Math.round rounds 0.5 up)
        Sm2Input input = new Sm2Input(1.5, 7, 5, 0, ReviewRating.GOOD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(11);
    }

    @Test
    void good_efIsUnchanged() {
        // EF must not change on GOOD
        Sm2Input input = new Sm2Input(2.1, 6, 2, 0, ReviewRating.GOOD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.easeFactor()).isCloseTo(2.1, within(0.001));
    }

    // -------------------------------------------------------------------------
    // EASY — bonus path
    // -------------------------------------------------------------------------

    @Test
    void easy_increasesEfAndAppliesBonusMultiplier() {
        // EF 2.5 + 0.15 = 2.65; interval=6 → round(6 * 2.65 * 1.3) = round(20.67) = 21
        Sm2Input input = new Sm2Input(DEFAULT_EF, 6, 2, 0, ReviewRating.EASY);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.easeFactor()).isCloseTo(2.65, within(0.001));
        assertThat(result.intervalDays()).isEqualTo(21);
        assertThat(result.repetitions()).isEqualTo(3);
        assertThat(result.lapses()).isEqualTo(0);
        assertThat(result.state()).isEqualTo(CardLearningState.REVIEW);
    }

    @Test
    void easy_onNewCard_interval1IsFloor() {
        // New card: interval=0, effectiveInterval=1, EF 2.5 + 0.15 = 2.65
        // round(1 * 2.65 * 1.3) = round(3.445) = 3
        Sm2Input input = new Sm2Input(DEFAULT_EF, 0, 0, 0, ReviewRating.EASY);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.easeFactor()).isCloseTo(2.65, within(0.001));
        assertThat(result.intervalDays()).isEqualTo(3);
        assertThat(result.repetitions()).isEqualTo(1);
    }

    @Test
    void easy_repsIncremented() {
        Sm2Input input = new Sm2Input(DEFAULT_EF, 10, 5, 0, ReviewRating.EASY);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.repetitions()).isEqualTo(6);
    }

    @Test
    void easy_intervalBonusRoundingCorrect() {
        // interval=1, ef=2.5 → newEf=2.65; round(1*2.65*1.3)=round(3.445)=3
        Sm2Input input = new Sm2Input(DEFAULT_EF, 1, 1, 0, ReviewRating.EASY);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // EF floor enforcement across all paths
    // -------------------------------------------------------------------------

    @Test
    void efNormalizedBeforeCompute_whenStoredBelowFloor() {
        // If EF somehow stored as 1.0 (below floor), scheduler normalizes to 1.3 first.
        // AGAIN on normalized EF 1.3 → stays 1.3 (not 0.8)
        Sm2Input input = new Sm2Input(1.0, 3, 2, 0, ReviewRating.AGAIN);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.easeFactor()).isCloseTo(1.3, within(0.001));
    }

    // -------------------------------------------------------------------------
    // Interval floor (>= 1)
    // -------------------------------------------------------------------------

    @Test
    void intervalFloor_hardOnZeroInterval_returnsAtLeastOne() {
        // interval=0 → effectiveInterval=1 → round(1*1.2)=1
        Sm2Input input = new Sm2Input(DEFAULT_EF, 0, 1, 0, ReviewRating.HARD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void intervalFloor_goodOnZeroRepsWithZeroInterval_returnsOne() {
        // reps=0, GOOD → always 1 day
        Sm2Input input = new Sm2Input(DEFAULT_EF, 0, 0, 0, ReviewRating.GOOD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.intervalDays()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // M1 regression: HARD/GOOD/EASY must preserve pre-existing lapses unchanged;
    //                AGAIN must increment lapses.
    // -------------------------------------------------------------------------

    @Test
    void hard_preservesPreExistingLapses() {
        // Card with 3 pre-existing lapses, reps >= 2 — HARD must NOT reset lapses to 0
        Sm2Input input = new Sm2Input(DEFAULT_EF, 10, 3, 3, ReviewRating.HARD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.lapses()).isEqualTo(3);
    }

    @Test
    void good_preservesPreExistingLapses() {
        // Card with 3 pre-existing lapses, reps >= 2 — GOOD must NOT reset lapses to 0
        Sm2Input input = new Sm2Input(DEFAULT_EF, 10, 3, 3, ReviewRating.GOOD);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.lapses()).isEqualTo(3);
    }

    @Test
    void easy_preservesPreExistingLapses() {
        // Card with 3 pre-existing lapses, reps >= 2 — EASY must NOT reset lapses to 0
        Sm2Input input = new Sm2Input(DEFAULT_EF, 10, 3, 3, ReviewRating.EASY);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.lapses()).isEqualTo(3);
    }

    @Test
    void again_incrementsPreExistingLapses() {
        // AGAIN on a card with 3 lapses → lapses becomes 4
        Sm2Input input = new Sm2Input(DEFAULT_EF, 10, 3, 3, ReviewRating.AGAIN);
        Sm2Result result = scheduler.schedule(input);

        assertThat(result.lapses()).isEqualTo(4);
    }
}
