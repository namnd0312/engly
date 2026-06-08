package com.engly.study.application;

import com.engly.study.api.dto.ReviewRating;
import com.engly.study.domain.CardLearningState;
import org.springframework.stereotype.Component;

/**
 * Pure SM-2 scheduler — no I/O, no repositories, no clock.
 * Input: current card state snapshot (Sm2Input). Output: new scheduling values (Sm2Result).
 * Caller (ReviewService) applies dueOn = studyClock.today() + result.intervalDays().
 *
 * Brainstorm mapping (authoritative — wins over research where they differ):
 *   AGAIN → reps=0, interval=1, ef=max(1.3, ef-0.20), lapses++, state=LEARNING
 *   HARD  → ef=max(1.3, ef-0.15), interval=max(1, round(interval*1.2)), reps++, state=REVIEW
 *   GOOD  → reps==0→1d; reps==1→6d; else round(interval*ef); reps++; ef unchanged, state=REVIEW
 *   EASY  → ef=ef+0.15, interval=max(1, round(interval*ef*1.3)), reps++, state=REVIEW
 *   All ratings: ef floor=1.3, interval floor=1.
 */
@Component
public class Sm2Scheduler {

    private static final double EF_FLOOR = 1.3;
    private static final double INITIAL_EF = 2.5;

    /**
     * Compute new SM-2 state from current card snapshot + user rating.
     * Pure function: same input always produces same output.
     *
     * @param input current card state + user rating
     * @return new scheduling values (caller sets dueOn and lastReviewedAt)
     */
    public Sm2Result schedule(Sm2Input input) {
        double ef = input.easeFactor();
        int interval = input.intervalDays();
        int reps = input.repetitions();
        int lapses = input.lapses();

        // Guard: if EF was somehow stored below floor, normalize before computing
        ef = Math.max(EF_FLOOR, ef);
        // New cards (interval=0) treated as interval=1 for multiplication formulas
        int effectiveInterval = Math.max(1, interval);

        return switch (input.rating()) {
            case AGAIN -> scheduleAgain(ef, lapses);
            case HARD  -> scheduleHard(ef, effectiveInterval, reps, lapses);
            case GOOD  -> scheduleGood(ef, effectiveInterval, reps, lapses);
            case EASY  -> scheduleEasy(ef, effectiveInterval, reps, lapses);
        };
    }

    /** AGAIN: card forgotten — reset reps, short re-study interval, penalize EF, count lapse. */
    private Sm2Result scheduleAgain(double ef, int lapses) {
        double newEf = Math.max(EF_FLOOR, ef - 0.20);
        return new Sm2Result(newEf, 1, 0, lapses + 1, CardLearningState.LEARNING);
    }

    /** HARD: correct but difficult — slight EF penalty, modest interval growth, increment reps. Lapses unchanged. */
    private Sm2Result scheduleHard(double ef, int effectiveInterval, int reps, int lapses) {
        double newEf = Math.max(EF_FLOOR, ef - 0.15);
        int newInterval = Math.max(1, (int) Math.round(effectiveInterval * 1.2));
        return new Sm2Result(newEf, newInterval, reps + 1, lapses, CardLearningState.REVIEW);
    }

    /**
     * GOOD: standard SM-2 interval progression; EF unchanged (brainstorm: "standard SM-2
     * baseline q=4 ≈ +0.0 net" → no EF delta for GOOD).
     * reps==0 → 1 day; reps==1 → 6 days; else round(interval * ef). Lapses unchanged.
     */
    private Sm2Result scheduleGood(double ef, int effectiveInterval, int reps, int lapses) {
        int newInterval;
        if (reps == 0) {
            newInterval = 1;
        } else if (reps == 1) {
            newInterval = 6;
        } else {
            newInterval = Math.max(1, (int) Math.round(effectiveInterval * ef));
        }
        return new Sm2Result(ef, newInterval, reps + 1, lapses, CardLearningState.REVIEW);
    }

    /** EASY: card well-known — boost EF and apply interval bonus multiplier. Lapses unchanged. */
    private Sm2Result scheduleEasy(double ef, int effectiveInterval, int reps, int lapses) {
        double newEf = ef + 0.15;
        // Use updated EF in interval calculation (per brainstorm: round(interval * ef * 1.3))
        int newInterval = Math.max(1, (int) Math.round(effectiveInterval * newEf * 1.3));
        return new Sm2Result(newEf, newInterval, reps + 1, lapses, CardLearningState.REVIEW);
    }
}
