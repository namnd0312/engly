package com.engly.study.domain;

/**
 * SM-2 spaced-repetition learning states for a vocabulary card.
 * NEW      — card has never been studied.
 * LEARNING — card is in active learning (short intervals, reps < threshold).
 * REVIEW   — card is in long-term review (daily/weekly intervals).
 */
public enum CardLearningState {
    NEW,
    LEARNING,
    REVIEW
}
