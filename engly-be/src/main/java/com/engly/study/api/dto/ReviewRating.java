package com.engly.study.api.dto;

/**
 * The four SM-2 review buttons presented to the user.
 * Brainstorm mapping (authoritative):
 *   AGAIN → fail (reps=0, interval=1, ef-0.20, lapses++)
 *   HARD  → ef-0.15, interval×1.2, reps++
 *   GOOD  → standard SM-2 interval progression, ef unchanged, reps++
 *   EASY  → ef+0.15, interval×ef×1.3, reps++
 */
public enum ReviewRating {
    AGAIN,
    HARD,
    GOOD,
    EASY
}
