package com.engly.study.api.dto;

/**
 * Per-deck counts returned by GET /api/study/decks.
 * newCount    = words in deck with no user_card_state row for this user.
 * dueCount    = rows where due_on <= today AND state IN (LEARNING, REVIEW).
 * learnedCount = rows with state = REVIEW (graduated from learning phase).
 */
public record StudyDeckSummary(
        Long deckId,
        String name,
        String slug,
        String topic,
        long newCount,
        long dueCount,
        long learnedCount
) {}
